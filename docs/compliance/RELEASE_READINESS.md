# Release readiness

**Recommendation: CONDITIONAL** for a free public 0.1.0 release on Windows.

Why not BLOCKED: no critical findings, no high-severity FAIL, no exposed credentials or personal data, the
file-operation safety checks all pass, and every licence is compatible.

Why not READY: these conditions are open.

| # | Condition | Checks | Owner action |
|---|---|---|---|
| 1 | Terms of Use and Privacy notice are unreviewed drafts | CON-005, CON-009 | Have an Ontario lawyer review `TERMS.md` and `PRIVACY.md` (cheapest option: a one-off document review). |
| 2 | GPL corresponding source for the bundled Java runtime and OpenJFX | LIC-014, LIC-026, GIT-021 | Attach the Temurin and OpenJFX source archives to each GitHub release that ships the zip. |
| 3 | Logo rights unrecorded | IP-010 | Confirm where the logo came from. |
| 4 | Trademark not searched | BRAND-001..013 | CIPO and USPTO search for IDLESTOW before promotion. |
| 5 | GitHub private vulnerability reporting not confirmed | SEC-039, GIT-007 | Enable it (Settings > Security). |
| 6 | Unsigned binaries, no published checksums | DIST-010, DIST-011, SEC-041 | Publish SHA-256 sums with the release; signing is optional (costs money). |
| 7 | Accessibility untested | ACC-* | Narrator and keyboard-only pass before 1.0. |

Verified for this release: 424 Java tests and 171 Python tests pass, ruff is clean, GUI smoke test passes from
source and from the rebuilt package, and the package contains only the intended dependency versions
(see `sbom.cdx.json`).
