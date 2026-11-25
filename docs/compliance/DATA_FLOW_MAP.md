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

There is no network flow. Checks: no networking APIs in the Java modules or `python/idlestow` (source search,
2026-11-24); no TCP or UDP endpoints owned by the packaged app process during a full smoke run (2026-11-24).
The short-lived worker processes were not captured by that sampling, so their no-network status rests on the
source search.
