from __future__ import annotations

import sys
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path


L10N_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(L10N_ROOT))

from l10n_tool.commands.sync import (  # noqa: E402
    _attach_section_markers_to_first_locale_entries,
    sync_command,
)
from l10n_tool.xml_backend import (  # noqa: E402
    PluralsEntry,
    StringArrayEntry,
    StringEntry,
    XmlBackend,
    build_entry_map,
    read_entries,
)


class SectionCommentAssignmentTest(unittest.TestCase):
    def test_preserves_comment_order_when_section_first_entry_exists(self) -> None:
        base_entries = [
            StringEntry(
                name="first",
                text="First",
                translatable=True,
                comments=["First entry note", "Section"],
            )
        ]
        locale_map = build_entry_map(
            [
                StringEntry(
                    name="first",
                    text="Translated",
                    translatable=True,
                    comments=[],
                )
            ]
        )

        prepared = _attach_section_markers_to_first_locale_entries(
            base_entries,
            locale_map,
            "de",
        )

        self.assertEqual(["First entry note", "Section"], prepared[0].comments)

    def test_moves_only_section_marker_to_first_complete_locale_entry(self) -> None:
        base_entries = [
            StringEntry(
                name="first",
                text="First",
                translatable=True,
                comments=["First entry note", "Section"],
            ),
            StringEntry(
                name="second",
                text="Second",
                translatable=True,
                comments=[],
            ),
        ]
        locale_map = build_entry_map(
            [
                StringEntry(
                    name="second",
                    text="Translated",
                    translatable=True,
                    comments=[],
                )
            ]
        )

        prepared = _attach_section_markers_to_first_locale_entries(
            base_entries,
            locale_map,
            "de",
        )

        self.assertEqual(["First entry note"], prepared[0].comments)
        self.assertEqual(["Section"], prepared[1].comments)

    def test_skips_incomplete_plural_before_translated_array(self) -> None:
        base_entries = [
            StringEntry(
                name="first",
                text="First",
                translatable=True,
                comments=["Section"],
            ),
            PluralsEntry(
                name="second",
                items={"one": "One", "other": "Other"},
                translatable=True,
                comments=[],
            ),
            StringArrayEntry(
                name="third",
                items=["Item"],
                translatable=True,
                comments=[],
            ),
        ]
        locale_map = build_entry_map(
            [
                PluralsEntry(
                    name="second",
                    items={"one": "Eins"},
                    translatable=True,
                    comments=[],
                ),
                StringArrayEntry(
                    name="third",
                    items=["Eintrag"],
                    translatable=True,
                    comments=[],
                ),
            ]
        )

        prepared = _attach_section_markers_to_first_locale_entries(
            base_entries,
            locale_map,
            "de",
        )

        self.assertEqual([], prepared[1].comments)
        self.assertEqual(["Section"], prepared[2].comments)


class SyncSectionCommentIntegrationTest(unittest.TestCase):
    def test_sync_restores_marker_before_first_existing_locale_resource(self) -> None:
        if not XmlBackend().use_lxml:
            self.skipTest("lxml is required for comment-preserving sync")

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            res_dir = root / "res"
            base_dir = res_dir / "values"
            locale_dir = res_dir / "values-de"
            base_dir.mkdir(parents=True)
            locale_dir.mkdir(parents=True)

            (base_dir / "strings.xml").write_text(
                """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!--Notification Channels-->
    <string name="MmsListener_notification_text">MMS text</string>
    <string name="MmsListener_notification_title">MMS title</string>
    <string name="NotificationChannel_failures">Failures</string>
</resources>
""",
                encoding="utf-8",
            )
            locale_file = locale_dir / "strings.xml"
            locale_file.write_text(
                """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="NotificationChannel_failures">Fehler</string>
</resources>
""",
                encoding="utf-8",
            )

            sync_command(
                Namespace(
                    res=str(res_dir),
                    base="values",
                    langs="de",
                    langs_file="",
                    state_dir=str(root),
                    glossary_file="",
                    mark_all_stale=False,
                    mark_all_ok=False,
                    mark_all_plurals_stale=False,
                )
            )

            entries, _ = read_entries(XmlBackend(), locale_file)
            self.assertEqual(1, len(entries))
            self.assertEqual("NotificationChannel_failures", entries[0].name)
            self.assertEqual(["Notification Channels"], entries[0].comments)


if __name__ == "__main__":
    unittest.main()
