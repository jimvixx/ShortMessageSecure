# l10n_tool/commands/report.py
# -*- coding: utf-8 -*-

from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, List

from ..state import open_state_session
from ..utils import resolve_langs


def report_command(args) -> int:
    res_dir = Path(args.res).expanduser().resolve()
    state_session = open_state_session(
        res_dir=res_dir,
        state_dir_arg=args.state_dir,
        from_branch=getattr(args, "from_branch", ""),
        read_only=True,
    )
    state_path = state_session.state_path
    state = state_session.branch_state

    if state_session.notice:
        print(f"[STATE] {state_session.notice}")

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
        "--from-branch",
        default="",
        help="State branch to inspect when the current branch has no state",
    )
    rp.add_argument(
        "--status",
        default="missing,stale",
        help="Comma-separated statuses: missing,stale,ok,copied,skipped",
    )
    rp.set_defaults(func=report_command)
