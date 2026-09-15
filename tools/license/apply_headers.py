#!/usr/bin/env python3
"""Put the Apache header on every Java source file that lacks one.

The text lives in HEADER.txt beside this script and nowhere else: the guard that
holds the tree to it reads the same file, so the header cannot be right in one
place and wrong in another.

Two details decide where it goes, and both are about not breaking what is already
there:

  * It is a `/* */` comment, never `/** */`. A doc comment in this position would
    be read by javadoc as the package's documentation on a package-info.java, and
    as a stray doc comment everywhere else.

  * It goes at the very top, above whatever is already there. On a package-info.java
    the javadoc must stay immediately before the `package` line — inserting between
    the two would detach a package's documentation from the package.

Usage:
    python3 tools/license/apply_headers.py [--check]

`--check` reports what is missing and changes nothing.
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HEADER_TEXT = (Path(__file__).parent / "HEADER.txt").read_text().rstrip("\n")
MARKER = "Licensed under the Apache License"


def block() -> str:
    lines = ["/*"]
    for line in HEADER_TEXT.splitlines():
        lines.append(" * " + line if line else " *")
    lines.append(" */")
    return "\n".join(lines) + "\n"


def java_files():
    for src in sorted(ROOT.glob("*/src/*/java")):
        for path in sorted(src.rglob("*.java")):
            if "/build/" not in str(path):
                yield path


def main() -> int:
    check = "--check" in sys.argv
    header = block()
    missing = []
    for path in java_files():
        text = path.read_text(encoding="utf-8")
        if MARKER in text:
            continue
        missing.append(path)
        if not check:
            path.write_text(header + text, encoding="utf-8")

    rel = [str(p.relative_to(ROOT)) for p in missing]
    if check:
        for r in rel[:20]:
            print(f"  missing: {r}")
        if len(rel) > 20:
            print(f"  ... and {len(rel) - 20} more")
        print(f"{len(rel)} file(s) without the header")
        return 1 if rel else 0

    print(f"header added to {len(rel)} file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
