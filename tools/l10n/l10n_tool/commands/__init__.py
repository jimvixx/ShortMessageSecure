# -*- coding: utf-8 -*-

from __future__ import annotations

from . import sync
from . import report
from . import translate
from . import remove_key
from . import unused_keys


def register_all(subparsers) -> None:
    sync.register(subparsers)
    report.register(subparsers)
    translate.register(subparsers)
    remove_key.register(subparsers)
    unused_keys.register(subparsers)