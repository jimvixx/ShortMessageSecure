# -*- coding: utf-8 -*-
"""
unused_keys.py

Find unused resources defined in base values/strings.xml (string/plurals/string-array)
by doing a best-effort project scan.

Rules:
- We scan almost all text files under project_root, excluding caches/build/binaries.
- We IGNORE res/values*/ XML files as "usage" (definitions are not usages).
- strict hits: java/kt/xml/gradle/kts/properties/... (see STRICT_EXTS)
- soft hits: md/txt/etc and other text files (see SOFT_EXTS)
- Candidate for deletion: strict hits == 0
- If soft hits > 0: warn, still allow deletion with explicit confirmation.

Interactive mode:
- Shows key, soft examples, where key is defined (locale XMLs)
- Asks permission to delete from all locales

Semantics:
- --apply enables actual file modifications
- Without --apply the command is DRY-RUN (no files will be modified)
- Non-interactive mode never deletes (lists only), even if --apply is passed
- --yes skips the extra yes/no confirmation after choosing delete (still asks Action)
- Action input is validated; invalid input is reprompted (not treated as skip)
"""

from __future__ import annotations

from pathlib import Path
from typing import Dict, List, Tuple
import sys

from l10n_tool.commands._resource_ops import (
    load_base_keys,
    group_candidates_by_key,
    scan_usage_with_context,
    find_key_hits,
    remove_key_from_hits,
    STRICT_EXTS,
    SOFT_EXTS,
)


def _prompt_line(prompt: str) -> str:
    try:
        return input(prompt)
    except EOFError:
        return ""


def _prompt_yes_no(prompt: str, default: bool = False) -> bool:
    suffix = " [Y/n] " if default else " [y/N] "
    while True:
        ans = _prompt_line(prompt + suffix).strip().lower()
        if not ans:
            return default
        if ans in ("y", "yes"):
            return True
        if ans in ("n", "no"):
            return False
        print("Please answer 'y' or 'n'.")


def _is_quit(s: str) -> bool:
    s = (s or "").strip().lower()
    return s in ("q", "quit", "exit")


def _print_examples(title: str, examples) -> None:
    if not examples:
        return
    print(title)
    for ex in examples:
        line = ex.line
        if len(line) > 220:
            line = line[:220] + "…"
        print(f"  - {ex.file}:{ex.line_no}: {line}")


def _prompt_action() -> str | None:
    """
    Ask for an action and validate input.
    Returns one of: "delete", "skip", "quit"
    """
    while True:
        ans = _prompt_line("Action: [d]elete / [s]kip / [q]uit ? ").strip().lower()
        if not ans:
            print("Please enter d, s, or q.")
            continue
        if ans in ("d", "del", "delete"):
            return "delete"
        if ans in ("s", "skip"):
            return "skip"
        if ans in ("q", "quit", "exit"):
            return "quit"
        print("Invalid action. Please enter d (delete), s (skip), or q (quit).")


def command(args) -> int:
    res_dir = Path(args.res).resolve()
    project_root = Path(args.project_root).resolve() if args.project_root else Path.cwd().resolve()

    base_strings_xml = res_dir / "values" / "strings.xml"

    if not res_dir.exists() or not res_dir.is_dir():
        print(f"[unused-keys] ERROR: res dir not found: {res_dir}", file=sys.stderr)
        return 2
    if not base_strings_xml.exists():
        print(f"[unused-keys] ERROR: base file not found: {base_strings_xml}", file=sys.stderr)
        return 2

    print(f"[unused-keys] project_root = {project_root}")
    print(f"[unused-keys] res = {res_dir}")
    print(f"[unused-keys] base = {base_strings_xml}")
    print()

    interactive = bool(args.interactive)
    apply = bool(args.apply)
    assume_yes = bool(args.yes)

    # Safety: non-interactive should never delete automatically.
    if not interactive and apply:
        print("[unused-keys] NOTE: --apply is ignored in --no-interactive mode (list only).")
        apply = False

    base_keys: List[Tuple[str, str]] = load_base_keys(base_strings_xml)
    base_by_name: Dict[str, List[str]] = group_candidates_by_key(base_keys)

    print(f"[unused-keys] Base keys loaded: {len(base_by_name)}")
    print("[unused-keys] Scanning project for usages (strict/soft, best-effort)...")
    print(f"[unused-keys] strict exts: {', '.join(sorted(STRICT_EXTS))}")
    print(f"[unused-keys] soft exts:   {', '.join(sorted(SOFT_EXTS))}")
    print()

    candidates: List[str] = []
    scan_cache: Dict[str, object] = {}
    strict_used = 0

    for key in sorted(base_by_name.keys()):
        r = scan_usage_with_context(project_root, key)
        scan_cache[key] = r
        if r.strict_files:
            strict_used += 1
            continue
        candidates.append(key)

    print(f"[unused-keys] Strict-used keys: {strict_used}")
    print(f"[unused-keys] Candidates (strict hits == 0): {len(candidates)}")
    if not candidates:
        return 0

    if not interactive:
        limit = int(args.limit)
        print()
        print(f"[unused-keys] Listing first {min(limit, len(candidates))} candidates:")
        for k in candidates[:limit]:
            kinds = ", ".join(base_by_name.get(k, []))
            r = scan_cache.get(k)
            soft_n = len(r.soft_files) if r else 0  # type: ignore[attr-defined]
            soft_note = f", soft_mentions={soft_n}" if soft_n else ""
            print(f"  - {k} ({kinds}{soft_note})")
        if len(candidates) > limit:
            print(f"  ... +{len(candidates) - limit} more")
        print()
        print("[unused-keys] This was list-only mode; no files were modified.")
        return 0

    idx = 0
    deleted = 0
    skipped = 0

    while idx < len(candidates):
        key = candidates[idx]
        kinds = ", ".join(base_by_name.get(key, []))
        r = scan_cache.get(key) or scan_usage_with_context(project_root, key)

        print()
        print("=" * 72)
        print(f"[unused-keys] Candidate {idx + 1}/{len(candidates)}: {key} ({kinds})")
        print("=" * 72)

        # Candidate means strict hits == 0; re-check to be safe.
        if r.strict_files:
            print("[unused-keys] NOTE: strict usages appeared (maybe cache mismatch). Skipping.")
            skipped += 1
            idx += 1
            continue

        if r.soft_files:
            print(f"[unused-keys] Soft mentions found in {len(r.soft_files)} file(s).")
            _print_examples("[unused-keys] Soft examples:", r.examples_soft)
            print()
            print("[unused-keys] NOTE: soft mentions are non-blocking, but review before deleting.")
        else:
            print("[unused-keys] No soft mentions found.")

        # Show where the key is defined (all locales)
        key_hits = find_key_hits(res_dir, key)
        if not key_hits:
            print("[unused-keys] Note: key not found in locale XMLs (unexpected). Skipping.")
            skipped += 1
            idx += 1
            continue

        print()
        print(f"[unused-keys] Defined in {len(key_hits)} locale file(s). Example list:")
        for h in key_hits[:50]:
            print(f"  - {h.file} ({', '.join(h.kinds)})")
        if len(key_hits) > 50:
            print(f"  ... +{len(key_hits) - 25} more")

        print()
        action = _prompt_action()

        if action == "quit":
            break

        if action == "skip":
            skipped += 1
            idx += 1
            continue

        if action == "delete":
            # One confirmation only unless --yes is passed.
            if apply:
                if not assume_yes:
                    print()
                    if not _prompt_yes_no("Delete this key from ALL locales now?", default=False):
                        skipped += 1
                        idx += 1
                        continue
                changed = remove_key_from_hits(key_hits, key)
                print()
                print(f"[unused-keys] Deleted from {len(changed)} file(s).")
                deleted += 1
                idx += 1
                continue

            # Dry-run deletion simulation
            if not assume_yes:
                print()
                if not _prompt_yes_no(
                    "Dry-run only (no --apply). Simulate deletion from ALL locales?",
                    default=False,
                ):
                    skipped += 1
                    idx += 1
                    continue
            print()
            print("[unused-keys] Dry-run: no files were modified.")
            idx += 1
            continue

    print()
    print(f"[unused-keys] Done. Deleted={deleted}, Skipped={skipped}, TotalCandidates={len(candidates)}")
    return 0


def register(subparsers) -> None:
    p = subparsers.add_parser(
        "unused-keys",
        help="Find keys with zero strict usages and offer interactive deletion (soft mentions are non-blocking)",
    )
    p.add_argument("--res", required=True, help="Path to Android res/ directory")
    p.add_argument("--project-root", default="", help="Project root for usage scan (default: cwd)")

    p.add_argument(
        "--apply",
        action="store_true",
        help="Enable actual deletion (without it, command is dry-run).",
    )

    p.add_argument(
        "--yes",
        action="store_true",
        help="Assume 'yes' for the final delete confirmation after choosing action delete.",
    )

    p.add_argument(
        "--interactive",
        dest="interactive",
        action="store_true",
        default=True,
        help="Interactive mode (default).",
    )
    p.add_argument(
        "--no-interactive",
        dest="interactive",
        action="store_false",
        help="Non-interactive: only prints candidates list.",
    )

    p.add_argument(
        "--limit",
        type=int,
        default=200,
        help="Max list items printed in --no-interactive mode (default: 200)",
    )
    p.set_defaults(func=command)