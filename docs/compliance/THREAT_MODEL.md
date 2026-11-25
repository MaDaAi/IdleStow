# Threat model

The maintained threat model is [docs/security/threat-model.md](../security/threat-model.md), and the 70-check
security audit is [docs/security/AUDIT-2026-11-25.md](../security/AUDIT-2026-11-25.md). This audit added no new
attack surface. The Simple view and the tray menu call the same engine methods as the Advanced view (approvals go
through `approveRecommendations`, which re-previews and re-checks every file), and the Terms screen only writes a
version string and time to `ui-state.json`.

The 0.1.1 updater adds one outbound surface: HTTPS requests to GitHub. Its threats and mitigations (host
allowlist per redirect hop, exact release-asset URLs, size limits, SHA-256 verification, newer-version-only,
zip-slip and marker-guarded deletion, refusal when the data folder is inside the program folder, database
backup and automatic rollback) were reviewed on 2026-11-25. The residual risk is a compromised GitHub account
publishing a malicious release with matching checksums, because releases are not signed (SEC-034).
