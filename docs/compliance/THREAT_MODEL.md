# Threat model

The maintained threat model is [docs/security/threat-model.md](../security/threat-model.md), and the 70-check
security audit is [docs/security/AUDIT-2026-11-25.md](../security/AUDIT-2026-11-25.md). This audit added no new
attack surface. The Simple view and the tray menu call the same engine methods as the Advanced view (approvals go
through `approveRecommendations`, which re-previews and re-checks every file), and the Terms screen only writes a
version string and time to `ui-state.json`.
