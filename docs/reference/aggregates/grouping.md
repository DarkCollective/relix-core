# Name: GROUPING (which keys a subtotal row rolled up)

# Syntax:
GROUPING(<key> [, <key> ...]) [→ alias]
GROUPING_ID(<key> [, <key> ...]) [→ alias]   -- SQL Server's and Oracle's name

γ ROLLUP (region, product), SUM(amount) → total, GROUPING(region, product) → level (Sales)

# Description:
A ROLLUP, CUBE or GROUPING SETS gives subtotal rows. A subtotal over every product
shows NULL for the product, and so does a sale whose product was never recorded.
GROUPING tells the two apart. It is 1 in a row that rolled the key up and 0 in a row
grouped by it.

# Technical Description:
Each argument must be one of the γ's grouping keys: the same expression as the key, or
the key's output column name. Each named key gives one bit, the last key the lowest
bit, and the bit is set when the row's grouping set leaves that key out. So
`GROUPING(region, product)` is 0 in a detail row, 1 in a region subtotal and 3 in the
grand total. GROUPING_ID is the same function under another name. In an ordinary
grouping no key is rolled up, so GROUPING is 0.

GROUPING reads no rows. The engine computes it from the grouping set, so it takes no
DISTINCT and no FILTER. It belongs only in a γ, not in ROLLING. An unaliased GROUPING
of one key is named `grouping_<key>`.

# Examples:
Mark the rows a ROLLUP added:
  γ ROLLUP (region), SUM(amount) → total, GROUPING(region) → is_total (Sales)

Keep only the subtotals:
  σ level > 0 (γ ROLLUP (region, product), SUM(amount) → total, GROUPING(region, product) → level (Sales))

# Worked Example:
One sale has no region recorded, so its row in the detail also shows a NULL region:

```relix
Sales := [
| region | amount |
|--------|--------|
| east   | 10     |
| east   | 30     |
| NULL   | 7      |
];

query { τ g, region (γ ROLLUP (region), SUM(amount) → total, GROUPING(region) → g (Sales)) };
```

```
 region  total  g
 ──────  ─────  ─
 east       40  0
 NULL        7  0
 NULL       47  1
(3 rows)
```

Two rows show a NULL region. The one with `g = 0` is the group of sales with no
region. The one with `g = 1` is the grand total, where the ROLLUP left `region` out.

# Pushdown:
As `GROUPING(k)` per key, each multiplied by its bit and the products added, wherever
its grouping sets push down. SQL Server's GROUPING takes a single key. Under an
ordinary grouping it is the constant `0`.

# See Also:
[group](../operators/group.md), [count](count.md), [sum](sum.md)
