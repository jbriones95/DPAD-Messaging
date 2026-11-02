#!/usr/bin/env python3
"""Translation checks and English string inventory for DPAD Messaging.

Usage:
    python3 scripts/check-translations.py
        Validate every app/src/main/res/values-*/strings.xml file against the
        English source. Untranslated strings are allowed (they fall back to
        English). Unknown keys and placeholder mismatches are errors.

    python3 scripts/check-translations.py --inventory
        Print the English string inventory as Markdown, grouped by the section
        comments in values/strings.xml.

    python3 scripts/check-translations.py --list-missing LANG
        List source keys not yet present in values-<LANG>/strings.xml.
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES_DIR = ROOT / "app" / "src" / "main" / "res"
SOURCE_FILE = RES_DIR / "values" / "strings.xml"

# %s, %d, %1$s, %1$d, %%, and similar Android format specifiers.
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[a-zA-Z%]")
STRING_LINE = re.compile(r'<string name="([^"]+)"[^>]*>(.*)</string>')
SECTION_LINE = re.compile(r"<!--\s*[─\-]+\s*(.*?)\s*[─\-]+\s*-->")


def load_strings(path: Path) -> dict[str, str]:
    root = ET.parse(path).getroot()
    values: dict[str, str] = {}
    for element in root.findall("string"):
        name = element.get("name")
        if not name:
            continue
        if name in values:
            raise ValueError(f"{path}: duplicate string name {name!r}")
        values[name] = element.text or ""
    return values


def placeholders(text: str) -> list[str]:
    return sorted(PLACEHOLDER.findall(text))


def sections() -> list[tuple[str, list[tuple[str, str]]]]:
    """Return (section name, [(key, raw xml text), ...]) in source order."""
    result: list[tuple[str, list[tuple[str, str]]]] = [("Getting started", [])]
    for line in SOURCE_FILE.read_text(encoding="utf-8").splitlines():
        section = SECTION_LINE.search(line)
        if section:
            result.append((section.group(1).strip(), []))
            continue
        entry = STRING_LINE.search(line)
        if entry:
            result[-1][1].append((entry.group(1), entry.group(2)))
    return [(name, items) for name, items in result if items]


def unescape(text: str) -> str:
    return (
        text.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", '"')
    )


def notes(key: str, raw: str) -> str:
    items: list[str] = []
    if key == "app_name":
        items.append("Do not translate (app name)")
    found = placeholders(raw)
    if found:
        items.append("Keep placeholders: " + ", ".join(f"`{p}`" for p in found))
    if "\\n" in raw:
        items.append("Contains a line break (`\\n`)")
    if re.fullmatch(r"-|\+|%\d+/\d+|%\d+%%|100%%|\.\.\.", raw.strip()):
        items.append("Format or symbol only")
    return "; ".join(items) or "—"


def print_inventory() -> None:
    total = 0
    for section, items in sections():
        print(f"### {section}\n")
        print("| Key | English | Notes |")
        print("| --- | --- | --- |")
        for key, raw in items:
            total += 1
            text = unescape(raw).replace("|", "\\|")
            print(f"| `{key}` | {text} | {notes(key, raw)} |")
        print()
    print(f"<!-- {total} strings -->")


def locale_files() -> list[tuple[str, Path]]:
    files = []
    for path in sorted(RES_DIR.glob("values-*/strings.xml")):
        files.append((path.parent.name.removeprefix("values-"), path))
    return files


def check() -> int:
    source = load_strings(SOURCE_FILE)
    print(f"English source: {len(source)} strings")

    locales = locale_files()
    if not locales:
        print("No translations yet. See TRANSLATING.md to add one.")
        print("Translation check passed.")
        return 0

    failed = False
    for locale, path in locales:
        try:
            translated = load_strings(path)
        except (ET.ParseError, ValueError) as error:
            print(f"FAIL {path}: {error}")
            failed = True
            continue

        unknown = sorted(translated.keys() - source.keys())
        missing = sorted(source.keys() - translated.keys())
        problems: list[str] = []

        for key in unknown:
            problems.append(f"unknown key (not in English source): {key}")

        for key, text in translated.items():
            if key not in source:
                continue
            expected = placeholders(source[key])
            actual = placeholders(text)
            if expected != actual:
                problems.append(
                    f"placeholder mismatch in {key}: expected {expected or 'none'}, "
                    f"found {actual or 'none'}"
                )

        cover = len(source) - len(missing)
        percent = round(cover * 100 / len(source)) if source else 0
        status = "FAIL" if problems else "OK"
        print(f"\n{path.relative_to(ROOT)}: {status} ({cover}/{len(source)}, {percent}%)")
        for problem in problems:
            print(f"  error: {problem}")
        if missing:
            print(f"  {len(missing)} untranslated string(s); English is used for those.")
            print("  Run with --list-missing " + locale + " to see them.")
        failed = failed or bool(problems)

    print("\nTranslation check failed." if failed else "\nTranslation check passed.")
    return 1 if failed else 0


def list_missing(locale: str) -> int:
    source = load_strings(SOURCE_FILE)
    path = RES_DIR / f"values-{locale}" / "strings.xml"
    if not path.exists():
        print(f"No such locale file: {path}")
        return 2
    translated = load_strings(path)
    for key in source.keys() - translated.keys():
        print(key)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", action="store_true", help="print the English string inventory")
    parser.add_argument("--list-missing", metavar="LANG", help="list keys missing from values-<LANG>")
    args = parser.parse_args()

    if args.inventory:
        print_inventory()
        return 0
    if args.list_missing:
        return list_missing(args.list_missing)
    return check()


if __name__ == "__main__":
    sys.exit(main())
