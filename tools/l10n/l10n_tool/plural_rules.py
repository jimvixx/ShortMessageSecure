# l10n_tool/plural_rules.py
# -*- coding: utf-8 -*-

from __future__ import annotations

from typing import Dict, List

ALL_PLURAL_QUANTITIES: List[str] = ["zero", "one", "two", "few", "many", "other"]

# Only legacy Android / historical aliases belong here.
# This module must work with canonical lowercase language codes.
ANDROID_LANG_ALIASES: Dict[str, str] = {
    "in": "id",
    "iw": "he",
    "ji": "yi",
}

LANG_TO_QUANTITIES: Dict[str, List[str]] = {
    "ar": ["zero", "one", "two", "few", "many", "other"],
    "bg": ["one", "other"],
    "cs": ["one", "few", "many", "other"],
    "da": ["one", "other"],
    "de": ["one", "other"],
    "el": ["one", "other"],
    "en": ["one", "other"],
    "es": ["one", "many", "other"],
    "et": ["one", "other"],
    "fi": ["one", "other"],
    "fr": ["one", "many", "other"],
    "he": ["one", "two", "other"],
    "hu": ["one", "other"],
    "id": ["other"],
    "it": ["one", "many", "other"],
    "ja": ["other"],
    "ko": ["other"],
    "lt": ["one", "few", "many", "other"],
    "lv": ["zero", "one", "other"],
    "nb": ["one", "other"],
    "nl": ["one", "other"],
    "pl": ["one", "few", "many", "other"],
    "pt": ["one", "many", "other"],
    "ro": ["one", "few", "other"],
    "ru": ["one", "few", "many", "other"],
    "sk": ["one", "few", "many", "other"],
    "sl": ["one", "two", "few", "other"],
    "sv": ["one", "other"],
    "tr": ["one", "other"],
    "uk": ["one", "few", "many", "other"],
    "zh": ["other"],
}

def normalize_plural_lang(lang: str) -> str:
    value = (lang or "").strip()
    if not value:
        return ""

    value = value.replace("_", "-")
    lower = value.lower()

    lower = ANDROID_LANG_ALIASES.get(lower, lower)

    if "-r" in lower:
        lower = lower.split("-r", 1)[0]
    elif "-" in lower:
        lower = lower.split("-", 1)[0]

    return lower


def relevant_plural_quantities_for_lang(lang: str) -> List[str]:
    normalized = normalize_plural_lang(lang)
    return list(LANG_TO_QUANTITIES.get(normalized, ALL_PLURAL_QUANTITIES))


def filter_plural_items_for_lang(lang: str, items: Dict[str, str]) -> Dict[str, str]:
    allowed = set(relevant_plural_quantities_for_lang(lang))
    return {q: v for q, v in items.items() if q in allowed}
