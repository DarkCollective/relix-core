# Name: Quantified Comparison (ANY / SOME / ALL)

# Syntax:
x op ANY {v1, v2, ...}       x op ANY (v1, v2, ...)
x op SOME {v1, v2, ...}
x op ALL {v1, v2, ...}       x op ALL (v1, v2, ...)
x op ANY (relation)          x op ALL (relation)       a relation of one column

where op is one of = ≠ < ≤ > ≥

σ price > ALL {10, 20, 30} (Items)
σ code = ANY {'A1', 'B2'} (Parts)
σ amount > ALL (π amount (σ customer_id = 2 (Orders))) (Orders)

# Description:
A quantified comparison compares one value with each value of a list. `ANY` (and
its synonym `SOME`) holds when the comparison holds for at least one of them, and
`ALL` when it holds for every one. `x > ALL {10, 20}` says "greater than each of
them", and `x = ANY {…}` is the same test as `x IN {…}`.

The list is written in braces, as a set is, or in SQL's parentheses. In place of a
list, a relation of one column compares with each of its values: `x > ALL (π v (S))`.

# Technical Description:
`ANY` parses to the disjunction of the comparisons and `ALL` to their conjunction:
`x > ALL {1, 2}` is `x > 1 ∧ x > 2`. Over an empty list `ANY` is false and `ALL` is
true, as in SQL. A NULL element, or a NULL `x`, leaves the answer UNKNOWN wherever
it could have decided it: `x = ANY {1, n}` with `x` 3 and `n` NULL is UNKNOWN, and
the row is not kept. There is no separate node, so pushdown sends the expansion.

**Over a relation.** `x op ANY (relation)` compares `x` with each value of a
one-column [subquery](../language/subquery.md), which may read the row being tested
through qualified names. The answer is three-valued, as SQL's is: `ANY` is true when
some comparison is true, UNKNOWN when none is but one is UNKNOWN, and false
otherwise; `ALL` is false when one is false, UNKNOWN when none is but one is UNKNOWN,
and true otherwise. Over no rows `ANY` is false and `ALL` true, whatever `x` is.
`x IN (relation)` is `x = ANY (relation)`.

`ANY`, `SOME` and `ALL` are not reserved. One is read as a quantifier when braces
follow it, a parenthesis holding a comma or starting with a literal, or a subquery;
a single name in parentheses after one is a column called `any` and the relation it
filters, `σ x = any (R)`. At the top of a SELECT's condition a subquery after one is
the comparison's only when more of the selection follows it: `σ x > all (π v (S))`
alone filters `π v (S)` by a column `all`.

# Examples:
Items dearer than every listed threshold:
  σ price > ALL {10, 20, 30} (Items)

Parts matching one of two codes:
  σ code = SOME ('A1', 'B2') (Parts)

A value below at least one of two columns:
  σ price < ANY {list_price, rrp} (Items)

Orders larger than every order customer 2 placed:
  σ amount > ALL (π amount (σ customer_id = 2 (Orders))) (Orders)

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

Over a relation, the orders larger than every order of customer 2, and those smaller
than at least one of customer 1's:

```relix
Orders := [
| order_id | customer_id | amount |
|----------|-------------|--------|
| 100      | 1           | 120    |
| 101      | 1           | 80     |
| 102      | 2           | 45     |
| 103      | 4           | 60     |
];

query { σ amount > ALL (π amount (σ customer_id = 2 (Orders))) (Orders) };
```

```
 order_id  customer_id  amount
 ────────  ───────────  ──────
      100            1     120
      101            1      80
      103            4      60
(3 rows)
```

```relix
query { σ amount < ANY (π amount (σ customer_id = 1 (Orders))) (Orders) };
```

```
 order_id  customer_id  amount
 ────────  ───────────  ──────
      101            1      80
      102            2      45
      103            4      60
(3 rows)
```

# Limitations:
A literal `NULL` in the list is refused at analysis, as any comparison with a literal
NULL is. A relation compared with has one column, which analysis checks. SQL's
subquery is written with Relix's operators, `(π mark (Marks))`:

```relix-invalid
query { σ score > ALL (SELECT mark FROM Marks) (Scores) };
```

# Alternatives:
[IN](in.md) for `= ANY` and `NOT IN` for `≠ ALL`. `score > 70` when the list's
largest value is known.

# See Also:
[comparison](comparison.md), [in](in.md), [exists](exists.md), [subquery](../language/subquery.md), [and](and.md), [or](or.md)
