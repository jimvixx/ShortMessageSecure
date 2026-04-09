# l10n_tool/xml_backend.py
# -*- coding: utf-8 -*-

from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Tuple, Union
from xml.sax.saxutils import escape


PLURAL_QUANTITY_ORDER: Dict[str, int] = {
    "zero": 0,
    "one": 1,
    "two": 2,
    "few": 3,
    "many": 4,
    "other": 5,
}


@dataclass
class StringEntry:
    name: str
    text: str
    translatable: bool
    comments: List[str]


@dataclass
class PluralsEntry:
    name: str
    items: Dict[str, str]
    translatable: bool
    comments: List[str]


@dataclass
class StringArrayEntry:
    name: str
    items: List[str]
    translatable: bool
    comments: List[str]


ResourceEntry = Union[StringEntry, PluralsEntry, StringArrayEntry]


def entry_kind(e: ResourceEntry) -> str:
    if isinstance(e, StringEntry):
        return "string"
    if isinstance(e, PluralsEntry):
        return "plurals"
    return "string-array"


def build_entry_map(entries: List[ResourceEntry]) -> Dict[Tuple[str, str], ResourceEntry]:
    out: Dict[Tuple[str, str], ResourceEntry] = {}
    for e in entries:
        out[(entry_kind(e), e.name)] = e  # type: ignore[attr-defined]
    return out


def _sha1_text(text: str) -> str:
    import hashlib
    return hashlib.sha1(text.encode("utf-8")).hexdigest()


def _sorted_plural_quantity_keys(items: Dict[str, str]) -> List[str]:
    return sorted(
        items.keys(),
        key=lambda q: (PLURAL_QUANTITY_ORDER.get(q, 999), q),
    )


def entry_source_hash(e: ResourceEntry) -> str:
    sep = "\u241E"
    if isinstance(e, StringEntry):
        return _sha1_text(e.text)
    if isinstance(e, PluralsEntry):
        parts = [f"{q}={e.items.get(q, '')}" for q in _sorted_plural_quantity_keys(e.items)]
        return _sha1_text(sep.join(parts))
    if isinstance(e, StringArrayEntry):
        return _sha1_text(sep.join(e.items))
    raise AssertionError("Unknown entry type")


class XmlBackend:
    """
    Backend that uses lxml when available (preserves comments & formatting better),
    falls back to xml.etree.ElementTree otherwise.

    Important:
    text/item values store FULL inner XML, not only .text.
    This preserves mixed content such as <xliff:g>.
    """

    def __init__(self) -> None:
        self.use_lxml = False
        self.LET = None
        try:
            import lxml.etree as LET  # type: ignore
            self.LET = LET
            self.use_lxml = True
        except Exception:
            self.use_lxml = False
            self.LET = None

    def parse(self, path: Path):
        if self.use_lxml:
            parser = self.LET.XMLParser(remove_blank_text=False, recover=True)
            return self.LET.parse(str(path), parser)
        else:
            import xml.etree.ElementTree as ET
            return ET.parse(str(path))

    def tostring(self, tree) -> str:
        _ensure_root_xliff_namespace(tree)
        _remove_local_xliff_ns_from_plurals(tree)

        if self.use_lxml:
            xml = self.LET.tostring(
                tree,
                encoding="utf-8",
                xml_declaration=True,
                pretty_print=True,
            ).decode("utf-8")
            return xml.replace("\r\n", "\n")
        else:
            import xml.etree.ElementTree as ET
            root = tree.getroot()
            xml = ET.tostring(root, encoding="utf-8").decode("utf-8")
            return '<?xml version="1.0" encoding="utf-8"?>\n' + xml + "\n"


_XLIFF_NS = "urn:oasis:names:tc:xliff:document:1.2"


def _ensure_root_xliff_namespace(tree) -> None:
    if not hasattr(tree, "getroot"):
        return

    root = tree.getroot()
    if root is None:
        return

    if getattr(root, "tag", None) != "resources":
        return

    if getattr(root, "nsmap", None) is not None:
        nsmap = dict(root.nsmap or {})
        if nsmap.get("xliff") != _XLIFF_NS:
            import lxml.etree as LET2  # type: ignore

            new_root = LET2.Element("resources", nsmap={**nsmap, "xliff": _XLIFF_NS})
            new_root.text = root.text
            new_root.tail = root.tail

            for key, value in root.attrib.items():
                new_root.set(key, value)

            for child in list(root):
                root.remove(child)
                new_root.append(child)

            parent = root.getparent()
            if parent is None:
                tree._setroot(new_root)
            else:
                parent.replace(root, new_root)
    else:
        import xml.etree.ElementTree as ET
        ET.register_namespace("xliff", _XLIFF_NS)


def _remove_local_xliff_ns_from_plurals(tree) -> None:
    root = tree.getroot()
    if root is None:
        return

    for node in root.findall("plurals"):
        bad_keys = [
            key
            for key in list(node.attrib.keys())
            if key == "xmlns:xliff" or key.endswith("}xliff")
        ]
        for key in bad_keys:
            node.attrib.pop(key, None)


def _lxml_inner_xml(node) -> str:
    import lxml.etree as LET2  # type: ignore

    parts: List[str] = []
    if node.text:
        parts.append(escape(node.text))
    for child in node:
        parts.append(LET2.tostring(child, encoding="unicode"))
    return "".join(parts)


def _lxml_replace_inner_xml(node, inner_xml: str) -> None:
    import lxml.etree as LET2  # type: ignore

    for child in list(node):
        node.remove(child)
    node.text = None

    if not inner_xml:
        return

    wrapper = LET2.fromstring(
        f'<wrapper xmlns:xliff="{_XLIFF_NS}">{inner_xml}</wrapper>'
    )

    node.text = wrapper.text
    for child in list(wrapper):
        wrapper.remove(child)
        node.append(child)

    children = list(node)
    for child in children:
        if child.tail is None:
            child.tail = ""


def _etree_inner_xml(node) -> str:
    import xml.etree.ElementTree as ET

    parts: List[str] = []
    if node.text:
        parts.append(escape(node.text))
    for child in list(node):
        parts.append(ET.tostring(child, encoding="unicode"))
    return "".join(parts)


def _etree_replace_inner_xml(node, inner_xml: str) -> None:
    import xml.etree.ElementTree as ET

    for child in list(node):
        node.remove(child)
    node.text = None

    if not inner_xml:
        return

    ET.register_namespace("xliff", _XLIFF_NS)
    wrapper = ET.fromstring(f'<wrapper xmlns:xliff="{_XLIFF_NS}">{inner_xml}</wrapper>')

    node.text = wrapper.text
    for child in list(wrapper):
        wrapper.remove(child)
        node.append(child)


def read_entries(xml_backend: XmlBackend, path: Path) -> Tuple[List[ResourceEntry], Any]:
    tree = xml_backend.parse(path)
    root = tree.getroot()

    entries: List[ResourceEntry] = []
    comments_buffer: List[str] = []

    if xml_backend.use_lxml:
        LET = xml_backend.LET
        for node in root.iterchildren():
            if isinstance(node, LET._Comment):  # type: ignore[attr-defined]
                c = (node.text or "").strip()
                if c:
                    comments_buffer.append(c)
                continue

            tag = getattr(node, "tag", None)

            if tag == "string":
                name = (node.get("name") or "").strip()
                if not name:
                    comments_buffer = []
                    continue
                text = _lxml_inner_xml(node)
                translatable = (node.get("translatable", "true") != "false")
                entries.append(
                    StringEntry(
                        name=name,
                        text=text,
                        translatable=translatable,
                        comments=list(comments_buffer),
                    )
                )
                comments_buffer = []
                continue

            if tag == "plurals":
                name = (node.get("name") or "").strip()
                if not name:
                    comments_buffer = []
                    continue
                translatable = (node.get("translatable", "true") != "false")
                items: Dict[str, str] = {}
                for it in node.findall("item"):
                    q = (it.get("quantity") or "").strip()
                    if not q:
                        continue
                    items[q] = _lxml_inner_xml(it)
                entries.append(
                    PluralsEntry(
                        name=name,
                        items=items,
                        translatable=translatable,
                        comments=list(comments_buffer),
                    )
                )
                comments_buffer = []
                continue

            if tag == "string-array":
                name = (node.get("name") or "").strip()
                if not name:
                    comments_buffer = []
                    continue
                translatable = (node.get("translatable", "true") != "false")
                items: List[str] = [_lxml_inner_xml(it) for it in node.findall("item")]
                entries.append(
                    StringArrayEntry(
                        name=name,
                        items=items,
                        translatable=translatable,
                        comments=list(comments_buffer),
                    )
                )
                comments_buffer = []
                continue

            comments_buffer = []
    else:
        for node in root.findall("string"):
            name = (node.get("name") or "").strip()
            if not name:
                continue
            text = _etree_inner_xml(node)
            translatable = (node.get("translatable", "true") != "false")
            entries.append(StringEntry(name=name, text=text, translatable=translatable, comments=[]))

        for node in root.findall("plurals"):
            name = (node.get("name") or "").strip()
            if not name:
                continue
            translatable = (node.get("translatable", "true") != "false")
            items: Dict[str, str] = {}
            for it in node.findall("item"):
                q = (it.get("quantity") or "").strip()
                if not q:
                    continue
                items[q] = _etree_inner_xml(it)
            entries.append(PluralsEntry(name=name, items=items, translatable=translatable, comments=[]))

        for node in root.findall("string-array"):
            name = (node.get("name") or "").strip()
            if not name:
                continue
            translatable = (node.get("translatable", "true") != "false")
            items: List[str] = [_etree_inner_xml(it) for it in node.findall("item")]
            entries.append(StringArrayEntry(name=name, items=items, translatable=translatable, comments=[]))

    return entries, tree


def _lxml_find_node(tree, tag: str, name: str):
    root = tree.getroot()
    for node in root.iterchildren():
        if getattr(node, "tag", None) == tag and node.get("name") == name:
            return node
    return None


def _lxml_get_immediate_comment_block_before(node) -> List[Any]:
    import lxml.etree as LET2  # type: ignore

    prev = node.getprevious()
    block: List[Any] = []
    while prev is not None:
        if isinstance(prev, LET2._Comment):
            block.append(prev)
            prev = prev.getprevious()
            continue
        break
    return list(reversed(block))


def _lxml_comment_indent(node) -> str:
    parent = node.getparent()
    if parent is None:
        return "\n    "
    return parent.text if parent.text is not None else "\n    "


def _lxml_resource_indent(node) -> str:
    parent = node.getparent()
    if parent is None:
        return "\n"
    return parent.text if parent.text is not None else "\n    "


def _lxml_child_indent() -> str:
    return "\n        "


def _lxml_closing_indent() -> str:
    return "\n    "


def _lxml_normalize_block_format(node) -> None:
    indent = _lxml_comment_indent(node)
    for c in _lxml_get_immediate_comment_block_before(node):
        c.tail = indent
    node.tail = _lxml_resource_indent(node)


def _lxml_sync_all_comments_before(node, desired_comments: List[str]) -> None:
    import lxml.etree as LET2  # type: ignore

    desired = [(c or "").strip() for c in (desired_comments or []) if (c or "").strip()]
    existing_nodes = _lxml_get_immediate_comment_block_before(node)
    existing_texts = [(n.text or "").strip() for n in existing_nodes]

    if existing_texts != desired:
        parent = node.getparent()
        if parent is None:
            return

        for n in existing_nodes:
            parent.remove(n)

        indent = _lxml_comment_indent(node)
        for c in desired:
            comment = LET2.Comment(c)
            comment.tail = indent
            node.addprevious(comment)

    _lxml_normalize_block_format(node)


def lxml_remove_entry(tree, tag: str, name: str) -> None:
    node = _lxml_find_node(tree, tag, name)
    if node is None:
        return

    parent = node.getparent()
    if parent is None:
        return

    for c in _lxml_get_immediate_comment_block_before(node):
        parent.remove(c)

    parent.remove(node)


def lxml_upsert_string(tree, e: StringEntry, text: str) -> None:
    import lxml.etree as LET2  # type: ignore

    root = tree.getroot()
    node = _lxml_find_node(tree, "string", e.name)

    if node is None:
        node = LET2.Element("string")
        node.set("name", e.name)
        if not e.translatable:
            node.set("translatable", "false")
        _lxml_replace_inner_xml(node, text)
        root.append(node)
        _lxml_sync_all_comments_before(node, e.comments)
    else:
        _lxml_replace_inner_xml(node, text)
        if not e.translatable:
            node.set("translatable", "false")
        else:
            node.attrib.pop("translatable", None)
        _lxml_sync_all_comments_before(node, e.comments)

    if "\n" not in text:
        node.tail = _lxml_resource_indent(node)


def lxml_upsert_plurals(tree, e: PluralsEntry, items: Dict[str, str]) -> None:
    import lxml.etree as LET2  # type: ignore

    root = tree.getroot()
    node = _lxml_find_node(tree, "plurals", e.name)

    if node is None:
        node = LET2.Element("plurals")
        node.set("name", e.name)
        root.append(node)

    if not e.translatable:
        node.set("translatable", "false")
    else:
        node.attrib.pop("translatable", None)

    node.attrib.pop("xmlns:xliff", None)

    for child in list(node.findall("item")):
        node.remove(child)

    for quantity, text in items.items():
        it = LET2.SubElement(node, "item")
        it.set("quantity", quantity)
        _lxml_replace_inner_xml(it, text)

    if len(node):
        node.text = _lxml_child_indent()
        children = list(node)
        for child in children[:-1]:
            child.tail = _lxml_child_indent()
        children[-1].tail = _lxml_closing_indent()
    else:
        node.text = None

    _lxml_sync_all_comments_before(node, e.comments)
    node.tail = _lxml_resource_indent(node)


def lxml_upsert_array(tree, e: StringArrayEntry, items: List[str]) -> None:
    import lxml.etree as LET2  # type: ignore

    root = tree.getroot()
    node = _lxml_find_node(tree, "string-array", e.name)

    if node is None:
        node = LET2.Element("string-array")
        node.set("name", e.name)
        if not e.translatable:
            node.set("translatable", "false")

        for t in items:
            it = LET2.SubElement(node, "item")
            _lxml_replace_inner_xml(it, t)

        root.append(node)

    else:
        if not e.translatable:
            node.set("translatable", "false")
        else:
            node.attrib.pop("translatable", None)

        existing_items = node.findall("item")
        while len(existing_items) > len(items):
            node.remove(existing_items[-1])
            existing_items = node.findall("item")

        while len(existing_items) < len(items):
            LET2.SubElement(node, "item")
            existing_items = node.findall("item")

        for i, t in enumerate(items):
            _lxml_replace_inner_xml(existing_items[i], t)

    if len(node):
        node.text = _lxml_child_indent()
        children = list(node)
        for child in children[:-1]:
            child.tail = _lxml_child_indent()
        children[-1].tail = _lxml_closing_indent()
    else:
        node.text = None

    _lxml_sync_all_comments_before(node, e.comments)
    node.tail = _lxml_resource_indent(node)


def lxml_reorder_entries(tree, ordered_keys: List[Tuple[str, str]]) -> None:
    root = tree.getroot()
    resource_tags = {"string", "plurals", "string-array"}

    snapshots: Dict[Tuple[str, str], List[Any]] = {}
    extras: List[List[Any]] = []

    for node in list(root.iterchildren()):
        tag = getattr(node, "tag", None)
        if tag not in resource_tags:
            continue

        name = (node.get("name") or "").strip()
        if not name:
            continue

        block = _lxml_get_immediate_comment_block_before(node) + [node]
        block_copy = [deepcopy(n) for n in block]
        key = (tag, name)

        if key not in snapshots:
            snapshots[key] = block_copy
        else:
            extras.append(block_copy)

    for node in list(root.iterchildren()):
        tag = getattr(node, "tag", None)
        if tag not in resource_tags:
            continue

        for c in _lxml_get_immediate_comment_block_before(node):
            parent = c.getparent()
            if parent is root:
                root.remove(c)

        parent = node.getparent()
        if parent is root:
            root.remove(node)

    used_keys = set()

    for key in ordered_keys:
        block = snapshots.get(key)
        if not block:
            continue
        used_keys.add(key)
        for n in block:
            root.append(n)

    for key, block in snapshots.items():
        if key in used_keys:
            continue
        for n in block:
            root.append(n)

    for block in extras:
        for n in block:
            root.append(n)

    for node in root.iterchildren():
        if getattr(node, "tag", None) in resource_tags:
            _lxml_normalize_block_format(node)


def etree_remove_entry(tree, tag: str, name: str) -> None:
    root = tree.getroot()
    for node in list(root.findall(tag)):
        if (node.get("name") or "") == name:
            root.remove(node)
            return


def etree_upsert_string(tree, e: StringEntry, text: str) -> None:
    root = tree.getroot()
    node = None
    for n in root.findall("string"):
        if (n.get("name") or "") == e.name:
            node = n
            break

    if node is None:
        import xml.etree.ElementTree as ET
        node = ET.SubElement(root, "string")
        node.set("name", e.name)

    if not e.translatable:
        node.set("translatable", "false")
    else:
        node.attrib.pop("translatable", None)

    _etree_replace_inner_xml(node, text)


def etree_upsert_plurals(tree, e: PluralsEntry, items: Dict[str, str]) -> None:
    root = tree.getroot()
    node = None
    for n in root.findall("plurals"):
        if (n.get("name") or "") == e.name:
            node = n
            break

    if node is None:
        import xml.etree.ElementTree as ET
        node = ET.SubElement(root, "plurals")
        node.set("name", e.name)

    if not e.translatable:
        node.set("translatable", "false")
    else:
        node.attrib.pop("translatable", None)

    node.attrib.pop("xmlns:xliff", None)

    import xml.etree.ElementTree as ET

    for child in list(node.findall("item")):
        node.remove(child)

    for quantity, text in items.items():
        it = ET.SubElement(node, "item")
        it.set("quantity", quantity)
        _etree_replace_inner_xml(it, text)


def etree_upsert_array(tree, e: StringArrayEntry, items: List[str]) -> None:
    root = tree.getroot()
    node = None
    for n in root.findall("string-array"):
        if (n.get("name") or "") == e.name:
            node = n
            break

    if node is None:
        import xml.etree.ElementTree as ET
        node = ET.SubElement(root, "string-array")
        node.set("name", e.name)

    if not e.translatable:
        node.set("translatable", "false")
    else:
        node.attrib.pop("translatable", None)

    import xml.etree.ElementTree as ET
    existing_items = node.findall("item")

    while len(existing_items) > len(items):
        node.remove(existing_items[-1])
        existing_items = node.findall("item")

    while len(existing_items) < len(items):
        ET.SubElement(node, "item")
        existing_items = node.findall("item")

    for i, t in enumerate(items):
        _etree_replace_inner_xml(existing_items[i], t)