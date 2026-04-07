# l10n_tool/constants.py
# -*- coding: utf-8 -*-

from __future__ import annotations

import re

STATE_DIR = ".smsecure-l10n"
STATE_FILE = "state.json"

DEEPL_MAX_TEXTS_PER_REQUEST = 50

ANDROID_FORMAT_RE = re.compile(
    r"""
    %%
    |
    %
    (?:\d+\$)?
    [-+#, 0(]*
    \d*
    (?:\.\d+)?
    [a-zA-Z]
    """,
    re.VERBOSE,
)

XLIFF_TAG_RE = re.compile(
    r"<\s*xliff:g\b[^>]*>.*?<\s*/\s*xliff:g\s*>",
    re.DOTALL | re.IGNORECASE,
)

GENERIC_TAG_RE = re.compile(r"</?[^>]+>")