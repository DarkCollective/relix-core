# Name: Inline Table (markdown / csv literal)

# Syntax:
-- Markdown form:
<Name> := [
| col1 | col2 |
|------|------|
| v1   | v2   |
];

-- CSV form:
<Name> := csv[
  col1, col2
  v1, v2
];

# Description:
An inline table embeds small reference data directly in the script — lookup
tables, status-code mappings, region lists — without needing an external file or
database. Two formats are available: a markdown table (easy to read) and a csv
block (for values containing commas or pipes). Column names come from the header
row.

# Technical Description:
An inline table binds an InlineRelationSymbol. In the markdown form, column names
come from the header row and all values are strings unless a column contains only
numbers (which infers NUMBER). In the csv form, the first non-blank line is the
header and fields with commas are quoted. Use csv[ ] when values contain commas or
pipe characters.

An inline table may declare foreign-key relationships with a trailing
`references { col -> Target.col }` clause — the inline-table
counterpart of the `references:` field on source declarations. Each entry
becomes a named edge of the schema graph, validated at analysis time. See
[relate](relate.md) for the full relationship model.

# Examples:
A markdown reference table:
```relix
Regions := [
| region | country        |
|--------|----------------|
| EMEA   | United Kingdom |
| APAC   | Australia      |
];
```

Numeric column infers NUMBER:
```relix
Prices := [
| item | price |
|------|-------|
| Pen  | 1.99  |
| Book | 12.50 |
];
```

CSV form for values containing commas:
```relix
Cities := csv[
    name, country
    "London, UK", GB
    "Paris, France", FR
];
```

An inline table that declares how it joins to a live source (each office row
references at most one country — the FK arrow points at the referenced side):

```relix
source Countries from database { url: "${DB}", table: "countries",
    schema: { code: STRING, name: STRING } };

Offices := [
| city   | country |
|--------|---------|
| London | GB      |
| Paris  | FR      |
] references { country -> Countries.code };
```

# Limitations:
Intended for small, static reference data — not large datasets (use a source for
those). Type inference is limited (numbers vs strings); there is no explicit
schema clause on inline tables.

# Alternatives:
`source … from csv("path")` for external files; `source … from database` for live
tables.

# See Also:
[source](source.md), [relate](relate.md), [assignment](assignment.md), [natural-join](../joins/natural-join.md)

# Notes:
Inline tables join cleanly against live sources — a handy way to attach a small
mapping (e.g. status codes → labels) to query results.
