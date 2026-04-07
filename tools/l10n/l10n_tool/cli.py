### `l10n_tool/cli.py`
# -*- coding: utf-8 -*-

from __future__ import annotations

import argparse

from .commands import register_all
from .utils import print_man_and_exit


def build_parser() -> argparse.ArgumentParser:
    epilog = r"""
Sectioned glossary file example:

  [common]
  HEX
  SMS
  MMS
  PIN
  PUK
  Signal Protocol = Signal Protocol

  [lang:ru]
  message thread = цепочка сообщений
  safety number = номер безопасности

  [lang:cs]
  message thread = vlákno zpráv

Run:
  python3 l10n_sync.py translate \
    --res /home/user/Repos/ShortMessageSecure/app/src/main/res \
    --langs-file /home/user/Repos/ShortMessageSecure/langs.txt \
    --glossary-sync \
    --glossary-file /home/user/Repos/ShortMessageSecure/glossary.txt

Order when using --sync together with --glossary-sync:
  sync -> glossary-sync -> translate -> sync

Important:
  Some DeepL target languages support translation but do not support
  glossary creation for the same EN -> target pair.
  In that case glossary sync is skipped only for that language,
  translation still continues without glossary,
  and support is checked again on later runs.
"""

    ap = argparse.ArgumentParser(
        prog="l10n_sync.py",
        description=r"""
Sync Android strings/plurals/arrays across locales, track stale keys, and translate via DeepL.

What it does:
- Sync keys from base (values/strings.xml) into locale folders
- Track missing/stale/ok/skipped translation status in state.json
- Translate missing/stale entries with DeepL
- Optionally run sync before and after translate
- Optionally sync a sectioned glossary file with DeepL before translate
- Mark affected translations stale when glossary terms change

State is stored in the project state file:
  <project-root>/.smsecure-l10n/state.json

Glossary notes:
- Use a single glossary file with sections
- Supported sections:
    [common]
    [lang:ru]
    [lang:cs]
    [lang:de]
- [common] applies to all languages
- [lang:xx] overrides entries from [common] for that language
- A line may be written as:
    source<TAB>target
    source = target
    source => target
    source,target
    source
  If only "source" is provided, target is assumed to be identical to source

DeepL note:
- A language may support translation but not glossary creation for EN -> target
- In that case glossary sync is skipped for that language
- Translation still continues without glossary
- Support is rechecked on future runs
""",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=epilog,
    )

    ap.add_argument("--man", action="store_true", help="Show full manual and exit")

    sub = ap.add_subparsers(dest="cmd")
    register_all(sub)
    return ap


def main() -> int:
    ap = build_parser()
    args = ap.parse_args()

    if getattr(args, "man", False):
        import sys
        entry_file = sys.argv[0]
        print_man_and_exit(entry_file)

    if not getattr(args, "cmd", None):
        ap.print_usage()
        import sys
        print("\nerror: the following arguments are required: cmd", file=sys.stderr)
        return 2

    return int(args.func(args))