#!/usr/bin/env bash
# Releases the npm package (README "Publishing to npm"). Run it on master; it works from the repository root.
#
# Usage:
#   scripts/release.sh check <version>   run every check below; change no tracked file
#   scripts/release.sh run <version>     run the checks; then CHANGELOG [Unreleased] -> [<version>] - <date>,
#                                        `npm version`, a signed commit "<version>" and a signed tag v<version>;
#                                        after you type "yes": push both, create the GitHub release (it starts
#                                        npm-publish.yml) and wait for that workflow
#   scripts/release.sh verify <version>  after the staged version is approved on npmjs.com: npm serves it as
#                                        latest, with a provenance statement
#
# Checks: tools (Node of npm-publish.yml, gh login, JDK, Android SDK); git (master, clean, equal to origin/master,
# tag and release free); versions (new, greater than npm latest, major = Capacitor's, package.json =
# package-lock.json = src/web/device.ts); CHANGELOG ([Unreleased] first and not empty); git can sign; private names;
# the steps of .github/workflows/ci.yml on this computer (about 10 minutes, mostly Gradle); README = npm run docgen;
# npm pack contents; no tracked file changed by the builds.
#
# Private names: the changes and commit messages since the last tag must match none of the extended regular
# expressions (case-insensitive, one per line, # for comments) in $LT_PRIVATE_PATTERNS, default
# ~/.config/lt-release/private-patterns. The file stays outside the repository, so the names never become public.
set -euo pipefail

PKG=@bricks-soft/capacitor-location-tracking
REPO=bricks-soft/location-tracking
PATTERNS=${LT_PRIVATE_PATTERNS:-$HOME/.config/lt-release/private-patterns}

fail() { echo "release: $*" >&2; exit 1; }
step() { printf '\n== %s\n' "$*"; }
# True when version $1 is greater than version $2.
newer() { [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]; }
json() { node -p "require('./$1')$2"; }
# The body of the CHANGELOG section whose heading starts with "## [$1]".
section() { awk -v h="## [$1]" 'index($0, h) == 1 { f = 1; next } f && /^## \[/ { exit } f' CHANGELOG.md; }
web_version() { sed -n "s/^export const PLUGIN_VERSION = '\(.*\)';$/\1/p" src/web/device.ts; }
gradle_in() { (cd "$1" && ./gradlew -q --console=plain "${@:2}"); }

check_tools() {
  step "tools"
  local node_ci
  node_ci=$(sed -n '/node-version:/{s/[^0-9]*\([0-9]*\).*/\1/p;q}' .github/workflows/npm-publish.yml)
  [ "$(node -p 'process.versions.node.split(".")[0]')" = "$node_ci" ] ||
    fail "Node $node_ci is needed (npm-publish.yml builds with it); this is $(node -v)"
  gh auth status -h github.com >/dev/null 2>&1 || fail "gh is not logged in to github.com"
  [ -n "${JAVA_HOME:-}" ] || command -v java >/dev/null || fail "no JDK for Gradle: set JAVA_HOME"
  [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ] || fail "no Android SDK: set ANDROID_HOME"
  command -v python3 >/dev/null || fail "python3 is needed for the 16 KB page-size check"
  echo "node $(node -v), JDK ${JAVA_HOME:-$(command -v java)}, SDK ${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
}

check_git() {
  step "git"
  [ "$(git rev-parse --abbrev-ref HEAD)" = master ] || fail "not on master"
  [ -z "$(git status --porcelain)" ] || fail "the working tree is not clean"
  git fetch -q --tags origin master
  [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/master)" ] || fail "master is not equal to origin/master"
  ! git rev-parse -q --verify "refs/tags/v$V" >/dev/null || fail "tag v$V exists"
  ! git ls-remote --exit-code --tags origin "refs/tags/v$V" >/dev/null || fail "tag v$V exists on origin"
  ! gh release view "v$V" --repo "$REPO" >/dev/null 2>&1 || fail "the GitHub release v$V exists"
  echo "master = origin/master = $(git rev-parse --short HEAD); v$V is free"
}

check_version() {
  step "version"
  [[ $V =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "the version must be X.Y.Z, got '$V'"
  local cur lock lock_root web latest cap
  cur=$(json package.json .version)
  lock=$(json package-lock.json .version)
  lock_root=$(json package-lock.json ".packages['']?.version")
  web=$(web_version)
  [ "$lock" = "$cur" ] && [ "$lock_root" = "$cur" ] && [ "$web" = "$cur" ] ||
    fail "versions differ: package.json $cur, package-lock.json $lock and $lock_root, src/web/device.ts '$web'"
  latest=$(npm view "$PKG" dist-tags.latest)
  newer "$V" "$cur" || fail "$V is not greater than package.json ($cur)"
  newer "$V" "$latest" || fail "$V is not greater than npm latest ($latest)"
  cap=$(json package.json ".peerDependencies['@capacitor/core'].match(/\\d+/)[0]")
  [ "${V%%.*}" = "$cap" ] || fail "the major version of $V is not Capacitor's ($cap in peerDependencies)"
  echo "package.json $cur, npm latest $latest -> $V (Capacitor $cap)"
}

check_changelog() {
  step "CHANGELOG"
  [ "$(grep -m1 '^## \[' CHANGELOG.md)" = "## [Unreleased]" ] || fail "the first section of CHANGELOG.md is not '## [Unreleased]'"
  [ -n "$(section Unreleased | tr -d '[:space:]')" ] || fail "the [Unreleased] section of CHANGELOG.md is empty"
  ! grep -q "^## \[$V\]" CHANGELOG.md || fail "CHANGELOG.md already has a [$V] section"
  echo "[Unreleased] has $(section Unreleased | grep -c .) lines"
}

check_signing() {
  step "signing"
  # Signs a commit object that no branch points to: the real signing path (1Password may ask), no ref changes.
  git commit-tree -S -m "release.sh signing check" "HEAD^{tree}" >/dev/null 2>&1 ||
    fail "git cannot sign: unlock 1Password (or check user.signingkey)"
  echo "git can sign"
}

check_private() {
  step "private names"
  [ -f "$PATTERNS" ] || fail "no private-name patterns at $PATTERNS (see the top of this script)"
  local patterns last hits
  patterns=$(grep -v -E '^[[:space:]]*(#|$)' "$PATTERNS" || true)
  [ -n "$patterns" ] || fail "$PATTERNS has no patterns"
  last=$(git describe --tags --abbrev=0)
  hits=$({ git diff "$last" HEAD | grep '^+' || true; git log --format=%B "$last..HEAD"; } |
    grep -n -i -E -f <(printf '%s\n' "$patterns") || true)
  [ -z "$hits" ] || { printf '%s\n' "$hits" | head -20; fail "changes or commit messages since $last match a private-name pattern (above)"; }
  echo "nothing since $last matches the $(printf '%s\n' "$patterns" | grep -c .) patterns"
}

run_tests() {
  step "tests (the steps of .github/workflows/ci.yml)"
  npm ci && npm test
  (cd testing/e2e-kit && npm ci && npm run typecheck && npm test)
  (cd e2e/plugin && npm ci && npm run typecheck && npm run dry-run)
  (cd examples/field-force/e2e && npm ci && npm run typecheck && npm run dry-run)
  gradle_in android testDebugUnitTest
  (cd example && npm ci && npm run sync)
  gradle_in example/android assembleDebug -PlocationTracking.providers=gms,hms
  (cd examples/field-force && npm ci && npm test && npm run sync)
  gradle_in examples/field-force/android assembleDebug :bricks-soft-capacitor-premise-monitor:testDebugUnitTest assembleRelease
  python3 .github/scripts/check-16kb.py --report-only --label "example debug (gms,hms)" \
    example/android/app/build/outputs/apk/debug/app-debug.apk
  python3 .github/scripts/check-16kb.py --label "field-force release (gms)" \
    examples/field-force/android/app/build/outputs/apk/release/app-release-unsigned.apk
}

check_docs() {
  step "API docs"
  npm run -s docgen >/dev/null
  git diff --quiet -- README.md || {
    git diff --stat -- README.md
    git checkout -q -- README.md
    fail "README.md is not what npm run docgen writes: run it and commit the result"
  }
  echo "README.md = npm run docgen"
}

check_package() {
  step "package contents"
  local out
  out=$(npm pack --dry-run --json --ignore-scripts 2>/dev/null)
  node -e '
    const [p] = JSON.parse(process.argv[1]);
    const allowed = [...require("./package.json").files, "package.json", "README.md", "LICENSE"];
    const paths = p.files.map((f) => f.path);
    const extra = paths.filter((f) => !allowed.some((a) => (a.endsWith("/") ? f.startsWith(a) : f === a)));
    const missing = ["dist/plugin.js", "dist/plugin.cjs.js", "dist/esm/index.js", "dist/esm/index.d.ts"].filter((f) => !paths.includes(f));
    if (extra.length || missing.length) {
      console.error(`not in package.json "files": ${extra.join(", ") || "-"}; missing: ${missing.join(", ") || "-"}`);
      process.exit(1);
    }
    console.log(`${p.entryCount} files, ${Math.round(p.size / 1024)} kB packed`);
  ' "$out" || fail "npm pack has unexpected or missing files"
}

check_clean() {
  [ -z "$(git status --porcelain)" ] || { git status --short; fail "the builds changed tracked files (above)"; }
}

check_all() {
  check_tools
  check_git
  check_version
  check_changelog
  check_signing
  check_private
  run_tests
  check_docs
  check_package
  check_clean
}

release() {
  step "release commit and tag"
  node -e '
    const fs = require("fs");
    const [v, date] = process.argv.slice(1);
    const s = fs.readFileSync("CHANGELOG.md", "utf8");
    fs.writeFileSync("CHANGELOG.md", s.replace("## [Unreleased]\n", `## [Unreleased]\n\n## [${v}] - ${date}\n`));
  ' "$V" "$(date +%F)"
  # Sets package.json and package-lock.json; the "version" script copies the version to src/web/device.ts.
  npm version "$V" --no-git-tag-version >/dev/null
  [ "$(web_version)" = "$V" ] || fail "src/web/device.ts has '$(web_version)', not $V"
  npm test
  git add CHANGELOG.md package.json package-lock.json src/web/device.ts
  [ -z "$(git status --porcelain | grep -v '^M  ' || true)" ] || { git status --short; fail "unexpected changes (above)"; }
  git commit -q -S -m "$V"
  git tag -s "v$V" -m "$V"
  echo "commit $(git rev-parse --short HEAD) and tag v$V, not pushed yet"

  local answer sha id=""
  read -r -p "Push master and v$V to origin and create the GitHub release v$V (it starts the npm publish)? Type yes: " answer
  [ "$answer" = yes ] || fail "stopped before pushing. To undo the commit and the tag: git tag -d v$V && git reset --hard HEAD~1"

  step "push and GitHub release"
  git push --atomic origin master "refs/tags/v$V"
  sha=$(git rev-parse HEAD)
  gh release create "v$V" --repo "$REPO" --verify-tag --title "$V" --notes-file <(section "$V")

  step "npm-publish.yml"
  for _ in $(seq 30); do
    id=$(gh run list --repo "$REPO" --workflow npm-publish.yml --event release --json databaseId,headSha \
      --jq ".[] | select(.headSha == \"$sha\") | .databaseId" | head -1)
    [ -n "$id" ] && break
    sleep 2
  done
  [ -n "$id" ] || fail "npm-publish.yml did not start within 60 s: https://github.com/$REPO/actions"
  gh run watch "$id" --repo "$REPO" --exit-status ||
    fail "npm-publish.yml failed: gh run view $id --repo $REPO --log-failed"
  echo
  echo "$V is staged on npm. Approve it on npmjs.com (package -> Staged Packages) with 2FA, then:"
  echo "  scripts/release.sh verify $V"
}

verify() {
  step "npm"
  [ "$(npm view "$PKG@$V" version 2>/dev/null)" = "$V" ] || fail "npm has no $V yet: approve the staged version first"
  [ "$(npm view "$PKG" dist-tags.latest)" = "$V" ] || fail "npm latest is not $V"
  local provenance
  provenance=$(npm view "$PKG@$V" dist.attestations.provenance.predicateType 2>/dev/null)
  [ -n "$provenance" ] || fail "$V on npm has no provenance statement"
  echo "$PKG@$V is latest, provenance $provenance"
}

V=${2:-}
[ -n "$V" ] || { sed -n '2,10p' "$0"; exit 2; }
cd "$(git rev-parse --show-toplevel)"
case ${1:-} in
  check) check_all; printf '\nAll checks passed for %s. No tracked file changed.\n' "$V" ;;
  run) check_all; release ;;
  verify) verify ;;
  *) sed -n '2,10p' "$0"; exit 2 ;;
esac
