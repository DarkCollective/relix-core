# Name: Limit (λ / LIMIT)

# Syntax:
λ <count> (Relation)             -- first N rows
λ <offset>, <count> (Relation)   -- skip offset rows, then take count
LIMIT ...

λ 10 (Orders)
λ 20, 10 (Orders)                -- skip 20, take 10 (pagination)

# Description:
Limit restricts how many rows come out. The one-number form keeps the first N
rows; the two-number form skips some rows first and then keeps the next batch —
exactly what you need for paging through results a screenful at a time.

Limit is almost always paired with SORT so that "first N" means something
predictable (e.g. the 10 largest, the 5 most recent).

# Technical Description:
λ applies an optional offset and a row cap to its input. It is a streaming
operator that stops pulling once the cap is reached — which is what makes it safe
over an unbounded generator (it bounds the stream). The optimizer pushes λ below
projections (LIM-001) and folds it into SQL as LIMIT/OFFSET (dialect-aware) or
Mongo as $skip/$limit.

# Examples:
First 10 orders:
  λ 10 (Orders)

Page 3 of results, 10 per page (skip 20, take 10):
  λ 20, 10 (Orders)

Top 10 salaries (sort then limit):
  λ 10 (τ salary DESC (Employees))

Take a finite prefix of an infinite generator — first 100 prime numbers:
  λ 100 (Primes)

# Worked Example:
Four rows of sales, two of them with no `bonus` recorded — the NULLs are there deliberately, because they are what makes the rules below visible.

```relix
Sales := [
| region | rep  | amount | bonus |
|--------|------|--------|-------|
| east   | Ada  | 120    | 10    |
| east   | Bo   | 80     | NULL  |
| west   | Cy   | 200    | 25    |
| west   | Dee  | 50     | NULL  |
];

query { λ 2 (Sales) };
```

```
 region  rep  amount  bonus
 ──────  ───  ──────  ─────
 east    Ada     120     10
 east    Bo       80  NULL
(2 rows)
```

The **first** two rows — and "first" is only meaningful if the input has an
order. Without a `τ` beneath it, a limit takes whatever the source happens to
deliver, which for a database table is not guaranteed to be stable between runs.
`τ amount DESC (Sales)` then `λ 2` is the "top 2" idiom; for top-N *per group*
reach for [TOP … PER](../advanced/top.md) instead. Writing the idiom costs
nothing: the optimizer fuses `λ` over `τ` into a bounded top-N rather than
sorting the whole input (`LIM-003`, see the [optimizer](../advanced/optimizer.md)
page).

# Limitations:
Without a SORT below it, "first N" is whatever order the source happens to
deliver — not guaranteed. Limit caps rows but does not sample randomly; for that
use SAMPLE.

The count and the offset are whole numbers. A fraction, or a number too large for a
64-bit integer, is a parse error:

```relix-invalid
query { λ 2.5 (Orders) };
```

# Alternatives:
For a random subset use SAMPLE p (probabilistic) or SAMPLE n ROWS (exact count).
For "N per group" rather than N overall use TOP … PER.

# See Also:
[sort](sort.md), [top](../advanced/top.md), [sample-bernoulli](../advanced/sample-bernoulli.md), [sample-reservoir](../advanced/sample-reservoir.md)

# Notes:
λ is the canonical "bound" that rescues an otherwise-blocking query over an
unbounded source: `γ count (λ 1000 (Primes))` is allowed, `γ count (Primes)` is a
plan-time error.
