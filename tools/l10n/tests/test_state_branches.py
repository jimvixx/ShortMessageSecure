from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path
from unittest.mock import patch


L10N_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(L10N_ROOT))

from l10n_tool.commands.report import report_command  # noqa: E402
from l10n_tool.commands.sync import sync_command  # noqa: E402
from l10n_tool.commands.translate import translate_command  # noqa: E402
from l10n_tool.state import open_state_session  # noqa: E402
from l10n_tool.utils import save_json  # noqa: E402


def git(root: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(root), *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def create_git_repo(root: Path) -> None:
    git(root, "init", "-b", "main")
    git(root, "config", "user.email", "tests@example.invalid")
    git(root, "config", "user.name", "Localization tests")
    (root / "README").write_text("test\n", encoding="utf-8")
    git(root, "add", "README")
    git(root, "commit", "-m", "initial")


def write_resources(root: Path) -> Path:
    res_dir = root / "res"
    (res_dir / "values").mkdir(parents=True)
    (res_dir / "values-de").mkdir()
    (res_dir / "values" / "strings.xml").write_text(
        """<?xml version=\"1.0\" encoding=\"utf-8\"?>
<resources>
    <string name=\"hello\">Hello</string>
</resources>
""",
        encoding="utf-8",
    )
    (res_dir / "values-de" / "strings.xml").write_text(
        """<?xml version=\"1.0\" encoding=\"utf-8\"?>
<resources />
""",
        encoding="utf-8",
    )
    return res_dir


def sync_args(res_dir: Path, root: Path, *, from_branch: str = "") -> Namespace:
    return Namespace(
        res=str(res_dir),
        base="values",
        langs="de",
        langs_file="",
        state_dir=str(root),
        from_branch=from_branch,
        glossary_file="",
        mark_all_stale=False,
        mark_all_ok=False,
        mark_all_plurals_stale=False,
    )


class BranchStateMigrationTest(unittest.TestCase):
    def test_legacy_state_on_main_is_backed_up_and_migrated(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            create_git_repo(root)
            state_path = root / ".smsecure-l10n" / "state.json"
            state_path.parent.mkdir()
            legacy = {
                "base": {"string:hello": "old-base-hash"},
                "langs": {"de": {"string:hello": {"status": "stale"}}},
                "custom_metadata": {"preserve": True},
            }
            original = json.dumps(legacy, indent=2) + "\n"
            state_path.write_text(original, encoding="utf-8")

            session = open_state_session(
                res_dir=root / "res",
                state_dir_arg=str(root),
            )
            self.assertEqual("main", session.branch)
            self.assertEqual(legacy, session.branch_state)
            self.assertIsNotNone(session.legacy_backup)
            self.assertEqual(original, session.legacy_backup.read_text(encoding="utf-8"))

            session.save()
            migrated = json.loads(state_path.read_text(encoding="utf-8"))
            self.assertEqual(2, migrated["version"])
            self.assertEqual(legacy, migrated["branches"]["main"])
            self.assertEqual(legacy, json.loads(session.legacy_backup.read_text(encoding="utf-8")))

    def test_legacy_state_on_feature_creates_independent_main_and_feature_copies(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            create_git_repo(root)
            git(root, "switch", "-c", "feature/mms-localization")
            state_path = root / ".smsecure-l10n" / "state.json"
            state_path.parent.mkdir()
            legacy = {
                "base": {"string:hello": "old-base-hash"},
                "langs": {"de": {"string:hello": {"status": "ok"}}},
            }
            state_path.write_text(json.dumps(legacy), encoding="utf-8")

            session = open_state_session(
                res_dir=root / "res",
                state_dir_arg=str(root),
            )
            session.branch_state["langs"]["de"]["string:hello"]["status"] = "stale"
            session.save()

            migrated = json.loads(state_path.read_text(encoding="utf-8"))
            self.assertEqual({"main", "feature/mms-localization"}, set(migrated["branches"]))
            self.assertEqual("main", migrated["branches"]["feature/mms-localization"]["created_from"])
            self.assertEqual("ok", migrated["branches"]["main"]["langs"]["de"]["string:hello"]["status"])
            self.assertEqual("stale", migrated["branches"]["feature/mms-localization"]["langs"]["de"]["string:hello"]["status"])

    def test_missing_state_on_feature_requires_main_initialization(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            create_git_repo(root)
            git(root, "switch", "-c", "feature/foo/bar")

            with self.assertRaisesRegex(SystemExit, "Run the localization tool on main first"):
                open_state_session(res_dir=root / "res", state_dir_arg=str(root))

            self.assertFalse((root / ".smsecure-l10n" / "state.json").exists())


class BranchSelectionTest(unittest.TestCase):
    def test_reflog_selects_non_main_origin_and_deep_copies_state(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            create_git_repo(root)
            git(root, "switch", "-c", "release/next")
            state_path = root / ".smsecure-l10n" / "state.json"
            state_path.parent.mkdir()
            save_json(
                state_path,
                {
                    "version": 2,
                    "branches": {
                        "main": {"base": {"main": "h"}, "langs": {}, "glossaries": {}},
                        "release/next": {"base": {"release": "h"}, "langs": {}, "glossaries": {}},
                    },
                },
            )
            git(root, "switch", "-c", "feature/from-release", "release/next")

            session = open_state_session(res_dir=root / "res", state_dir_arg=str(root))
            self.assertEqual("release/next", session.branch_state["created_from"])
            session.branch_state["base"]["feature"] = "new"
            session.save()

            data = json.loads(state_path.read_text(encoding="utf-8"))
            self.assertNotIn("feature", data["branches"]["release/next"]["base"])
            self.assertEqual("release/next", data["branches"]["feature/from-release"]["created_from"])

    def test_unknown_parent_requires_explicit_source_without_corrupting_state(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            create_git_repo(root)
            git(root, "switch", "-c", "feature/unknown")
            state_path = root / ".smsecure-l10n" / "state.json"
            state_path.parent.mkdir()
            save_json(
                state_path,
                {
                    "version": 2,
                    "branches": {
                        "main": {"base": {"main": "h"}, "langs": {}, "glossaries": {}},
                        "release": {"base": {"release": "h"}, "langs": {}, "glossaries": {}},
                    },
                },
            )

            original = state_path.read_bytes()
            with patch("l10n_tool.state.infer_parent_branch", return_value=None):
                with self.assertRaisesRegex(SystemExit, "--from-branch"):
                    open_state_session(res_dir=root / "res", state_dir_arg=str(root))

            self.assertEqual(original, state_path.read_bytes())

    def test_unknown_parent_without_main_requires_action(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            create_git_repo(root)
            git(root, "switch", "-c", "feature/unknown")
            state_path = root / ".smsecure-l10n" / "state.json"
            state_path.parent.mkdir()
            save_json(
                state_path,
                {"version": 2, "branches": {"release": {"base": {}, "langs": {}}}},
            )

            with patch("l10n_tool.state.infer_parent_branch", return_value=None):
                with self.assertRaisesRegex(SystemExit, "--from-branch"):
                    open_state_session(res_dir=root / "res", state_dir_arg=str(root))

    def test_from_branch_override_uses_requested_existing_state(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            create_git_repo(root)
            git(root, "switch", "-c", "feature/override")
            state_path = root / ".smsecure-l10n" / "state.json"
            state_path.parent.mkdir()
            save_json(
                state_path,
                {
                    "version": 2,
                    "branches": {
                        "main": {"base": {"main": "h"}, "langs": {}, "glossaries": {}},
                        "release": {"base": {"release": "h"}, "langs": {}, "glossaries": {}},
                    },
                },
            )

            session = open_state_session(
                res_dir=root / "res",
                state_dir_arg=str(root),
                from_branch="release",
            )
            self.assertEqual("release", session.branch_state["created_from"])

    def test_from_branch_main_is_explicit_and_deep_copied(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            create_git_repo(root)
            git(root, "switch", "-c", "feature/explicit-main")
            state_path = root / ".smsecure-l10n" / "state.json"
            state_path.parent.mkdir()
            save_json(
                state_path,
                {
                    "version": 2,
                    "branches": {
                        "main": {"base": {"main": "h"}, "langs": {}, "glossaries": {}},
                    },
                },
            )

            session = open_state_session(
                res_dir=root / "res",
                state_dir_arg=str(root),
                from_branch="main",
            )
            session.branch_state["base"]["feature"] = "new"
            session.save()

            data = json.loads(state_path.read_text(encoding="utf-8"))
            self.assertEqual("main", data["branches"]["feature/explicit-main"]["created_from"])
            self.assertNotIn("feature", data["branches"]["main"]["base"])

    def test_existing_branch_state_is_reused_without_parent_resolution(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            create_git_repo(root)
            git(root, "switch", "-c", "feature/reuse")
            state_path = root / ".smsecure-l10n" / "state.json"
            state_path.parent.mkdir()
            save_json(
                state_path,
                {
                    "version": 2,
                    "branches": {
                        "main": {"base": {}, "langs": {}, "glossaries": {}},
                        "feature/reuse": {
                            "created_from": "main",
                            "base": {"feature": "hash"},
                            "langs": {},
                            "glossaries": {},
                        },
                    },
                },
            )

            with patch("l10n_tool.state.infer_parent_branch", side_effect=AssertionError("not needed")):
                session = open_state_session(res_dir=root / "res", state_dir_arg=str(root))

            self.assertEqual("feature/reuse", session.branch)
            self.assertEqual("hash", session.branch_state["base"]["feature"])

    def test_invalid_from_branch_is_rejected_without_state_change(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            create_git_repo(root)
            git(root, "switch", "-c", "feature/invalid-source")
            state_path = root / ".smsecure-l10n" / "state.json"
            state_path.parent.mkdir()
            save_json(
                state_path,
                {
                    "version": 2,
                    "branches": {
                        "main": {"base": {}, "langs": {}, "glossaries": {}},
                    },
                },
            )
            original = state_path.read_bytes()

            with self.assertRaisesRegex(SystemExit, "has no state"):
                open_state_session(
                    res_dir=root / "res",
                    state_dir_arg=str(root),
                    from_branch="does-not-exist",
                )

            self.assertEqual(original, state_path.read_bytes())


class CommandStateIsolationTest(unittest.TestCase):
    def test_sync_marks_inherited_translation_stale_when_base_diverges(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            create_git_repo(root)
            res_dir = write_resources(root)
            (res_dir / "values-de" / "strings.xml").write_text(
                """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="hello">Hallo</string>
</resources>
""",
                encoding="utf-8",
            )

            sync_command(sync_args(res_dir, root))
            main_state = json.loads((root / ".smsecure-l10n" / "state.json").read_text(encoding="utf-8"))
            self.assertEqual("ok", main_state["branches"]["main"]["langs"]["de"]["string:hello"]["status"])

            git(root, "switch", "-c", "feature/diverged")
            (res_dir / "values" / "strings.xml").write_text(
                (res_dir / "values" / "strings.xml").read_text(encoding="utf-8").replace("Hello", "Changed"),
                encoding="utf-8",
            )
            sync_command(sync_args(res_dir, root, from_branch="main"))

            feature_state = json.loads((root / ".smsecure-l10n" / "state.json").read_text(encoding="utf-8"))
            self.assertEqual("stale", feature_state["branches"]["feature/diverged"]["langs"]["de"]["string:hello"]["status"])

            git(root, "switch", "main")
            main_state = json.loads((root / ".smsecure-l10n" / "state.json").read_text(encoding="utf-8"))
            self.assertEqual("ok", main_state["branches"]["main"]["langs"]["de"]["string:hello"]["status"])

    def test_sync_report_and_translate_use_independent_branch_state(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            create_git_repo(root)
            res_dir = write_resources(root)

            sync_command(sync_args(res_dir, root))
            main_state = json.loads((root / ".smsecure-l10n" / "state.json").read_text(encoding="utf-8"))
            self.assertEqual("missing", main_state["branches"]["main"]["langs"]["de"]["string:hello"]["status"])

            git(root, "switch", "-c", "feature/isolated")
            sync_command(sync_args(res_dir, root, from_branch="main"))
            feature_state = json.loads((root / ".smsecure-l10n" / "state.json").read_text(encoding="utf-8"))
            self.assertEqual("main", feature_state["branches"]["feature/isolated"]["created_from"])

            report_command(
                Namespace(
                    res=str(res_dir),
                    langs="de",
                    langs_file="",
                    state_dir=str(root),
                    from_branch="",
                    status="missing",
                )
            )

            with patch("l10n_tool.commands.translate.deepl_get_supported_target_langs", return_value=[]):
                translate_command(
                    Namespace(
                        res=str(res_dir),
                        base="values",
                        langs="de",
                        langs_file="",
                        status="missing",
                        state_dir=str(root),
                        from_branch="",
                        deepl_key="test-key",
                        deepl_base_url="https://example.invalid",
                        sleep_ms=0,
                        verbose=False,
                        sync=False,
                        glossary_sync=False,
                        glossary_file="",
                    )
                )

            feature_state = json.loads((root / ".smsecure-l10n" / "state.json").read_text(encoding="utf-8"))
            self.assertEqual("skipped", feature_state["branches"]["feature/isolated"]["langs"]["de"]["string:hello"]["status"])

            git(root, "switch", "main")
            main_state = json.loads((root / ".smsecure-l10n" / "state.json").read_text(encoding="utf-8"))
            self.assertEqual("missing", main_state["branches"]["main"]["langs"]["de"]["string:hello"]["status"])

    def test_report_does_not_write_legacy_state(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            create_git_repo(root)
            state_path = root / ".smsecure-l10n" / "state.json"
            state_path.parent.mkdir()
            original = '{"base": {}, "langs": {"de": {}}, "glossaries": {}}\n'
            state_path.write_text(original, encoding="utf-8")

            report_command(
                Namespace(
                    res=str(root / "res"),
                    langs="de",
                    langs_file="",
                    state_dir=str(root),
                    from_branch="",
                    status="missing",
                )
            )
            self.assertEqual(original, state_path.read_text(encoding="utf-8"))
            self.assertFalse(state_path.with_name("state.json.legacy-backup").exists())


class AtomicStateSaveTest(unittest.TestCase):
    def test_failed_serialization_preserves_existing_state_and_cleans_temp_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "state.json"
            original = '{"version": 2}\n'
            path.write_text(original, encoding="utf-8")

            with self.assertRaises(TypeError):
                save_json(path, {"bad": object()})

            self.assertEqual(original, path.read_text(encoding="utf-8"))
            self.assertEqual([], list(path.parent.glob(".state.json.*.tmp")))

    def test_replace_failure_preserves_existing_state_and_cleans_temp_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "state.json"
            original = '{"version": 2}\n'
            path.write_text(original, encoding="utf-8")

            with patch("l10n_tool.utils.os.replace", side_effect=OSError("simulated power loss")):
                with self.assertRaises(OSError):
                    save_json(path, {"version": 2, "branches": {}})

            self.assertEqual(original, path.read_text(encoding="utf-8"))
            self.assertEqual([], list(path.parent.glob(".state.json.*.tmp")))

    def test_missing_optional_legacy_sections_are_preserved_and_usable(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            create_git_repo(root)
            state_path = root / ".smsecure-l10n" / "state.json"
            state_path.parent.mkdir()
            save_json(state_path, {"base": {"string:old": "hash"}, "langs": {"de": {}}})

            session = open_state_session(res_dir=root / "res", state_dir_arg=str(root))
            self.assertEqual({}, session.branch_state.get("glossaries", {}))
            self.assertEqual("hash", session.branch_state["base"]["string:old"])


if __name__ == "__main__":
    unittest.main()
