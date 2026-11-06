#!/usr/bin/env python3
"""Basic repository secret scan. Not a proof that no secret exists; use before packaging."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SKIP_DIRS = {".git", "target", "node_modules", ".tools", "data", "dist", ".m2", "__pycache__"}
SKIP_NAMES = {".env.example"}
BINARY_SUFFIXES = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".png", ".jar", ".zip", ".db",
                   ".woff", ".woff2", ".ttf", ".eot", ".mp4", ".mov", ".3gp", ".pdf", ".docx", ".xlsx"}
PATTERNS = [
    ("private key", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")),
    ("OpenAI-style key", re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b")),
    ("Anthropic-style key", re.compile(r"\bsk-ant-[A-Za-z0-9_-]{20,}\b")),
    ("key assignment", re.compile(r"(?i)\b(?:LLM_API_KEY|API_KEY|SECRET|PASSWORD)\s*[:=]\s*[\"']?[A-Za-z0-9_\-]{12,}")),
]


def candidate_files():
    for path in ROOT.rglob("*"):
        if not path.is_file() or path.name in SKIP_NAMES:
            continue
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        if path.suffix.lower() in BINARY_SUFFIXES:
            continue
        yield path


def main() -> int:
    findings = []
    for path in candidate_files():
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for label, pattern in PATTERNS:
            for match in pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                findings.append(f"{path.relative_to(ROOT)}:{line}: {label}")
    if findings:
        print("SECRET_SCAN_FINDINGS")
        print("\n".join(findings))
        return 1
    print("SECRET_SCAN_CLEAN (pattern scan only; manually review the package)")
    return 0


if __name__ == "__main__":
    sys.exit(main())