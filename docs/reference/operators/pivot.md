# Name: Pivot (PIVOT)

# Syntax:
PIVOT valueColumn BY keyColumn (Relation)
PIVOT valueColumn BY keyColumn PER groupKey1, groupKey2, ... (Relation)

PIVOT revenue BY quarter PER region (QuarterlySales)
PIVOT amount BY category (Transactions)

# Description:
PIVOT rotates rows into columns: it groups the input by the optional PER keys (or
treats the whole relation as one group when PER is absent), then turns each distinct
value of keyColumn into a new output column whose cell is taken from valueColumn for
the matching row. When a group has no row for a particular key value the cell is
NULL.

PIVOT is the inverse of UNPIVOT — it denormalises "long" tables (one row per
category) into "wide" tables (one column per category).

# Technical Description:
PIVOT is a blocking operator (materialisation mode BAG) — it must consume the
entire input before emitting output, because the full set of column headers (the
distinct key values) is not known until all rows have been seen.

For a group identified by groupKey values G, and distinct key values {k1, k2, ...,
kn} seen across all rows:
  - One output row is emitted per distinct group G
  - The output row contains the groupKey columns (the PER columns), followed by one
    column per distinct key value; the column name is the string representation of
    the key value
  - Each pivot cell holds the valueColumn value from the unique row in G whose
    keyColumn equals that header value; it is NULL when no such row exists

Two cases follow from "the unique row" not always being unique, or present:

  - When a group holds **more than one** row for the same key value, the last one
    read wins and the earlier values are discarded without a diagnostic. Input
    order therefore decides the cell, so put a τ below the PIVOT if it matters —
    or aggregate first (γ) if what you wanted was a total rather than a pick.
  - A row whose keyColumn is **NULL** contributes no column, since there is no
    header to name. It has still been seen, so its group is emitted like any
    other, holding NULL in every cell no other row filled.

The output schema is open (Schema.open()) because the column headers depend on
runtime data and cannot be determined at parse time. Column order is the order the
headers were first seen, so it too depends on the input. PIVOT is never pushed down
to a source backend.

# Examples:
Long quarterly sales table → wide format, one column per quarter (worked through
with real output below):

  PIVOT revenue BY quarter PER region (QuarterlySales)

Category totals as columns:

  PIVOT total BY category (CategoryTotals)

Global pivot (no PER — one row total across all groups):

  PIVOT amount BY status (Orders)

Combine with filtering and sorting:

```relix
TopN    := { σ score > 80 (Results) };
Rotated := { PIVOT score BY subject PER student_id (TopN) };
```


# Worked Example:
Three sales rows, two regions, two quarters — and deliberately **no q2 row for
West**, because what PIVOT does with a missing cell is the part prose cannot
settle.

```relix
QuarterlySales := [
| region | quarter | revenue |
|--------|---------|---------|
| East   | q1      | 100000  |
| East   | q2      | 120000  |
| West   | q1      | 80000   |
];

query { PIVOT revenue BY quarter PER region (QuarterlySales) };
```

```
 region  q1      q2
 ──────  ──────  ──────
 East    100000  120000
 West     80000  NULL
(2 rows)
```

One row per region, and one column per *distinct value* of `quarter` — the column
headers came out of the data, which is why the output schema is open. West's `q2`
cell is NULL: there was no row to take a value from.

# Limitations:
The output schema is open — downstream operators that require a closed schema
(such as certain projections or joins on named columns) may need a ρ rename or
σ predicate based on runtime column names. PIVOT is never pushed down to SQL or
MongoDB. When a group has multiple rows with the same key value, only the value
from the first-encountered row is used (last-write-wins is not guaranteed; use
γ SUM/MAX/MIN/... to aggregate before pivoting if deduplication is needed).

# Alternatives:
UNPIVOT is the reverse: folds listed columns into rows.
γ + COLLECT produces a similar wide-to-row grouping when the data is already flat.

# See Also:
[unpivot](unpivot.md), [group](group.md), [unnest](unnest.md)

# Notes:
Column headers in the output are the asDisplayString() rendering of the key values.
For NUMBER keys the rendering is the numeric string (e.g. "2024", "3.14"). For
STRING keys it is the string value itself. NULL key values are silently skipped
(they do not generate a new header column).
