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

## Source code for GPL-licensed components

The Eclipse Temurin runtime and OpenJFX are licensed under the GNU General Public License version 2 with the
Classpath Exception. The Classpath Exception lets IdleStow (Apache-2.0) link to them, but the runtime and
OpenJFX themselves remain under GPL-2.0, so their source code must be available to anyone who receives the
binaries. IdleStow bundles them unmodified. Their corresponding source code is published by their upstream
projects:

- Eclipse Temurin 21 (OpenJDK): https://github.com/adoptium/temurin21-binaries/releases (source archives are
  published with each release) and https://github.com/adoptium/jdk21u
- OpenJFX 21: https://github.com/openjdk/jfx21u (tag for version 21.0.13)

The IdleStow maintainer intends to attach these source archives to each GitHub release that ships binaries.

## Modified components

To keep the download small, the Windows package removes the native libraries for other operating systems
(Linux, macOS and others) from the SQLite JDBC and JNA jars. No other part of those jars, and no Windows
library, is changed. Their unmodified originals are available from Maven Central under the versions listed
above.

Test-only dependencies (not distributed): JUnit 5 (EPL-2.0), Mockito (MIT), pytest (MIT), Ruff (MIT).

Exact versions are pinned in `pom.xml`, `python/pyproject.toml` and `scripts/package-windows.ps1`.
