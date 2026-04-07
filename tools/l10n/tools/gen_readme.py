#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAN = ROOT / "docs" / "MAN.md"
README = ROOT / "README.md"

text = MAN.read_text(encoding="utf-8")

# Optional: trim very technical sections if needed
README.write_text(text.strip() + "\n", encoding="utf-8")

print("README.md updated from docs/MAN.md")
