#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import sys
from pathlib import Path

# Ensure local imports work even when executed from another cwd.
sys.path.insert(0, str(Path(__file__).resolve().parent))

from l10n_tool.cli import main  # noqa: E402


if __name__ == "__main__":
    raise SystemExit(main())
