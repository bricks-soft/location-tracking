#!/usr/bin/env python3
"""Checks whether the native libraries of Android APKs support 16 KB memory pages.

Usage:
    python3 .github/scripts/check-16kb.py [--report-only] [--label NAME] [--zipalign PATH] APK [APK ...]

For every APK the script runs two checks:

1. `zipalign -c -P 16 -v 4 <apk>` (build-tools 35.0.0 or newer). zipalign reports an entry as BAD when an uncompressed
   `.so` entry does not start on a 16 KB boundary inside the APK, or when another uncompressed entry is not 4-byte
   aligned. zipalign is looked up in `$ANDROID_HOME/build-tools/<version>/` (the newest version >= 35) unless
   `--zipalign` names it.
2. For every `lib/<abi>/*.so` entry: the ELF program headers are parsed and the smallest `p_align` of all `PT_LOAD`
   segments must be >= 16384 (2**14). For an uncompressed entry the script also checks that its data starts on a
   16 KB boundary inside the APK (the rule zipalign checks, shown per library in the table).

Google Play requires 16 KB support on 64-bit devices for apps that target Android 15 (API 35) or newer
(https://developer.android.com/guide/practices/page-sizes). So only libraries of the 64-bit ABIs (arm64-v8a, x86_64)
can fail an APK. Libraries of the 32-bit ABIs (armeabi-v7a, x86) are listed with their alignment and marked
"32-bit, not required"; a zipalign failure whose BAD entries are all 32-bit libraries does not fail the APK either.
An APK without native libraries passes check 2 (the table says so).

Output: a text table per APK on stdout. When `$GITHUB_STEP_SUMMARY` is set, the same result is appended to it as
Markdown. When `$GITHUB_ACTIONS` is `true`, a failing APK also produces an `::error` annotation (a `::warning`
annotation with `--report-only`).

Exit status without `--report-only`: 0 = every APK passed; 1 = at least one APK failed a check; 2 = an APK could not
be read or zipalign was not found (the other APKs are still checked and reported). With `--report-only` the exit
status is always 0 (after a usage error it is 2).
"""

from __future__ import annotations

import argparse
import os
import re
import struct
import subprocess
import sys
import zipfile
from dataclasses import dataclass, field

PAGE_16K = 16384
PT_LOAD = 1
PN_XNUM = 0xFFFF
ABIS_64 = {"arm64-v8a", "x86_64"}
LIB_ENTRY = re.compile(r"^lib/([^/]+)/[^/]+\.so$")
ZIPALIGN_BAD = re.compile(r"^\s*\d+\s+(.+?)\s+\(BAD - \d+\)\s*$")
ZIPALIGN_OK = re.compile(r"\(OK( - [a-z]+)?\)\s*$")


def is_64bit_lib(entry: str) -> bool:
    match = LIB_ENTRY.match(entry)
    return bool(match) and match.group(1) in ABIS_64


def is_32bit_lib(entry: str) -> bool:
    match = LIB_ENTRY.match(entry)
    return bool(match) and match.group(1) not in ABIS_64


@dataclass
class LibResult:
    entry: str
    abi: str
    compressed: bool
    #: smallest p_align of all PT_LOAD segments; None when the ELF could not be read
    load_align: int | None
    #: data offset of an uncompressed entry inside the APK; None for compressed entries
    data_offset: int | None
    error: str | None

    @property
    def is_64bit_abi(self) -> bool:
        return self.abi in ABIS_64

    @property
    def elf_ok(self) -> bool:
        return self.load_align is not None and self.load_align >= PAGE_16K

    @property
    def zip_ok(self) -> bool:
        return self.data_offset is None or self.data_offset % PAGE_16K == 0

    @property
    def fails(self) -> bool:
        """True when this library breaks Google Play's 16 KB rule (64-bit ABIs only)."""
        return self.is_64bit_abi and (self.error is not None or not self.elf_ok or not self.zip_ok)

    def verdict(self) -> str:
        if self.error is not None:
            base = f"ERROR: {self.error}"
        elif self.elf_ok and self.zip_ok:
            base = "aligned"
        else:
            problems = []
            if not self.elf_ok:
                problems.append("ELF LOAD < 16 KB")
            if not self.zip_ok:
                problems.append("zip offset not 16 KB aligned")
            base = "UNALIGNED (" + ", ".join(problems) + ")"
        if not self.is_64bit_abi:
            return f"{base}; 32-bit, not required"
        return base


@dataclass
class ZipalignResult:
    #: None when zipalign could not be run
    exit_ok: bool | None
    #: lines worth showing (BAD entries, summary, errors)
    lines: list[str] = field(default_factory=list)
    #: entry names zipalign reported as BAD
    bad_entries: list[str] = field(default_factory=list)
    #: why zipalign did not run
    error: str | None = None

    @property
    def passed(self) -> bool:
        """zipalign passed, or every BAD entry is a 32-bit library (not required)."""
        if self.exit_ok is None:
            return False
        if self.exit_ok:
            return True
        return bool(self.bad_entries) and all(is_32bit_lib(entry) for entry in self.bad_entries)

    def describe(self) -> str:
        if self.exit_ok is None:
            return f"NOT RUN ({self.error})"
        if self.exit_ok:
            return "passed"
        if self.passed:
            return "passed for the 64-bit ABIs (BAD entries are 32-bit libraries only: " + ", ".join(self.bad_entries) + ")"
        return "FAILED"


@dataclass
class ApkResult:
    apk: str
    label: str
    libs: list[LibResult] = field(default_factory=list)
    zipalign: ZipalignResult | None = None
    #: the APK could not be read
    error: str | None = None

    @property
    def failing_libs(self) -> list[LibResult]:
        return [lib for lib in self.libs if lib.fails]

    @property
    def passed(self) -> bool:
        return self.error is None and self.zipalign is not None and self.zipalign.passed and not self.failing_libs

    @property
    def is_error(self) -> bool:
        """The check could not be completed (unreadable APK or zipalign not run)."""
        return self.error is not None or (self.zipalign is not None and self.zipalign.exit_ok is None)


def describe_align(value: int | None) -> str:
    if value is None:
        return "?"
    if value > 0 and value & (value - 1) == 0:
        return f"2**{value.bit_length() - 1} ({value})"
    return str(value)


def min_load_align(data: bytes) -> int:
    """Smallest p_align of the PT_LOAD program headers of an ELF file. Raises ValueError when it cannot be read."""
    if len(data) < 52 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    elf_class = data[4]
    elf_data = data[5]
    if elf_data == 1:
        endian = "<"
    elif elf_data == 2:
        endian = ">"
    else:
        raise ValueError(f"unknown ELF data encoding {elf_data}")
    if elf_class == 2:  # ELFCLASS64
        if len(data) < 64:
            raise ValueError("truncated ELF64 header")
        (phoff,) = struct.unpack_from(endian + "Q", data, 0x20)
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x36)
        align_fmt, align_off, min_entsize = endian + "Q", 48, 56
    elif elf_class == 1:  # ELFCLASS32
        (phoff,) = struct.unpack_from(endian + "I", data, 0x1C)
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x2A)
        align_fmt, align_off, min_entsize = endian + "I", 28, 32
    else:
        raise ValueError(f"unknown ELF class {elf_class}")
    if phnum == PN_XNUM:
        raise ValueError("more than 65534 program headers (PN_XNUM) are not supported")
    if phentsize < min_entsize:
        raise ValueError(f"program header entry size {phentsize} is too small")
    aligns = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        if offset + phentsize > len(data):
            raise ValueError("program header table is truncated")
        (p_type,) = struct.unpack_from(endian + "I", data, offset)
        if p_type == PT_LOAD:
            (p_align,) = struct.unpack_from(align_fmt, data, offset + align_off)
            aligns.append(p_align)
    if not aligns:
        raise ValueError("no PT_LOAD segment")
    return min(aligns)


def data_offset(apk_file, info: zipfile.ZipInfo) -> int:
    """File offset where the entry's data starts (after its local file header)."""
    apk_file.seek(info.header_offset)
    header = apk_file.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise ValueError("bad local file header")
    name_len, extra_len = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_len + extra_len


def inspect_apk(path: str) -> list[LibResult]:
    results = []
    with zipfile.ZipFile(path) as apk, open(path, "rb") as raw:
        for info in apk.infolist():
            match = LIB_ENTRY.match(info.filename)
            if not match:
                continue
            compressed = info.compress_type != zipfile.ZIP_STORED
            load_align = None
            offset = None
            error = None
            try:
                if not compressed:
                    offset = data_offset(raw, info)
                load_align = min_load_align(apk.read(info))
            except (ValueError, struct.error, zipfile.BadZipFile, NotImplementedError) as exc:
                # NotImplementedError: a zip compression method Python cannot read
                error = str(exc) or type(exc).__name__
            results.append(LibResult(info.filename, match.group(1), compressed, load_align, offset, error))
    return results


def find_zipalign(explicit: str | None) -> str:
    if explicit:
        if not os.access(explicit, os.X_OK):
            raise FileNotFoundError(f"{explicit} is not an executable file")
        return explicit
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise FileNotFoundError("ANDROID_HOME is not set; pass --zipalign")
    build_tools = os.path.join(sdk, "build-tools")
    candidates = []
    for name in os.listdir(build_tools) if os.path.isdir(build_tools) else []:
        numbers = re.match(r"^(\d+)(?:\.(\d+))?(?:\.(\d+))?", name)
        tool = os.path.join(build_tools, name, "zipalign")
        if numbers and int(numbers.group(1)) >= 35 and os.access(tool, os.X_OK):
            candidates.append((tuple(int(n or 0) for n in numbers.groups()), tool))
    if not candidates:
        raise FileNotFoundError(f"no zipalign of build-tools >= 35 in {build_tools}")
    return max(candidates)[1]


def run_zipalign(zipalign: str, apk: str) -> ZipalignResult:
    """Runs `zipalign -c -P 16 -v 4`."""
    try:
        proc = subprocess.run(
            [zipalign, "-c", "-P", "16", "-v", "4", apk],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
        )
    except OSError as exc:
        return ZipalignResult(exit_ok=None, error=str(exc))
    lines = proc.stdout.splitlines()
    # -v prints one line per entry ending in "(OK)", "(OK - compressed)" or "(OK - directory)"; keep the others (BAD
    # entries and the summary lines).
    shown = [line for line in lines if line.strip() and not ZIPALIGN_OK.search(line)]
    bad = [m.group(1) for m in (ZIPALIGN_BAD.match(line) for line in lines) if m]
    return ZipalignResult(exit_ok=proc.returncode == 0, lines=shown, bad_entries=bad)


def check_apk(apk: str, label: str, zipalign: str | None, zipalign_error: str | None) -> ApkResult:
    result = ApkResult(apk=apk, label=label)
    if not os.path.isfile(apk):
        result.error = "no such file"
        return result
    try:
        result.libs = inspect_apk(apk)
    except (zipfile.BadZipFile, OSError) as exc:
        result.error = str(exc)
        return result
    if zipalign is None:
        result.zipalign = ZipalignResult(exit_ok=None, error=zipalign_error)
    else:
        result.zipalign = run_zipalign(zipalign, apk)
    return result


def print_text(result: ApkResult, zipalign: str | None, mode: str) -> None:
    print(f"== 16 KB page-size check: {result.label} ({mode})")
    print(f"   APK: {result.apk}")
    if result.error is not None:
        print(f"   ERROR: cannot read the APK: {result.error}")
        print("   result: ERROR")
        print()
        return
    assert result.zipalign is not None
    print(f"   zipalign -c -P 16 -v 4 ({zipalign or 'not found'}): {result.zipalign.describe()}")
    for line in result.zipalign.lines:
        print(f"     {line}")
    if not result.libs:
        print("   native libraries: none (nothing to check)")
    else:
        width = max(len(lib.entry) for lib in result.libs)
        print(f"   {'library'.ljust(width)}  {'stored':6}  {'min LOAD p_align':18}  result")
        for lib in result.libs:
            stored = "no" if lib.compressed else "yes"
            print(f"   {lib.entry.ljust(width)}  {stored:6}  {describe_align(lib.load_align):18}  {lib.verdict()}")
    count_64 = sum(1 for lib in result.libs if lib.is_64bit_abi)
    status = "PASSED" if result.passed else ("ERROR" if result.is_error else "FAILED")
    print(
        f"   result: {status} ({len(result.libs)} libraries, {count_64} in 64-bit ABIs, "
        f"{len(result.failing_libs)} failing)"
    )
    print()


def markdown(result: ApkResult, mode: str) -> list[str]:
    out = [f"### 16 KB page-size check: {result.label} ({mode})", ""]
    if result.error is not None:
        out += [f"- Result: **ERROR**: cannot read `{result.apk}`: {result.error}", ""]
        return out
    assert result.zipalign is not None
    count_64 = sum(1 for lib in result.libs if lib.is_64bit_abi)
    status = "passed" if result.passed else ("ERROR" if result.is_error else "FAILED")
    out.append(f"- Result: **{status}**")
    out.append(f"- `zipalign -c -P 16 -v 4`: {result.zipalign.describe()}")
    out.append(
        f"- Native libraries: {len(result.libs)} ({count_64} in 64-bit ABIs, "
        f"{len(result.failing_libs)} failing the 16 KB rule)"
    )
    if result.libs:
        out += ["", "| Library | Stored | Min LOAD p_align | Result |", "|---|---|---|---|"]
        for lib in result.libs:
            stored = "no" if lib.compressed else "yes"
            out.append(f"| `{lib.entry}` | {stored} | {describe_align(lib.load_align)} | {lib.verdict()} |")
    out.append("")
    return out


def annotate(kind: str, title: str, message: str) -> None:
    if os.environ.get("GITHUB_ACTIONS") == "true":
        message = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::{kind} title={title}::{message}")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Check 16 KB page-size support of the native libraries in APKs.")
    parser.add_argument("apks", nargs="+", metavar="APK")
    parser.add_argument("--report-only", action="store_true", help="print the result but always exit 0")
    parser.add_argument("--label", default=None, help="name shown in the report (one APK only; default: the path)")
    parser.add_argument("--zipalign", default=None, help="zipalign binary (default: newest build-tools >= 35)")
    args = parser.parse_args(argv)
    if args.label is not None and len(args.apks) > 1:
        parser.error("--label needs exactly one APK")

    try:
        zipalign: str | None = find_zipalign(args.zipalign)
        zipalign_error = None
    except OSError as exc:
        zipalign, zipalign_error = None, str(exc)
        print(f"check-16kb: zipalign not found: {exc}", file=sys.stderr)

    mode = "report only" if args.report_only else "enforced"
    results = []
    summary: list[str] = []
    for apk in args.apks:
        result = check_apk(apk, args.label or apk, zipalign, zipalign_error)
        results.append(result)
        print_text(result, zipalign, mode)
        summary += markdown(result, mode)
        if not result.passed:
            if result.is_error:
                message = f"{result.label}: the 16 KB check could not run: {result.error or zipalign_error}"
            else:
                names = ", ".join(lib.entry for lib in result.failing_libs) or "see the zipalign output"
                message = f"{result.label}: not 16 KB page-size compatible: {names}"
            annotate("warning" if args.report_only else "error", "16 KB page size", message)

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as out:
            out.write("\n".join(summary) + "\n")

    if args.report_only:
        return 0
    if any(result.is_error for result in results):
        return 2
    if any(not result.passed for result in results):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
