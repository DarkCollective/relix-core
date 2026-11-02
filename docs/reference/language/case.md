# Name: CASE (a value chosen by conditions)

# Syntax:
CASE WHEN <condition> THEN <operand> { WHEN <condition> THEN <operand> } [ ELSE <operand> ] END

CASE <operand> WHEN <operand> THEN <operand> { WHEN <operand> THEN <operand> } [ ELSE <operand> ] END

π order_id, CASE WHEN amount >= 100 THEN 'large' WHEN amount >= 50 THEN 'medium' ELSE 'small' END → size (Orders)

# Description:
CASE is SQL's conditional expression, written as SQL writes it. The **searched** form
tests each `WHEN` condition in turn and gives the value after the first one that
holds; the **simple** form compares one value with each `WHEN` value. When nothing
matches, the `ELSE` value is the answer, and with no `ELSE` the answer is NULL. A CASE
is an operand, so it goes anywhere a value does: a projection, a condition, a `GROUP`
key, an aggregate's argument, a `SORT` key.

# Technical Description:
A CASE parses to [Switch](../functions/conditional/switch.md), and prints back as that
call:

| CASE | Switch |
|------|--------|
| `CASE WHEN c1 THEN a WHEN c2 THEN b ELSE d END` | `Switch(c1, a, c2, b, d)` |
| `CASE WHEN c THEN a END` | `Switch(c, a)` |
| `CASE x WHEN 1 THEN a WHEN 2 THEN b ELSE d END` | `Switch(x = 1, a, x = 2, b, d)` |

So it behaves exactly as SQL's does. A condition that is unknown (NULL) is not a
match, and the next `WHEN` is tried, so a NULL compared in a simple CASE matches no
`WHEN` and takes the `ELSE`. Only the chosen value is evaluated, so an earlier `WHEN`
can guard a later value that would fail on its own. The result type is the type the
values share, or ANY when they differ.

Over a database source a CASE is sent as the database's own `CASE` on every SQL
dialect. `CASE`, `WHEN`, `THEN`, `ELSE` and `END` are words in any case, and none is
reserved: a column called `case` is still a column wherever no `WHEN` follows it.

# Examples:
Band each order by its amount:
  π order_id, CASE WHEN amount >= 100 THEN 'large' ELSE 'small' END → size (Orders)

Count the orders in each band:
  γ CASE WHEN amount >= 100 THEN 'large' ELSE 'small' END → size, COUNT(*) → n (Orders)

A total over the rows that meet a condition:
  γ SUM(CASE WHEN status = 'paid' THEN amount ELSE 0 END) → paid (Orders)

Name a code with the simple form:
  π order_id, CASE status WHEN 'P' THEN 'paid' WHEN 'O' THEN 'open' ELSE 'other' END → state (Orders)

# Worked Example:
Orders with a missing amount and an unknown status:

```relix
Orders := [
| id | amount | status |
|----|--------|--------|
| 1  | 120    | P      |
| 2  | 60     | O      |
| 3  |        | X      |
];

query { τ id (π id, CASE WHEN amount >= 100 THEN 'large' WHEN amount >= 50 THEN 'medium' ELSE 'small' END → size, CASE status WHEN 'P' THEN 'paid' WHEN 'O' THEN 'open' END → state (Orders)) };
```

```
 id  size    state
 ──  ──────  ─────
  1  large   paid
  2  medium  open
  3  small   NULL
(3 rows)
```

Order 3's amount is NULL, so neither condition holds and the `ELSE` gives `small`;
its status matches no `WHEN`, and with no `ELSE` its state is NULL.

A CASE needs its `END`:

```relix-invalid
query { π id, CASE WHEN amount >= 100 THEN 'large' ELSE 'small' → size (Orders) };
```

# Limitations:
`WHEN` takes a condition in the searched form and a value in the simple form; the
simple form compares with `=` only, so `CASE x WHEN > 3` is written
`CASE WHEN x > 3`.

# SQL names:
SQL's `CASE` in both forms, as written here. MySQL's and a spreadsheet's `IF(c, a, b)`
is [IIf](../functions/conditional/iif.md), and `DECODE(x, 1, a, 2, b, d)` (Oracle) is
the simple form, `CASE x WHEN 1 THEN a WHEN 2 THEN b ELSE d END`.

# See Also:
[switch](../functions/conditional/switch.md), [iif](../functions/conditional/iif.md), [choose](../functions/conditional/choose.md), [coalesce](../functions/conditional/coalesce.md), [FILTER](../aggregates/filter.md)
