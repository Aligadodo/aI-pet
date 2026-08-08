#!/usr/bin/env python3
"""Rebuild a public release ZIP without caches, shortcuts, or unsafe paths."""

from __future__ import annotations

import argparse
import os
import shutil
import tempfile
import zipfile
from pathlib import Path, PurePosixPath


SKIPPED_PARTS = {"__pycache__", ".venv", "venv", ".git"}
SKIPPED_SUFFIXES = {".pyc", ".pyo", ".lnk"}
FIXED_TIMESTAMP = (2026, 8, 8, 0, 0, 0)


def normalized_name(raw_name: str) -> str:
    name = raw_name.replace("\\", "/")
    path = PurePosixPath(name)
    if not name or name.startswith("/") or path.is_absolute():
        raise ValueError(f"unsafe absolute ZIP path: {raw_name!r}")
    if any(part in {"", ".", ".."} for part in path.parts):
        raise ValueError(f"unsafe relative ZIP path: {raw_name!r}")
    if ":" in path.parts[0] or any(ord(char) < 32 for char in name):
        raise ValueError(f"unsafe ZIP path characters: {raw_name!r}")
    return path.as_posix()


def should_skip(name: str) -> bool:
    path = PurePosixPath(name)
    lower_parts = {part.lower() for part in path.parts}
    if lower_parts.intersection(SKIPPED_PARTS):
        return True
    return path.suffix.lower() in SKIPPED_SUFFIXES


def copied_info(source: zipfile.ZipInfo, name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=source.date_time)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = source.external_attr
    info.create_system = source.create_system
    info.comment = source.comment
    return info


def injected_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=FIXED_TIMESTAMP)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    info.create_system = 3
    return info


def sanitize(source: Path, output: Path, notices: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    notice_files = [notices / "THIRD_PARTY_NOTICES.txt"]
    notice_files.extend(sorted((notices / "licenses").glob("*.txt")))
    missing = [path for path in notice_files if not path.is_file()]
    if missing:
        raise FileNotFoundError(f"missing notice files: {missing}")

    with tempfile.NamedTemporaryFile(
        prefix=f".{output.name}.", suffix=".tmp", dir=output.parent, delete=False
    ) as temporary:
        temporary_path = Path(temporary.name)

    try:
        seen: set[str] = set()
        with zipfile.ZipFile(source, "r") as archive, zipfile.ZipFile(
            temporary_path, "w", allowZip64=True
        ) as rebuilt:
            for entry in archive.infolist():
                if entry.is_dir():
                    continue
                name = normalized_name(entry.filename)
                if should_skip(name):
                    continue
                key = name.casefold()
                if key in seen:
                    raise ValueError(f"duplicate ZIP path: {name}")
                seen.add(key)
                with archive.open(entry, "r") as source_stream, rebuilt.open(
                    copied_info(entry, name), "w"
                ) as target_stream:
                    shutil.copyfileobj(source_stream, target_stream, length=1024 * 1024)

            for notice in notice_files:
                if notice.name == "THIRD_PARTY_NOTICES.txt":
                    name = notice.name
                else:
                    name = f"THIRD_PARTY_LICENSES/{notice.name}"
                key = name.casefold()
                if key in seen:
                    continue
                seen.add(key)
                rebuilt.writestr(injected_info(name), notice.read_bytes())

        with zipfile.ZipFile(temporary_path, "r") as verified:
            corrupt = verified.testzip()
            if corrupt is not None:
                raise ValueError(f"rebuilt ZIP has a corrupt member: {corrupt}")
            names = {entry.filename for entry in verified.infolist()}
            if "THIRD_PARTY_NOTICES.txt" not in names:
                raise ValueError("rebuilt ZIP is missing THIRD_PARTY_NOTICES.txt")
            if any(should_skip(name) for name in names):
                raise ValueError("rebuilt ZIP still contains a skipped entry")

        os.replace(temporary_path, output)
    finally:
        temporary_path.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--notices", required=True, type=Path)
    args = parser.parse_args()
    sanitize(args.source.resolve(), args.output.resolve(), args.notices.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
