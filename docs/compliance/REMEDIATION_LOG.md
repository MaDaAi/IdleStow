# Remediation log (2026-11-24)

| Check(s) | File(s) | Change |
|---|---|---|
| CON-005, 007, 008, 010, 011 | `TERMS.md` (new) | Plain-language draft Terms of Use: licence first, user responsibilities, no warranty, limitation of liability with non-waivable carve-outs, support, trademarks, Ontario governing law with a savings clause. |
| CON-025 | `app/.../ui/Legal.java` (new), `IdleStowApp.java`, `app/pom.xml`, `SettingsView.java` | Agreement screen before the database opens; versioned acceptance in `ui-state.json`; asked again when the version changes; documents bundled into the app and linked from Settings > Advanced. Test: `LegalTest` (3). |
| PRIV-003 | `PRIVACY.md` | Effective date corrected; draft label; what IdleStow does not do; children; change process; responsibility. |
| CON-022 | `SimpleHome`, `OverviewView`, `OnboardingWizard`, `PlanPreviewDialog`, `ExplorerView`, `README.md`, user guide | "Never deletes anything" claims qualified, because Recovery lets the user remove retained originals or compressed copies. |
| LIC-037, DIST-017 | `scripts/package-windows.ps1` | Clears `app/target/lib` before building and refuses duplicate library versions. The previous local package shipped logback 1.5.18 next to 1.5.38. Package rebuilt and verified. |
| LIC-014, LIC-026 | `THIRD-PARTY-NOTICES.md` | Upstream source locations for the GPL-2.0+CE components. |
| LIC-004, LIC-036 | `docs/compliance/sbom.cdx.json` | CycloneDX SBOM of the shipped package. |
| SEC-040 | `SECURITY.md` | Incident steps; best-effort response wording. |
| DIST-019 | `TERMS.md` s. 6 | No support or update promise. |
| Owner request | `assets/branding/*`, `scripts/branding/BrandingGenerator.java`, `app/src/main/resources/branding/*`, `packaging/windows/IdleStow.ico`, `dark.css`, `light.css` | New logo from the owner's SVG; regenerated PNG and ICO sizes; theme accent changed to the logo's mint (contrast checked, ACC-006). |
| Owner request | `app/.../ui/simple/SimpleHome.java` (new), `MainWindow.java`, `IdleStowApp.java`, `base.css`, docs | Simple view (default) with Free up space (transparent compression only); the existing UI becomes the Advanced view. |
| Owner request | `app/.../tray/TrayService.java`, `TrayMenu.java` (new), `IdleStowEngine.pausedUntilMs()` | Themed tray popup with status. Test: `PauseStateTest`. |
| CON-020 | `base.css` | Unticked checkboxes showed a faint tick app-wide (could read as pre-ticked consent); hyperlink text was painted like body text. Both fixed. |
| IP-010 (2026-11-25) | `assets/branding/README.md` | Owner confirmed the logo was made with an AI image tool; origin and its copyright limits recorded. UNVERIFIED to PARTIAL. |
| SEC-039, GIT-007, GIT-001 (2026-11-25) | GitHub settings | Owner enabled private vulnerability reporting (confirmed via API); repository description set. |
| SEC-007, SEC-034, DIST-003, DIST-012, DIST-020, API-* (2026-11-25) | `core/update/*`, `app/update/*`, PRIVACY.md, TERMS.md | Opt-in in-app updater (HTTPS to GitHub only, checksum-verified, rollback, database backup); privacy notice and terms describe it; security review findings M1-M3 and L1-L5 fixed; live end-to-end update tested. |

Rollback: each change is independent; `git checkout e80009b -- <file>` restores any file.
