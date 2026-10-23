# Name: Limit (λ / LIMIT)

# Syntax:
λ <count> (Relation)             -- first N rows
λ <offset>, <count> (Relation)   -- skip offset rows, then take count
λ <count> WITH TIES (τ ... (Relation))   -- and every row tied with the last
LIMIT ...

λ 10 (Orders)
λ 20, 10 (Orders)                -- skip 20, take 10 (pagination)

# Description:
Limit restricts how many rows come out. The one-number form keeps the first N
rows; the two-number form skips some rows first and then keeps the next batch —
exactly what you need for paging through results a screenful at a time.

Limit is almost always paired with SORT so that "first N" means something
predictable (e.g. the 10 largest, the 5 most recent).

`WITH TIES` keeps going past the Nth row while the rows that follow tie with it on
every sort key, so that two rows the order cannot tell apart are kept or dropped
together: the top 3 scores with a tie for third is four rows.

# Technical Description:
λ applies an optional offset and a row cap to its input. It is a streaming
operator that stops pulling once the cap is reached — which is what makes it safe
over an unbounded generator (it bounds the stream). The optimizer pushes λ below
projections (LIM-001) and folds it into SQL as LIMIT/OFFSET (dialect-aware) or
Mongo as $skip/$limit.

`λ n WITH TIES` takes a `τ` as its input, since the sort keys are what decide a tie,
and the pair is the global `TOP n WITH TIES` with those keys: the offset is skipped,
`n` rows are taken, and then every further row that compares equal to the last one
taken on all the keys. Equal means equal to the sort, so two NULLs tie and `5` ties
with `5.0`. It folds into SQL as `FETCH FIRST n ROWS WITH TIES` on PostgreSQL (13 or
later) and runs in the engine elsewhere.

# Examples:
First 10 orders:
  λ 10 (Orders)

Page 3 of results, 10 per page (skip 20, take 10):
  λ 20, 10 (Orders)

Top 10 salaries (sort then limit):
  λ 10 (τ salary DESC (Employees))

Take a finite prefix of an infinite generator — first 100 prime numbers:
  λ 100 (Primes)

The 3 highest scores, and anyone tied with the third:
  λ 3 WITH TIES (τ score DESC (Results))

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

With `WITH TIES`, a cut that falls between two rows of equal value takes both. The
two sales with no bonus tie with each other:

```relix
query { λ 1 WITH TIES (τ bonus DESC NULLS FIRST (Sales)) };
```

```
 region  rep  amount  bonus
 ──────  ───  ──────  ─────
 east    Bo       80  NULL
 west    Dee      50  NULL
(2 rows)
```

# Limitations:
Without a SORT below it, "first N" is whatever order the source happens to
deliver — not guaranteed. Limit caps rows but does not sample randomly; for that
use SAMPLE.

The count and the offset are whole numbers. A fraction, or a number too large for a
64-bit integer, is a parse error:

```relix-invalid
query { λ 2.5 (Orders) };
```

`WITH TIES` needs a sorted input, since without one nothing ties:

```relix-invalid
query { λ 3 WITH TIES (Orders) };
```

# Alternatives:
For a random subset use SAMPLE p (probabilistic) or SAMPLE n ROWS (exact count).
For "N per group" rather than N overall use TOP … PER.

# SQL names:
SQL's `LIMIT n`, `TOP n`, `FETCH FIRST n ROWS ONLY` and `ROWNUM <= n` are `LIMIT n (R)`,
and `LIMIT n OFFSET m` or `OFFSET m ROWS FETCH NEXT n ROWS ONLY` is `LIMIT m, n (R)`, the
offset first. Its `ORDER BY` is a [sort](sort.md) below it:
`ORDER BY amount DESC LIMIT 10` is `LIMIT 10 (SORT amount DESC (R))`, and
`FETCH FIRST n ROWS WITH TIES` is `LIMIT n WITH TIES (SORT … (R))`. Relix's
[TOP](../advanced/top.md) is the top rows per group, SQL's `ROW_NUMBER() OVER (PARTITION
BY …)` filtered to `<= n`; `ORDER BY RANDOM() LIMIT n` is
[SAMPLE n ROWS](../advanced/sample-reservoir.md).

# See Also:
[sort](sort.md), [top](../advanced/top.md), [sample-bernoulli](../advanced/sample-bernoulli.md), [sample-reservoir](../advanced/sample-reservoir.md)

# Notes:
λ is the canonical "bound" that rescues an otherwise-blocking query over an
unbounded source: `γ count (λ 1000 (Primes))` is allowed, `γ count (Primes)` is a
plan-time error.
