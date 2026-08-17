#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path
from typing import Dict, List, Optional

# Keep local imports working when the script is launched from any cwd.
SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from l10n_tool.deepl_api import (  # noqa: E402
    deepl_get_supported_target_langs,
    deepl_translate_batch,
    guess_deepl_base_url,
    map_android_lang_to_deepl_target,
    protect_text_for_translation,
    unprotect_text,
)


FILES_TO_TRANSLATE = ("short_description.txt", "full_description.txt")

# Android resource locale -> Fastlane metadata locale.
FASTLANE_LOCALE_MAP: Dict[str, str] = {
    "ar": "ar",
    "bg": "bg-BG",
    "cs": "cs-CZ",
    "da": "da-DK",
    "de": "de-DE",
    "el": "el-GR",
    "es": "es-ES",
    "et": "et",
    "fi": "fi-FI",
    "fr": "fr-FR",
    "he": "iw-IL",
    "hu": "hu-HU",
    "id": "id-ID",
    "it": "it-IT",
    "ja": "ja-JP",
    "ko": "ko-KR",
    "lt": "lt-LT",
    "lv": "lv-LV",
    "nb": "nb-NO",
    "nl": "nl-NL",
    "pl": "pl-PL",
    "pt": "pt",
    "pt-rBR": "pt-BR",
    "ro": "ro",
    "ru": "ru-RU",
    "sk": "sk-SK",
    "sl": "sl-SI",
    "sv": "sv-SE",
    "tr": "tr-TR",
    "uk": "uk",
    "zh-rCN": "zh-CN",
    "zh-rHK": "zh-HK",
    "zh-rTW": "zh-TW",
}


def load_dotenv(path: Optional[Path] = None) -> None:
    env_path = path or (SCRIPT_DIR / ".env")
    if not env_path.exists() or not env_path.is_file():
        return

    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue

        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()

        if len(value) >= 2 and (
            (value.startswith('"') and value.endswith('"'))
            or (value.startswith("'") and value.endswith("'"))
        ):
            value = value[1:-1]

        if key and key not in os.environ:
            os.environ[key] = value


def find_repo_root(start: Path) -> Path:
    current = start.resolve()
    if current.is_file():
        current = current.parent

    while True:
        if (current / ".git").exists() or (current / "settings.gradle.kts").exists():
            return current
        if current.parent == current:
            raise SystemExit("Could not find repository root.")
        current = current.parent


def read_langs_file(path: Path) -> List[str]:
    if not path.exists():
        raise SystemExit(f"Languages file not found: {path}")

    langs: List[str] = []
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.split("#", 1)[0].strip()
        if not line:
            continue

        for item in re.split(r"[\s,]+", line):
            item = item.strip()
            if item and item not in langs:
                langs.append(item)

    if not langs:
        raise SystemExit(f"No languages found in: {path}")

    return langs


def android_lang_to_fastlane_locale(lang: str) -> str:
    normalized = lang.strip().replace("_", "-")
    if normalized in FASTLANE_LOCALE_MAP:
        return FASTLANE_LOCALE_MAP[normalized]

    lower = normalized.lower()
    if lower in FASTLANE_LOCALE_MAP:
        return FASTLANE_LOCALE_MAP[lower]

    if "-r" in normalized:
        base, region = normalized.split("-r", 1)
        return f"{base.lower()}-{region.upper()}"

    parts = normalized.split("-", 1)
    if len(parts) == 2:
        return f"{parts[0].lower()}-{parts[1].upper()}"

    return normalized.lower()


def normalize_output_text(text: str) -> str:
    text = re.sub(r"[ \t]+\n", "\n", text)
    return text.strip() + "\n"


def translate_one_file(
    *,
    source_text: str,
    file_name: str,
    deepl_target: str,
    deepl_base_url: str,
    deepl_key: str,
    model_type: Optional[str],
) -> str:
    protected_text, protection_map = protect_text_for_translation(source_text)

    context = (
        "This is an F-Droid app store listing for SMSecure, an Android SMS messenger. "
        "Keep these names unchanged: SMSecure, Short Message Secure, Silence, TextSecure, "
        "Signal, SMS, QR, GPLv3, Android, F-Droid. "
        "Keep emoji headings and paragraph breaks. "
        f"Current file: {file_name}."
    )

    translated = deepl_translate_batch(
        deepl_base_url=deepl_base_url,
        api_key=deepl_key,
        texts=[protected_text],
        target_lang=deepl_target,
        source_lang="EN",
        preserve_formatting=True,
        context=context,
        model_type=model_type,
    )[0]

    return normalize_output_text(unprotect_text(translated, protection_map))


def parse_args() -> argparse.Namespace:
    repo_root = find_repo_root(SCRIPT_DIR)
    default_metadata_dir = repo_root / "fastlane" / "metadata" / "android"
    default_langs_file = SCRIPT_DIR / "langs.txt"

    parser = argparse.ArgumentParser(
        description=(
            "Translate F-Droid/Fastlane description files from en-US to all "
            "languages listed in tools/l10n/langs.txt. title.txt is not translated."
        )
    )
    parser.add_argument(
        "--metadata",
        default=str(default_metadata_dir),
        help="Path to fastlane/metadata/android directory.",
    )
    parser.add_argument(
        "--source-locale",
        default="en-US",
        help="Source metadata locale. Default: en-US.",
    )
    parser.add_argument(
        "--langs-file",
        default=str(default_langs_file),
        help="Path to langs.txt. Default: tools/l10n/langs.txt.",
    )
    parser.add_argument(
        "--langs",
        nargs="*",
        default=None,
        help="Optional explicit Android language codes. Overrides --langs-file.",
    )
    parser.add_argument(
        "--deepl-key",
        default="",
        help="DeepL API key. If omitted, DEEPL_API_KEY or tools/l10n/.env is used.",
    )
    parser.add_argument(
        "--deepl-base-url",
        default="",
        help="DeepL API base URL. If omitted, it is guessed from the API key.",
    )
    parser.add_argument(
        "--model-type",
        default="",
        help="Optional DeepL model_type, for example quality_optimized.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Translate and print target paths, but do not write files.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    load_dotenv()

    metadata_dir = Path(args.metadata).expanduser().resolve()
    source_dir = metadata_dir / args.source_locale

    if not metadata_dir.exists() or not metadata_dir.is_dir():
        raise SystemExit(f"Metadata directory not found: {metadata_dir}")

    if not source_dir.exists() or not source_dir.is_dir():
        raise SystemExit(f"Source metadata directory not found: {source_dir}")

    source_files: Dict[str, str] = {}
    for file_name in FILES_TO_TRANSLATE:
        path = source_dir / file_name
        if not path.exists() or not path.is_file():
            raise SystemExit(f"Source file not found: {path}")
        source_files[file_name] = path.read_text(encoding="utf-8")

    deepl_key = args.deepl_key or os.environ.get("DEEPL_API_KEY", "")
    if not deepl_key:
        raise SystemExit(
            "Missing DeepL API key. Use --deepl-key, set DEEPL_API_KEY, "
            "or create tools/l10n/.env."
        )

    deepl_base_url = args.deepl_base_url or os.environ.get("DEEPL_BASE_URL", "")
    if not deepl_base_url:
        deepl_base_url = guess_deepl_base_url(deepl_key)

    langs = args.langs if args.langs is not None else read_langs_file(Path(args.langs_file))
    supported_targets = deepl_get_supported_target_langs(deepl_base_url, deepl_key)
    model_type = (args.model_type or "").strip() or None

    total_written = 0
    total_skipped = 0

    for android_lang in langs:
        target_locale = android_lang_to_fastlane_locale(android_lang)

        if target_locale == args.source_locale:
            print(f"[SKIP] {android_lang} -> {target_locale}: source locale")
            total_skipped += 1
            continue

        deepl_target = map_android_lang_to_deepl_target(android_lang, supported_targets)
        if not deepl_target:
            print(f"[SKIP] {android_lang} -> {target_locale}: unsupported by DeepL")
            total_skipped += 1
            continue

        target_dir = metadata_dir / target_locale
        print(f"[LANG] {android_lang} -> {target_locale} / DeepL {deepl_target}")

        for file_name, source_text in source_files.items():
            translated = translate_one_file(
                source_text=source_text,
                file_name=file_name,
                deepl_target=deepl_target,
                deepl_base_url=deepl_base_url,
                deepl_key=deepl_key,
                model_type=model_type,
            )

            target_path = target_dir / file_name
            if args.dry_run:
                print(f"  [DRY] {target_path}")
            else:
                target_dir.mkdir(parents=True, exist_ok=True)
                target_path.write_text(translated, encoding="utf-8", newline="\n")
                print(f"  [OK]  {target_path}")

            total_written += 1

    print(f"[DONE] files translated: {total_written}, skipped languages: {total_skipped}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
