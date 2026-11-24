"""Worker entry point: ``python -m idlestow.worker``."""

from __future__ import annotations

import logging
import os
import sys

from .ipc.server import Server


def main() -> int:
    logging.basicConfig(
        stream=sys.stderr,
        level=os.environ.get("IDLESTOW_LOG", "WARNING").upper(),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    protocol_out = sys.stdout.buffer
    protocol_in = sys.stdin.buffer
    # stdout carries protocol messages only: route any stray print() to stderr.
    sys.stdout = sys.stderr
    return Server(protocol_in, protocol_out).serve()


if __name__ == "__main__":
    sys.exit(main())
