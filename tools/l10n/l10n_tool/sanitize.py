# l10n_tool/sanitize.py
# -*- coding: utf-8 -*-

from __future__ import annotations

import re
from typing import Dict, List


def _escape_xml_text(text: str) -> str:
    if not text:
        return text

    text = re.sub(r"&(?!#\d+;|#x[0-9A-Fa-f]+;|[A-Za-z_:][A-Za-z0-9_.:-]*;)", "&amp;", text)
    text = text.replace("<", "&lt;")
    text = text.replace(">", "&gt;")
    return text


def _sanitize_xml_tag(tag_text: str) -> str:
    if not tag_text:
        return tag_text

    tag_text = re.sub(r'\s+xmlns:xliff="[^"]+"', "", tag_text)
    return tag_text


def _flatten_android_string_xml_layout(text: str) -> str:
    """
    Flatten real XML line breaks/indentation inside a string value.

    Important:
    - real newlines in XML text are formatting noise for our pipeline
    - explicit Android line breaks must be represented only as the literal sequence "\\n"
    - this keeps values stable and prevents indentation from becoming part of the string
    """
    if not text:
        return text

    text = text.replace("\r\n", "\n").replace("\r", "\n")

    # When a source line intentionally ends with the literal sequence "\n",
    # remove the real XML newline and indentation after it.
    text = re.sub(r'\\n[ \t]*\n[ \t]*', r'\\n', text)

    # Remove remaining real XML newlines and surrounding indentation.
    text = re.sub(r'[ \t]*\n[ \t]*', ' ', text)

    # Collapse repeated spaces introduced by formatting, but preserve literal \n.
    text = re.sub(r'[ \t]{2,}', ' ', text)

    return text.strip()


def sanitize_android_string(text: str) -> str:
    """
    Sanitize text for Android strings.xml.

    What it does:
    - Flattens real XML multiline formatting into a single stable line
    - Replaces NBSP with normal space
    - Replaces escaped apostrophe \' with typographic ’
    - Replaces ASCII apostrophe ' with typographic ’
    - Replaces triple dots with ellipsis …
    - Escapes XML special chars in text nodes
    - Preserves XML tags (<xliff:g> etc.)
    - Removes duplicated inline xmlns:xliff declarations
    - Keeps placeholders like %1$s untouched
    """
    if not text:
        return text

    text = _flatten_android_string_xml_layout(text)
    text = text.replace("\u00A0", " ")

    parts = re.split(r"(<[^>]+>)", text)

    for i, part in enumerate(parts):
        if part.startswith("<") and part.endswith(">"):
            parts[i] = _sanitize_xml_tag(part)
            continue

        part = part.replace("\\'", "’")
        part = part.replace("...", "…")
        part = part.replace("'", "’")
        part = _escape_xml_text(part)
        parts[i] = part

    return "".join(parts)


def sanitize_android_plural_items(items: Dict[str, str]) -> Dict[str, str]:
    return {q: sanitize_android_string(v) for q, v in items.items()}


def sanitize_android_array_items(items: List[str]) -> List[str]:
    return [sanitize_android_string(v) for v in items]