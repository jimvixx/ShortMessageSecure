# l10n_tool/glossary.py
# -*- coding: utf-8 -*-

from __future__ import annotations

import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from .deepl_api import deepl_request
from .utils import read_text_file, sha1_text


def parse_glossary_file_to_tsv(path: str) -> Tuple[str, str]:
    raw = read_text_file(path)
    pairs: List[Tuple[str, str]] = []

    for line in raw.splitlines():
        l = line.strip()
        if not l or l.startswith("#"):
            continue
        l = l.split("#", 1)[0].strip()
        if not l:
            continue

        if "\t" in l:
            a, b = l.split("\t", 1)
            src, tgt = a.strip(), b.strip()
        elif "," in l:
            a, b = l.split(",", 1)
            src, tgt = a.strip(), b.strip()
        else:
            src = l.strip()
            tgt = src

        if not src or not tgt:
            continue
        if any(ch in src for ch in ("\t", "\n", "\r")) or any(ch in tgt for ch in ("\t", "\n", "\r")):
            continue

        pairs.append((src, tgt))

    uniq: Dict[str, str] = {}
    for s, t in pairs:
        if s not in uniq:
            uniq[s] = t

    entries = "\n".join([f"{s}\t{t}" for s, t in uniq.items()])
    return entries, sha1_text(entries)


def deepl_list_glossaries_v3(deepl_base_url: str, api_key: str) -> List[Dict[str, Any]]:
    url = f"{deepl_base_url}/v3/glossaries"
    resp = deepl_request("GET", url, api_key)
    if resp.status_code != 200:
        return []
    payload = resp.json()
    return list(payload.get("glossaries", []))


def deepl_create_glossary_v2(
    deepl_base_url: str,
    api_key: str,
    *,
    name: str,
    source_lang: str,
    target_lang: str,
    entries_tsv: str,
    entries_format: str = "tsv",
) -> Dict[str, Any]:
    url = f"{deepl_base_url}/v2/glossaries"
    body = {
        "name": name,
        "source_lang": source_lang.lower(),
        "target_lang": target_lang.lower(),
        "entries": entries_tsv,
        "entries_format": entries_format,
    }
    resp = deepl_request("POST", url, api_key, json_data=body)
    if resp.status_code not in (200, 201):
        raise SystemExit(f"DeepL create glossary failed: HTTP {resp.status_code} {resp.text[:300]}")
    return resp.json()


def find_per_lang_glossary_file(dir_path: str, android_lang: str, deepl_target: str) -> Optional[str]:
    d = Path(dir_path)
    if not d.exists() or not d.is_dir():
        return None

    exts = [".tsv", ".txt", ".csv", ".glossary"]
    bases = [android_lang, android_lang.lower(), deepl_target, deepl_target.lower()]

    for b in bases:
        for ext in exts:
            p = d / (b + ext)
            if p.exists() and p.is_file():
                return str(p)

    return None


def find_default_glossary_in_dir(dir_path: str, default_name: str) -> Optional[str]:
    if not dir_path:
        return None
    d = Path(dir_path)
    if not d.exists() or not d.is_dir():
        return None
    p = d / default_name
    if p.exists() and p.is_file():
        return str(p)
    return None


def tsv_to_dict(entries_tsv: str) -> Dict[str, str]:
    out: Dict[str, str] = {}
    for line in (entries_tsv or "").splitlines():
        l = line.strip()
        if not l or l.startswith("#"):
            continue
        if "\t" not in l:
            continue
        src, tgt = l.split("\t", 1)
        src = src.strip()
        tgt = tgt.strip()
        if not src or not tgt:
            continue
        if src not in out:
            out[src] = tgt
    return out


def compute_glossary_diff(old_map: Dict[str, str], new_map: Dict[str, str]) -> Tuple[List[str], List[str], List[Tuple[str, str, str]]]:
    old_keys = set(old_map.keys())
    new_keys = set(new_map.keys())

    added = sorted(new_keys - old_keys)
    removed = sorted(old_keys - new_keys)

    changed: List[Tuple[str, str, str]] = []
    for k in sorted(old_keys & new_keys):
        if old_map[k] != new_map[k]:
            changed.append((k, old_map[k], new_map[k]))

    return added, removed, changed


def format_diff_report(
    added: List[str],
    removed: List[str],
    changed: List[Tuple[str, str, str]],
    old_map: Dict[str, str],
    new_map: Dict[str, str],
    *,
    limit: int = 50,
) -> str:
    lines: List[str] = []

    def cap(items: List[Any]) -> Tuple[List[Any], int]:
        if limit <= 0:
            return (items, 0)
        if len(items) <= limit:
            return (items, 0)
        return (items[:limit], len(items) - limit)

    add_show, add_more = cap(added)
    rem_show, rem_more = cap(removed)
    chg_show, chg_more = cap(changed)

    if not added and not removed and not changed:
        return "    No changes."

    if added:
        lines.append(f"    Added ({len(added)}):")
        for k in add_show:
            lines.append(f"      + {k}\t{new_map.get(k,'')}")
        if add_more:
            lines.append(f"      ... +{add_more} more")

    if removed:
        lines.append(f"    Removed ({len(removed)}):")
        for k in rem_show:
            lines.append(f"      - {k}\t{old_map.get(k,'')}")
        if rem_more:
            lines.append(f"      ... +{rem_more} more")

    if changed:
        lines.append(f"    Changed ({len(changed)}):")
        for (k, old_tgt, new_tgt) in chg_show:
            lines.append(f"      * {k}")
            lines.append(f"          old: {old_tgt}")
            lines.append(f"          new: {new_tgt}")
        if chg_more:
            lines.append(f"      ... +{chg_more} more")

    return "\n".join(lines)


def ensure_glossary_cached(
    state: Dict[str, Any],
    *,
    cache_key: str,
    deepl_base_url: str,
    api_key: str,
    glossary_file: str,
    name: str,
    target_lang: str,
    source_lang: str = "EN",
) -> Optional[str]:
    entries_tsv, entries_hash = parse_glossary_file_to_tsv(glossary_file)
    if not entries_tsv.strip():
        return None

    cache = state.setdefault("glossaries", {})
    cached = cache.get(cache_key, {})
    if cached.get("entries_hash") == entries_hash and cached.get("glossary_id"):
        return str(cached["glossary_id"])

    _ = deepl_list_glossaries_v3(deepl_base_url, api_key)  # best-effort warmup/availability check

    created = deepl_create_glossary_v2(
        deepl_base_url,
        api_key,
        name=name,
        source_lang=source_lang,
        target_lang=target_lang,
        entries_tsv=entries_tsv,
        entries_format="tsv",
    )
    glossary_id = str(created.get("glossary_id", ""))
    cache[cache_key] = {
        "glossary_id": glossary_id,
        "entries_hash": entries_hash,
        "entries_tsv": entries_tsv,
        "name": name,
        "ready": bool(created.get("ready", True)),
        "updated_at": int(time.time()),
        "file": str(glossary_file),
    }
    return glossary_id or None
