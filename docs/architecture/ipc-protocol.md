# IdleStow Java ⇄ Python Worker IPC Protocol (version 1)

The Java application (the *host*) launches one persistent Python worker process
(`python -m idlestow.worker`) and talks to it over the worker's **standard input and
standard output**. No network sockets are opened.

Java is always the authority for authorization and filesystem mutation. The worker only
reads paths it is given and only creates the exact output paths it is given. It never
deletes anything except partial output that it created during the same request.

## Transport and framing

* UTF-8, one JSON object per line (`\n` terminated). No pretty printing.
* Maximum line length: **1 MiB** in both directions. Longer lines are rejected
  (`MESSAGE_TOO_LARGE`) and the rest of the line is discarded.
* `stdout` carries protocol messages only. All diagnostics go to `stderr`
  (the host captures stderr into the diagnostic log, bounded).
* The host starts the worker with `-u -B -X utf8` (plus `-s` for the bundled runtime, whose `._pth` file
  fixes the import path) and the environment variables
  `PYTHONIOENCODING=utf-8` and `PYTHONPATH=<worker dir>`.
* Authentication: the pipes are private to the parent process, so no extra token is used.
  The worker runs with the same privileges as the host (never elevated).

## Handshake

Immediately after start the worker writes one message:

```json
{"v":1,"type":"ready","worker":"idlestow-worker","version":"0.1.0","protocol":1,
 "python":"3.12.6","pid":1234,
 "capabilities":{"formats":["zip","tar.zst"],"zstd":true,"psutil":false}}
```

`formats` only lists formats that are actually usable (`tar.zst` requires the optional
`zstandard` package).

## Requests

```json
{"v":1,"id":"r-42","op":"hash_file","params":{"path":"C:\\data\\a.txt"}}
```

* `v` must equal `1`; otherwise the worker answers `UNSUPPORTED_VERSION`.
* `id`: 1–64 chars from `[A-Za-z0-9_.-]`. Unique per in-flight request.
* `op`: one of the operations below. Unknown ops → `UNKNOWN_OP`.
* `params`: object. Unknown keys are ignored; missing/invalid required keys → `BAD_REQUEST`.
* All path parameters must be **absolute**, must not contain NUL characters, and must be
  at most 32 000 characters. Otherwise → `UNSAFE_PATH`.

### Scheduling

* Control operations (`ping`, `cancel`, `shutdown`, `capabilities`) are answered
  immediately, even while work is running.
* Work operations are executed **sequentially** (FIFO) on one worker thread.
  The queue holds at most 16 pending work requests; more → `BUSY` (retryable).

## Responses

Exactly one terminal message (`result` or `error`) per request, optionally preceded by
`progress` messages.

```json
{"v":1,"id":"r-42","type":"progress","done":1048576,"total":5242880,"unit":"bytes","stage":"hashing"}
{"v":1,"id":"r-42","type":"result","result":{"sha256":"…","size":5242880}}
{"v":1,"id":"r-42","type":"error","error":{"code":"NOT_FOUND","message":"…","retryable":false}}
```

Progress messages are rate limited (at most ~4 per second per request).
If a request line cannot be parsed at all, the worker answers with `"id":null`.

### Error codes

| Code | Meaning |
|---|---|
| `BAD_REQUEST` | Malformed JSON or invalid parameters |
| `UNSUPPORTED_VERSION` | `v` is not 1 |
| `UNKNOWN_OP` | Unknown operation |
| `MESSAGE_TOO_LARGE` | Line exceeds 1 MiB |
| `UNSAFE_PATH` | Relative path, NUL byte, over-long path, or unsafe archive entry |
| `NOT_FOUND` | Input path does not exist |
| `EXISTS` | Output path already exists (outputs are always created exclusively) |
| `PERMISSION_DENIED` | OS refused access |
| `LIMIT_EXCEEDED` | A size/count/ratio limit was hit (e.g. decompression bomb guard) |
| `INTEGRITY_FAILURE` | Hash/size/structure verification failed |
| `NO_BENEFIT` | Archive would not be smaller than the source (`fail_if_larger`) |
| `UNSUPPORTED_FORMAT` | Format unavailable (e.g. zstandard missing) |
| `CANCELLED` | Cancelled by a `cancel` request |
| `BUSY` | Work queue full (retryable) |
| `IO_ERROR` | Other OS error (retryable when transient) |
| `INTERNAL` | Unexpected worker bug |

## Operations

### `ping` (control)
params `{}` → `{"pong":true,"time_ms":<epoch ms>}`

### `capabilities` (control)
→ same object as the handshake `capabilities`.

### `cancel` (control)
params `{"target":"<request id>"}` → `{"cancelled":true|false}`. The target request then
terminates with `CANCELLED` at its next chunk boundary and removes any partial output it
created.

### `shutdown` (control)
→ `{"bye":true}`; the worker then stops accepting work, cancels running work and exits 0.

### `hash_file`
params `{"path":abs}` → `{"sha256":hex,"size":int}`. Streams in 1 MiB chunks; progress.

### `estimate_compressibility`
params:
```json
{"path":abs, "max_sample_bytes":4194304, "chunk_size":262144, "level":"balanced"}
```
`max_sample_bytes` is clamped to [64 KiB, 64 MiB]. `level` ∈ `fast|balanced|maximum`.
Reads at most `max_sample_bytes` using evenly spaced chunks (whole file if smaller).
Detects the format from magic bytes (not the extension).

Result:
```json
{"file_size":int, "sampled_bytes":int, "full_read":bool,
 "detected_format":"text|jpeg|png|gif|webp|mp3|mp4|mkv|zip|gzip|7z|rar|zstd|xz|bzip2|pdf|ooxml|sqlite|exe|unknown",
 "entropy_bits_per_byte":float,
 "zip_ratio":float,          // estimated archive size / original (deflate)
 "zstd_ratio":float|null,    // when zstandard available
 "ntfs_ratio":float,         // estimated NTFS-compressed allocation / original allocation
 "estimated_zip_savings":int, "estimated_zstd_savings":int|null, "estimated_ntfs_savings":int,
 "basis":"sampled|full"}
```
NTFS estimate: the file is processed in 64 KiB compression units; a unit saves whole
4 KiB clusters only if its compressed proxy size (deflate level 1 × 1.15 to approximate
the weaker LZNT1) fits in fewer than 16 clusters. This is an **estimate** and is labelled
as such everywhere in the UI.

### `create_archive`
params:
```json
{"source":abs, "dest":abs, "format":"zip|tar.zst", "level":"fast|balanced|maximum",
 "entry_name":"report.txt", "expected_sha256":hex|null, "fail_if_larger":true,
 "manifest":{...}}
```
* `dest` must not exist; it is created exclusively. Its parent directory must exist.
* `entry_name` must be a plain file name (no `/`, `\`, `..`, drive letters, NUL, control chars).
* The archive contains exactly two members: the data entry `entry_name` and
  `.idlestow-manifest.json` (written last) containing
  `{"format_version":1,"entry_name","size","sha256","source_mtime_ns","created_utc","tool":"idlestow-worker/0.1.0", ...host manifest keys}`.
* The source is streamed once; its SHA-256 is computed from the exact bytes archived.
  If `expected_sha256` is given and differs → the partial archive is deleted and
  `INTEGRITY_FAILURE` is returned (the source changed).
* The archive file is flushed and `fsync`ed before the result is returned.
* If `fail_if_larger` and archive size ≥ source size → archive deleted, `NO_BENEFIT`.

Result: `{"archive":abs,"archive_size":int,"source_size":int,"source_sha256":hex,"entry_name":str,"format":str}`

### `verify_archive`
params `{"archive":abs,"format":"zip|tar.zst","entry_name":str,"expected_sha256":hex,"expected_size":int}`
Fully decompresses the data entry in a streaming fashion (never to disk), validates that the
archive contains only the data entry and the manifest, that no member name is unsafe, that
the manifest agrees with the expected values, and that the decompressed size and SHA-256
match. Decompression stops with `LIMIT_EXCEEDED` as soon as more than `expected_size` bytes
are produced. → `{"ok":true,"sha256":hex,"size":int,"manifest":{...}}` or `INTEGRITY_FAILURE`.

### `extract_archive`
params `{"archive":abs,"format":str,"entry_name":str,"dest":abs,"expected_sha256":hex,"expected_size":int}`
Writes the data entry to the exact `dest` path (created exclusively; names from inside the
archive are never used to build paths, which makes ZIP-slip impossible). Output is capped
at `expected_size` bytes. On any failure the partial `dest` is removed. Output is fsynced.
→ `{"dest":abs,"size":int,"sha256":hex}`.

### `inspect_archive`
params `{"archive":abs,"format":str}` → `{"members":[{"name","size","compressed_size","unsafe":bool}],"manifest":{...}|null}`
(at most 1000 members listed; bounded manifest size 64 KiB).

### `system_status`
→ `{"cpu_percent":float|null,"battery":{"percent":float,"plugged":bool}|null,"source":"psutil|none"}`

## Host behaviour (Java side)

* Per-request timeouts (default 30 s for control/quick ops; long ops are bounded by an
  inactivity timeout: if no progress/result arrives for 120 s the request fails).
* Heartbeat: `ping` every 30 s while idle. Missed heartbeat or process exit → the worker is
  restarted (bounded: max 3 restarts per 5 minutes), all in-flight requests fail with a
  retryable `WORKER_CRASHED` error on the host side.
* Graceful shutdown: `shutdown`, wait up to 5 s, then destroy the process tree.
