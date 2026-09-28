// Parsing of `logcat -v threadtime [-v UTC -v year]` lines (Unit 7).

/** One parsed logcat line. */
export interface LogcatLine {
  /** the time as printed, e.g. `2026-09-27 10:15:30.123` */
  time: string;
  /**
   * epoch ms when the line has a year (the kit prints `-v UTC -v year`); a `+hhmm`/`-hhmm` zone suffix (`-v zone`) is
   * applied, a time without a suffix is taken as UTC; undefined without a year
   */
  epochMs: number | undefined;
  /** pid of the process that logged the line */
  pid: number;
  tid: number;
  level: 'V' | 'D' | 'I' | 'W' | 'E' | 'F' | 'S';
  tag: string;
  message: string;
  raw: string;
}

// `[YYYY-]MM-DD hh:mm:ss.mmm [zone]  pid  tid L tag: message`
const THREADTIME =
  /^(?:(\d{4})-)?(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})(?:\s+([+-]\d{4}|UTC|GMT))?\s+(\d+)\s+(\d+)\s+([VDIWEFS])\s+(.*?)\s*:\s?(.*)$/;

/** Parses one threadtime line; undefined for other lines (`--------- beginning of main`, blank lines). */
export function parseLogcatLine(raw: string): LogcatLine | undefined {
  const line = raw.replace(/\r$/, '');
  const m = THREADTIME.exec(line);
  if (!m) return undefined;
  const [, year, month, day, hh, mm, ss, ms, zone] = m;
  let offsetMs = 0;
  if (zone !== undefined && /^[+-]\d{4}$/.test(zone)) {
    const sign = zone.startsWith('-') ? -1 : 1;
    offsetMs = sign * (Number(zone.slice(1, 3)) * 60 + Number(zone.slice(3, 5))) * 60_000;
  }
  const epochMs =
    year !== undefined
      ? Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hh), Number(mm), Number(ss), Number(ms)) - offsetMs
      : undefined;
  return {
    time: `${year !== undefined ? `${year}-` : ''}${month}-${day} ${hh}:${mm}:${ss}.${ms}`,
    epochMs,
    pid: Number(m[9]),
    tid: Number(m[10]),
    level: m[11] as LogcatLine['level'],
    tag: m[12]!.trim(),
    message: m[13]!,
    raw: line,
  };
}

/** Parses every threadtime line of [text], skipping other lines. */
export function parseLogcat(text: string): LogcatLine[] {
  const out: LogcatLine[] = [];
  for (const raw of text.split('\n')) {
    const parsed = parseLogcatLine(raw);
    if (parsed) out.push(parsed);
  }
  return out;
}
