"""Magic-byte format detection (extension is never consulted)."""

from __future__ import annotations

import codecs

HEAD_BYTES = 8192

_SIMPLE_MAGIC: tuple[tuple[bytes, str], ...] = (
    (b"\xff\xd8\xff", "jpeg"),
    (b"\x89PNG\r\n\x1a\n", "png"),
    (b"GIF87a", "gif"),
    (b"GIF89a", "gif"),
    (b"\x1aE\xdf\xa3", "mkv"),
    (b"\x1f\x8b", "gzip"),
    (b"7z\xbc\xaf\x27\x1c", "7z"),
    (b"Rar!\x1a\x07", "rar"),
    (b"\x28\xb5\x2f\xfd", "zstd"),
    (b"\xfd7zXZ\x00", "xz"),
    (b"%PDF-", "pdf"),
    (b"SQLite format 3\x00", "sqlite"),
    (b"\x7fELF", "exe"),
)
_ZIP_MAGIC = (b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08")


def _is_mp3_frame(b0: int, b1: int, b2: int) -> bool:
    if b0 != 0xFF or (b1 & 0xE0) != 0xE0:
        return False
    version = (b1 >> 3) & 3
    layer = (b1 >> 1) & 3
    bitrate = (b2 >> 4) & 0xF
    sample_rate = (b2 >> 2) & 3
    return version != 1 and layer in (1, 2) and bitrate not in (0, 15) and sample_rate != 3


def _ooxml_or_zip(head: bytes) -> str:
    if head[:4] == b"PK\x03\x04" and len(head) >= 30:
        name_len = int.from_bytes(head[26:28], "little")
        first = head[30 : 30 + name_len]
        if first == b"[Content_Types].xml":
            return "ooxml"
    return "zip"


def _looks_like_text(head: bytes) -> bool:
    if not head:
        return False
    if head.startswith((b"\xef\xbb\xbf", b"\xff\xfe", b"\xfe\xff")):
        return True
    if b"\x00" in head:
        return False
    decoder = codecs.getincrementaldecoder("utf-8")()
    try:
        decoder.decode(head, final=False)
        return True
    except UnicodeDecodeError:
        pass
    printable = sum(1 for b in head if b in (9, 10, 13) or 32 <= b < 127 or b >= 128)
    controls = len(head) - printable
    return controls / len(head) < 0.02


def detect_format(head: bytes) -> str:
    """Classify a file from its first bytes. Returns one of the contract's format names."""
    head = head[:HEAD_BYTES]
    if head.startswith(_ZIP_MAGIC):
        return _ooxml_or_zip(head)
    for magic, name in _SIMPLE_MAGIC:
        if head.startswith(magic):
            return name
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return "webp"
    if head[4:8] == b"ftyp":
        return "mp4"
    if head.startswith(b"BZh") and len(head) > 3 and 0x31 <= head[3] <= 0x39:
        return "bzip2"
    if head.startswith(b"MZ"):
        return "exe"
    if head.startswith(b"ID3") or (len(head) >= 3 and _is_mp3_frame(head[0], head[1], head[2])):
        return "mp3"
    if _looks_like_text(head):
        return "text"
    return "unknown"
