#!/usr/bin/env python3
"""Generate the recipe finder from each recipe's coordinate line.

The finder is derived, never hand-maintained: it reads the fixed coordinate line at
the top of every recipe (see coordinate.md) and inverts it into three indexes — by
question word (Signals), by operator (Operators), and by class (Class). Run it from
the manual root:

    python3 _generate_finder.py        # writes finder.md
    python3 _generate_finder.py --check # exit 1 if finder.md is stale

When the manual moves to relix-core this becomes a build task; until then the check
mode is the guard that the committed finder.md matches the recipes.
"""
import re
import sys
import pathlib

ROOT = pathlib.Path(__file__).parent
RECIPES = ROOT / "recipes"
OUT = ROOT / "finder.md"

FIELD = re.compile(r"\*\*(Grain|Class|Signals|Operators|Difficulties):\*\*\s*(.+?)\s*(?=·\s*\*\*|$)")


def parse_coordinate(path):
    """Return {field: value} from the first blockquote after the H1, or None."""
    title = None
    for line in path.read_text().splitlines():
        s = line.strip()
        if title is None and s.startswith("# "):
            title = s[2:].strip()
            continue
        if title is not None and s.startswith(">"):
            fields = {k: v for k, v in FIELD.findall(s)}
            if "Class" in fields:
                fields["_title"] = title
                fields["_file"] = path.name
                return fields
    return None


def split_list(value):
    return [p.strip() for p in value.split(",") if p.strip()]


def build():
    coords = []
    for path in sorted(RECIPES.glob("*.md")):
        if path.name == "_template.md":
            continue
        c = parse_coordinate(path)
        if c is None:
            raise SystemExit(f"no coordinate found in {path.name}")
        coords.append(c)

    def link(c):
        return f"[{c['_title']}](recipes/{c['_file']})"

    lines = [
        "# Recipe finder",
        "",
        "*Generated from each recipe's coordinate by `_generate_finder.py` — do not edit"
        " by hand.* Three ways in: by the word in the question, by the operator, and by"
        " the class of problem.",
        "",
        "## By question word",
        "",
        "The words that signal each recipe, alphabetised.",
        "",
        "| Signal | Recipe |",
        "|---|---|",
    ]
    by_signal = {}
    for c in coords:
        for s in split_list(c.get("Signals", "")):
            by_signal.setdefault(s.lower(), set()).add(link(c))
    for s in sorted(by_signal):
        lines.append(f"| {s} | {' · '.join(sorted(by_signal[s]))} |")

    lines += ["", "## By operator", "", "| Operator | Recipe |", "|---|---|"]
    by_op = {}
    for c in coords:
        for op in split_list(c.get("Operators", "")):
            by_op.setdefault(op, set()).add(link(c))
    for op in sorted(by_op, key=lambda o: o.strip("`").lower()):
        lines.append(f"| {op} | {' · '.join(sorted(by_op[op]))} |")

    lines += ["", "## By class", "", "| Class | Recipe |", "|---|---|"]
    by_class = {}
    for c in coords:
        by_class.setdefault(c["Class"], set()).add(link(c))
    for cls in sorted(by_class):
        lines.append(f"| {cls} | {' · '.join(sorted(by_class[cls]))} |")

    return "\n".join(lines) + "\n"


def main():
    content = build()
    if "--check" in sys.argv:
        current = OUT.read_text() if OUT.exists() else ""
        if current != content:
            print("finder.md is stale; run _generate_finder.py", file=sys.stderr)
            sys.exit(1)
        print("finder.md is up to date")
    else:
        OUT.write_text(content)
        print(f"wrote {OUT.name}")


if __name__ == "__main__":
    main()
