# LegalGuard audit summary: IdleStow 0.1.0

- **Audit date:** 2026-11-24 · **Mode:** AUDIT_AND_FIX · **Checklist:** LegalGuard catalogue v1
- **Developer jurisdiction:** Ontario, Canada (individual, no company) · **Distribution:** free, open source (Apache-2.0), GitHub
- **Before:** commit `e80009b` · **After:** working tree on top of `e80009b` (not yet committed)
- **Scope:** repository, user-facing documents, the Windows portable package built locally, the in-app first-run flow.
  Not in scope: live GitHub settings, official trademark databases, Ontario statutes (site returned HTTP 403),
  testing with assistive technology.

**Not legal advice.** The Terms of Use and Privacy notice are drafts based on verified facts about the software and
should be reviewed by an Ontario lawyer. No document or score can guarantee you will not be sued.

## Before and after (original 311 checks)

| Status | Before | After | Change |
|---|---:|---:|---:|
| Total checks | 311 | 311 | 0 |
| Scored checks | 202 | 202 | 0 |
| Passed | 152 | 163 | +11 |
| Failed | 11 | 1 | -10 |
| Partial | 11 | 11 | 0 |
| Unverified | 28 | 27 | -1 |
| Not applicable | 109 | 109 | 0 |
| High findings | 4 | 1 | -3 |
| Medium findings | 21 | 15 | -6 |
| Low findings | 25 | 23 | -2 |
| Verified pass rate | 75.2% | 80.7% | +5.4 pp |

Expanded scope (3 new regulation checks, REG-001..003): 314 checks, 164 PASS, 203 scored, 80.8%.
Counts come from `compare.py` run on `BASELINE_AUDIT.json` and `FINAL_AUDIT.json`.

Resolved (12): LIC-004, LIC-036, LIC-037, PRIV-003, SEC-040, DIST-017, DIST-019, CON-007, CON-008, CON-011,
CON-022, CON-025. Improved but not resolved: CON-005, LIC-014, LIC-026, GIT-021 (FAIL to PARTIAL).
Regression (1): IP-010 is PARTIAL instead of PASS. The new logo was AI-generated (recorded 2026-11-25); the tool's terms are not recorded, and AI-generated images may not be copyrightable.

## Release recommendation: CONDITIONAL

No critical issue remains, and no high-severity FAIL. Conditions are listed in
[RELEASE_READINESS.md](RELEASE_READINESS.md). Every open item is in [RISK_REGISTER.md](RISK_REGISTER.md), and
the items that need a person (lawyer, trademark search, owner confirmations) are in
[HUMAN_REVIEW_REQUIRED.md](HUMAN_REVIEW_REQUIRED.md).

## Files

[CHECKLIST.md](CHECKLIST.md) · [BASELINE_AUDIT.json](BASELINE_AUDIT.json) · [FINAL_AUDIT.json](FINAL_AUDIT.json) ·
[LEGAL_SOURCES.md](LEGAL_SOURCES.md) · [REMEDIATION_LOG.md](REMEDIATION_LOG.md) ·
[THIRD_PARTY_INVENTORY.md](THIRD_PARTY_INVENTORY.md) · [sbom.cdx.json](sbom.cdx.json) ·
[DATA_INVENTORY.md](DATA_INVENTORY.md) · [DATA_FLOW_MAP.md](DATA_FLOW_MAP.md) · [THREAT_MODEL.md](THREAT_MODEL.md) ·
[TRADEMARK_RESEARCH.md](TRADEMARK_RESEARCH.md)
