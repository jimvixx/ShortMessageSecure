# -*- coding: utf-8 -*-
"""
remove_key.py

Interactive remove-key command for Android string resources.

Additions:
- Loop mode: remove multiple keys in a row without restarting the command.
- Uses shared helpers from _resource_ops.py

Default mode is interactive; you can disable with --no-interactive.
"""

from __future__ import annotations

from pathlib import Path
from typing import List
import sys

from l10n_tool.commands._resource_ops import (
    find_key_hits,
    scan_usage,
    remove_key_from_hits,
    Hit,
)


# ----------------------------- Interactive helpers -----------------------------

def _prompt_line(prompt: str) -> str:
    try:
        return input(prompt)
    except EOFError:
        return ""


def _prompt_yes_no(prompt: str, default: bool = False) -> bool:
    """
    Ask a yes/no question.
    Returns True for yes, False for no.
    """
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


def _print_hits(hits: List[Hit], limit: int = 200) -> None:
    print(f"[remove-key] Found in {len(hits)} file(s):")
    shown = hits[:limit]
    for h in shown:
        print(f"  - {h.file}  ({', '.join(h.kinds)})")
    if len(hits) > limit:
        print(f"  ... +{len(hits) - limit} more")


def _print_usage(usage_hits: List[str], limit: int = 80) -> None:
    print(f"[remove-key] Usage hits found: {len(usage_hits)}")
    shown = usage_hits[:limit]
    for p in shown:
        print(f"  - {p}")
    if len(usage_hits) > limit:
        print(f"  ... +{len(usage_hits) - limit} more")


def _is_quit(s: str) -> bool:
    s = (s or "").strip().lower()
    return s in ("q", "quit", "exit")


# ----------------------------- Core runner (one key) -----------------------------

def _run_one_key(res_dir: Path, project_root: Path, key: str, *, interactive: bool, apply: bool, force: bool) -> int:
    key = (key or "").strip()
    if not key:
        return 0

    hits = find_key_hits(res_dir, key)
    if not hits:
        print("[remove-key] Key not found in any values*/strings*.xml.")
        return 0

    _print_hits(hits)
    print()

    usage_hits = scan_usage(project_root, key)
    if usage_hits:
        _print_usage(usage_hits)
        print()
        print("[remove-key] WARNING: the key is referenced in source files.")
        print("            Removing it will likely break the build unless you update references.")
        print()

    # Non-interactive mode:
    if not interactive:
        if usage_hits and not force:
            print("[remove-key] Refusing to delete in non-interactive mode because usage exists. Use --force.")
            return 1
        if not apply:
            print("[remove-key] Dry-run (no changes). Use --apply to delete.")
            return 0

    # Interactive confirmations:
    if interactive:
        if usage_hits and not force:
            if not _prompt_yes_no("Usages found. Force delete anyway?", default=False):
                print("[remove-key] Aborted for this key.")
                return 1
            force = True

        if not apply:
            if not _prompt_yes_no("Proceed to delete from these files?", default=False):
                print("[remove-key] Aborted for this key.")
                return 0
            apply = True

    # Final safety:
    if usage_hits and not force:
        print("[remove-key] Refusing to delete because usage exists. Use --force.")
        return 1
    if not apply:
        print("[remove-key] Dry-run (no changes). Use --apply to delete.")
        return 0

    changed_files = remove_key_from_hits(hits, key)

    print()
    print(f"[remove-key] Deleted from {len(changed_files)} file(s):")
    for f, removed in changed_files:
        print(f"  - {f}  ({', '.join(removed)})")

    return 0


# ----------------------------- Command entrypoint -----------------------------

def command(args) -> int:
    res_dir = Path(args.res).resolve()
    project_root = Path(args.project_root).resolve() if args.project_root else Path.cwd().resolve()

    if not res_dir.exists() or not res_dir.is_dir():
        print(f"[remove-key] ERROR: res dir not found: {res_dir}", file=sys.stderr)
        return 2

    print(f"[remove-key] project_root = {project_root}")
    print(f"[remove-key] res = {res_dir}")
    print()

    interactive = bool(args.interactive)
    loop_mode = bool(args.loop)
    apply = bool(args.apply)
    force = bool(args.force)

    # If key is passed as argument, run once (or as first iteration in loop).
    first_key = (args.key or "").strip()

    def ask_key() -> str:
        return _prompt_line("Enter resource key to remove (or 'q' to quit): ").strip()

    if not interactive and not first_key:
        print("[remove-key] ERROR: key is required in non-interactive mode.", file=sys.stderr)
        return 2

    # Loop logic:
    keys_done = 0
    current_key = first_key

    while True:
        if not current_key and interactive:
            current_key = ask_key()

        if not current_key:
            break

        if _is_quit(current_key):
            break

        print(f"[remove-key] key = {current_key}")
        print()

        _run_one_key(
            res_dir,
            project_root,
            current_key,
            interactive=interactive,
            apply=apply,
            force=force,
        )

        keys_done += 1

        if not loop_mode:
            break

        print()
        current_key = ""  # ask next one

    if loop_mode:
        print()
        print(f"[remove-key] Done. Keys processed: {keys_done}")

    return 0


def register(subparsers) -> None:
    """
    Registration hook expected by l10n_tool.commands.__init__.py
    """
    p = subparsers.add_parser(
        "remove-key",
        help="Remove a key from all locale strings*.xml (interactive, with optional loop)",
    )
    p.add_argument("--res", required=True, help="Path to Android res/ directory")
    p.add_argument("--project-root", default="", help="Project root for usage scan (default: cwd)")

    p.add_argument("--apply", action="store_true", help="Apply changes (non-interactive). In interactive mode you'll be asked.")
    p.add_argument("--force", action="store_true", help="Allow deletion even if usage exists (or confirm interactively).")

    # Interactive by default:
    p.add_argument("--interactive", dest="interactive", action="store_true", default=True,
                   help="Interactive mode (default). Prompts for key/confirmation.")
    p.add_argument("--no-interactive", dest="interactive", action="store_false",
                   help="Disable interactive prompts (CI-friendly).")

    # Loop mode:
    p.add_argument("--loop", action="store_true", help="Loop: ask for keys repeatedly until 'q'.")

    # Optional positional key (if omitted, interactive mode will ask)
    p.add_argument("key", nargs="?", default="", help="Resource key name (name=\"...\")")
    p.set_defaults(func=command)