# l10n_tool/commands/sync.py
# -*- coding: utf-8 -*-

from __future__ import annotations

import re
import time
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

from ..constants import STATE_DIR, STATE_FILE
from ..plural_rules import (
    filter_plural_items_for_lang,
    relevant_plural_quantities_for_lang,
)
from ..sanitize import (
    sanitize_android_array_items,
    sanitize_android_plural_items,
    sanitize_android_string,
)
from ..utils import (
    ensure_strings_xml_exists,
    load_json,
    normalize_lang_to_folder,
    resolve_langs,
    save_json,
    sha1_text,
    read_text_file,
)
from ..xml_backend import (
    XmlBackend,
    PluralsEntry,
    StringArrayEntry,
    StringEntry,
    build_entry_map,
    entry_source_hash,
    etree_remove_entry,
    etree_upsert_array,
    etree_upsert_plurals,
    etree_upsert_string,
    lxml_remove_entry,
    lxml_reorder_entries,
    lxml_upsert_array,
    lxml_upsert_plurals,
    lxml_upsert_string,
    read_entries,
)

try:
    from lxml import etree as LxmlEtree
except Exception:
    LxmlEtree = None


PLURAL_QUANTITY_ORDER: Dict[str, int] = {
    "zero": 0,
    "one": 1,
    "two": 2,
    "few": 3,
    "many": 4,
    "other": 5,
}


GLOSSARY_SECTION_RE = re.compile(r"^\[(?P<name>[^\]]+)\]$")
WORD_RE_TEMPLATE = r"(?<![0-9A-Za-z_]){term}(?![0-9A-Za-z_])"


def _has_non_empty_plural_values(items: Dict[str, str]) -> bool:
    return any((v or "").strip() != "" for v in items.values())


def _has_complete_plural_values(lang: str, items: Dict[str, str]) -> bool:
    required = relevant_plural_quantities_for_lang(lang)
    if not required:
        return False

    for quantity in required:
        value = items.get(quantity)
        if (value or "").strip() == "":
            return False

    return True


def _has_non_empty_array_values(items: Iterable[str]) -> bool:
    return any((v or "").strip() != "" for v in items)


def _find_project_root_by_git(start: Path) -> Path:
    current = start.resolve()

    if current.is_file():
        current = current.parent

    while True:
        if (current / ".git").exists():
            return current

        if current.parent == current:
            raise SystemExit(
                "Could not find project root by walking up to locate '.git'. "
                "Use --state-dir to specify where state should be stored."
            )

        current = current.parent


def _resolve_state_path(res_dir: Path, state_dir_arg: str) -> Path:
    if state_dir_arg:
        state_root = Path(state_dir_arg).expanduser().resolve()
    else:
        state_root = _find_project_root_by_git(res_dir)

    return state_root / STATE_DIR / STATE_FILE


def _entry_translation_hash(entry) -> Optional[str]:
    """
    Return a hash only for a real, non-empty translation.

    Missing/empty translations are represented as None in state.json.
    """
    if entry is None:
        return None

    if isinstance(entry, StringEntry):
        text = (entry.text or "").strip()
        return entry_source_hash(entry) if text != "" else None

    if isinstance(entry, PluralsEntry):
        return entry_source_hash(entry) if _has_non_empty_plural_values(entry.items) else None

    if isinstance(entry, StringArrayEntry):
        return entry_source_hash(entry) if _has_non_empty_array_values(entry.items) else None

    return None


def _remove_extra_locale_entries(
    xml_backend: XmlBackend,
    locale_tree,
    base_map_keys: set[tuple[str, str]],
) -> None:
    """
    Always remove top-level locale entries that do not exist in base.
    """
    if xml_backend.use_lxml:
        root = locale_tree.getroot()
        to_remove = []

        for node in root.iterchildren():
            tag = getattr(node, "tag", None)
            if tag in ("string", "plurals", "string-array"):
                name = node.get("name")
                if name and (tag, name) not in base_map_keys:
                    to_remove.append((tag, name))

        for tag, name in to_remove:
            lxml_remove_entry(locale_tree, tag, name)
    else:
        root = locale_tree.getroot()
        for tag in ("string", "plurals", "string-array"):
            for node in list(root.findall(tag)):
                name = node.get("name")
                if name and (tag, name) not in base_map_keys:
                    etree_remove_entry(locale_tree, tag, name)


def _compute_status(
    *,
    translatable: bool,
    translation_exists: bool,
    prev_status: str,
    prev_source_hash: Optional[str],
    current_source_hash: str,
    prev_translation_hash: Optional[str],
    current_translation_hash: Optional[str],
    mark_all_stale: bool,
    mark_all_ok: bool,
) -> str:
    if not translatable:
        return "skipped"

    if not translation_exists:
        return "missing"

    if mark_all_stale:
        return "stale"

    if mark_all_ok:
        return "ok"

    source_changed = (
        prev_source_hash is not None
        and prev_source_hash != current_source_hash
    )

    if source_changed:
        return "stale"

    # sync may make an entry stale, but it must not make it OK again.
    # Only translate_command(), or an explicit --mark-all-ok, should clear stale.
    if prev_status == "stale":
        return "stale"

    translation_changed = prev_translation_hash != current_translation_hash

    if translation_changed:
        return "ok"

    if prev_status in ("missing", "ok", "skipped"):
        return prev_status

    return "ok"


def _sorted_plural_items(items: Dict[str, str]) -> Dict[str, str]:
    return dict(
        sorted(
            items.items(),
            key=lambda x: (PLURAL_QUANTITY_ORDER.get(x[0], 999), x[0]),
        )
    )


def _normalize_comment_text(comment: str) -> str:
    return (comment or "").strip()


def _extract_section_marker(comments: Optional[List[str]]) -> Optional[str]:
    """
    Section marker = last non-empty comment attached to the entry.

    Section means:
      comment + all following resources until next comment or end of file.
    """
    if not comments:
        return None

    non_empty = [_normalize_comment_text(c) for c in comments if _normalize_comment_text(c)]
    if not non_empty:
        return None

    return non_empty[-1]


def _strip_section_marker_from_comments(
    comments: Optional[List[str]],
    section_marker: Optional[str],
) -> List[str]:
    if not comments:
        return []

    if not section_marker:
        return list(comments)

    result: List[str] = []
    removed = False

    for comment in comments:
        normalized = _normalize_comment_text(comment)
        if not removed and normalized == section_marker:
            removed = True
            continue
        result.append(comment)

    return result


def _clone_entry_with_comments(entry, comments: List[str]):
    if isinstance(entry, StringEntry):
        return StringEntry(
            name=entry.name,
            text=entry.text,
            translatable=entry.translatable,
            comments=comments,
        )

    if isinstance(entry, PluralsEntry):
        return PluralsEntry(
            name=entry.name,
            items=dict(entry.items),
            translatable=entry.translatable,
            comments=comments,
        )

    if isinstance(entry, StringArrayEntry):
        return StringArrayEntry(
            name=entry.name,
            items=list(entry.items),
            translatable=entry.translatable,
            comments=comments,
        )

    raise AssertionError("Unknown entry type")


def _entry_name_sort_key(entry) -> Tuple[str]:
    return (entry.name.casefold(),)


def _sort_base_entries_by_section(entries: List) -> List:
    """
    Sort rules:
      1) sort sections alphabetically by section marker text
      2) sort entries inside section by resource name
      3) keep section marker attached only to the first entry of the section
      4) plural items are already normalized separately
    """
    sections: List[Dict[str, Any]] = []
    current_section_label = ""
    current_section_entries: List = []

    def flush_current_section() -> None:
        if not current_section_entries:
            return
        sections.append(
            {
                "label": current_section_label,
                "entries": list(current_section_entries),
            }
        )
        current_section_entries.clear()

    for entry in entries:
        section_marker = _extract_section_marker(getattr(entry, "comments", None))

        if section_marker is not None:
            flush_current_section()
            current_section_label = section_marker

        entry_without_section_marker = _clone_entry_with_comments(
            entry,
            _strip_section_marker_from_comments(
                getattr(entry, "comments", None),
                section_marker,
            ),
        )

        current_section_entries.append(entry_without_section_marker)

    flush_current_section()

    sections.sort(key=lambda s: (s["label"].casefold(), s["label"]))

    sorted_entries: List = []

    for section in sections:
        ordered = sorted(section["entries"], key=_entry_name_sort_key)
        for index, entry in enumerate(ordered):
            comments = list(getattr(entry, "comments", None) or [])
            if index == 0 and section["label"]:
                comments = [section["label"], *comments]
            sorted_entries.append(_clone_entry_with_comments(entry, comments))

    return sorted_entries


def _normalize_base_entries(entries: List) -> List:
    normalized: List = []

    for entry in entries:
        if isinstance(entry, StringEntry):
            normalized.append(
                StringEntry(
                    name=entry.name,
                    text=sanitize_android_string(entry.text),
                    translatable=entry.translatable,
                    comments=list(entry.comments or []),
                )
            )

        elif isinstance(entry, PluralsEntry):
            normalized.append(
                PluralsEntry(
                    name=entry.name,
                    items=_sorted_plural_items(
                        sanitize_android_plural_items(dict(entry.items))
                    ),
                    translatable=entry.translatable,
                    comments=list(entry.comments or []),
                )
            )

        elif isinstance(entry, StringArrayEntry):
            normalized.append(
                StringArrayEntry(
                    name=entry.name,
                    items=sanitize_android_array_items(list(entry.items)),
                    translatable=entry.translatable,
                    comments=list(entry.comments or []),
                )
            )

        else:
            raise AssertionError("Unknown entry type")

    return normalized


def _lxml_replace_inner_xml(node, inner_xml: str) -> None:
    if LxmlEtree is None:
        raise SystemExit("lxml is required for base sorting with section comments")

    for child in list(node):
        node.remove(child)
    node.text = None

    if not inner_xml:
        return

    wrapper = LxmlEtree.fromstring(
        '<wrapper xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">'
        f"{inner_xml}"
        "</wrapper>"
    )

    node.text = wrapper.text
    for child in list(wrapper):
        wrapper.remove(child)
        node.append(child)

    children = list(node)
    for child in children:
        if child.tail is None:
            child.tail = ""


def _write_base_sorted_with_lxml(base_file: Path, entries: List) -> None:
    """
    Rebuild base strings.xml from scratch using lxml.

    Needed because current xml_backend lxml_upsert_plurals() keeps the incoming
    order, and for base we want explicit order:
    zero, one, two, few, many, other.
    """
    if LxmlEtree is None:
        raise SystemExit("lxml is required for base sorting with section comments")

    root = LxmlEtree.Element(
        "resources",
        nsmap={"xliff": "urn:oasis:names:tc:xliff:document:1.2"},
    )
    root.text = "\n    "

    for entry in entries:
        comments = list(getattr(entry, "comments", None) or [])
        for comment_text in comments:
            comment = LxmlEtree.Comment(comment_text)
            comment.tail = "\n    "
            root.append(comment)

        if isinstance(entry, StringEntry):
            node = LxmlEtree.Element("string")
            node.set("name", entry.name)
            if not entry.translatable:
                node.set("translatable", "false")
            _lxml_replace_inner_xml(node, entry.text)
            node.tail = "\n    "
            root.append(node)

        elif isinstance(entry, PluralsEntry):
            node = LxmlEtree.Element("plurals")
            node.set("name", entry.name)
            if not entry.translatable:
                node.set("translatable", "false")

            items = _sorted_plural_items(dict(entry.items))
            if items:
                node.text = "\n        "
                ordered_items = list(items.items())
                for item_index, (quantity, text) in enumerate(ordered_items):
                    it = LxmlEtree.SubElement(node, "item")
                    it.set("quantity", quantity)
                    _lxml_replace_inner_xml(it, text)
                    it.tail = "\n        " if item_index < len(ordered_items) - 1 else "\n    "
            else:
                node.text = None

            node.tail = "\n    "
            root.append(node)

        elif isinstance(entry, StringArrayEntry):
            node = LxmlEtree.Element("string-array")
            node.set("name", entry.name)
            if not entry.translatable:
                node.set("translatable", "false")

            items = list(entry.items)
            if items:
                node.text = "\n        "
                for item_index, text in enumerate(items):
                    it = LxmlEtree.SubElement(node, "item")
                    _lxml_replace_inner_xml(it, text)
                    it.tail = "\n        " if item_index < len(items) - 1 else "\n    "
            else:
                node.text = None

            node.tail = "\n    "
            root.append(node)

        else:
            raise AssertionError("Unknown entry type")

    if len(root):
        root[-1].tail = "\n"
    else:
        root.text = "\n"

    tree = LxmlEtree.ElementTree(root)
    xml = LxmlEtree.tostring(
        tree,
        encoding="utf-8",
        xml_declaration=True,
        pretty_print=True,
    ).decode("utf-8").replace("\r\n", "\n")

    base_file.write_text(xml, encoding="utf-8")


def _prepare_base_for_sync(
    xml_backend: XmlBackend,
    base_entries: List,
    base_file: Path,
) -> Tuple[List, Any]:
    """
    Sanitize and sort base before locale sync.

    Section sorting is supported only when lxml backend is active, because only
    lxml preserves resource comments and real node order.
    """
    normalized_entries = _normalize_base_entries(base_entries)

    if xml_backend.use_lxml and LxmlEtree is not None:
        sorted_entries = _sort_base_entries_by_section(normalized_entries)
        _write_base_sorted_with_lxml(base_file, sorted_entries)
        return read_entries(xml_backend, base_file)

    return normalized_entries, None


def _normalize_glossary_section_name(name: str) -> str:
    return name.strip().lower().replace("_", "-")


def _parse_glossary_line(raw_line: str) -> Optional[Tuple[str, str]]:
    line = raw_line.strip()
    if not line or line.startswith("#"):
        return None

    if "#" in line:
        line = line.split("#", 1)[0].strip()
        if not line:
            return None

    if "\t" in line:
        src, tgt = line.split("\t", 1)
    elif "=>" in line:
        src, tgt = line.split("=>", 1)
    elif "=" in line:
        src, tgt = line.split("=", 1)
    elif "," in line:
        src, tgt = line.split(",", 1)
    else:
        src = line
        tgt = line

    src = src.strip()
    tgt = tgt.strip()

    if not src or not tgt:
        return None

    if any(ch in src for ch in ("\t", "\n", "\r")):
        return None
    if any(ch in tgt for ch in ("\t", "\n", "\r")):
        return None

    return src, tgt


def _parse_sectioned_glossary_file(path: Path) -> Dict[str, Any]:
    raw = read_text_file(str(path))

    common: Dict[str, str] = {}
    per_lang: Dict[str, Dict[str, str]] = {}
    current_section = "common"

    for line_no, raw_line in enumerate(raw.splitlines(), start=1):
        stripped = raw_line.strip()

        if not stripped or stripped.startswith("#"):
            continue

        sec_match = GLOSSARY_SECTION_RE.match(stripped)
        if sec_match:
            section_name = _normalize_glossary_section_name(sec_match.group("name"))

            if section_name == "common":
                current_section = "common"
                continue

            if section_name.startswith("lang:"):
                lang_key = _normalize_glossary_section_name(section_name.split(":", 1)[1])
                if not lang_key:
                    raise SystemExit(f"Invalid empty glossary section at line {line_no}: {raw_line}")
                current_section = f"lang:{lang_key}"
                per_lang.setdefault(lang_key, {})
                continue

            raise SystemExit(
                f"Unknown glossary section at line {line_no}: {raw_line}\n"
                "Supported sections: [common], [lang:ru], [lang:cs], ..."
            )

        parsed = _parse_glossary_line(raw_line)
        if parsed is None:
            continue

        src, tgt = parsed

        if current_section == "common":
            if src not in common:
                common[src] = tgt
        else:
            lang_key = current_section.split(":", 1)[1]
            bucket = per_lang.setdefault(lang_key, {})
            if src not in bucket:
                bucket[src] = tgt

    canonical = ["[common]"]
    for src, tgt in common.items():
        canonical.append(f"{src}\t{tgt}")

    for lang_key in sorted(per_lang.keys()):
        canonical.append("")
        canonical.append(f"[lang:{lang_key}]")
        for src, tgt in per_lang[lang_key].items():
            canonical.append(f"{src}\t{tgt}")

    canonical_text = "\n".join(canonical).strip() + "\n"

    return {
        "common": common,
        "langs": per_lang,
        "file_hash": sha1_text(canonical_text),
        "canonical_text": canonical_text,
    }


def _merge_glossary_entries_for_lang(glossary_data: Dict[str, Any], lang: str) -> Dict[str, str]:
    merged: Dict[str, str] = dict(glossary_data.get("common", {}) or {})
    per_lang = glossary_data.get("langs", {}) or {}

    raw_lang = lang.strip()
    candidates: List[str] = []
    for item in (raw_lang, normalize_lang_to_folder(raw_lang).removeprefix("values-")):
        normalized = _normalize_glossary_section_name(item)
        if normalized and normalized not in candidates:
            candidates.append(normalized)

    for candidate in candidates:
        section = per_lang.get(candidate)
        if section:
            merged.update(section)

    return _stringify_glossary_map(merged)


def _stringify_glossary_map(entries: Dict[str, str]) -> Dict[str, str]:
    out: Dict[str, str] = {}
    for src, tgt in (entries or {}).items():
        s = str(src).strip()
        t = str(tgt).strip()
        if s and t:
            out[s] = t
    return out


def _compute_glossary_term_changes(
    old_map: Dict[str, str],
    new_map: Dict[str, str],
) -> Tuple[Set[str], List[str], List[str], List[str]]:
    old_keys = set(old_map.keys())
    new_keys = set(new_map.keys())

    added = sorted(new_keys - old_keys)
    removed = sorted(old_keys - new_keys)

    changed: List[str] = []
    for key in sorted(old_keys & new_keys):
        if old_map[key] != new_map[key]:
            changed.append(key)

    affected_terms = set(added) | set(removed) | set(changed)
    return affected_terms, added, removed, changed


def _entry_source_texts(entry) -> List[str]:
    if isinstance(entry, StringEntry):
        return [entry.text or ""]

    if isinstance(entry, PluralsEntry):
        return [entry.items.get(q, "") or "" for q in sorted(entry.items.keys())]

    if isinstance(entry, StringArrayEntry):
        return [x or "" for x in entry.items]

    return []


def _text_contains_term(text: str, term: str) -> bool:
    if not text or not term:
        return False

    haystack = text.casefold()
    needle = term.casefold()

    if not re.search(r"[0-9A-Za-z]", term):
        return needle in haystack

    starts_wordy = bool(re.match(r"^[0-9A-Za-z_]", term))
    ends_wordy = bool(re.search(r"[0-9A-Za-z_]$", term))

    if starts_wordy and ends_wordy:
        pattern = WORD_RE_TEMPLATE.format(term=re.escape(term))
        return re.search(pattern, text, flags=re.IGNORECASE) is not None

    return needle in haystack


def _entry_uses_any_glossary_term(entry, terms: Set[str]) -> bool:
    if not terms:
        return False

    for text in _entry_source_texts(entry):
        for term in terms:
            if _text_contains_term(text, term):
                return True

    return False


def _glossary_entries_hash(entries: Dict[str, str]) -> str:
    return sha1_text("\n".join(f"{src}\t{tgt}" for src, tgt in entries.items())) if entries else ""


def _resolve_glossary_file(res_dir: Path, state_dir_arg: str, glossary_file_arg: str) -> Optional[Path]:
    if glossary_file_arg:
        glossary_path = Path(glossary_file_arg).expanduser().resolve()
        if not glossary_path.exists() or not glossary_path.is_file():
            raise SystemExit(f"Glossary file not found: {glossary_path}")
        return glossary_path

    # Keep sync backward-compatible: if a repository-level glossary.txt exists,
    # track it automatically; otherwise do nothing.
    project_root = Path(state_dir_arg).expanduser().resolve() if state_dir_arg else _find_project_root_by_git(res_dir)
    glossary_path = project_root / "glossary.txt"
    if glossary_path.exists() and glossary_path.is_file():
        return glossary_path

    return None



def _prepare_glossary_tracking_for_sync(
    *,
    state: Dict[str, Any],
    langs: List[str],
    base_map: Dict[Tuple[str, str], Any],
    glossary_path: Optional[Path],
) -> Tuple[Dict[str, Set[str]], Dict[str, Dict[str, Any]]]:
    """
    Detect glossary changes before the normal sync loop.

    Returns:
      - keys_by_lang: resource keys that must stay/become stale in this sync run
      - reports_by_lang: metadata and counters printed/saved after the sync loop

    Important: this function only decides what is affected.  The actual status
    assignment is done inside the main sync loop, so the normal sync pass cannot
    overwrite glossary-triggered stale back to ok in the same run.
    """
    if glossary_path is None:
        return {}, {}

    glossary_data = _parse_sectioned_glossary_file(glossary_path)
    tracking_root = state.setdefault("glossary_tracking", {})
    if not isinstance(tracking_root, dict):
        tracking_root = {}
        state["glossary_tracking"] = tracking_root

    tracking_root["version"] = 2
    tracking_root["glossary_file"] = str(glossary_path)
    tracking_root["glossary_file_hash"] = glossary_data["file_hash"]

    langs_meta: Dict[str, Any] = tracking_root.setdefault("langs", {})
    keys_by_lang: Dict[str, Set[str]] = {}
    reports_by_lang: Dict[str, Dict[str, Any]] = {}

    print(f"[GLOSSARY] Tracking file: {glossary_path}")

    for lang in langs:
        merged_entries = _merge_glossary_entries_for_lang(glossary_data, lang)
        entries_hash = _glossary_entries_hash(merged_entries)

        prev_meta = langs_meta.get(lang, {}) or {}
        prev_map = _stringify_glossary_map(prev_meta.get("entries_map", {}) or {})
        prev_hash = str(prev_meta.get("entries_hash") or "")

        affected_terms, added, removed, changed = _compute_glossary_term_changes(prev_map, merged_entries)

        affected_keys: Set[str] = set()
        if prev_hash and affected_terms:
            for (kind, name), base_entry in base_map.items():
                if not getattr(base_entry, "translatable", True):
                    continue
                if _entry_uses_any_glossary_term(base_entry, affected_terms):
                    affected_keys.add(f"{kind}:{name}")

        if affected_keys:
            keys_by_lang[lang] = affected_keys

        reports_by_lang[lang] = {
            "prev_hash": prev_hash,
            "entries_hash": entries_hash,
            "entries_count": len(merged_entries),
            "entries_map": merged_entries,
            "affected_terms": sorted(affected_terms),
            "added": added,
            "removed": removed,
            "changed": changed,
            "matched": len(affected_keys),
            "marked_stale": 0,
            "already_stale": 0,
            "missing": 0,
            "skipped": 0,
            "missing_state": 0,
            "updated_at": int(time.time()),
        }

    return keys_by_lang, reports_by_lang


def _apply_glossary_stale_override(
    *,
    lang: str,
    key: str,
    status: str,
    current_translation_hash: Optional[str],
    glossary_keys_by_lang: Dict[str, Set[str]],
    glossary_reports_by_lang: Dict[str, Dict[str, Any]],
) -> str:
    affected_keys = glossary_keys_by_lang.get(lang)
    if not affected_keys or key not in affected_keys:
        return status

    report = glossary_reports_by_lang.get(lang)
    if report is None:
        return status

    if status == "skipped":
        report["skipped"] = int(report.get("skipped", 0)) + 1
        return status

    if status == "missing" or current_translation_hash is None:
        report["missing"] = int(report.get("missing", 0)) + 1
        return status

    if status == "stale":
        report["already_stale"] = int(report.get("already_stale", 0)) + 1
        return status

    report["marked_stale"] = int(report.get("marked_stale", 0)) + 1
    return "stale"


def _finish_glossary_tracking_after_sync(
    *,
    state: Dict[str, Any],
    glossary_reports_by_lang: Dict[str, Dict[str, Any]],
) -> int:
    if not glossary_reports_by_lang:
        return 0

    tracking_root = state.setdefault("glossary_tracking", {})
    langs_meta: Dict[str, Any] = tracking_root.setdefault("langs", {})
    total_marked_stale = 0

    for lang, report in glossary_reports_by_lang.items():
        marked_stale = int(report.get("marked_stale", 0))
        total_marked_stale += marked_stale

        langs_meta[lang] = {
            "entries_hash": report.get("entries_hash", ""),
            "entries_count": int(report.get("entries_count", 0)),
            "entries_map": report.get("entries_map", {}) or {},
            "updated_at": int(report.get("updated_at", int(time.time()))),
        }

        prev_hash = str(report.get("prev_hash") or "")
        affected_terms = report.get("affected_terms", []) or []

        if not prev_hash:
            print(f"[GLOSSARY] {lang}: tracking initialized ({int(report.get('entries_count', 0))} entries).")
        elif affected_terms:
            print(
                f"[GLOSSARY] {lang}: added={len(report.get('added', []) or [])}, "
                f"changed={len(report.get('changed', []) or [])}, "
                f"removed={len(report.get('removed', []) or [])}, "
                f"matched={int(report.get('matched', 0))}, "
                f"marked stale={marked_stale}, "
                f"already stale={int(report.get('already_stale', 0))}, "
                f"missing={int(report.get('missing', 0))}, "
                f"skipped={int(report.get('skipped', 0))}."
            )
        else:
            print(f"[GLOSSARY] {lang}: unchanged.")

    if total_marked_stale:
        print(f"[GLOSSARY] Total marked stale: {total_marked_stale}")

    return total_marked_stale


def sync_command(args) -> int:
    mark_all_stale = bool(getattr(args, "mark_all_stale", False))
    mark_all_ok = bool(getattr(args, "mark_all_ok", False))
    mark_all_plurals_stale = bool(getattr(args, "mark_all_plurals_stale", False))

    if sum(1 for flag in (mark_all_stale, mark_all_ok, mark_all_plurals_stale) if flag) > 1:
        raise SystemExit(
            "Use only one of: --mark-all-stale, --mark-all-ok, or --mark-all-plurals-stale"
        )

    res_dir = Path(args.res).expanduser().resolve()
    base_file = res_dir / args.base / "strings.xml"
    if not base_file.exists():
        raise SystemExit(f"Base strings.xml not found: {base_file}")

    langs = resolve_langs(args.langs, args.langs_file)
    if not langs:
        raise SystemExit("No languages provided. Use --langs or --langs-file.")

    xml_backend = XmlBackend()

    base_entries, _base_tree = read_entries(xml_backend, base_file)
    base_entries, _base_tree = _prepare_base_for_sync(xml_backend, base_entries, base_file)

    base_map = build_entry_map(base_entries)
    base_map_keys = set(base_map.keys())

    state_path = _resolve_state_path(res_dir, args.state_dir)
    state = load_json(state_path, default={"base": {}, "langs": {}, "glossaries": {}})
    glossary_path = _resolve_glossary_file(
        res_dir,
        args.state_dir,
        str(getattr(args, "glossary_file", "") or ""),
    )

    prev_base_hashes: Dict[str, str] = state.get("base", {}) or {}

    glossary_keys_by_lang, glossary_reports_by_lang = _prepare_glossary_tracking_for_sync(
        state=state,
        langs=langs,
        base_map=base_map,
        glossary_path=glossary_path,
    )

    base_hashes: Dict[str, str] = {}
    for (kind, name), entry in base_map.items():
        base_hashes[f"{kind}:{name}"] = entry_source_hash(entry)

    state["base"] = base_hashes

    for lang in langs:
        folder = res_dir / normalize_lang_to_folder(lang)
        lang_file = folder / "strings.xml"
        ensure_strings_xml_exists(lang_file)

        locale_entries, locale_tree = read_entries(xml_backend, lang_file)
        locale_map = build_entry_map(locale_entries)

        lang_state: Dict[str, Dict[str, Any]] = state.setdefault("langs", {}).setdefault(lang, {})

        _remove_extra_locale_entries(xml_backend, locale_tree, base_map_keys)

        for key in list(lang_state.keys()):
            parts = key.split(":", 1)
            if len(parts) != 2:
                continue
            kind, name = parts
            if (kind, name) not in base_map_keys:
                del lang_state[key]

        for (kind, name), base_entry in base_map.items():
            key = f"{kind}:{name}"
            current_source_hash = base_hashes[key]

            prev_entry_state = lang_state.get(key) or {}
            prev_status = str(prev_entry_state.get("status") or "")
            prev_source_hash = prev_entry_state.get("source_hash")
            prev_translation_hash = prev_entry_state.get("translation_hash")

            locale_entry = locale_map.get((kind, name))
            current_translation_hash = _entry_translation_hash(locale_entry)

            if prev_source_hash is None and key in prev_base_hashes:
                prev_source_hash = prev_base_hashes[key]

            if isinstance(base_entry, StringEntry):
                if not base_entry.translatable:
                    if xml_backend.use_lxml:
                        lxml_remove_entry(locale_tree, "string", base_entry.name)
                    else:
                        etree_remove_entry(locale_tree, "string", base_entry.name)

                    status = "skipped"
                    current_translation_hash = None

                else:
                    if isinstance(locale_entry, StringEntry) and (locale_entry.text or "").strip() != "":
                        text_to_write = sanitize_android_string(locale_entry.text)

                        status = _compute_status(
                            translatable=True,
                            translation_exists=True,
                            prev_status=prev_status,
                            prev_source_hash=prev_source_hash,
                            current_source_hash=current_source_hash,
                            prev_translation_hash=prev_translation_hash,
                            current_translation_hash=current_translation_hash,
                            mark_all_stale=mark_all_stale,
                            mark_all_ok=mark_all_ok,
                        )

                        if xml_backend.use_lxml:
                            lxml_upsert_string(locale_tree, base_entry, text_to_write)
                        else:
                            etree_upsert_string(locale_tree, base_entry, text_to_write)
                    else:
                        status = "missing"
                        current_translation_hash = None

                        if xml_backend.use_lxml:
                            lxml_remove_entry(locale_tree, "string", base_entry.name)
                        else:
                            etree_remove_entry(locale_tree, "string", base_entry.name)

            elif isinstance(base_entry, PluralsEntry):
                if not base_entry.translatable:
                    if xml_backend.use_lxml:
                        lxml_remove_entry(locale_tree, "plurals", base_entry.name)
                    else:
                        etree_remove_entry(locale_tree, "plurals", base_entry.name)

                    status = "skipped"
                    current_translation_hash = None

                else:
                    if isinstance(locale_entry, PluralsEntry):
                        items_to_write = filter_plural_items_for_lang(lang, dict(locale_entry.items))
                        items_to_write = sanitize_android_plural_items(items_to_write)
                        items_to_write = _sorted_plural_items(items_to_write)

                        locale_entry_filtered = PluralsEntry(
                            name=locale_entry.name,
                            items=items_to_write,
                            translatable=locale_entry.translatable,
                            comments=locale_entry.comments,
                        )

                        is_complete_plural = _has_complete_plural_values(lang, items_to_write)
                        current_translation_hash = (
                            _entry_translation_hash(locale_entry_filtered)
                            if is_complete_plural
                            else None
                        )

                        if is_complete_plural:
                            if mark_all_plurals_stale:
                                status = "stale"
                            else:
                                status = _compute_status(
                                    translatable=True,
                                    translation_exists=True,
                                    prev_status=prev_status,
                                    prev_source_hash=prev_source_hash,
                                    current_source_hash=current_source_hash,
                                    prev_translation_hash=prev_translation_hash,
                                    current_translation_hash=current_translation_hash,
                                    mark_all_stale=mark_all_stale,
                                    mark_all_ok=mark_all_ok,
                                )

                            if xml_backend.use_lxml:
                                lxml_remove_entry(locale_tree, "plurals", base_entry.name)
                                lxml_upsert_plurals(locale_tree, base_entry, items_to_write)
                            else:
                                etree_remove_entry(locale_tree, "plurals", base_entry.name)
                                etree_upsert_plurals(locale_tree, base_entry, items_to_write)
                        else:
                            status = "missing"
                            current_translation_hash = None

                            if xml_backend.use_lxml:
                                lxml_remove_entry(locale_tree, "plurals", base_entry.name)
                            else:
                                etree_remove_entry(locale_tree, "plurals", base_entry.name)
                    else:
                        status = "missing"
                        current_translation_hash = None

                        if xml_backend.use_lxml:
                            lxml_remove_entry(locale_tree, "plurals", base_entry.name)
                        else:
                            etree_remove_entry(locale_tree, "plurals", base_entry.name)

            elif isinstance(base_entry, StringArrayEntry):
                if not base_entry.translatable:
                    if xml_backend.use_lxml:
                        lxml_remove_entry(locale_tree, "string-array", base_entry.name)
                    else:
                        etree_remove_entry(locale_tree, "string-array", base_entry.name)

                    status = "skipped"
                    current_translation_hash = None

                else:
                    if isinstance(locale_entry, StringArrayEntry):
                        items_to_write = sanitize_android_array_items(list(locale_entry.items))
                        current_translation_hash = _entry_translation_hash(
                            StringArrayEntry(
                                name=locale_entry.name,
                                items=items_to_write,
                                translatable=locale_entry.translatable,
                                comments=locale_entry.comments,
                            )
                        ) if _has_non_empty_array_values(items_to_write) else None

                        if _has_non_empty_array_values(items_to_write):
                            status = _compute_status(
                                translatable=True,
                                translation_exists=True,
                                prev_status=prev_status,
                                prev_source_hash=prev_source_hash,
                                current_source_hash=current_source_hash,
                                prev_translation_hash=prev_translation_hash,
                                current_translation_hash=current_translation_hash,
                                mark_all_stale=mark_all_stale,
                                mark_all_ok=mark_all_ok,
                            )

                            if xml_backend.use_lxml:
                                lxml_upsert_array(locale_tree, base_entry, items_to_write)
                            else:
                                etree_upsert_array(locale_tree, base_entry, items_to_write)
                        else:
                            status = "missing"
                            current_translation_hash = None

                            if xml_backend.use_lxml:
                                lxml_remove_entry(locale_tree, "string-array", base_entry.name)
                            else:
                                etree_remove_entry(locale_tree, "string-array", base_entry.name)
                    else:
                        status = "missing"
                        current_translation_hash = None

                        if xml_backend.use_lxml:
                            lxml_remove_entry(locale_tree, "string-array", base_entry.name)
                        else:
                            etree_remove_entry(locale_tree, "string-array", base_entry.name)

            else:
                raise AssertionError("Unknown base entry type")

            status = _apply_glossary_stale_override(
                lang=lang,
                key=key,
                status=status,
                current_translation_hash=current_translation_hash,
                glossary_keys_by_lang=glossary_keys_by_lang,
                glossary_reports_by_lang=glossary_reports_by_lang,
            )

            lang_state[key] = {
                "source_hash": current_source_hash,
                "translation_hash": current_translation_hash,
                "status": status,
                "updated_at": int(time.time()),
            }

        if xml_backend.use_lxml:
            lxml_reorder_entries(locale_tree, list(base_map.keys()))

        lang_file.write_text(xml_backend.tostring(locale_tree), encoding="utf-8")

    _finish_glossary_tracking_after_sync(
        state=state,
        glossary_reports_by_lang=glossary_reports_by_lang,
    )

    save_json(state_path, state)
    print(f"Sync done. State: {state_path}")
    return 0


def register(subparsers) -> None:
    sp = subparsers.add_parser(
        "sync",
        help="Synchronize locale strings.xml with base and update state",
    )
    sp.add_argument("--res", required=True, help="Path to Android res/ directory")
    sp.add_argument("--base", default="values", help="Base values folder (default: values)")
    sp.add_argument("--langs", default="", help="Comma/space separated language codes")
    sp.add_argument("--langs-file", default="", help="Path to file with language list")
    sp.add_argument(
        "--state-dir",
        default="",
        help="Override directory where .smsecure-l10n/state.json is stored",
    )
    sp.add_argument(
        "--glossary-file",
        default="",
        help="Path to a sectioned glossary file. If omitted, repository-level glossary.txt is used when present.",
    )
    sp.add_argument(
        "--mark-all-stale",
        action="store_true",
        help="Force all existing translatable translations to stale",
    )
    sp.add_argument(
        "--mark-all-plurals-stale",
        action="store_true",
        help="Force only existing plural translations to stale",
    )
    sp.add_argument(
        "--mark-all-ok",
        action="store_true",
        help="Force all existing translatable translations to ok",
    )
    sp.set_defaults(func=sync_command)