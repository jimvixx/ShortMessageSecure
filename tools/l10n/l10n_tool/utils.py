# l10n_tool/utils.py
# -*- coding: utf-8 -*-

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any, List


# Canonical language normalization for internal logic/state.
# Android legacy resource aliases are handled separately in normalize_lang_to_folder().
LANG_ALIASES_TO_CANONICAL = {
    "iw": "he",
    "in": "id",
    "ji": "yi",
}

# Android resource folder aliases.
# Keep internal language codes modern/canonical, but write legacy folder names where Android expects them.
CANONICAL_TO_ANDROID_FOLDER_LANG = {
    "he": "iw",
    "id": "in",
    "yi": "ji",
}


def print_man_and_exit(entry_file: str) -> None:
    """
    Print docs/MAN.md located next to the entry script and exit.
    """
    here = Path(entry_file).resolve().parent
    man_file = here / "docs" / "MAN.md"

    if not man_file.exists():
        print("MAN file not found:", man_file, file=sys.stderr)
        raise SystemExit(1)

    print(man_file.read_text(encoding="utf-8").rstrip())
    raise SystemExit(0)


def sha1_text(s: str) -> str:
    return hashlib.sha1(s.encode("utf-8")).hexdigest()


def load_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def save_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def normalize_lang(lang: str) -> str:
    """
    Normalize language code to canonical/internal form.

    Examples:
      iw -> he
      in -> id
      ji -> yi
    """
    value = (lang or "").strip()
    if not value:
        return value
    return LANG_ALIASES_TO_CANONICAL.get(value, value)


def normalize_lang_to_folder(lang: str) -> str:
    """
    Convert canonical/internal language code to Android values folder name.

    Examples:
      he -> values-iw
      id -> values-in
      yi -> values-ji
      cs -> values-cs
    """
    canonical = normalize_lang(lang)
    folder_lang = CANONICAL_TO_ANDROID_FOLDER_LANG.get(canonical, canonical)
    return f"values-{folder_lang}"


def ensure_strings_xml_exists(path: Path) -> None:
    if path.exists():
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n',
        encoding="utf-8",
    )


def parse_langs_arg(langs: str) -> List[str]:
    if not langs:
        return []
    raw = re.split(r"[,\s]+", langs.strip())
    return [normalize_lang(x.strip()) for x in raw if x.strip()]


def load_langs_from_file(path: str) -> List[str]:
    p = Path(path)
    if not p.exists():
        raise SystemExit(f"Language file not found: {p}")

    out: List[str] = []
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        out.extend(parse_langs_arg(line))

    seen = set()
    uniq: List[str] = []
    for x in out:
        if x not in seen:
            seen.add(x)
            uniq.append(x)
    return uniq


def resolve_langs(langs: str, langs_file: str) -> List[str]:
    a = parse_langs_arg(langs)
    b = load_langs_from_file(langs_file) if langs_file else []

    seen = set()
    merged: List[str] = []
    for x in a + b:
        normalized = normalize_lang(x)
        if normalized not in seen:
            seen.add(normalized)
            merged.append(normalized)
    return merged


def read_text_file(path: str) -> str:
    p = Path(path)
    if not p.exists():
        raise SystemExit(f"File not found: {p}")
    return p.read_text(encoding="utf-8")