# l10n_tool/state.py
# -*- coding: utf-8 -*-

from __future__ import annotations

import copy
import os
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

from .constants import STATE_DIR, STATE_FILE
from .utils import load_json, save_json


DEFAULT_BRANCH_STATE: Dict[str, Any] = {
    "base": {},
    "langs": {},
    "glossaries": {},
}

_CREATED_FROM_RE = re.compile(r"^branch: Created from (?P<source>.+)$")


def empty_branch_state() -> Dict[str, Any]:
    return copy.deepcopy(DEFAULT_BRANCH_STATE)


def find_project_root_by_git(start: Path) -> Path:
    current = start.expanduser().resolve()

    if current.is_file():
        current = current.parent

    while True:
        if (current / ".git").exists():
            return current

        if current.parent == current:
            raise SystemExit(
                "Could not find project root by walking up to locate '.git'. "
                "Use --state-dir to specify where state should be stored."
            )

        current = current.parent


def resolve_project_root(res_dir: Path, state_dir_arg: str) -> Path:
    if state_dir_arg:
        return Path(state_dir_arg).expanduser().resolve()

    return find_project_root_by_git(res_dir)


def resolve_state_path(res_dir: Path, state_dir_arg: str) -> Path:
    state_root = (
        Path(state_dir_arg).expanduser().resolve()
        if state_dir_arg
        else find_project_root_by_git(res_dir)
    )
    return state_root / STATE_DIR / STATE_FILE


def _run_git(project_root: Path, *args: str) -> Optional[str]:
    try:
        result = subprocess.run(
            ["git", "-C", str(project_root), *args],
            check=False,
            capture_output=True,
            text=True,
        )
    except OSError:
        return None

    if result.returncode != 0:
        return None

    value = result.stdout.strip()
    return value or None


def current_branch_name(project_root: Path) -> str:
    branch = _run_git(project_root, "symbolic-ref", "--quiet", "--short", "HEAD")
    if branch:
        return branch

    commit = _run_git(project_root, "rev-parse", "--short=12", "HEAD") or "unknown"
    return f"detached/{commit}"


def _normalize_branch_candidate(candidate: str) -> str:
    value = candidate.strip()
    if value.startswith("refs/heads/"):
        return value[len("refs/heads/"):]
    return value


def infer_parent_branch(
    project_root: Path,
    branch: str,
    available_branches: Dict[str, Any],
) -> Optional[str]:
    """Infer only an explicit Git branch-creation parent from the reflog."""
    subjects = _run_git(
        project_root,
        "reflog",
        "show",
        "--format=%gs",
        branch,
    )
    if not subjects:
        return None

    candidates = []
    for subject in subjects.splitlines():
        match = _CREATED_FROM_RE.match(subject.strip())
        if not match:
            continue

        candidate = _normalize_branch_candidate(match.group("source"))
        if candidate == branch or candidate not in available_branches:
            continue
        if candidate not in candidates:
            candidates.append(candidate)

    if len(candidates) == 1:
        return candidates[0]
    return None


def _backup_legacy_state(path: Path) -> Path:
    """Create a non-overwriting byte-for-byte backup of a legacy state file."""
    for index in range(1000):
        suffix = ".legacy-backup" if index == 0 else f".legacy-backup.{index}"
        backup_path = path.with_name(path.name + suffix)
        try:
            fd = os.open(
                backup_path,
                os.O_CREAT | os.O_EXCL | os.O_WRONLY,
                0o600,
            )
        except FileExistsError:
            continue

        try:
            with os.fdopen(fd, "wb") as backup_file:
                backup_file.write(path.read_bytes())
                backup_file.flush()
                os.fsync(backup_file.fileno())
        except Exception:
            try:
                backup_path.unlink()
            except FileNotFoundError:
                pass
            raise

        return backup_path

    raise SystemExit(f"Could not create a non-overwriting backup for legacy state: {path}")


def _is_v2_state(raw: Any) -> bool:
    return isinstance(raw, dict) and raw.get("version") == 2 and isinstance(raw.get("branches"), dict)


def _require_branch_state(branches: Dict[str, Any], branch: str) -> Dict[str, Any]:
    value = branches.get(branch)
    if not isinstance(value, dict):
        raise SystemExit(f"State branch {branch!r} must contain a JSON object.")
    return value


def _select_parent_branch(
    *,
    project_root: Path,
    branch: str,
    branches: Dict[str, Any],
    from_branch: str,
) -> Tuple[str, str]:
    if from_branch:
        if from_branch == branch:
            raise SystemExit("--from-branch must name a different branch.")
        if from_branch not in branches:
            raise SystemExit(
                f"Cannot inherit {branch!r} from {from_branch!r}: that branch has no state. "
                "Run the localization tool on the source branch first, or choose an existing state branch."
            )
        return from_branch, "--from-branch"

    inferred = infer_parent_branch(project_root, branch, branches)
    if inferred:
        return inferred, "Git reflog"

    raise SystemExit(
        f"Cannot determine the parent branch for {branch!r}. "
        "Use --from-branch BRANCH with a branch already present in state.json."
    )


@dataclass
class StateSession:
    state_path: Path
    project_root: Path
    branch: str
    root_state: Dict[str, Any]
    branch_state: Dict[str, Any]
    read_only: bool
    notice: Optional[str] = None
    legacy_backup: Optional[Path] = None

    def save(self) -> None:
        if self.read_only:
            raise RuntimeError("Cannot save a read-only localization state session")

        branches = self.root_state.setdefault("branches", {})
        branches[self.branch] = self.branch_state
        self.root_state["version"] = 2
        save_json(self.state_path, self.root_state)


def open_state_session(
    *,
    res_dir: Path,
    state_dir_arg: str,
    from_branch: str = "",
    read_only: bool = False,
) -> StateSession:
    state_path = resolve_state_path(res_dir, state_dir_arg)
    project_root = resolve_project_root(res_dir, state_dir_arg)
    branch = current_branch_name(project_root)

    raw = load_json(state_path, default=None)
    legacy_backup = None

    if raw is None:
        root_state: Dict[str, Any] = {"version": 2, "branches": {}}
        branches = root_state["branches"]

        if from_branch:
            raise SystemExit(
                f"Cannot inherit {branch!r} from {from_branch!r}: {state_path} does not contain any branch state yet. "
                "Run the localization tool on the source branch first."
            )

        if branch == "main":
            branch_state = empty_branch_state()
            branches[branch] = branch_state
            return StateSession(state_path, project_root, branch, root_state, branch_state, read_only)

        raise SystemExit(
            f"Cannot determine the parent branch for {branch!r}: {state_path} does not exist. "
            "Run the localization tool on main first, then use --from-branch BRANCH if Git cannot identify the parent."
        )

    if not isinstance(raw, dict):
        raise SystemExit(f"Localization state must be a JSON object: {state_path}")

    if _is_v2_state(raw):
        root_state = raw
        branches = root_state["branches"]

        if branch in branches:
            branch_state = _require_branch_state(branches, branch)
            return StateSession(state_path, project_root, branch, root_state, branch_state, read_only)

        if branch == "main":
            branch_state = empty_branch_state()
            if not read_only:
                branches[branch] = branch_state
            notice = (
                "State had no main branch; initialized an empty main branch."
                if not read_only
                else "State had no main branch; report used an empty main state."
            )
            return StateSession(
                state_path,
                project_root,
                branch,
                root_state,
                branch_state,
                read_only,
                notice=notice,
            )

        parent, reason = _select_parent_branch(
            project_root=project_root,
            branch=branch,
            branches=branches,
            from_branch=from_branch,
        )
        branch_state = copy.deepcopy(_require_branch_state(branches, parent))
        branch_state["created_from"] = parent
        if not read_only:
            branches[branch] = branch_state
        action = "Initialized" if not read_only else "Report selected"

        return StateSession(
            state_path,
            project_root,
            branch,
            root_state,
            branch_state,
            read_only,
            notice=f"{action} {branch!r} from {parent!r} ({reason}).",
        )

    legacy_state = copy.deepcopy(raw)
    if not read_only:
        legacy_backup = _backup_legacy_state(state_path)

    root_state = {"version": 2, "branches": {}}
    branches = root_state["branches"]
    branches["main"] = copy.deepcopy(legacy_state)

    if branch == "main":
        branch_state = branches["main"]
        notice = "Migrated legacy localization state into branches.main."
    else:
        branch_state = copy.deepcopy(legacy_state)
        branch_state["created_from"] = "main"
        branches[branch] = branch_state
        notice = f"Migrated legacy localization state into branches.main and {branch!r}."

    if read_only:
        branch_state = copy.deepcopy(legacy_state)
        notice += " Report is read-only; migration was not written."

    return StateSession(
        state_path,
        project_root,
        branch,
        root_state,
        branch_state,
        read_only,
        notice=notice,
        legacy_backup=legacy_backup,
    )
