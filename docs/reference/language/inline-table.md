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

-- Either form, with declared column types:
<Name> := [ … ] schema { col1: TIMESTAMP };

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

A `schema { col: TYPE, … }` block after the rows declares column types, as a CSV
source's `schema:` field does. Each cell of a declared column is parsed to its type as
a CSV source parses a field: NUMBER as a decimal, BOOLEAN as `true`/`1` or
`false`/`0` in any case, and DATE, TIME, TIMESTAMP, TIMESTAMPTZ and DURATION against
ISO-8601. STRING keeps the text. An empty cell is NULL. The block may name some
columns and not others; the rest are inferred as above. It names each column of the
header at most once, gives each a type and nothing more (no binding or modifier), and
goes before a `references` clause. A schema naming a column the header lacks is a
syntax error at that name, and a cell that does not parse as its column's type is an
error at its row:

```
Readings.read_at, row 2: 'yesterday' is not a TIMESTAMP, the type its schema declares
```

Analysis does not warn about a declared column's cells, as it does below for
inferred ones: a cell that does not read as the declared type is already an error.

The header names every column once: no header cell is empty, and no two name the same
column, ignoring case as column names are matched. Every row has exactly as many cells as
the header has columns, and a missing value is written as an empty cell. A markdown table
puts each row on a line of its own, and a row may end with a `--` comment after its last
`|`. Text that breaks one of these rules is a syntax
error at the row that breaks it, rather than a row cut short or filled out:

```relix-invalid
Items := [ | item | value | |------|-------| | pen | 2 | ];
```

```relix-invalid
Items := [
| item | value |
|------|-------|
| pen  | 2     | 3 |
];
```

```relix-invalid
Items := csv[
  item, item
  pen, 2
];
```

A cell is a bare value, so quotes written around one are part of it: the cell
`"45%"` is five characters long, and `SUBSTRING` and `Len` see the quotes. Analysis
warns about a markdown cell that starts and ends with the same quote mark, naming its
table, column and row and the bare value to write:

```
T.s, row 1: the cell "45%" keeps its quotes, because table cells are bare values; write 45%
```

The CSV form is not warned about, since quoting a field is how CSV keeps a comma in it.

A column of dates or timestamps holds text such as
`2026-03-01T09:00:00Z`, which a comparison or `SESSIONIZE` reads as the temporal it
spells. Two cells pass analysis and then cannot be read that way, so analysis warns
about each:

- A cell written in the literal form, such as `TIMESTAMP '2026-03-01T09:00:00Z'`, is
  that whole text and never reads as a timestamp. The warning gives the bare value to
  write instead.
- A cell that is not the temporal kind the rest of its column is, such as
  `2026-03-1T10:45:00Z` among ISO timestamps. The warning names its row and its text.

A run-time failure to read a cell as a temporal names the cell's text, and the column
when a comparison reads it from one:

```
Cannot compare STRING with TIMESTAMP: cannot read '2026-03-1T10:45:00Z' (ts) as a TIMESTAMP
```

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

Declared types, here a TIMESTAMP column beside an inferred NUMBER one with a
missing value:
```relix
Readings := [
| capability_id | read_at              | value |
|---------------|----------------------|-------|
| 701           | 2026-10-22T14:00:00Z | 70.0  |
| 702           | 2026-10-22T13:00:00Z |       |
] schema { read_at: TIMESTAMP };
```

The csv form takes a schema block the same way:
```relix
Flags := csv[
    id, enabled, since
    1, true, 2026-01-05
    2, false, 2026-03-12
] schema { enabled: BOOLEAN, since: DATE };
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
those). Type inference is limited to numbers and strings; declare any other type with
a schema block. A schema block's types are scalar: a nested or ANY column is refused.

# Alternatives:
`source … from csv("path")` for external files; `source … from database` for live
tables.

# See Also:
[source](source.md), [relate](relate.md), [assignment](assignment.md), [natural-join](../joins/natural-join.md)

# Notes:
Inline tables join cleanly against live sources — a handy way to attach a small
mapping (e.g. status codes → labels) to query results.
