# l10n_tool/commands/report.py
# -*- coding: utf-8 -*-

from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, List

from ..constants import STATE_DIR, STATE_FILE
from ..utils import load_json, resolve_langs


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
                "Use --state-dir to specify where state is stored."
            )

        current = current.parent


def _resolve_state_path(res_dir: Path, state_dir_arg: str) -> Path:
    if state_dir_arg:
        state_root = Path(state_dir_arg).expanduser().resolve()
    else:
        state_root = _find_project_root_by_git(res_dir)

    return state_root / STATE_DIR / STATE_FILE


def report_command(args) -> int:
    res_dir = Path(args.res).expanduser().resolve()
    state_path = _resolve_state_path(res_dir, args.state_dir)
    state = load_json(state_path, default={"base": {}, "langs": {}, "glossaries": {}})

    langs_state: Dict[str, Dict[str, Dict[str, Any]]] = state.get("langs", {}) or {}
    langs = (
        resolve_langs(args.langs, args.langs_file)
        if (args.langs or args.langs_file)
        else sorted(langs_state.keys())
    )

    want = {x.strip() for x in (args.status or "").split(",") if x.strip()} or {"missing", "stale"}

    print("l10n report")
    print(f"res:   {res_dir}")
    print(f"state: {state_path}")
    print(f"filter: {', '.join(sorted(want))}")
    print("-" * 72)

    totals: Dict[str, int] = {s: 0 for s in ("missing", "stale", "ok", "skipped")}

    for lang in langs:
        lang_map = langs_state.get(lang, {})
        buckets: Dict[str, List[str]] = {s: [] for s in totals.keys()}

        for key, value in lang_map.items():
            status = str(value.get("status", ""))
            if status in buckets:
                buckets[status].append(key)

        if not any(buckets.get(status) for status in want):
            continue

        print(f"{lang}:")
        for status in ("missing", "stale", "skipped", "ok"):
            if status in want and buckets[status]:
                buckets[status].sort()
                print(f"  {status:7s} ({len(buckets[status])}): {', '.join(buckets[status])}")
                totals[status] += len(buckets[status])
        print()

    print("-" * 72)
    for status in ("missing", "stale", "skipped", "ok"):
        if status in want:
            print(f"Total {status:7s}: {totals[status]}")

    return 0


def register(subparsers) -> None:
    rp = subparsers.add_parser("report", help="Report translation statuses based on state.json")
    rp.add_argument("--res", required=True, help="Path to Android res/ directory")
    rp.add_argument("--langs", default="", help="Comma/space separated language codes")
    rp.add_argument("--langs-file", default="", help="Path to file with language list")
    rp.add_argument(
        "--state-dir",
        default="",
        help="Override directory where .smsecure-l10n/state.json is stored",
    )
    rp.add_argument(
        "--status",
        default="missing,stale",
        help="Comma-separated statuses: missing,stale,ok,copied,skipped",
    )
    rp.set_defaults(func=report_command)