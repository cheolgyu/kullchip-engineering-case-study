from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ALLOWED_SUFFIXES = {
    ".csv",
    ".json",
    ".kt",
    ".md",
    ".py",
    ".rs",
    ".txt",
    ".toml",
    ".lock",
    ".yml",
}
BLOCKED_SUFFIXES = {
    ".aab",
    ".apk",
    ".db",
    ".jks",
    ".jsonl",
    ".keystore",
    ".p12",
    ".parquet",
    ".pem",
    ".sqlite",
    ".tar",
    ".zip",
}
SKIP_PARTS = {".git", "target"}
MAX_FILE_BYTES = 1_000_000

PATTERNS = {
    "private-key": re.compile("BEGIN " + "PRIVATE KEY"),
    "google-api-key": re.compile("AI" + "za[0-9A-Za-z_-]{30,}"),
    "aws-access-key": re.compile("AK" + "IA[0-9A-Z]{16}"),
    "github-token": re.compile("gh" + "[pousr]_[A-Za-z0-9]{30,}"),
    "password-url": re.compile(r"(?i)(postgres|mysql)://[^\s:/]+:[^\s@]+@"),
    "email": re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"),
    "high-precision-coordinate-pair": re.compile(
        r"(?<![\d.])-?(?:[0-8]?\d(?:\.\d{6,})|90(?:\.0{6,}))\s*[,|]\s*"
        r"-?(?:(?:1[0-7]\d|[0-9]?\d)(?:\.\d{6,})|180(?:\.0{6,}))(?![\d.])"
    ),
}


def iter_files() -> list[Path]:
    return [
        path
        for path in ROOT.rglob("*")
        if path.is_file() and not any(part in SKIP_PARTS for part in path.parts)
    ]


def main() -> int:
    failures: list[str] = []
    for path in iter_files():
        relative = path.relative_to(ROOT)
        suffix = path.suffix.lower()
        if suffix in BLOCKED_SUFFIXES:
            failures.append(f"blocked file type: {relative}")
        if path.stat().st_size > MAX_FILE_BYTES:
            failures.append(f"file exceeds {MAX_FILE_BYTES} bytes: {relative}")
        if suffix and suffix not in ALLOWED_SUFFIXES:
            failures.append(f"unreviewed file type: {relative}")
        if path == Path(__file__):
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            failures.append(f"non-UTF-8 file: {relative}")
            continue
        for name, pattern in PATTERNS.items():
            if pattern.search(text):
                failures.append(f"{name} pattern: {relative}")

    metrics = ROOT / "evidence" / "validation" / "metrics.json"
    try:
        json.loads(metrics.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        failures.append(f"invalid metrics JSON: {exc}")

    if failures:
        print("Privacy guard failed:")
        for failure in sorted(set(failures)):
            print(f"- {failure}")
        return 1
    print("Privacy guard passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
