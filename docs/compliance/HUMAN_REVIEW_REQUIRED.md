# Human review required

| Item | Who | Why |
|---|---|---|
| TERMS.md and PRIVACY.md (CON-005, CON-009) | Lawyer licensed in Ontario | Drafts written from verified facts, not legal advice. Confirm the warranty disclaimer, liability limit, governing-law clause and consumer-protection applicability (the Ontario statute could not be fetched). |
| Trademark search (BRAND-001..013) | Owner or trademark agent | Only informal web searches were done. |
| Logo rights (IP-010) | Owner | The logo was AI-generated (recorded). Note which tool made it and check that its terms allow public use of the output. |
| AI-assisted code (IP-005, AI-006, AI-007) | Owner; lawyer if commercial | Review safety-critical code; copyright in AI-generated code is unsettled. |
| Employer or school IP policy (IP-018) | Owner | Confirm no agreement claims your personal projects. |
| GPL source availability (LIC-014, LIC-026) | Owner | Attach Temurin and OpenJFX source archives to each binary release. |
| GitHub settings | Owner | Private vulnerability reporting and the description are done (2026-11-25). Still recommended: branch protection on `main` and two-factor authentication on your account. |
| Accessibility (ACC-*) | Owner, ideally with users of assistive technology | Narrator and keyboard-only testing; confirm AODA does not apply to you as an individual. |
| Code signing and update signing (DIST-010, DIST-012, SEC-034) | Owner | Checksums are published and verified by the updater. Signing (a code-signing certificate, or a free open-source signing program) would also protect users if your GitHub account were taken over. Turn on GitHub 2FA either way. |
| Business matters | Owner; accountant if monetised | Nothing is needed for a free personal project. If IdleStow is ever sold or offered with paid support, revisit pricing and refund checks (CON-012..018) and the EU Cyber Resilience Act (REG-001), and consider errors-and-omissions insurance. Incorporating does not remove all personal liability. |
