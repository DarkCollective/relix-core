# Name: Unpivot (UNPIVOT)

# Syntax:
UNPIVOT (col1, col2, ...) AS (nameColumn, valueColumn) (Relation)

UNPIVOT (q1, q2, q3, q4) AS (quarter, revenue) (QuarterlySales)

# Description:
UNPIVOT folds a set of named columns into rows: for each input row it emits one
output row per listed column. The output contains all the non-listed input columns
unchanged, plus two new columns: one for the source column name (a STRING) and one
for the cell value (ANY).

UNPIVOT is the inverse of PIVOT — it normalises "wide" tables (one column per
category) into "long" tables (one row per category).

# Technical Description:
For an input row r and listed columns [c1, c2, ..., cn], UNPIVOT emits n output
rows. Each output row contains:
  - every column of r that is NOT in the listed column set (pass-through columns),
    in their original order
  - nameColumn: a STRING holding the source column name (c1, c2, ..., or cn)
  - valueColumn: the cell value r[ci] from that input row

UNPIVOT is streaming — it never buffers the full input; each input row fans out to
exactly n output rows. The operator is never pushed down to a source backend.

Requires at least one column in the column list. The nameColumn and valueColumn
names must not clash with any of the pass-through (non-listed) columns, and must
not clash with each other.

# Examples:
Wide quarterly sales table → long format, one row per quarter (worked through with
real output below):

  UNPIVOT (q1, q2, q3, q4) AS (quarter, revenue) (QuarterlySales)

Temperature sensors with side-by-side readings → normalised:

  UNPIVOT (sensor_a, sensor_b, sensor_c) AS (sensor, temp) (Readings)

Combine with PIVOT for wide ↔ long round-trips:

```relix
Long := { UNPIVOT (q1, q2, q3, q4) AS (quarter, revenue) (QuarterlySales) };
Wide := { PIVOT revenue BY quarter PER region (Long) };
```


# Worked Example:
The wide table PIVOT produces, folded back into rows.

```relix
QuarterlySales := [
| region | q1     | q2     |
|--------|--------|--------|
| East   | 100000 | 120000 |
| West   | 80000  | 90000  |
];

query { UNPIVOT (q1, q2) AS (quarter, revenue) (QuarterlySales) };
```

```
 region  quarter  revenue
 ──────  ───────  ───────
 East    q1        100000
 East    q2        120000
 West    q1         80000
 West    q2         90000
(4 rows)
```

Two input rows fan out to four: one per listed column, per row. `region` is a
pass-through column and repeats; `quarter` holds the *name* of the column the
value came from, and `revenue` its value.

Feeding that straight back into PIVOT returns the original shape — the two
operators are inverses:

```relix
query {
    PIVOT revenue BY quarter PER region
        (UNPIVOT (q1, q2) AS (quarter, revenue) (QuarterlySales))
};
```

```
 region  q1      q2
 ──────  ──────  ──────
 East    100000  120000
 West     80000   90000
(2 rows)
```

# Limitations:
NULL values in the listed columns are preserved as NULL in valueColumn — there is
no automatic NULL-filtering (add σ valueColumn ≠ ⊥ downstream if needed). The
output schema is closed (column headers are fixed at parse time). UNPIVOT is never
pushed down to SQL or MongoDB.

# Alternatives:
PIVOT is the reverse: turns distinct values in a column into new column headers.

# See Also:
[pivot](pivot.md)

# Notes:
The output type of valueColumn is ANY — it may hold values of different types when
the listed columns have different types. Use σ or π to restrict or recast after
unpivoting if uniform types are needed.
