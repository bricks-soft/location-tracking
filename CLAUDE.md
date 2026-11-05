# CLAUDE.md

## Checks and CI

- **TypeScript checks run on GitHub on every push** (`.github/workflows/typescript.yml`). Nothing to start by hand.
- **Native checks (Android, iOS) must be run on your own computer before a pull request is finalized.** Run the steps
  of `.github/workflows/android.yml` (or `scripts/release.sh check <version>`, which runs them), and the iOS steps on
  a Mac. Say in the pull request which native checks you ran and where.
- **Native workflows on GitHub start by hand only, when needed** (`gh workflow run <file> --ref <branch>`, or the
  Actions tab). Never add `push`, `pull_request` or `schedule` triggers to them: they cost the most Actions minutes,
  macOS above all. See docs/DECISIONS.md, "CI workflows".
