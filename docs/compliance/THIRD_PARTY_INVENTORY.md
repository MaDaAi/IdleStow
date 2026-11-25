# Third-party inventory

The authoritative list with licences is [THIRD-PARTY-NOTICES.md](../../THIRD-PARTY-NOTICES.md). The
machine-readable list of what the portable package actually ships is [sbom.cdx.json](sbom.cdx.json)
(CycloneDX 1.5, generated from `dist/windows/IdleStow/app` after the 2026-11-24 rebuild).

| Licence | Components | Obligation | How it is met |
|---|---|---|---|
| Apache-2.0 | Jackson, SQLite JDBC, JNA, IdleStow | Keep licence and NOTICE | Jars shipped intact; LICENSE and NOTICE bundled |
| MIT | SLF4J | Keep notice | In jar and THIRD-PARTY-NOTICES |
| BSD-3-Clause | python-zstandard | Keep notice | Wheel metadata in the worker folder |
| EPL-1.0 / LGPL-2.1 | Logback | EPL option used | Jar shipped intact |
| PSF-2.0 | CPython | Keep licence | `worker/python/LICENSE.txt` |
| GPL-2.0 + Classpath Exception | Temurin runtime, OpenJFX | Licence texts; corresponding source available | Texts in `runtime/legal`; upstream source pointers in THIRD-PARTY-NOTICES; **attach source archives to releases (open item LIC-014)** |
