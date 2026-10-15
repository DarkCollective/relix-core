# Name: Range Test (BETWEEN / NOT BETWEEN)

# Syntax:
attr BETWEEN low AND high
attr NOT BETWEEN low AND high

σ amount BETWEEN 10 AND 100 (Orders)
σ placed NOT BETWEEN DATE '2026-01-01' AND DATE '2026-03-31' (Orders)

# Description:
BETWEEN keeps rows whose value lies in a range, both ends included. NOT BETWEEN
keeps rows whose value lies outside it. It is SQL's shorthand for two comparisons,
and reads the way the question is usually asked: "orders between 10 and 100".

# Technical Description:
`x BETWEEN lo AND hi` parses to the two comparisons it stands for,
`x >= lo ∧ x <= hi`, and `x NOT BETWEEN lo AND hi` to `x < lo ∨ x > hi`; there is
no separate node, so validation, evaluation and pushdown are those of the
comparisons. The bounds are inclusive and are not reordered: when `lo` is greater
than `hi` no value is between them. A NULL subject or bound makes the test UNKNOWN
where the comparison it depends on is, so a NULL value is dropped by BETWEEN and
by NOT BETWEEN alike. The `AND` belongs to BETWEEN, so
`x BETWEEN 1 AND 5 AND y = 2` is a range test joined to a second condition.
BETWEEN, like the other words it is written with, is matched by its text and is
not reserved.

# Examples:
Mid-sized orders:
  σ amount BETWEEN 10 AND 100 (Orders)

Outside office hours:
  σ hour NOT BETWEEN 9 AND 17 (Logins)

A range test and another condition:
  σ amount BETWEEN 10 AND 100 AND status = 'open' (Orders)

As a value, in a conditional:
  π student_id, IIf(score BETWEEN 50 AND 79, 'pass', 'other') → band (Results)

# Worked Example:
Both ends of the range count, and a missing amount is in neither result:

```relix
Orders := [
| id | amount |
|----|--------|
| 1  | 5      |
| 2  | 10     |
| 3  | 60     |
| 4  | 100    |
| 5  | 140    |
| 6  | NULL   |
];

query { σ amount BETWEEN 10 AND 100 (Orders) };
```

```
 id  amount
 ──  ──────
  2      10
  3      60
  4     100
(3 rows)
```

```relix
query { σ amount NOT BETWEEN 10 AND 100 (Orders) };
```

```
 id  amount
 ──  ──────
  1       5
  5     140
(2 rows)
```

Order 6 is in neither: its amount is unknown, so whether it lies in the range is
unknown too, and a selection keeps only rows for which its condition is true.

# Limitations:
Both bounds are required:

```relix-invalid
query { σ amount BETWEEN 10 (Orders) };
```

SQL's `BETWEEN SYMMETRIC`, which swaps bounds given in the wrong order, is not
part of the language. The subject is evaluated once per comparison.

# Alternatives:
Write the comparisons out (`x >= lo ∧ x < hi`) for a half-open range.

# See Also:
[comparison](comparison.md), [and](and.md), [or](or.md), [not](not.md), [select](../operators/select.md)
