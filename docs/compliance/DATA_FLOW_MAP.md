# Data flow map

```
 user picks folder --> IdleStow app (Java, JavaFX) --> SQLite database (data folder)
                           |  ^                    +-> logs, manifests, ui-state.json (data folder)
       stdin/stdout JSON   |  | results
                           v  |
                  Python worker (child process): reads selected files, writes verified archives
                           |
                           v
          the user's own folders (NTFS compression flag, archives, .idlestow/retained)
```

The only network flow (since 0.1.1) is the opt-in updater:

```
 IdleStow app --HTTPS GET (daily if allowed, or on "Check for updates")--> api.github.com  (latest version)
 IdleStow app --HTTPS GET (only after "Install update")--> github.com release files (zip, SHA256SUMS.txt)
```

Sent: a generic User-Agent `IdleStow-Updater/<version>` and what any HTTPS request carries (IP address). Never
sent: file names, contents, settings or statistics. Checks: `NetworkAccessArchitectureTest` fails the build if any
class other than `core/update/HttpsTransport` (or any worker module) uses networking APIs; the packaged smoke
run (2026-11-24) opened no TCP or UDP endpoints; the live updater test (2026-11-25) contacted only GitHub hosts.
