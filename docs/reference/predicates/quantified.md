# Name: Quantified Comparison (ANY / SOME / ALL)

# Syntax:
x op ANY {v1, v2, ...}       x op ANY (v1, v2, ...)
x op SOME {v1, v2, ...}
x op ALL {v1, v2, ...}       x op ALL (v1, v2, ...)

where op is one of = ≠ < ≤ > ≥

σ price > ALL {10, 20, 30} (Items)
σ code = ANY {'A1', 'B2'} (Parts)

# Description:
A quantified comparison compares one value with each value of a list. `ANY` (and
its synonym `SOME`) holds when the comparison holds for at least one of them, and
`ALL` when it holds for every one. `x > ALL {10, 20}` says "greater than each of
them", and `x = ANY {…}` is the same test as `x IN {…}`.

The list is written in braces, as a set is, or in SQL's parentheses.

# Technical Description:
`ANY` parses to the disjunction of the comparisons and `ALL` to their conjunction:
`x > ALL {1, 2}` is `x > 1 ∧ x > 2`. Over an empty list `ANY` is false and `ALL` is
true, as in SQL. A NULL element, or a NULL `x`, leaves the answer UNKNOWN wherever
it could have decided it: `x = ANY {1, n}` with `x` 3 and `n` NULL is UNKNOWN, and
the row is not kept. There is no separate node, so pushdown sends the expansion.

`ANY`, `SOME` and `ALL` are not reserved. One is read as a quantifier when braces
follow it, or a parenthesis holding a comma or starting with a literal; a single
name in parentheses after one is a column called `any` and the relation it filters,
`σ x = any (R)`.

# Examples:
Items dearer than every listed threshold:
  σ price > ALL {10, 20, 30} (Items)

Parts matching one of two codes:
  σ code = SOME ('A1', 'B2') (Parts)

A value below at least one of two columns:
  σ price < ANY {list_price, rrp} (Items)

# Worked Example:
Exam scores against a set of pass marks. `ALL` keeps a score above every mark, and
`ANY` a score above at least one:

```relix
Scores := [
| student | score |
|---------|-------|
| ana     | 45    |
| ben     | 62    |
| cy      | 78    |
];

query { σ score > ALL {50, 60, 70} (Scores) };
```

```
 student  score
 ───────  ─────
 cy          78
(1 row)
```

```relix
query { σ score > ANY {50, 60, 70} (Scores) };
```

```
 student  score
 ───────  ─────
 ben         62
 cy          78
(2 rows)
```

# Limitations:
The list holds values, not a relation: to compare with every row of another
relation, compare with its `GROUP MIN` or `MAX`, or join it. A literal `NULL` in
the list is refused at analysis, as any comparison with a literal NULL is.

```relix-invalid
query { σ score > ALL (SELECT mark FROM Marks) (Scores) };
```

# Alternatives:
[IN](in.md) for `= ANY` and `NOT IN` for `≠ ALL`. `score > 70` when the list's
largest value is known.

# See Also:
[comparison](comparison.md), [in](in.md), [and](and.md), [or](or.md)
