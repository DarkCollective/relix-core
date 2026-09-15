#!/usr/bin/env python3
"""Merge a connector-catalog entry into the hosted catalog.

Usage:
    update_connector_catalog.py <catalog.json> <entry.json>

Replaces any existing entry with the same ``type`` (idempotent re-releases), then
appends the new entry, writing the catalog back as pretty-printed JSON. The entry
is produced by ``./gradlew :relix-mongo-connector:connectorCatalogEntry``.
"""
import json
import sys


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit("usage: update_connector_catalog.py <catalog.json> <entry.json>")
    catalog_path, entry_path = sys.argv[1], sys.argv[2]

    with open(entry_path, encoding="utf-8") as f:
        entry = json.load(f)
    entry_type = entry.get("type")
    if not entry_type:
        sys.exit(f"{entry_path}: entry has no 'type'")

    try:
        with open(catalog_path, encoding="utf-8") as f:
            catalog = json.load(f)
    except FileNotFoundError:
        catalog = []
    if not isinstance(catalog, list):
        sys.exit(f"{catalog_path}: expected a JSON array")

    catalog = [e for e in catalog if e.get("type") != entry_type]
    catalog.append(entry)

    with open(catalog_path, "w", encoding="utf-8") as f:
        json.dump(catalog, f, indent=2)
        f.write("\n")

    print(f"Updated {catalog_path}: '{entry_type}' "
          f"({len(entry.get('artifacts', []))} artifacts)")


if __name__ == "__main__":
    main()
