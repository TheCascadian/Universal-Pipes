#!/usr/bin/env python3
"""Compare every lang file against en_us.json: missing keys, extra keys, format specifiers."""
import json
import re
import sys
from collections import Counter
from pathlib import Path

LANG_DIR = Path(__file__).resolve().parent.parent / "src/main/resources/assets/universal_pipes/lang"
REFERENCE = "en_us.json"
SPECIFIER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]|%%")


def load(path):
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, dict):
        raise ValueError("top level is not an object")
    return data


def specifiers(text):
    return Counter(match for match in SPECIFIER.findall(text) if match != "%%")


def main():
    lang_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else LANG_DIR
    reference = load(lang_dir / REFERENCE)
    failed = False
    others = sorted(path for path in lang_dir.glob("*.json") if path.name != REFERENCE)
    if not others:
        print("No other locales to compare against " + REFERENCE)
    for path in others:
        try:
            data = load(path)
        except (ValueError, OSError) as error:
            print(f"{path.name}: cannot read: {error}")
            failed = True
            continue
        missing = sorted(reference.keys() - data.keys())
        extra = sorted(data.keys() - reference.keys())
        problems = [f"missing key: {key}" for key in missing] + [f"extra key: {key}" for key in extra]
        for key in sorted(reference.keys() & data.keys()):
            wanted, found = specifiers(str(reference[key])), specifiers(str(data[key]))
            if wanted != found:
                problems.append(
                    f"format mismatch in {key}: expected {sorted(wanted.elements())}, found {sorted(found.elements())}")
        for problem in problems:
            print(f"{path.name}: {problem}")
        print(f"{path.name}: {len(problems)} problem(s)")
        failed = failed or bool(problems)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
