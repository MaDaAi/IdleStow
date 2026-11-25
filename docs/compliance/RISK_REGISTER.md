# Risk register

Every FAIL, PARTIAL and UNVERIFIED check in `FINAL_AUDIT.json` (38 items), most severe first. None is
CRITICAL. UNVERIFIED means not checked, not proven to be a problem.

| ID | Severity | Status | Risk | Action |
|---|---|---|---|---|
| CON-005 | HIGH | PARTIAL | Appropriate terms of service | Lawyer (Ontario): review TERMS.md before wider distribution. |
| ACC-001 | MEDIUM | UNVERIFIED | Applicable accessibility law | Owner: Narrator + keyboard-only pass before 1.0. |
| AI-006 | MEDIUM | UNVERIFIED | AI-generated code provenance | Owner review. |
| AI-007 | MEDIUM | UNVERIFIED | Similarity to third-party code | - |
| BRAND-001 | MEDIUM | UNVERIFIED | Canadian trademark search (CIPO) | Owner/trademark agent: search CIPO for IDLESTOW / IDLE STOW. |
| BRAND-002 | MEDIUM | UNVERIFIED | US trademark search (USPTO) | Owner: USPTO search incl. dead/pending records. |
| BRAND-004 | MEDIUM | UNVERIFIED | Existing software product names | - |
| CON-009 | MEDIUM | UNVERIFIED | Mandatory consumer rights | Lawyer: confirm consumer-protection applicability and the savings clause. |
| DIST-012 | MEDIUM | PARTIAL | Secure update delivery | As SEC-034. |
| GIT-021 | MEDIUM | PARTIAL | Licence compliance across packages | - |
| IP-003 | MEDIUM | UNVERIFIED | Copied online snippets | Owner: optional similarity scan before a commercial release. |
| IP-005 | MEDIUM | UNVERIFIED | AI-generated code provenance | Owner: line-by-line review of safety-critical code; lawyer if the project becomes commercial. |
| IP-018 | MEDIUM | UNVERIFIED | Employer/university IP obligations | Owner: check any employment/school IP policy covers personal projects. |
| LIC-014 | MEDIUM | PARTIAL | GPL obligations | Owner: attach Temurin and OpenJFX source archives to each GitHub release that ships binaries (or obtain advice on an alternative). |
| LIC-026 | MEDIUM | PARTIAL | Corresponding source availability | As LIC-014. |
| SEC-034 | MEDIUM | PARTIAL | Update mechanism | Owner: consider release signing (key kept offline) and enable GitHub 2FA. |
| ACC-002 | LOW | UNVERIFIED | Applicable standard (WCAG 2.2 AA benchmark) | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-003 | LOW | PARTIAL | Keyboard navigation | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-004 | LOW | UNVERIFIED | Screen-reader compatibility | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-005 | LOW | PARTIAL | Accessible labels | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-007 | LOW | PARTIAL | Text resizing | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-009 | LOW | PARTIAL | Alternatives to visual information | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-010 | LOW | UNVERIFIED | Focus management | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-012 | LOW | UNVERIFIED | No unnecessary barriers | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-013 | LOW | UNVERIFIED | Automated accessibility testing | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-014 | LOW | UNVERIFIED | Manual accessibility testing | Owner: Narrator + keyboard-only pass before 1.0. |
| BRAND-003 | LOW | UNVERIFIED | International trademark search | Owner: search before international promotion. |
| BRAND-005 | LOW | UNVERIFIED | GitHub repository name conflicts | - |
| BRAND-007 | LOW | UNVERIFIED | Domain/company names | - |
| BRAND-008 | LOW | UNVERIFIED | Similar spellings/phonetics | - |
| BRAND-009 | LOW | UNVERIFIED | Logo similarity | - |
| BRAND-013 | LOW | UNVERIFIED | Passing-off/confusion | - |
| DIST-010 | LOW | FAIL | Executable signing | Owner: code-signing certificate if budget allows; publish SHA-256 sums. |
| DIST-011 | LOW | UNVERIFIED | Release checksums | Owner: code-signing certificate if budget allows; publish SHA-256 sums. |
| GIT-013 | LOW | UNVERIFIED | Release artifact integrity | - |
| GIT-018 | LOW | UNVERIFIED | Build reproducibility | - |
| IP-010 | LOW | PARTIAL | Icon and logo rights | Owner: keep a note of which AI tool made it and check that its terms allow public use of the output. |
| SEC-041 | LOW | UNVERIFIED | Release integrity verification | Owner: publish SHA-256 sums with releases. |
