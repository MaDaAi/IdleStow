# LegalGuard checklist (final)

Generated from `FINAL_AUDIT.json`. Baseline status is from `BASELINE_AUDIT.json` (commit e80009b).

| ID | Criterion | Severity | Baseline | Final | Evidence | Human action |
|---|---|---|---|---|---|---|
| IP-001 | Original source-code ownership | MEDIUM | PASS | PASS | Git history: every commit authored by MaDaAi (Mansoor Alakoozai); no other contributors. |  |
| IP-002 | Authorization for third-party code | MEDIUM | PASS | PASS | Third-party code only via declared Maven/PyPI dependencies and bundled runtimes (see LIC-*). |  |
| IP-003 | Copied online snippets | MEDIUM | UNVERIFIED | UNVERIFIED | No record of copied snippets; no code-similarity scan was run. | Owner: optional similarity scan before a commercial release. |
| IP-004 | Code from other repositories | LOW | PASS | PASS | No vendored or copied repositories; packaging/windows/build and dist are git-ignored build output. |  |
| IP-005 | AI-generated code provenance | MEDIUM | UNVERIFIED | UNVERIFIED | Most source was written with an AI coding assistant at the owner's direction. Copyright protection of AI-generated portions is legally unsettled; provenance not independently reviewed. | Owner: line-by-line review of safety-critical code; lawyer if the project becomes commercial. |
| IP-006 | Copyright notice accuracy | LOW | PASS | PASS | NOTICE, README, package metadata, Settings > About: Copyright 2026 Mansoor Alakoozai. |  |
| IP-007 | Third-party notices preserved | MEDIUM | PASS | PASS | THIRD-PARTY-NOTICES.md; packaged runtime/legal (Temurin) and worker/python/LICENSE.txt; dependency jars shipped intact with META-INF notices. |  |
| IP-008 | Documentation ownership | LOW | PASS | PASS | All docs written in-project. |  |
| IP-009 | Image/illustration rights | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No images or illustrations other than the logo (IP-010). |  |
| IP-010 | Icon and logo rights | LOW | PASS | PARTIAL | Baseline: simple original geometric mark drawn in-project. Final: logo replaced with an owner-supplied SVG that the owner generated with an AI image tool (origin recorded in assets/branding/README.md). The specific tool and its output terms are not recorded; purely AI-generated images may not be copyrightable; no similarity search (BRAND-009). | Owner: keep a note of which AI tool made it and check that its terms allow public use of the output. |
| IP-011 | Font licensing | LOW | PASS | PASS | No fonts bundled; CSS references system fonts (Segoe UI etc.). |  |
| IP-012 | Audio/music licensing | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No audio. |  |
| IP-013 | Video/animation licensing | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No video or animation assets. |  |
| IP-014 | Dataset rights | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No datasets; tests use generated synthetic files. |  |
| IP-015 | Screenshot rights | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No screenshots committed (README says so). |  |
| IP-016 | Contractor contributions | MEDIUM | NOT_APPLICABLE | NOT_APPLICABLE | No contractors (git history). |  |
| IP-017 | Collaborator contributions | MEDIUM | PASS | PASS | No merged third-party contributions; CONTRIBUTING.md sets inbound Apache-2.0 for future ones. |  |
| IP-018 | Employer/university IP obligations | MEDIUM | UNVERIFIED | UNVERIFIED | Owner's employment or school agreements are unknown to the audit. | Owner: check any employment/school IP policy covers personal projects. |
| IP-019 | No proprietary code or trade secrets | HIGH | PASS | PASS | No proprietary sources, SDKs or keys found (secret scan of history: 0 findings). |  |
| IP-020 | No copied expressive UI/artwork | LOW | PASS | PASS | UI and icons are original; logo motif (document + moon) is generic. |  |
| BRAND-001 | Canadian trademark search (CIPO) | MEDIUM | UNVERIFIED | UNVERIFIED | Not performed: the CIPO database is interactive and was not searchable from the audit environment. | Owner/trademark agent: search CIPO for IDLESTOW / IDLE STOW. |
| BRAND-002 | US trademark search (USPTO) | MEDIUM | UNVERIFIED | UNVERIFIED | Not performed in the official database. An informal web search (2026-11-24) found no IDLESTOW record; nearest marks: IDLEWISE (99895042), IDLEBOT (99253682), NO IDLE (97663756), STOW IT (5417092). Not a clearance. | Owner: USPTO search incl. dead/pending records. |
| BRAND-003 | International trademark search | LOW | UNVERIFIED | UNVERIFIED | WIPO Global Brand Database not searched. | Owner: search before international promotion. |
| BRAND-004 | Existing software product names | MEDIUM | UNVERIFIED | UNVERIFIED | Informal web search found no software named IdleStow; similar: Idle Smart, IdleDownloadManager, IdleBackup. Limited search. |  |
| BRAND-005 | GitHub repository name conflicts | LOW | UNVERIFIED | UNVERIFIED | Not searched (gh CLI unavailable to the audit). |  |
| BRAND-006 | App-store name conflicts | LOW | NOT_APPLICABLE | NOT_APPLICABLE | Not distributed through app stores. |  |
| BRAND-007 | Domain/company names | LOW | UNVERIFIED | UNVERIFIED | Not checked. |  |
| BRAND-008 | Similar spellings/phonetics | LOW | UNVERIFIED | UNVERIFIED | Not systematically checked (see BRAND-002 informal results). |  |
| BRAND-009 | Logo similarity | LOW | UNVERIFIED | UNVERIFIED | No image-similarity search for the new logo. |  |
| BRAND-010 | No misleading affiliations | LOW | PASS | PASS | Microsoft/Windows/NTFS/GitHub named only descriptively; no endorsement claims. |  |
| BRAND-011 | Correct (R)/(TM) symbols | LOW | PASS | PASS | No registration symbols used; TERMS.md states no registration is claimed. |  |
| BRAND-012 | Third-party trademark guidelines | LOW | PASS | PASS | Nominative use only. |  |
| BRAND-013 | Passing-off/confusion | LOW | UNVERIFIED | UNVERIFIED | Depends on BRAND-001..009. |  |
| LIC-001 | Root licence | HIGH | PASS | PASS | LICENSE: Apache License 2.0. |  |
| LIC-002 | Authority to apply licence | HIGH | PASS | PASS | Sole author (IP-001); AI provenance caveat in IP-005. |  |
| LIC-003 | Direct dependency inventory | MEDIUM | PASS | PASS | THIRD-PARTY-NOTICES.md with pinned versions (pom.xml, pyproject, package script). |  |
| LIC-004 | Transitive dependency inventory | MEDIUM | PARTIAL | PASS | Baseline: notices table only. Final: CycloneDX SBOM docs/compliance/sbom.cdx.json generated from the resolved runtime classpath (app/target/lib) plus bundled runtimes and Python packages. |  |
| LIC-005 | Vendored source inventory | LOW | PASS | PASS | None vendored. |  |
| LIC-006 | Native library inventory | MEDIUM | PASS | PASS | Natives ship inside sqlite-jdbc, JNA and JavaFX jars under those jars' licences. |  |
| LIC-007 | External binary inventory | MEDIUM | PASS | PASS | Temurin runtime and CPython embeddable listed with versions and SHA-256 pins. |  |
| LIC-008 | Licence identification | MEDIUM | PASS | PASS | Each component's licence identified in THIRD-PARTY-NOTICES.md. |  |
| LIC-009 | Licence compatibility | HIGH | PASS | PASS | Apache-2.0 app with Apache/MIT/BSD/EPL/PSF deps; GPL-2.0 runtime and OpenJFX used under the Classpath Exception. |  |
| LIC-010 | No unknown licences | MEDIUM | PASS | PASS | None unknown. |  |
| LIC-011 | MIT obligations | LOW | PASS | PASS | SLF4J notice retained (jar + notices). |  |
| LIC-012 | BSD obligations | LOW | PASS | PASS | python-zstandard licence travels in the wheel dist-info; listed in notices. |  |
| LIC-013 | Apache obligations | LOW | PASS | PASS | Jars redistributed unmodified with their META-INF/NOTICE files. |  |
| LIC-014 | GPL obligations | MEDIUM | FAIL | PARTIAL | Temurin runtime and OpenJFX binaries (GPL-2.0+CE) are redistributed in the package. Baseline: no pointer to corresponding source. Final: THIRD-PARTY-NOTICES.md names the upstream source locations; source archives not yet attached to a release (no release exists). | Owner: attach Temurin and OpenJFX source archives to each GitHub release that ships binaries (or obtain advice on an alternative). |
| LIC-015 | LGPL obligations | LOW | PASS | PASS | Logback and JNA are dual-licensed; the EPL/Apache options suffice, so LGPL relinking terms are not relied on. |  |
| LIC-016 | AGPL obligations | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No AGPL components. |  |
| LIC-017 | MPL obligations | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No MPL components. |  |
| LIC-018 | Other copyleft | LOW | NOT_APPLICABLE | NOT_APPLICABLE | None besides LIC-014. |  |
| LIC-019 | Creative Commons | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No CC-licensed content. |  |
| LIC-020 | Proprietary dependency agreements | LOW | NOT_APPLICABLE | NOT_APPLICABLE | None. |  |
| LIC-021 | Non-commercial restrictions | LOW | NOT_APPLICABLE | NOT_APPLICABLE | None. |  |
| LIC-022 | Source-available restrictions | LOW | NOT_APPLICABLE | NOT_APPLICABLE | None. |  |
| LIC-023 | Attribution requirements | LOW | PASS | PASS | THIRD-PARTY-NOTICES.md shipped in the app and shown in Settings > Advanced. |  |
| LIC-024 | Redistribution permissions | MEDIUM | PASS | PASS | All components allow redistribution; Temurin is the redistributable OpenJDK build. |  |
| LIC-025 | Source disclosure obligations (own code) | LOW | PASS | PASS | IdleStow source is public on GitHub. |  |
| LIC-026 | Corresponding source availability | MEDIUM | FAIL | PARTIAL | See LIC-014. | As LIC-014. |
| LIC-027 | Modification disclosure | LOW | PASS | PASS | GPL components bundled unmodified (jlink module subset of the Temurin image). |  |
| LIC-028 | Relinking requirements | LOW | NOT_APPLICABLE | NOT_APPLICABLE | LGPL options not used (LIC-015). |  |
| LIC-029 | Patent provisions | LOW | PASS | PASS | Apache-2.0 patent grant; no patent-retaliation concerns identified. |  |
| LIC-030 | NOTICE obligations | LOW | PASS | PASS | Root NOTICE present; dependency NOTICE files kept inside their jars. |  |
| LIC-031 | Runtime redistribution licensing | MEDIUM | PASS | PASS | Temurin (GPL-2.0+CE) redistributable; Oracle JDK is not bundled. |  |
| LIC-032 | Build-time dependencies | LOW | PASS | PASS | Maven wrapper, JUnit, Mockito, pytest, ruff are not distributed. |  |
| LIC-033 | Installer-bundled dependencies | MEDIUM | PASS | PASS | Portable package contents match THIRD-PARTY-NOTICES.md after the final repackage (see LIC-037). |  |
| LIC-034 | Platform-specific packages | LOW | PASS | PASS | javafx-*-win jars share OpenJFX licence. |  |
| LIC-035 | Commercial-use permissions | LOW | PASS | PASS | All licences permit commercial use. |  |
| LIC-036 | SBOM accuracy | MEDIUM | FAIL | PASS | Baseline: no SBOM. Final: docs/compliance/sbom.cdx.json (CycloneDX 1.5). |  |
| LIC-037 | Released artifact licence compliance | MEDIUM | FAIL | PASS | Baseline: the local package dist/windows/IdleStow shipped logback 1.5.18 AND 1.5.38 (stale jars), so it did not match the notices and kept a vulnerable version. Final: package script clears app/target/lib and refuses duplicate library versions; rebuilt package verified to contain only logback 1.5.38. |  |
| LIC-038 | Container images | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No containers. |  |
| LIC-039 | Dual-licence selection | LOW | PASS | PASS | Dual licences listed; permissive option sufficient. |  |
| LIC-040 | Ongoing licence monitoring | LOW | PASS | PASS | .github/dependabot.yml (Maven, pip, Actions) and pip-audit in CI. |  |
| PRIV-006 | Lawful processing grounds | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-011 | Access requests | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-012 | Correction requests | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-013 | Portability | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-016 | Analytics disclosures | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-017 | Crash-reporting disclosures | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-018 | Cookies/tracking | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-019 | Advertising identifiers | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-020 | Cross-border transfers | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-021 | Third-party processors | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-022 | Processor agreements | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-023 | Children's information | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-024 | Sensitive information | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-025 | Biometrics | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-026 | Location | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-027 | Screen/audio recording | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-028 | Mic/camera permissions | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-031 | Breach reporting obligations | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-032 | Incident notification | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-033 | Breach records | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-035 | Account deletion | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-037 | OAuth token storage | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-039 | Personal information in error reports | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-040 | Privacy impact assessment | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow collects nothing remotely; the developer receives no personal information (no network code, verified). No accounts, analytics, crash reporting, cookies, ads, sensors, OAuth or processors; PIPEDA applies to collection in the course of commercial activity (OPC PIPEDA brief). |  |
| PRIV-001 | Personal information inventory | MEDIUM | PASS | PASS | PRIVACY.md table; docs/compliance/DATA_INVENTORY.md (final). |  |
| PRIV-002 | Data-flow mapping | MEDIUM | PASS | PASS | Threat model data flows; docs/compliance/DATA_FLOW_MAP.md (final). |  |
| PRIV-003 | Privacy policy accuracy | HIGH | PARTIAL | PASS | Baseline: effective date 2026-11-25 was in the future; no statement on children, sensors, change process; no-network claim not runtime-tested. Final: fixed, marked as draft; no-network claim checked by source search and by sampling the app's and worker's TCP/UDP endpoints during a smoke run (none). |  |
| PRIV-004 | Collection disclosures | MEDIUM | PASS | PASS | PRIVACY.md lists stored data and purposes; Settings > Privacy repeats it. |  |
| PRIV-005 | Meaningful consent | MEDIUM | PASS | PASS | Folders are chosen explicitly by the user; nothing preselected. |  |
| PRIV-007 | Data minimization | LOW | PASS | PASS | Metadata only; file contents read transiently for estimates/checksums/archives. |  |
| PRIV-008 | Purpose limitation | LOW | PASS | PASS | Data used only for indexing, recommendations and recovery. |  |
| PRIV-009 | Retention schedules | LOW | PASS | PASS | Local, user-controlled; logs size-limited and rotating; documented. |  |
| PRIV-010 | Deletion procedures | LOW | PASS | PASS | Clear index, clear logs, delete data folder (documented). |  |
| PRIV-014 | Consent withdrawal | LOW | PASS | PASS | Removing a folder stops indexing and deletes its index entries. |  |
| PRIV-015 | Telemetry controls | HIGH | PASS | PASS | No telemetry code: no java.net/HttpClient/socket use in core/platform/app, no urllib/requests/socket in python/idlestow (grep 2026-11-24). |  |
| PRIV-029 | File-system access | MEDIUM | PASS | PASS | Authorizer limits reads/changes to authorized roots; protected system paths refused. |  |
| PRIV-030 | Background collection | LOW | PASS | PASS | Background scanning is opt-in (default off) and documented. |  |
| PRIV-034 | Privacy contact | LOW | PASS | PASS | GitHub issues/private reporting named in PRIVACY.md. |  |
| PRIV-036 | Default privacy settings | LOW | PASS | PASS | Defaults: background off, start-at-login off, Ask mode. |  |
| PRIV-038 | Personal information in logs | LOW | PASS | PASS | Logs may contain paths (disclosed), never contents; Clear logs available. |  |
| SEC-001 | Hardcoded passwords | MEDIUM | PASS | PASS | N/A to app; history secret scan 0 findings. |  |
| SEC-002 | Hardcoded API keys | MEDIUM | PASS | PASS | None (scan). |  |
| SEC-003 | Published tokens | MEDIUM | PASS | PASS | None (scan). |  |
| SEC-004 | Secrets in history | MEDIUM | PASS | PASS | Full-history scan 0 findings (security audit 2026-11). |  |
| SEC-005 | Secrets in artifacts | MEDIUM | PASS | PASS | Project has no credentials to leak; package contents reviewed. |  |
| SEC-006 | Credential storage | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No credentials. |  |
| SEC-007 | Encryption in transit | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No network traffic. |  |
| SEC-008 | Encryption at rest | LOW | NOT_APPLICABLE | NOT_APPLICABLE | Local metadata under the user's profile; OS account protection; encryption not warranted for a local index. |  |
| SEC-009 | Authentication | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No accounts. |  |
| SEC-010 | Authorization enforcement | MEDIUM | PASS | PASS | Authorizer + plan-bound approvals; regression tests. |  |
| SEC-011 | Privilege escalation | MEDIUM | PASS | PASS | Runs unelevated; no services. |  |
| SEC-012 | Least privilege | MEDIUM | PASS | PASS | No admin rights needed. |  |
| SEC-013 | Input validation | MEDIUM | PASS | PASS | IPC schema validation; path normalization. |  |
| SEC-014 | SQL injection | MEDIUM | PASS | PASS | Parameterized SQL only. |  |
| SEC-015 | Command injection | MEDIUM | PASS | PASS | Subprocess argument arrays; no shell. |  |
| SEC-016 | XSS | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No web UI. |  |
| SEC-017 | CSRF | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No web UI. |  |
| SEC-018 | SSRF | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No network. |  |
| SEC-019 | Path traversal | MEDIUM | PASS | PASS | Root containment checks; tests. |  |
| SEC-020 | Insecure extraction | MEDIUM | PASS | PASS | Zip-slip and bomb limits; tests. |  |
| SEC-021 | Unsafe deserialization | MEDIUM | PASS | PASS | Jackson without default typing. |  |
| SEC-022 | Remote code execution | MEDIUM | PASS | PASS | No remote input surface. |  |
| SEC-023 | Dependency vulnerabilities | MEDIUM | PASS | PASS | OSV query 2026-11-24: 0 advisories for runtime deps after logback 1.5.38 (see LIC-037 for the stale package). |  |
| SEC-024 | Unsupported dependencies | MEDIUM | PASS | PASS | All maintained, current versions. |  |
| SEC-025 | Insecure endpoints | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No endpoints. |  |
| SEC-026 | Unsafe defaults | MEDIUM | PASS | PASS | Ask mode, background off, autonomy needs explicit confirmation. |  |
| SEC-027 | Session management | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No sessions. |  |
| SEC-028 | Password hashing | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No passwords. |  |
| SEC-029 | Rate limiting | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No network service. |  |
| SEC-030 | Resource abuse | MEDIUM | PASS | PASS | Bounded concurrency, size limits. |  |
| SEC-031 | Temporary files | MEDIUM | PASS | PASS | IdleStow-owned temp files next to targets, cleaned. |  |
| SEC-032 | Symlink/junction attacks | MEDIUM | PASS | PASS | Reparse points never modified; identity revalidation. |  |
| SEC-033 | Race conditions | MEDIUM | PASS | PASS | Per-path and per-record locks; identity re-check before change. |  |
| SEC-034 | Update mechanism | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No auto-update. |  |
| SEC-035 | Supply-chain integrity | MEDIUM | PASS | PASS | Pinned SHA-256 for Maven wrapper, Temurin, CPython, zstandard; Actions pinned to SHAs. |  |
| SEC-036 | Build artifact integrity | MEDIUM | PASS | PASS | Inputs checksum-verified; zip hashed. |  |
| SEC-037 | TLS validation | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No TLS clients in app. |  |
| SEC-038 | Security logging | MEDIUM | PASS | PASS | Operation journal and history. |  |
| SEC-042 | Admin permissions | MEDIUM | PASS | PASS | None required. |  |
| SEC-043 | Debug interfaces | MEDIUM | PASS | PASS | No debug ports; --smoke-test/--self-test are local and refuse non-empty dirs. |  |
| SEC-044 | IPC security | MEDIUM | PASS | PASS | Private stdio pipes to a child process. |  |
| SEC-045 | Threat modelling | MEDIUM | PASS | PASS | docs/security/threat-model.md. |  |
| SEC-039 | Vulnerability reporting | MEDIUM | PARTIAL | PARTIAL | SECURITY.md exists; GitHub private vulnerability reporting not verified as enabled. | Owner: Settings > Security > enable private vulnerability reporting. |
| SEC-040 | Incident response planning | LOW | FAIL | PASS | Baseline: none. Final: SECURITY.md 'If a data-loss or security bug is found' steps. |  |
| SEC-041 | Release integrity verification | LOW | UNVERIFIED | UNVERIFIED | No release published; binaries unsigned (DIST-010). | Owner: publish SHA-256 sums with releases. |
| DATA-001 | Explicit authorization | HIGH | PASS | PASS | Every change needs a user approval, a plan preview or a confirmed autonomous policy. The new Simple view's Free up space asks for confirmation and uses the same approveRecommendations path with transparent compression only. |  |
| DATA-002 | Operation previews | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-003 | Scope boundaries | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-004 | Dry run (read-only mode, plan preview) | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-005 | Recoverability | HIGH | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-006 | Backup guidance (not a backup tool; disclosed) | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-007 | Integrity verification | HIGH | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-008 | Atomic writes | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-009 | Disk-space failure handling | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-010 | Interrupted-operation recovery | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-011 | Crash recovery | HIGH | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-012 | Original preserved until verified | HIGH | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-013 | Metadata preservation | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-014 | Ownership/permission preservation | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-015 | Files in use | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-016 | System-directory protection | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-017 | Cloud-sync placeholders skipped | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-018 | Symlink/junction protection | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-019 | Concurrent modifications | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-020 | Restore | HIGH | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-021 | Archive integrity | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-022 | Exclusions/allowlists | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-023 | Autonomous authorization | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-024 | Revocable autonomy | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-025 | History/audit logs | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-026 | Emergency stop (Pause) | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-027 | Uncertain evidence treated as UNKNOWN | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| DATA-028 | Synthetic test data only | MEDIUM | PASS | PASS | Journaled state machine, CrashRecoveryTest, EmergencyRecoveryTest, SafeMove, Authorizer; 424 Java tests pass (2026-11-24). |  |
| AI-001 | AI provider terms | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| AI-002 | Commercial-use permissions | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| AI-003 | Model weight licensing | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| AI-004 | Model redistribution | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| AI-005 | Model output conditions | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| AI-006 | AI-generated code provenance | MEDIUM | UNVERIFIED | UNVERIFIED | See IP-005. | Owner review. |
| AI-007 | Similarity to third-party code | MEDIUM | UNVERIFIED | UNVERIFIED | No similarity scan (IP-003). |  |
| AI-008 | Confidential info in prompts | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| AI-009 | Personal data to AI providers | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| AI-010 | AI retention policies | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| AI-011 | AI usage disclosures | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| AI-012 | Prompt injection | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| AI-013 | Autonomous tool access | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| AI-014 | Human approval | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| AI-015 | Content safety | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| AI-016 | AI recommendations/accuracy | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| AI-017 | AI transparency obligations | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| AI-018 | AI risk classification | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| AI-019 | Training-data licensing | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| AI-020 | Automated decisions about people | LOW | NOT_APPLICABLE | NOT_APPLICABLE | The product contains no AI model, AI service or AI-driven decision; its recommendations are rule-based (policy engine). AI was used only as a development tool. |  |
| API-001 | API terms compliance | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-002 | Commercial-use restrictions | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-003 | Rate limits | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-004 | OAuth configuration | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-005 | Minimum OAuth scopes | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-006 | OAuth consent accuracy | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-007 | Application verification | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-008 | API attribution | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-009 | Caching restrictions | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-010 | Redistribution restrictions | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-011 | Scraping restrictions | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-012 | Authentication circumvention | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-013 | Third-party branding | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-014 | Revocation of access | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-015 | Token lifecycle | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-016 | Platform developer policies | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-017 | API key exposure | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-018 | Bundled credentials | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-019 | Third-party data deletion | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| API-020 | Platform distribution requirements | LOW | NOT_APPLICABLE | NOT_APPLICABLE | IdleStow integrates with no external API, OAuth provider or online platform; GitHub hosting is assessed under GIT-020. |  |
| DIST-001 | Transparent installer behaviour | LOW | PASS | PASS | Portable zip; no installer; extracts and runs. |  |
| DIST-002 | Installation consent requirements | LOW | NOT_APPLICABLE | NOT_APPLICABLE | CASL s.8 covers installing a program on another person's computer in the course of commercial activity; users install IdleStow themselves and it is non-commercial. |  |
| DIST-003 | Automatic update consent | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No automatic updates. |  |
| DIST-004 | Background process disclosures | LOW | PASS | PASS | Tray and background behaviour documented; final: also in Terms dialog and guide. |  |
| DIST-005 | Startup behaviour | LOW | PASS | PASS | Start-at-login is opt-in. |  |
| DIST-006 | Uninstall | LOW | PASS | PASS | Documented in README and guide. |  |
| DIST-007 | Removal of optional services | LOW | PASS | PASS | Startup entry removal documented. |  |
| DIST-008 | Admin privilege disclosures | LOW | PASS | PASS | No admin rights needed (stated). |  |
| DIST-009 | No bundled unwanted software | LOW | PASS | PASS | Only required runtimes. |  |
| DIST-010 | Executable signing | LOW | FAIL | FAIL | IdleStow.exe and zip unsigned; SmartScreen warns. | Owner: code-signing certificate if budget allows; publish SHA-256 sums. |
| DIST-011 | Release checksums | LOW | UNVERIFIED | UNVERIFIED | Package script hashes the zip locally; no release published yet. | Owner: code-signing certificate if budget allows; publish SHA-256 sums. |
| DIST-012 | Secure update delivery | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No updates. |  |
| DIST-013 | Package repository requirements | LOW | NOT_APPLICABLE | NOT_APPLICABLE | Not in package repositories. |  |
| DIST-014 | App-store policies | LOW | NOT_APPLICABLE | NOT_APPLICABLE | Not in app stores. |  |
| DIST-015 | Platform distribution agreements | LOW | NOT_APPLICABLE | NOT_APPLICABLE | GitHub only (GIT-020). |  |
| DIST-016 | Installer licence notices | LOW | PASS | PASS | LICENSE, notices (final: also TERMS/PRIVACY) bundled in the app jar; runtime/legal present. |  |
| DIST-017 | Release-artifact contents | MEDIUM | FAIL | PASS | Baseline: stale duplicate logback jars in package (LIC-037). Final: fixed and verified. |  |
| DIST-018 | Supported OS disclosures | LOW | PASS | PASS | README and platform-compatibility.md. |  |
| DIST-019 | Support lifecycle | LOW | UNVERIFIED | PASS | Final: TERMS.md section 6 states there is no promise of support or updates. |  |
| DIST-020 | Update rollback | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No update mechanism; portable versions can be swapped manually. |  |
| CON-001 | Accurate marketing claims | LOW | PASS | PASS | README states limits; estimates labelled. |  |
| CON-002 | Accurate feature descriptions | LOW | PASS | PASS | Docs match implementation; final docs describe Simple view and tray. |  |
| CON-003 | Non-deceptive performance claims | LOW | PASS | PASS | Savings shown as estimates; benchmarks recorded, not promised. |  |
| CON-004 | Clear limitations | LOW | PASS | PASS | Known issues, platform support, not-a-backup warnings. |  |
| CON-005 | Appropriate terms of service | HIGH | FAIL | PARTIAL | Baseline: none. Final: TERMS.md (draft) with first-run acceptance; not reviewed by a lawyer. | Lawyer (Ontario): review TERMS.md before wider distribution. |
| CON-006 | Appropriate licence agreement | LOW | PASS | PASS | Apache-2.0 serves as the licence. |  |
| CON-007 | Warranty language | HIGH | PARTIAL | PASS | Baseline: only in LICENSE file. Final: TERMS.md s.4, first-run screen, Settings > About. |  |
| CON-008 | Limitation of liability | HIGH | PARTIAL | PASS | Baseline: only in LICENSE. Final: TERMS.md s.5 with carve-outs for non-excludable liability. |  |
| CON-009 | Mandatory consumer rights | MEDIUM | UNVERIFIED | UNVERIFIED | TERMS.md preserves non-waivable rights, but applicability of Ontario's Consumer Protection Act, 2002 (and the 2023 Act) to free software was not verified: ontario.ca returned HTTP 403. | Lawyer: confirm consumer-protection applicability and the savings clause. |
| CON-010 | Dispute provisions | LOW | PASS | PASS | No arbitration or class-action waiver; final terms keep users' local court rights. |  |
| CON-011 | Governing law | LOW | FAIL | PASS | Final: Ontario/Canada with a mandatory-law savings clause. |  |
| CON-012 | Clear pricing | LOW | NOT_APPLICABLE | NOT_APPLICABLE | Free. |  |
| CON-013 | Fee disclosures | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No fees. |  |
| CON-014 | Subscription disclosures | LOW | NOT_APPLICABLE | NOT_APPLICABLE | None. |  |
| CON-015 | Recurring payment authorization | LOW | NOT_APPLICABLE | NOT_APPLICABLE | None. |  |
| CON-016 | Cancellation | LOW | NOT_APPLICABLE | NOT_APPLICABLE | Nothing to cancel. |  |
| CON-017 | Refund policy | LOW | NOT_APPLICABLE | NOT_APPLICABLE | Free. |  |
| CON-018 | Free-trial conversion | LOW | NOT_APPLICABLE | NOT_APPLICABLE | None. |  |
| CON-019 | Advertising transparency | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No ads. |  |
| CON-020 | No dark patterns | LOW | PASS | PASS | Final consent box is unticked; Agree disabled until ticked; Exit offered; no nagging. |  |
| CON-021 | Age restrictions | LOW | NOT_APPLICABLE | NOT_APPLICABLE | General utility collecting no personal data; no age gate warranted. |  |
| CON-022 | No misleading security/privacy claims | MEDIUM | PARTIAL | PASS | Baseline: UI and docs said IdleStow 'never deletes anything', but Recovery offers user-initiated removal of retained originals/compressed copies. Final: wording qualified ('on its own'); no-network claim runtime-checked. |  |
| CON-023 | Support commitments | LOW | PASS | PASS | Final SECURITY.md/TERMS.md: best-effort only. |  |
| CON-024 | Service availability commitments | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No online service. |  |
| CON-025 | Acceptance and versioning of agreements | MEDIUM | FAIL | PASS | Final: Legal.TERMS_VERSION matches TERMS.md, acceptance stored with time, re-asked on change; LegalTest (3 tests) passes. |  |
| ACC-001 | Applicable accessibility law | MEDIUM | UNVERIFIED | UNVERIFIED | AODA Integrated Accessibility Standards (O. Reg. 191/11) could not be read (HTTP 403); applicability to an individual without employees unverified. | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-002 | Applicable standard (WCAG 2.2 AA benchmark) | LOW | UNVERIFIED | UNVERIFIED | Not fully assessed. | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-003 | Keyboard navigation | LOW | PARTIAL | PARTIAL | Shortcuts, focus styles; tray popup supports Esc and focusable items; not fully tested. | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-004 | Screen-reader compatibility | LOW | UNVERIFIED | UNVERIFIED | No Narrator test. | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-005 | Accessible labels | LOW | PARTIAL | PARTIAL | Accessible text on nav/icon buttons and folder removal; not exhaustive. | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-006 | Colour contrast | LOW | PASS | PASS | Computed WCAG ratios for key token pairs: old min 4.72, new min 5.00 (light) / 5.71 (dark); all >= 4.5. |  |
| ACC-007 | Text resizing | LOW | PARTIAL | PARTIAL | Display scaling honoured; Windows 'make text bigger' not applied (fixed px sizes). | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-008 | Accessible error messages | LOW | PASS | PASS | Textual errors with next steps. |  |
| ACC-009 | Alternatives to visual information | LOW | PARTIAL | PARTIAL | Charts have tooltips/tables; Simple view is text-first. | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-010 | Focus management | LOW | UNVERIFIED | UNVERIFIED | Not tested. | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-011 | Accessible documentation | LOW | PASS | PASS | Plain Markdown. |  |
| ACC-012 | No unnecessary barriers | LOW | UNVERIFIED | UNVERIFIED | Not assessed with users. | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-013 | Automated accessibility testing | LOW | UNVERIFIED | UNVERIFIED | Not performed. | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-014 | Manual accessibility testing | LOW | UNVERIFIED | UNVERIFIED | Not performed. | Owner: Narrator + keyboard-only pass before 1.0. |
| ACC-015 | Discriminatory functionality | LOW | NOT_APPLICABLE | NOT_APPLICABLE | No decisions about people. |  |
| GIT-001 | Repository description | LOW | UNVERIFIED | UNVERIFIED | Not checked (no GitHub API access during audit). |  |
| GIT-002 | Root licence | LOW | PASS | PASS | LICENSE. |  |
| GIT-003 | Third-party notices | LOW | PASS | PASS | THIRD-PARTY-NOTICES.md. |  |
| GIT-004 | README accuracy | LOW | PASS | PASS | Final README documents Simple/Advanced views and legal docs. |  |
| GIT-005 | Contributor guidelines | LOW | PASS | PASS | CONTRIBUTING.md. |  |
| GIT-006 | Contributor licensing | LOW | PASS | PASS | Inbound Apache-2.0 statement. |  |
| GIT-007 | Security policy | MEDIUM | PARTIAL | PARTIAL | SECURITY.md; private reporting unverified (SEC-039). |  |
| GIT-008 | No sensitive files | LOW | PASS | PASS | Tooling files excluded via .git/info/exclude. |  |
| GIT-009 | No sensitive history | LOW | PASS | PASS | History scan clean. |  |
| GIT-010 | No personal info in commits | LOW | PASS | PASS | GitHub noreply address. |  |
| GIT-011 | No proprietary files | LOW | PASS | PASS | None. |  |
| GIT-012 | No confidential config | LOW | PASS | PASS | None. |  |
| GIT-013 | Release artifact integrity | LOW | UNVERIFIED | UNVERIFIED | No releases yet. |  |
| GIT-014 | CI secret management | LOW | PASS | PASS | No secrets used. |  |
| GIT-015 | Workflow permissions | LOW | PASS | PASS | contents: read. |  |
| GIT-016 | Third-party action integrity | LOW | PASS | PASS | Pinned commit SHAs. |  |
| GIT-017 | Dependency monitoring | LOW | PASS | PASS | Dependabot. |  |
| GIT-018 | Build reproducibility | LOW | UNVERIFIED | UNVERIFIED | Not assessed; not claimed. |  |
| GIT-019 | Accurate release notes | LOW | PASS | PASS | CHANGELOG.md updated. |  |
| GIT-020 | GitHub policy compliance | LOW | PASS | PASS | Ordinary open-source content. |  |
| GIT-021 | Licence compliance across packages | MEDIUM | FAIL | PARTIAL | See LIC-014/LIC-037. |  |
| GIT-022 | No personal data in issues/examples | LOW | PASS | PASS | Synthetic examples. |  |
| GIT-023 | Accurate attribution | LOW | PASS | PASS | Commits credited to the owner. |  |
| GIT-024 | No sensitive info in docs | LOW | PASS | PASS | No exploit details or secrets. |  |
| GIT-025 | Publication permissions | LOW | PASS | PASS | Push authorized by owner. |  |
| REG-001 | EU Cyber Resilience Act applicability | LOW | new | PASS | Regulation (EU) 2024/2847 recital 18: FOSS not monetised is not a commercial activity; recital 20: hosting on open repositories is not making available on the market. Applies generally from 11 Dec 2027. Out of scope while IdleStow is not monetised. | Re-assess if IdleStow is ever sold, monetised or offered with paid support. |
| REG-002 | Encryption export controls | LOW | new | NOT_APPLICABLE | IdleStow compresses but does not encrypt; no cryptographic functionality beyond SHA-256 integrity checks. |  |
| REG-003 | Sector regulation (health, finance, children, safety-critical) | LOW | new | NOT_APPLICABLE | General file utility; TERMS.md s.3(4) excludes safety-critical and regulated-records use. |  |
