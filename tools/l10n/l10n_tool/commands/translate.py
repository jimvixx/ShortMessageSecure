# l10n_tool/commands/translate.py
# -*- coding: utf-8 -*-

from __future__ import annotations

import os
import re
import time
from argparse import Namespace
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

from ..constants import STATE_DIR, STATE_FILE, DEEPL_MAX_TEXTS_PER_REQUEST
from ..plural_rules import (
    filter_plural_items_for_lang,
    normalize_plural_lang,
    relevant_plural_quantities_for_lang,
)
from ..sanitize import (
    sanitize_android_array_items,
    sanitize_android_plural_items,
    sanitize_android_string,
)
from ..utils import (
    ensure_strings_xml_exists,
    normalize_lang_to_folder,
    resolve_langs,
    load_json,
    save_json,
    sha1_text,
    read_text_file,
)
from ..deepl_api import (
    guess_deepl_base_url,
    deepl_get_supported_target_langs,
    map_android_lang_to_deepl_target,
    protect_text_for_translation,
    unprotect_text,
    deepl_translate_batch,
    deepl_request,
)
from ..xml_backend import (
    XmlBackend,
    StringEntry,
    PluralsEntry,
    StringArrayEntry,
    read_entries,
    build_entry_map,
    entry_source_hash,
    lxml_remove_entry,
    lxml_upsert_string,
    lxml_upsert_plurals,
    lxml_upsert_array,
    etree_remove_entry,
    etree_upsert_string,
    etree_upsert_plurals,
    etree_upsert_array,
)
from .sync import sync_command


PLURAL_CONTEXT_HINTS: Dict[str, str] = {
    "uk": (
        "Target language plural rule hint for Ukrainian: "
        "'one' is used with numbers ending in 1, except 11; "
        "'few' is used with numbers ending in 2, 3, 4, except 12, 13, 14; "
        "'many' is used with 0, numbers ending in 5, 6, 7, 8, 9, and 11, 12, 13, 14; "
        "'other' is used for fractional numbers such as 1.5."
    ),
    "ru": (
        "Target language plural rule hint for Russian: "
        "'one' is used with numbers ending in 1, except 11; "
        "'few' is used with numbers ending in 2, 3, 4, except 12, 13, 14; "
        "'many' is used with 0, numbers ending in 5, 6, 7, 8, 9, and 11, 12, 13, 14; "
        "'other' is used for fractional numbers."
    ),
    "pl": (
        "Target language plural rule hint for Polish: "
        "'one' is used with 1; "
        "'few' is used with numbers ending in 2, 3, 4, except 12, 13, 14; "
        "'many' is used with 0, numbers ending in 5, 6, 7, 8, 9, and 11, 12, 13, 14; "
        "'other' is used for fractional numbers."
    ),
    "cs": (
        "Target language plural rule hint for Czech: "
        "'one' is used with 1; "
        "'few' is used with 2, 3, 4; "
        "'many' is not normally used; "
        "'other' is used with other numbers and fractional numbers."
    ),
    "sk": (
        "Target language plural rule hint for Slovak: "
        "'one' is used with 1; "
        "'few' is used with 2, 3, 4; "
        "'many' is not normally used; "
        "'other' is used with other numbers and fractional numbers."
    ),
    "be": (
        "Target language plural rule hint for Belarusian: "
        "'one' is used with numbers ending in 1, except 11; "
        "'few' is used with numbers ending in 2, 3, 4, except 12, 13, 14; "
        "'many' is used with 0, numbers ending in 5, 6, 7, 8, 9, and 11, 12, 13, 14; "
        "'other' is used for fractional numbers."
    ),
}

PLURAL_CATEGORY_EXAMPLES: Dict[str, Dict[str, str]] = {
    "uk": {
        "one": "Examples for Ukrainian: 1, 21, 31.",
        "few": "Examples for Ukrainian: 2, 3, 4, 22, 23, 24.",
        "many": "Examples for Ukrainian: 0, 5, 6, 7, 8, 9, 11, 12, 13, 14.",
        "other": "Examples for Ukrainian: 1.5, 2.7.",
    },
    "ru": {
        "one": "Examples for Russian: 1, 21, 31.",
        "few": "Examples for Russian: 2, 3, 4, 22, 23, 24.",
        "many": "Examples for Russian: 0, 5, 6, 7, 8, 9, 11, 12, 13, 14.",
        "other": "Examples for Russian: 1.5, 2.7.",
    },
    "pl": {
        "one": "Examples for Polish: 1.",
        "few": "Examples for Polish: 2, 3, 4, 22, 23, 24.",
        "many": "Examples for Polish: 0, 5, 6, 7, 8, 9, 11, 12, 13, 14.",
        "other": "Examples for Polish: 1.5, 2.7.",
    },
    "cs": {
        "one": "Examples for Czech: 1.",
        "few": "Examples for Czech: 2, 3, 4.",
        "other": "Examples for Czech: 0, 5, 6, 7, 8, 9, 10, 11 and fractional values such as 1.5.",
    },
    "sk": {
        "one": "Examples for Slovak: 1.",
        "few": "Examples for Slovak: 2, 3, 4.",
        "other": "Examples for Slovak: 0, 5, 6, 7, 8, 9, 10, 11 and fractional values such as 1.5.",
    },
    "be": {
        "one": "Examples for Belarusian: 1, 21, 31.",
        "few": "Examples for Belarusian: 2, 3, 4, 22, 23, 24.",
        "many": "Examples for Belarusian: 0, 5, 6, 7, 8, 9, 11, 12, 13, 14.",
        "other": "Examples for Belarusian: 1.5, 2.7.",
    },
}

DEFAULT_PLURAL_EXAMPLE_NUMBERS: Dict[str, str] = {
    "zero": "0",
    "one": "1",
    "two": "2",
    "few": "3",
    "many": "5",
    "other": "3",
}

LANG_PLURAL_EXAMPLE_NUMBERS: Dict[str, Dict[str, str]] = {
    "uk": {"zero": "0", "one": "1", "two": "2", "few": "3", "many": "5", "other": "1.5"},
    "ru": {"zero": "0", "one": "1", "two": "2", "few": "3", "many": "5", "other": "1.5"},
    "be": {"zero": "0", "one": "1", "two": "2", "few": "3", "many": "5", "other": "1.5"},
    "pl": {"zero": "0", "one": "1", "two": "2", "few": "3", "many": "5", "other": "1.5"},
    "cs": {"one": "1", "few": "3", "other": "5"},
    "sk": {"one": "1", "few": "3", "other": "5"},
    "ja": {"other": "3"},
    "zh": {"other": "3"},
    "ko": {"other": "3"},
    "en": {"one": "1", "other": "3"},
    "de": {"one": "1", "other": "3"},
    "fr": {"one": "1", "other": "3"},
    "es": {"one": "1", "other": "3"},
    "it": {"one": "1", "other": "3"},
    "pt": {"one": "1", "other": "3"},
}

XLIFF_G_PATTERN = re.compile(
    r'<xliff:g\b[^>]*\bid="(?P<id>[^"]+)"[^>]*>(?P<inner>.*?)</xliff:g>',
    flags=re.DOTALL,
)

GLOSSARY_SECTION_RE = re.compile(r"^\[(?P<name>[^\]]+)\]$")
WORD_RE_TEMPLATE = r"(?<![0-9A-Za-z_]){term}(?![0-9A-Za-z_])"


def load_dotenv(path: str = ".env") -> None:
    env_path = Path(path)
    if not env_path.exists() or not env_path.is_file():
        return

    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()

        if not line or line.startswith("#"):
            continue

        if "=" not in line:
            continue

        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()

        if not key:
            continue

        if len(value) >= 2 and (
            (value.startswith('"') and value.endswith('"')) or
            (value.startswith("'") and value.endswith("'"))
        ):
            value = value[1:-1]

        if key not in os.environ:
            os.environ[key] = value


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


def _entry_translation_hash_from_values(value) -> Optional[str]:
    if value is None:
        return None

    if isinstance(value, str):
        return entry_source_hash(StringEntry(name="", text=value, translatable=True, comments=[]))

    if isinstance(value, dict):
        if not any((v or "") != "" for v in value.values()):
            return None
        return entry_source_hash(PluralsEntry(name="", items=value, translatable=True, comments=[]))

    if isinstance(value, list):
        if not any((v or "") != "" for v in value):
            return None
        return entry_source_hash(StringArrayEntry(name="", items=value, translatable=True, comments=[]))

    return None


def _resource_name_sentence(name: str) -> str:
    return f"Android resource name: {name.replace('_', ' ')}."


def _join_comments(comments: List[str]) -> str:
    cleaned = [str(c).strip() for c in (comments or []) if str(c).strip()]
    return " ".join(cleaned)


def _get_plural_rule_hint(lang: str) -> str:
    normalized = normalize_plural_lang(lang)
    return PLURAL_CONTEXT_HINTS.get(normalized, "")


def _get_plural_category_examples(lang: str, quantity: str) -> str:
    normalized = normalize_plural_lang(lang)
    return PLURAL_CATEGORY_EXAMPLES.get(normalized, {}).get(quantity, "")


def _build_string_context(entry: StringEntry) -> str:
    parts: List[str] = [
        _resource_name_sentence(entry.name),
        "This is a short UI text from an Android application.",
    ]

    comments = _join_comments(entry.comments)
    if comments:
        parts.append("XML comments: " + comments)

    return " ".join(parts).strip()


def _build_plural_context(entry: PluralsEntry, quantity: str, lang: str) -> str:
    parts: List[str] = [
        _resource_name_sentence(entry.name),
        f"This text is used for the Android plural category '{quantity}'.",
        "Translate according to the plural rules of the target language, not according to English grammar.",
        "This is a plural UI text from an Android application.",
    ]

    comments = _join_comments(entry.comments)
    if comments:
        parts.append("XML comments: " + comments)

    siblings: List[str] = []
    for q in sorted(entry.items.keys()):
        txt = (entry.items.get(q, "") or "").strip()
        if txt:
            siblings.append(f"{q}: {txt}")

    if siblings:
        parts.append("All plural variants in the source language: " + " | ".join(siblings) + ".")

    rule_hint = _get_plural_rule_hint(lang)
    if rule_hint:
        parts.append(rule_hint)

    category_examples = _get_plural_category_examples(lang, quantity)
    if category_examples:
        parts.append(category_examples)

    return " ".join(parts).strip()


def _build_array_context(entry: StringArrayEntry, index: int) -> str:
    parts: List[str] = [
        _resource_name_sentence(entry.name),
        f"This text is array item index {index}.",
        "This is part of a string array in an Android application.",
    ]

    comments = _join_comments(entry.comments)
    if comments:
        parts.append("XML comments: " + comments)

    preview_items: List[str] = []
    for i, item in enumerate(entry.items):
        txt = (item or "").strip()
        if txt:
            preview_items.append(f"{i}: {txt}")

    if preview_items:
        parts.append("All array items in the source language: " + " | ".join(preview_items) + ".")

    return " ".join(parts).strip()


def _get_plural_example_number(lang: str, quantity: str) -> str:
    normalized = normalize_plural_lang(lang)
    per_lang = LANG_PLURAL_EXAMPLE_NUMBERS.get(normalized, {})
    if quantity in per_lang:
        return per_lang[quantity]
    return DEFAULT_PLURAL_EXAMPLE_NUMBERS.get(quantity, "3")


def _replace_first(text: str, needle: str, replacement: str) -> str:
    index = text.find(needle)
    if index == -1:
        return text
    return text[:index] + replacement + text[index + len(needle):]


def _is_valid_xml_fragment(fragment: str) -> bool:
    try:
        from lxml import etree as LET  # type: ignore

        LET.fromstring(
            '<wrapper xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">'
            f"{fragment}"
            "</wrapper>"
        )
        return True
    except Exception:
        return False


def _extract_single_xliff_placeholder(source_text: str) -> Optional[Dict[str, str]]:
    matches = list(XLIFF_G_PATTERN.finditer(source_text))
    if len(matches) != 1:
        return None

    match = matches[0]
    inner = (match.group("inner") or "").strip()
    if not inner:
        return None

    return {
        "full": match.group(0),
        "inner": inner,
    }


def _prepare_plural_source_for_translation(
    source_text: str,
    quantity: str,
    lang: str,
) -> Tuple[str, Optional[Dict[str, str]]]:
    placeholder = _extract_single_xliff_placeholder(source_text)
    if placeholder is None:
        return source_text, None

    example_number = _get_plural_example_number(lang, quantity)
    prepared = source_text.replace(placeholder["full"], example_number, 1)

    return prepared, {
        "full": placeholder["full"],
        "inner": placeholder["inner"],
        "example_number": example_number,
    }


def _restore_plural_placeholder_after_translation(
    translated_text: str,
    placeholder_info: Optional[Dict[str, str]],
) -> str:
    if not placeholder_info:
        return translated_text

    full_placeholder = placeholder_info["full"]
    inner_placeholder = placeholder_info["inner"]
    example_number = placeholder_info["example_number"]

    candidates: List[str] = []

    if example_number in translated_text:
        candidates.append(_replace_first(translated_text, example_number, full_placeholder))
        candidates.append(_replace_first(translated_text, example_number, inner_placeholder))
    else:
        if "." in example_number:
            number_pattern = r'(?<![\d%])\d+(?:[.,]\d+)?(?![\d%])'
        else:
            number_pattern = r'(?<![\d%])\d+(?![\d%])'

        match = re.search(number_pattern, translated_text)
        if match:
            start, end = match.span()
            candidates.append(translated_text[:start] + full_placeholder + translated_text[end:])
            candidates.append(translated_text[:start] + inner_placeholder + translated_text[end:])

    for candidate in candidates:
        if _is_valid_xml_fragment(candidate):
            return candidate

    return translated_text


def _append_job(
    jobs: List[Tuple[str, str, str, Dict[str, str], str, str, Optional[Dict[str, str]]]],
    key: str,
    subkey: str,
    source_text: str,
    context: str,
    placeholder_info: Optional[Dict[str, str]] = None,
) -> None:
    protected, tmap = protect_text_for_translation(source_text)
    jobs.append((key, subkey, protected, tmap, context, source_text, placeholder_info))


def _one_line(text: str, limit: int = 140) -> str:
    s = " ".join((text or "").split())
    if len(s) <= limit:
        return s
    return s[: limit - 1] + "…"


def _log_translation_progress(
    *,
    lang: str,
    index: int,
    total: int,
    key: str,
    subkey: str,
    source_text: str,
    translated_text: str,
) -> None:
    print(f"[{index}/{total}] {lang} {key} [{subkey}]")
    print(f"  SRC: {_one_line(source_text)}")
    print(f"  OUT: {_one_line(translated_text)}")


def _build_sync_args_from_translate_args(args) -> Namespace:
    return Namespace(
        res=args.res,
        base=args.base,
        langs=args.langs,
        langs_file=args.langs_file,
        state_dir=args.state_dir,
        mark_all_stale=False,
        mark_all_ok=False,
        mark_all_plurals_stale=False,
    )


def _run_sync_from_translate(args) -> None:
    sync_args = _build_sync_args_from_translate_args(args)
    sync_command(sync_args)


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


def _parse_sectioned_glossary_file(path: str) -> Dict[str, Any]:
    raw = read_text_file(path)

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


def _lang_glossary_candidates(lang: str, deepl_target: str) -> List[str]:
    candidates: List[str] = []

    raw_lang = lang.strip()
    normalized_lang = _normalize_glossary_section_name(raw_lang)
    normalized_plural = _normalize_glossary_section_name(normalize_plural_lang(raw_lang))
    normalized_target = _normalize_glossary_section_name(deepl_target)

    for item in (raw_lang, normalized_lang, normalized_plural, deepl_target, normalized_target):
        x = _normalize_glossary_section_name(item)
        if x and x not in candidates:
            candidates.append(x)

    return candidates


def _merge_glossary_entries_for_lang(
    glossary_data: Dict[str, Any],
    *,
    lang: str,
    deepl_target: str,
) -> Dict[str, str]:
    merged: Dict[str, str] = dict(glossary_data.get("common", {}) or {})
    per_lang = glossary_data.get("langs", {}) or {}

    for candidate in _lang_glossary_candidates(lang, deepl_target):
        section = per_lang.get(candidate)
        if section:
            merged.update(section)

    return merged


def _glossary_entries_to_tsv(entries: Dict[str, str]) -> str:
    return "\n".join(f"{src}\t{tgt}" for src, tgt in entries.items())


def _deepl_try_create_glossary_v2(
    deepl_base_url: str,
    api_key: str,
    *,
    name: str,
    source_lang: str,
    target_lang: str,
    entries_tsv: str,
) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    url = f"{deepl_base_url}/v2/glossaries"
    body = {
        "name": name,
        "source_lang": source_lang.lower(),
        "target_lang": target_lang.lower(),
        "entries": entries_tsv,
        "entries_format": "tsv",
    }

    resp = deepl_request("POST", url, api_key, json_data=body)

    if resp.status_code in (200, 201):
        return resp.json(), None

    text = (resp.text or "")[:1000]
    text_lower = text.lower()

    if resp.status_code == 400 and (
        "unsupported glossary source and target language pair" in text_lower
        or "glossary" in text_lower and "unsupported" in text_lower and "pair" in text_lower
    ):
        return None, "unsupported_pair"

    raise SystemExit(f"DeepL create glossary failed: HTTP {resp.status_code} {text}")


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


def _invalidate_lang_entries_for_glossary_changes(
    *,
    lang: str,
    lang_state: Dict[str, Dict[str, Any]],
    base_map: Dict[Tuple[str, str], Any],
    affected_terms: Set[str],
) -> int:
    if not affected_terms:
        return 0

    invalidated = 0

    for key, item_state in lang_state.items():
        if ":" not in key:
            continue

        kind, name = key.split(":", 1)
        base_entry = base_map.get((kind, name))
        if base_entry is None:
            continue

        if not getattr(base_entry, "translatable", True):
            continue

        if not _entry_uses_any_glossary_term(base_entry, affected_terms):
            continue

        current_status = str(item_state.get("status") or "")
        translation_hash = item_state.get("translation_hash")

        if current_status == "skipped":
            continue

        if translation_hash is None:
            continue

        if current_status != "stale":
            item_state["status"] = "stale"
            item_state["updated_at"] = int(time.time())
            invalidated += 1

    if invalidated:
        print(f"[GLOSSARY] {lang}: marked stale {invalidated} entries due to glossary changes.")

    return invalidated


def _ensure_glossaries_root(state: Dict[str, Any]) -> Dict[str, Any]:
    glossaries = state.setdefault("glossaries", {})
    if not isinstance(glossaries, dict):
        glossaries = {}
        state["glossaries"] = glossaries

    glossaries.setdefault("version", 2)
    glossaries.setdefault("langs", {})
    glossaries.setdefault("glossary_file", "")
    glossaries.setdefault("glossary_file_hash", "")

    return glossaries


def _get_cached_glossary_id_for_lang(state: Dict[str, Any], lang: str) -> Optional[str]:
    glossaries = _ensure_glossaries_root(state)
    langs = glossaries.get("langs", {}) or {}
    meta = langs.get(lang, {}) or {}

    if bool(meta.get("unsupported_pair", False)):
        return None

    glossary_id = str(meta.get("glossary_id") or "").strip()
    return glossary_id or None


def _sync_glossaries_before_translate(
    *,
    args,
    state: Dict[str, Any],
    langs: List[str],
    supported_targets: List[str],
    deepl_base_url: str,
    deepl_key: str,
    base_map: Dict[Tuple[str, str], Any],
) -> None:
    if not getattr(args, "glossary_sync", False):
        return

    glossary_file = (args.glossary_file or "").strip()
    if not glossary_file:
        raise SystemExit("--glossary-sync requires --glossary-file")

    glossary_path = Path(glossary_file).expanduser().resolve()
    if not glossary_path.exists() or not glossary_path.is_file():
        raise SystemExit(f"Glossary file not found: {glossary_path}")

    glossary_data = _parse_sectioned_glossary_file(str(glossary_path))
    glossaries_root = _ensure_glossaries_root(state)

    glossaries_root["glossary_file"] = str(glossary_path)
    glossaries_root["glossary_file_hash"] = glossary_data["file_hash"]

    langs_meta: Dict[str, Any] = glossaries_root.setdefault("langs", {})
    total_invalidated = 0

    print(f"[GLOSSARY] Sync from file: {glossary_path}")

    for lang in langs:
        target = map_android_lang_to_deepl_target(lang, supported_targets)
        if not target:
            print(f"[GLOSSARY] {lang}: skipped, DeepL target not supported.")
            continue

        merged_entries = _merge_glossary_entries_for_lang(
            glossary_data,
            lang=lang,
            deepl_target=target,
        )
        merged_entries = _stringify_glossary_map(merged_entries)

        entries_tsv = _glossary_entries_to_tsv(merged_entries)
        entries_hash = sha1_text(entries_tsv) if entries_tsv else ""

        prev_meta = langs_meta.get(lang, {}) or {}
        prev_map = _stringify_glossary_map(prev_meta.get("entries_map", {}) or {})
        prev_hash = str(prev_meta.get("entries_hash") or "")
        prev_glossary_id = str(prev_meta.get("glossary_id") or "").strip()

        affected_terms, added, removed, changed = _compute_glossary_term_changes(prev_map, merged_entries)

        if not merged_entries:
            langs_meta[lang] = {
                "glossary_id": "",
                "entries_hash": "",
                "entries_count": 0,
                "entries_map": {},
                "deepl_target": target,
                "source_lang": "EN",
                "unsupported_pair": False,
                "updated_at": int(time.time()),
            }
            print(f"[GLOSSARY] {lang}: empty after merge, cached glossary cleared.")
            continue

        if prev_hash == entries_hash and prev_glossary_id and not bool(prev_meta.get("unsupported_pair", False)):
            print(f"[GLOSSARY] {lang}: unchanged.")
            langs_meta[lang] = {
                "glossary_id": prev_glossary_id,
                "entries_hash": entries_hash,
                "entries_count": len(merged_entries),
                "entries_map": merged_entries,
                "deepl_target": target,
                "source_lang": "EN",
                "unsupported_pair": False,
                "updated_at": int(time.time()),
            }
            continue

        created, error_code = _deepl_try_create_glossary_v2(
            deepl_base_url=deepl_base_url,
            api_key=deepl_key,
            name=f"SMSecure EN->{target} ({lang})",
            source_lang="EN",
            target_lang=target,
            entries_tsv=entries_tsv,
        )

        if error_code == "unsupported_pair":
            langs_meta[lang] = {
                "glossary_id": "",
                "entries_hash": entries_hash,
                "entries_count": len(merged_entries),
                "entries_map": merged_entries,
                "deepl_target": target,
                "source_lang": "EN",
                "unsupported_pair": True,
                "updated_at": int(time.time()),
            }
            print(f"[GLOSSARY] {lang}: skipped, DeepL glossary pair EN->{target} is not supported.")
            continue

        glossary_id = str((created or {}).get("glossary_id", "") or "").strip()
        if not glossary_id:
            raise SystemExit(f"DeepL returned empty glossary_id for language: {lang}")

        langs_meta[lang] = {
            "glossary_id": glossary_id,
            "entries_hash": entries_hash,
            "entries_count": len(merged_entries),
            "entries_map": merged_entries,
            "deepl_target": target,
            "source_lang": "EN",
            "unsupported_pair": False,
            "updated_at": int(time.time()),
        }

        print(
            f"[GLOSSARY] {lang}: uploaded {len(merged_entries)} entries"
            f" (added={len(added)}, changed={len(changed)}, removed={len(removed)})."
        )

        if affected_terms:
            lang_state = state.setdefault("langs", {}).setdefault(lang, {})
            total_invalidated += _invalidate_lang_entries_for_glossary_changes(
                lang=lang,
                lang_state=lang_state,
                base_map=base_map,
                affected_terms=affected_terms,
            )

    if total_invalidated:
        print(f"[GLOSSARY] Total invalidated entries: {total_invalidated}")
    else:
        print("[GLOSSARY] No translation invalidation needed.")


def translate_command(args) -> int:
    load_dotenv()

    if getattr(args, "sync", False):
        print("[SYNC] Running sync before translate...")
        _run_sync_from_translate(args)

    res_dir = Path(args.res).expanduser().resolve()
    base_file = res_dir / args.base / "strings.xml"
    if not base_file.exists():
        raise SystemExit(f"Base strings.xml not found: {base_file}")

    deepl_key = args.deepl_key or os.environ.get("DEEPL_API_KEY", "")
    if not deepl_key:
        raise SystemExit("Missing DeepL API key. Use --deepl-key, set env DEEPL_API_KEY, or create a .env file.")

    deepl_base_url = (
        args.deepl_base_url
        or os.environ.get("DEEPL_BASE_URL", "")
        or guess_deepl_base_url(deepl_key)
    )

    state_path = _resolve_state_path(res_dir, args.state_dir)
    state = load_json(state_path, default={"base": {}, "langs": {}, "glossaries": {}})

    xml_backend = XmlBackend()
    base_entries, _ = read_entries(xml_backend, base_file)
    base_map = build_entry_map(base_entries)

    base_hashes: Dict[str, str] = {}
    for (kind, name), e in base_map.items():
        base_hashes[f"{kind}:{name}"] = entry_source_hash(e)
    state["base"] = base_hashes

    langs_state: Dict[str, Dict[str, Dict[str, Any]]] = state.get("langs", {}) or {}
    langs = resolve_langs(args.langs, args.langs_file) if (args.langs or args.langs_file) else sorted(langs_state.keys())
    if not langs:
        raise SystemExit("No languages found. Run sync first, or pass --langs/--langs-file.")

    wanted_statuses = {x.strip() for x in (args.status or "").split(",") if x.strip()} or {"missing", "stale"}
    supported_targets = deepl_get_supported_target_langs(deepl_base_url, deepl_key)

    if getattr(args, "glossary_sync", False):
        _sync_glossaries_before_translate(
            args=args,
            state=state,
            langs=langs,
            supported_targets=supported_targets,
            deepl_base_url=deepl_base_url,
            deepl_key=deepl_key,
            base_map=base_map,
        )

    total_translated_groups = 0
    total_skipped_lang = 0

    for lang in langs:
        target = map_android_lang_to_deepl_target(lang, supported_targets)
        if not target:
            lang_state = state.setdefault("langs", {}).setdefault(lang, {})
            for _, v in lang_state.items():
                if str(v.get("status", "")) in wanted_statuses:
                    v["status"] = "skipped"
            total_skipped_lang += 1
            print(f"[SKIP] {lang}: not supported by DeepL.")
            continue

        allowed_plural_quantities = set(relevant_plural_quantities_for_lang(lang))
        glossary_id: Optional[str] = _get_cached_glossary_id_for_lang(state, lang)

        folder = res_dir / normalize_lang_to_folder(lang)
        lang_file = folder / "strings.xml"
        ensure_strings_xml_exists(lang_file)

        _, locale_tree = read_entries(xml_backend, lang_file)
        lang_state = state.setdefault("langs", {}).setdefault(lang, {})

        jobs: List[Tuple[str, str, str, Dict[str, str], str, str, Optional[Dict[str, str]]]] = []

        for k, v in lang_state.items():
            status = str(v.get("status", ""))
            if status not in wanted_statuses:
                continue
            if ":" not in k:
                continue

            kind, name = k.split(":", 1)
            base_entry = base_map.get((kind, name))
            if base_entry is None:
                continue

            if not getattr(base_entry, "translatable", True):
                v["status"] = "skipped"
                continue

            if isinstance(base_entry, StringEntry):
                context = _build_string_context(base_entry)
                _append_job(jobs, k, "text", base_entry.text, context)

            elif isinstance(base_entry, PluralsEntry):
                for q in sorted(base_entry.items.keys()):
                    if q not in allowed_plural_quantities:
                        continue
                    src = base_entry.items.get(q, "")
                    prepared_src, placeholder_info = _prepare_plural_source_for_translation(src, q, lang)
                    context = _build_plural_context(base_entry, q, lang)
                    _append_job(jobs, k, f"q:{q}", prepared_src, context, placeholder_info)

            elif isinstance(base_entry, StringArrayEntry):
                for i, src in enumerate(base_entry.items):
                    context = _build_array_context(base_entry, i)
                    _append_job(jobs, k, f"i:{i}", src, context)

        if not jobs:
            print(f"[OK] {lang}: nothing to translate.")
            continue

        gloss = " + glossary" if glossary_id else ""
        print(f"[TRANSLATE] {lang} -> {target}: {len(jobs)} texts{gloss}")

        translated_results: Dict[Tuple[str, str], str] = {}
        context_groups: Dict[str, List[Tuple[str, str, str, Dict[str, str], str, str, Optional[Dict[str, str]]]]] = {}

        for job in jobs:
            context_groups.setdefault(job[4], []).append(job)

        progress_counter = 0
        total_jobs = len(jobs)

        for context, group_jobs in context_groups.items():
            idx = 0
            while idx < len(group_jobs):
                batch = group_jobs[idx: idx + DEEPL_MAX_TEXTS_PER_REQUEST]
                texts = [j[2] for j in batch]

                translated_batch = deepl_translate_batch(
                    deepl_base_url=deepl_base_url,
                    api_key=deepl_key,
                    texts=texts,
                    target_lang=target,
                    source_lang="EN",
                    glossary_id=glossary_id,
                    context=context,
                    model_type="quality_optimized",
                )

                for (key, sub, _protected, tmap, _context, source_text, placeholder_info), out in zip(batch, translated_batch):
                    translated_text = unprotect_text(out, tmap)
                    translated_text = _restore_plural_placeholder_after_translation(
                        translated_text,
                        placeholder_info,
                    )
                    translated_text = sanitize_android_string(translated_text)
                    translated_results[(key, sub)] = translated_text

                    progress_counter += 1
                    if args.verbose:
                        _log_translation_progress(
                            lang=lang,
                            index=progress_counter,
                            total=total_jobs,
                            key=key,
                            subkey=sub,
                            source_text=source_text,
                            translated_text=translated_text,
                        )
                    else:
                        print(f"[{progress_counter}/{total_jobs}] {lang} {key} [{sub}]")

                idx += len(batch)
                if args.sleep_ms > 0 and idx < len(group_jobs):
                    time.sleep(args.sleep_ms / 1000.0)

        for k in sorted({j[0] for j in jobs}):
            kind, name = k.split(":", 1)
            base_entry = base_map.get((kind, name))
            if base_entry is None:
                continue

            src_hash = base_hashes.get(k, entry_source_hash(base_entry))
            translation_hash: Optional[str] = None

            if isinstance(base_entry, StringEntry):
                txt = sanitize_android_string(
                    translated_results.get((k, "text"), base_entry.text)
                )

                if xml_backend.use_lxml:
                    lxml_upsert_string(locale_tree, base_entry, txt)
                else:
                    etree_upsert_string(locale_tree, base_entry, txt)

                translation_hash = _entry_translation_hash_from_values(txt)
                total_translated_groups += 1

            elif isinstance(base_entry, PluralsEntry):
                plural_items: Dict[str, str] = {}
                for q in sorted(base_entry.items.keys()):
                    if q not in allowed_plural_quantities:
                        continue
                    plural_items[q] = sanitize_android_string(
                        translated_results.get((k, f"q:{q}"), base_entry.items.get(q, ""))
                    )

                plural_items = filter_plural_items_for_lang(lang, plural_items)
                plural_items = sanitize_android_plural_items(plural_items)

                if xml_backend.use_lxml:
                    lxml_remove_entry(locale_tree, "plurals", base_entry.name)
                    lxml_upsert_plurals(locale_tree, base_entry, plural_items)
                else:
                    etree_remove_entry(locale_tree, "plurals", base_entry.name)
                    etree_upsert_plurals(locale_tree, base_entry, plural_items)

                translation_hash = _entry_translation_hash_from_values(plural_items)
                total_translated_groups += 1

            elif isinstance(base_entry, StringArrayEntry):
                array_items: List[str] = []
                for i in range(len(base_entry.items)):
                    array_items.append(
                        sanitize_android_string(
                            translated_results.get((k, f"i:{i}"), base_entry.items[i])
                        )
                    )

                array_items = sanitize_android_array_items(array_items)

                if xml_backend.use_lxml:
                    lxml_upsert_array(locale_tree, base_entry, array_items)
                else:
                    etree_upsert_array(locale_tree, base_entry, array_items)

                translation_hash = _entry_translation_hash_from_values(array_items)
                total_translated_groups += 1

            lang_state.setdefault(k, {})
            lang_state[k]["source_hash"] = src_hash
            lang_state[k]["translation_hash"] = translation_hash
            lang_state[k]["status"] = "ok"
            lang_state[k]["updated_at"] = int(time.time())
            lang_state[k]["translated_at"] = int(time.time())

        lang_file.write_text(xml_backend.tostring(locale_tree), encoding="utf-8")

    save_json(state_path, state)

    if getattr(args, "sync", False):
        print("[SYNC] Running sync after translate...")
        _run_sync_from_translate(args)

    print(f"Done. Entries translated (grouped by key): {total_translated_groups}")
    if total_skipped_lang:
        print(f"Languages skipped (unsupported by DeepL): {total_skipped_lang}")
    return 0


def register(subparsers) -> None:
    tp = subparsers.add_parser("translate", help="Translate missing/stale entries using DeepL API")
    tp.add_argument("--res", required=True, help="Path to Android res/ directory")
    tp.add_argument("--base", default="values", help="Base values folder (default: values)")
    tp.add_argument("--langs", default="", help="Comma/space separated language codes")
    tp.add_argument("--langs-file", default="", help="Path to file with language list")
    tp.add_argument(
        "--status",
        default="missing,stale",
        help="Translate only these statuses (default: missing,stale)",
    )
    tp.add_argument("--state-dir", default="", help="Override directory where .smsecure-l10n/state.json is stored")
    tp.add_argument("--deepl-key", default=os.environ.get("DEEPL_API_KEY", ""), help="DeepL API key, env DEEPL_API_KEY, or .env")
    tp.add_argument("--deepl-base-url", default=os.environ.get("DEEPL_BASE_URL", ""), help="Override DeepL base URL, env DEEPL_BASE_URL, or .env")
    tp.add_argument("--sleep-ms", type=int, default=150, help="Sleep between batches (default: 150ms)")
    tp.add_argument("--verbose", action="store_true", help="Print source and translated text for each item")
    tp.add_argument("--sync", action="store_true", help="Run sync before and after translate")
    tp.add_argument(
        "--glossary-sync",
        action="store_true",
        help="Sync sectioned glossary file with DeepL before translate",
    )
    tp.add_argument(
        "--glossary-file",
        default="",
        help="Path to a sectioned glossary file. Supported sections: [common], [lang:ru], [lang:cs], ...",
    )

    tp.set_defaults(func=translate_command)