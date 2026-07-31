"""Stable hashes used by splits, manifests, and deterministic identifiers."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any


def sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(chunk_size), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=True,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def sha256_object(value: Any) -> str:
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def stable_bucket(source: str, namespace: str, seed: int, modulus: int = 10_000) -> int:
    payload = f"{namespace}\x1f{seed}\x1f{source}".encode()
    first_u64 = int.from_bytes(hashlib.sha256(payload).digest()[:8], byteorder="big")
    return first_u64 % modulus


def stable_rank(source: str, namespace: str, seed: int) -> int:
    payload = f"{namespace}\x1f{seed}\x1f{source}".encode()
    return int.from_bytes(hashlib.sha256(payload).digest(), byteorder="big")
