# l10n_tool/deepl_api.py
# -*- coding: utf-8 -*-

from __future__ import annotations

import random
import re
import time
from typing import Any, Dict, List, Optional, Tuple

import requests

from .constants import ANDROID_FORMAT_RE, GENERIC_TAG_RE, XLIFF_TAG_RE
from .plural_rules import ANDROID_LANG_ALIASES

DEEPL_TARGET_MAP: Dict[str, str] = {
    "ar": "AR",
    "bg": "BG",
    "cs": "CS",
    "da": "DA",
    "de": "DE",
    "el": "EL",
    "en": "EN",
    "es": "ES",
    "et": "ET",
    "fi": "FI",
    "fr": "FR",
    "he": "HE",
    "hu": "HU",
    "id": "ID",
    "it": "IT",
    "ja": "JA",
    "ko": "KO",
    "lt": "LT",
    "lv": "LV",
    "nb": "NB",
    "nl": "NL",
    "pl": "PL",
    "pt": "PT-PT",
    "ro": "RO",
    "ru": "RU",
    "sk": "SK",
    "sl": "SL",
    "sv": "SV",
    "tr": "TR",
    "uk": "UK",
    "zh": "ZH",
}


def guess_deepl_base_url(api_key: str) -> str:
    if api_key.strip().endswith(":fx"):
        return "https://api-free.deepl.com"
    return "https://api.deepl.com"


def _normalize_android_lang_for_mapping(android_lang: str) -> str:
    value = (android_lang or "").strip()
    if not value:
        return ""

    value = value.replace("_", "-")
    lower = value.lower()
    lower = ANDROID_LANG_ALIASES.get(lower, lower)

    if "-r" in lower:
        base, region = lower.split("-r", 1)
        return f"{base}-r{region.upper()}"

    parts = lower.split("-", 1)
    if len(parts) == 2:
        return f"{parts[0]}-{parts[1]}"

    return lower


def _base_lang_for_deepl(android_lang: str) -> str:
    value = _normalize_android_lang_for_mapping(android_lang)

    if "-r" in value:
        return value.split("-r", 1)[0]

    if "-" in value:
        return value.split("-", 1)[0]

    return value


def deepl_request(
    method: str,
    url: str,
    api_key: str,
    *,
    params: Optional[Dict[str, Any]] = None,
    json_data: Optional[Dict[str, Any]] = None,
    timeout_s: int = 30,
    max_retries: int = 6,
) -> requests.Response:
    headers = {"Authorization": f"DeepL-Auth-Key {api_key}"}
    attempt = 0

    while True:
        attempt += 1
        try:
            resp = requests.request(
                method,
                url,
                headers=headers,
                params=params,
                json=json_data,
                timeout=timeout_s,
            )
        except requests.RequestException:
            if attempt >= max_retries:
                raise
            time.sleep(min(20.0, (2 ** (attempt - 1)) + random.random()))
            continue

        if resp.status_code == 429 and attempt < max_retries:
            time.sleep(min(30.0, (2 ** (attempt - 1)) + random.random()))
            continue

        if resp.status_code in (500, 502, 503, 504) and attempt < max_retries:
            time.sleep(min(20.0, (2 ** (attempt - 1)) + random.random()))
            continue

        return resp


def deepl_get_supported_target_langs(deepl_base_url: str, api_key: str) -> List[str]:
    url = f"{deepl_base_url}/v2/languages"
    resp = deepl_request("GET", url, api_key, params={"type": "target"})
    if resp.status_code != 200:
        raise SystemExit(f"DeepL /languages failed: HTTP {resp.status_code} {resp.text[:200]}")

    data = resp.json()
    return [str(item.get("language", "")).upper() for item in data if item.get("language")]


def map_android_lang_to_deepl_target(android_lang: str, supported_targets: List[str]) -> Optional[str]:
    value = _normalize_android_lang_for_mapping(android_lang)
    if not value:
        return None

    lower = value.lower()

    # Region-specific special cases first.
    if lower == "pt-rbr":
        if "PT-BR" in supported_targets:
            return "PT-BR"
        if "PT" in supported_targets:
            return "PT"
        return None

    # DeepL currently uses generic ZH target. Keep region/script variants mapped to ZH.
    if lower.startswith("zh"):
        if "ZH" in supported_targets:
            return "ZH"
        if "ZH-HANS" in supported_targets:
            return "ZH-HANS"
        return None

    base = _base_lang_for_deepl(lower)
    target = DEEPL_TARGET_MAP.get(base)

    if not target:
        return None

    if target in supported_targets:
        return target

    # Compatibility fallback for APIs/accounts that still expose PT instead of PT-PT.
    if target == "PT-PT" and "PT" in supported_targets:
        return "PT"

    return None


def _make_protected_token(prefix: str, index: int) -> str:
    return f"⟦SMSECURE_{prefix}_{index}⟧"


def protect_text_for_translation(text: str) -> Tuple[str, Dict[str, str]]:
    token_map: Dict[str, str] = {}
    counter = 0

    def token(prefix: str, original: str) -> str:
        nonlocal counter
        t = _make_protected_token(prefix, counter)
        counter += 1
        token_map[t] = original
        return t

    def xliff_repl(m: re.Match) -> str:
        return token("XLIFF", m.group(0))

    def tag_repl(m: re.Match) -> str:
        return token("TAG", m.group(0))

    def fmt_repl(m: re.Match) -> str:
        return token("FMT", m.group(0))

    protected = XLIFF_TAG_RE.sub(xliff_repl, text)
    protected = GENERIC_TAG_RE.sub(tag_repl, protected)
    protected = ANDROID_FORMAT_RE.sub(fmt_repl, protected)
    return protected, token_map


def unprotect_text(text: str, token_map: Dict[str, str]) -> str:
    for k in sorted(token_map.keys(), key=len, reverse=True):
        text = text.replace(k, token_map[k])
    return text


def deepl_translate_batch(
    deepl_base_url: str,
    api_key: str,
    texts: List[str],
    target_lang: str,
    source_lang: Optional[str] = "EN",
    preserve_formatting: bool = True,
    glossary_id: Optional[str] = None,
    context: Optional[str] = None,
    model_type: Optional[str] = None,
) -> List[str]:
    url = f"{deepl_base_url}/v2/translate"

    body: Dict[str, Any] = {
        "target_lang": target_lang,
        "text": texts,
    }

    if source_lang:
        body["source_lang"] = source_lang

    # For JSON requests DeepL expects boolean, not string.
    # Also skip this field when model_type is set.
    if preserve_formatting and not model_type:
        body["preserve_formatting"] = True

    if glossary_id:
        body["glossary_id"] = glossary_id

    if context:
        body["context"] = context

    if model_type:
        body["model_type"] = model_type

    resp = deepl_request("POST", url, api_key, json_data=body)
    if resp.status_code != 200:
        raise SystemExit(f"DeepL /translate failed: HTTP {resp.status_code} {resp.text[:300]}")

    payload = resp.json()
    translations = payload.get("translations", [])
    out = [t.get("text", "") for t in translations]

    if len(out) != len(texts):
        raise SystemExit(f"DeepL returned {len(out)} translations for {len(texts)} texts")

    return out