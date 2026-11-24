# IdleStow Python worker

Persistent optimization worker launched by the Java host: `python -m idlestow.worker`.
Protocol: `docs/architecture/ipc-protocol.md` (line-delimited JSON over stdin/stdout, v1).
No mandatory runtime dependencies. Optional: `zstandard==0.25.0` (`tar.zst`), `psutil` (`system_status`).

## Layout

- `idlestow/ipc/` framing (1 MiB bounded lines), request validation, dispatcher, FIFO work thread (queue cap 16).
- `idlestow/analysis/` format detection, entropy, sampling, zip/zstd/NTFS estimates.
- `idlestow/compression/` `create_archive` (zip, tar.zst).
- `idlestow/verification/` `hash_file`, `verify_archive`, `extract_archive`, `inspect_archive`.
- `idlestow/fixtures.py` synthetic test trees; `idlestow/benchmarking/` throughput benchmark.

## Development

```
python -m pip install --user zstandard==0.25.0 pytest ruff
python -m pytest -q
python -m ruff check .
python -m idlestow.fixtures --out <empty dir> --preset mixed|benchmark --files N --seed S
python -m idlestow.benchmarking --size-mib 32 --repeat 3
```

Decisions where the contract is silent are listed in the task report (error codes for unsafe archive
members, `compressed_size` for tar members, savings clamped at 0).
