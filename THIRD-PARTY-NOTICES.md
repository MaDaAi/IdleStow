# Third-party notices

IdleStow source code is licensed under the Apache License 2.0. Binary distributions include the
following third-party components, each under its own license. License texts for the Java runtime
are in `runtime/legal/` of the packaged application; CPython's license is in `worker/python/LICENSE.txt`.

| Component | Version | License | Used for |
|---|---|---|---|
| Eclipse Temurin OpenJDK runtime (jlink subset) | 21.0.13.1+2 | GPL-2.0 with Classpath Exception | Bundled Java runtime (packaged builds) |
| OpenJFX (javafx-base, -graphics, -controls) | 21.0.13 | GPL-2.0 with Classpath Exception | Desktop UI |
| SQLite JDBC (xerial) | 3.54.1.0 | Apache-2.0 (SQLite itself: public domain) | Local database |
| Jackson Databind / Core / Annotations | 2.23.0 | Apache-2.0 | JSON (IPC, manifests, export) |
| SLF4J API | 2.0.20 | MIT | Logging API |
| Logback Classic / Core | 1.5.38 | EPL-1.0 / LGPL-2.1 (dual) | Logging implementation |
| JNA / JNA Platform | 5.19.1 | Apache-2.0 / LGPL-2.1 (dual) | Windows API access |
| CPython (Windows embeddable package) | 3.12.10 | PSF License Version 2 | Bundled Python worker runtime |
| python-zstandard | 0.25.0 | BSD-3-Clause | tar.zst archives |

Test-only dependencies (not distributed): JUnit 5 (EPL-2.0), Mockito (MIT), pytest (MIT), Ruff (MIT).

Exact versions are pinned in `pom.xml`, `python/pyproject.toml` and `scripts/package-windows.ps1`.
