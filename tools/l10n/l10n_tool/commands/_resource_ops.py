# -*- coding: utf-8 -*-
"""
Shared resource operations for l10n_tool commands.

Contains:
- Discovery of values*/strings*.xml files
- Parse-and-detect key occurrences (string/plurals/string-array)
- Best-effort project usage scan with strict/soft classification + context lines
- Delete key from all locales (writes files)

Important:
- For "unused key" detection we MUST NOT treat resource *definitions*
  (i.e. the key declared inside values*/strings*.xml) as "usage".
  Otherwise, every key is "used" because it appears in name="KEY".

But:
- We DO want to count references inside other XML files (including values*/arrays.xml)
  as usage, because they can legitimately reference @string/KEY.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple
import re

from l10n_tool.xml_backend import XmlBackend, read_entries, entry_kind


# ----------------------------- Usage scan (best-effort, strict/soft) -----------------------------

# Strict file extensions: if a key appears here, it is considered a real dependency.
STRICT_EXTS = {
    ".java", ".kt",
    ".xml",
    ".gradle", ".kts",
    ".pro", ".properties",
    ".toml", ".json", ".yaml", ".yml",
}

# Typical docs / auxiliary file extensions (soft mentions).
SOFT_EXTS = {
    ".md", ".txt", ".rst", ".adoc",
    ".csv", ".tsv",
    ".log",
}

# Directories to skip completely (build outputs, caches, VCS, IDE configs).
SKIP_DIR_NAMES = {
    ".git", ".idea", ".gradle", ".kotlin", ".smsecure-l10n", ".venv",
    "build", "out", "target", "node_modules",
    ".cxx", ".externalNativeBuild", ".DS_Store",
}

# File name patterns to skip (binaries, large assets).
SKIP_FILE_SUFFIXES = {
    ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg",
    ".ttf", ".otf", ".jar", ".aar", ".so", ".a", ".o",
    ".zip", ".7z", ".tar", ".gz", ".xz",
    ".apk", ".aab",
    ".keystore",
    ".pdf",
}

MAX_FILE_BYTES = 3_000_000  # 3 MB text cap for scan

# Patterns for typical Android references:
_USAGE_PATTERNS = [
    # Java/Kotlin: R.string.KEY / R.plurals.KEY / R.array.KEY
    re.compile(r"\bR\.(string|plurals|array)\.(?P<key>[A-Za-z0-9_]+)\b"),
    # XML: @string/KEY / @plurals/KEY / @array/KEY
    re.compile(r"@(?P<type>string|plurals|array)/(?P<key>[A-Za-z0-9_]+)"),
]


def _literal_pat(key: str) -> re.Pattern[str]:
    """
    Match the key as a standalone identifier, not as a substring of a larger key.

    Example:
      key = ApplicationPreferencesActivity_delete
      matches:   "... ApplicationPreferencesActivity_delete ..."
      no match:  "... ApplicationPreferencesActivity_delete_all_old_messages_now ..."
    """
    return re.compile(rf"(?<![A-Za-z0-9_]){re.escape(key)}(?![A-Za-z0-9_])")


@dataclass
class UsageExample:
    """
    A single example match: file + line number + line text.
    """
    file: str
    line_no: int
    line: str
    strict: bool


@dataclass
class UsageScanResult:
    """
    Usage scan result with strict/soft split and some examples.
    """
    strict_files: List[str]
    soft_files: List[str]
    examples_strict: List[UsageExample]
    examples_soft: List[UsageExample]


def _is_probably_text_file(path: Path) -> bool:
    """
    Heuristic: treat file as text if it can be decoded as UTF-8 (with errors ignored)
    and has no NUL bytes in the first chunk.
    """
    try:
        with path.open("rb") as f:
            chunk = f.read(8192)
        if b"\x00" in chunk:
            return False
        chunk.decode("utf-8", errors="ignore")
        return True
    except Exception:
        return False


def _should_skip_path(path: Path) -> bool:
    parts = set(path.parts)
    if parts & SKIP_DIR_NAMES:
        return True
    suf = path.suffix.lower()
    if suf in SKIP_FILE_SUFFIXES:
        return True
    return False


def _is_values_resource_definition_xml(path: Path) -> bool:
    """
    Return True only for Android resource *definition* XMLs that must NOT count as usage.

    We are cleaning ONLY strings.xml across locales. Therefore:
    - We MUST ignore values*/strings*.xml as "usage" (otherwise every string key is used).
    - We MUST NOT ignore other values*/ XMLs (arrays.xml, styles.xml, etc),
      because they can legitimately *use* @string/KEY and should count as a dependency.
    """
    if path.suffix.lower() != ".xml":
        return False

    parts = list(path.parts)

    # Detect ".../res/values..." segment in path
    try:
        idx = parts.index("res")
    except ValueError:
        return False

    if idx + 1 >= len(parts):
        return False

    values_dir = parts[idx + 1]
    if not values_dir.startswith("values"):
        return False

    # Ignore ONLY strings*.xml under values*/.
    # This keeps arrays.xml (and any other values*/ XML) in the scan as usage.
    name = path.name.lower()
    if name.startswith("strings") and name.endswith(".xml"):
        return True

    return False


def scan_usage_with_context(
    project_root: Path,
    target_key: str,
    *,
    max_examples_strict: int = 8,
    max_examples_soft: int = 6,
) -> UsageScanResult:
    """
    Scan project_root recursively for references to target_key.

    Classification:
    - strict: matches in files with extensions in STRICT_EXTS
    - soft: matches in other text files (docs, scripts, misc)

    Matching:
    1) Try Android reference patterns (R.string.KEY, @string/KEY, etc.)
    2) Also detect literal key mentions as a fallback.

    IMPORTANT:
    - We skip values*/strings*.xml so that string definitions are not counted as "usage".
    - We DO NOT skip values*/arrays.xml, so @string/KEY inside arrays counts as usage.
    """
    target_key = (target_key or "").strip()
    strict_files_set: set[str] = set()
    soft_files_set: set[str] = set()
    examples_strict: List[UsageExample] = []
    examples_soft: List[UsageExample] = []

    literal_rx = _literal_pat(target_key)
    patterns = list(_USAGE_PATTERNS)

    include_all = True  # scan "almost all" text files

    for p in project_root.rglob("*"):
        if not p.is_file():
            continue
        if _should_skip_path(p):
            continue

        # Do not treat *string definitions* as "usage"
        if _is_values_resource_definition_xml(p):
            continue

        # Size cap for performance/stability
        try:
            if p.stat().st_size > MAX_FILE_BYTES:
                continue
        except Exception:
            continue

        if include_all:
            if not _is_probably_text_file(p):
                continue

        ext = p.suffix.lower()
        is_strict = ext in STRICT_EXTS

        try:
            text = p.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue

        matched_lines: List[Tuple[int, str]] = []

        # Try structured patterns first
        for rx in patterns:
            for m in rx.finditer(text):
                if m.groupdict().get("key") == target_key:
                    start = m.start()
                    line_no = text.count("\n", 0, start) + 1
                    line_start = text.rfind("\n", 0, start) + 1
                    line_end = text.find("\n", start)
                    if line_end == -1:
                        line_end = len(text)
                    line = text[line_start:line_end].strip()
                    matched_lines.append((line_no, line))
                    if len(matched_lines) >= 6:
                        break
            if len(matched_lines) >= 6:
                break

        # If no structured hits, check literal mention
        if not matched_lines and literal_rx.search(text):
            for i, line in enumerate(text.splitlines(), start=1):
                if literal_rx.search(line):
                    matched_lines.append((i, line.strip()))
                    if len(matched_lines) >= 6:
                        break

        if not matched_lines:
            continue

        path_str = str(p)
        if is_strict:
            strict_files_set.add(path_str)
        else:
            soft_files_set.add(path_str)

        # Store examples (bounded)
        for ln, line in matched_lines:
            ex = UsageExample(file=path_str, line_no=ln, line=line, strict=is_strict)
            if is_strict:
                if len(examples_strict) < max_examples_strict:
                    examples_strict.append(ex)
            else:
                if len(examples_soft) < max_examples_soft:
                    examples_soft.append(ex)

    return UsageScanResult(
        strict_files=sorted(strict_files_set),
        soft_files=sorted(soft_files_set),
        examples_strict=examples_strict,
        examples_soft=examples_soft,
    )


def scan_usage(project_root: Path, target_key: str) -> List[str]:
    """
    Backward compatible: return strict+soft file list (merged).
    Prefer scan_usage_with_context() for richer output.
    """
    res = scan_usage_with_context(project_root, target_key)
    return sorted(set(res.strict_files) | set(res.soft_files))


# ----------------------------- XML files discovery -----------------------------

def iter_strings_xml(res_dir: Path) -> List[Path]:
    """
    Find ALL values*/strings*.xml under the given Android res/ directory.
    """
    out: List[Path] = []
    for values_dir in res_dir.glob("values*"):
        if not values_dir.is_dir():
            continue
        for f in values_dir.glob("strings*.xml"):
            if f.is_file():
                out.append(f)
    return sorted(out)


# ----------------------------- Key hits in XML -----------------------------

@dataclass
class Hit:
    file: Path
    kinds: List[str]  # string / plurals / string-array


def detect_hit(xml_backend: XmlBackend, xml_path: Path, key: str) -> Optional[Hit]:
    """
    Parse a strings*.xml and return which resource kinds exist for the given key.
    Returns None if key not present in file.
    """
    entries, _tree = read_entries(xml_backend, xml_path)
    kinds: List[str] = []
    for e in entries:
        if getattr(e, "name", None) == key:
            kinds.append(entry_kind(e))
    kinds = sorted(set(kinds))
    if not kinds:
        return None
    return Hit(xml_path, kinds)


def find_key_hits(res_dir: Path, key: str) -> List[Hit]:
    """
    Find key occurrences across values*/strings*.xml.
    Uses fast text precheck before parsing.
    """
    xml_backend = XmlBackend()
    hits: List[Hit] = []

    for f in iter_strings_xml(res_dir):
        try:
            raw = f.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue

        if f'name="{key}"' not in raw:
            continue

        h = detect_hit(xml_backend, f, key)
        if h:
            hits.append(h)

    return hits


# ----------------------------- Deletion -----------------------------

def remove_key_from_file(xml_backend: XmlBackend, xml_path: Path, key: str) -> List[str]:
    """
    Remove <string>/<plurals>/<string-array> with name=key from one XML file.
    Writes file if anything was removed.

    Returns list of removed XML tags, e.g. ["string"].
    """
    _entries, tree = read_entries(xml_backend, xml_path)
    root = tree.getroot()

    tags = ("string", "plurals", "string-array")
    to_remove = []
    for node in list(root):
        tag = getattr(node, "tag", None)
        if tag in tags and (node.get("name") == key):
            to_remove.append(node)

    removed_tags: List[str] = []
    for node in to_remove:
        removed_tags.append(str(getattr(node, "tag", "")))
        root.remove(node)

    if to_remove:
        xml_path.write_text(xml_backend.tostring(tree), encoding="utf-8")

    return removed_tags


def remove_key_from_hits(hits: List[Hit], key: str) -> List[Tuple[Path, List[str]]]:
    """
    Delete key from a list of Hit objects.
    Returns list of (file, removed_tags) for changed files only.
    """
    xml_backend = XmlBackend()
    changed: List[Tuple[Path, List[str]]] = []
    for h in hits:
        removed = remove_key_from_file(xml_backend, h.file, key)
        if removed:
            changed.append((h.file, removed))
    return changed


# ----------------------------- Base keys extraction -----------------------------

def load_base_keys(values_strings_xml: Path) -> List[Tuple[str, str]]:
    """
    Return list of (kind, name) found in base values/strings.xml.
    kind is one of: "string", "plurals", "string-array".
    """
    xml_backend = XmlBackend()
    entries, _tree = read_entries(xml_backend, values_strings_xml)
    out: List[Tuple[str, str]] = []
    for e in entries:
        out.append((entry_kind(e), getattr(e, "name")))
    out.sort(key=lambda x: (x[0], x[1]))
    return out


def group_candidates_by_key(candidates: List[Tuple[str, str]]) -> Dict[str, List[str]]:
    """
    Convert [(kind, key), ...] into { key: [kind1, kind2] }.
    """
    out: Dict[str, List[str]] = {}
    for kind, key in candidates:
        out.setdefault(key, []).append(kind)
    for k in list(out.keys()):
        out[k] = sorted(set(out[k]))
    return out