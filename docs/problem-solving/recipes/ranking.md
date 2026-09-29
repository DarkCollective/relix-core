# The most, the top N, the latest

> **Grain:** one row per top thing (or per key, for the latest) · **Class:** Ranking · **Signals:** the most, top N, highest, latest, best per, runner-up · **Operators:** `TOP`, `WINDOW RANK`/`ROW_NUMBER`/`DENSE_RANK`, `ARGMAX`

## The problem

*"What are the two best-selling products? The best seller in each region? The latest
price for each product? And when two products tie for best, do I want one of them or
both?"*

## How to recognise it

The question says **the most**, **the highest**, **top N**, **latest**, **best per**,
**Nth**. It is about position in an order, not a total — so the answer is a **row**,
carrying its columns, not an aggregated number. That is what separates ranking from
summary: *how much did the top region sell* is a γ; *which region sold the most* is a
rank.

The choice of operator is really a choice about what you want back, and about ties:

- **The top row(s), with all their columns** → `TOP … PER`.
- **One value from the top row** (its name, its id) → `ARGMAX`.
- **Every row, labelled with its rank**, so you decide what to keep → `WINDOW RANK`
  and its family.

## The data

```relix
Sales := [
| region | product | units |
|--------|---------|-------|
| North  | yo-yo   | 30    |
| North  | kite    | 30    |
| North  | drum    | 10    |
| South  | atlas   | 20    |
| South  | globe   | 25    |
];
```

North has a **tie**: yo-yo and kite both sold 30. That tie is the interesting part.

## Recipe 1: the top rows (TOP)

`TOP n <sort>` keeps the top `n` rows; `PER` restarts the count per group. It returns
whole rows.

```relix
query { TOP 2 units DESC (Sales) };
query { TOP 1 units DESC PER region (Sales) };
```

```
 region  product  units
 ──────  ───────  ─────
 North   yo-yo       30
 North   kite        30
(2 rows)
 region  product  units
 ──────  ───────  ─────
 North   yo-yo       30
 South   globe       25
(2 rows)
```

Note that `TOP 1 PER region` returns **one** North row even though two are tied — it
keeps a fixed number of rows, and breaks the tie for you. Whether that is what you
want is Recipe 3.

## Recipe 2: one value from the top row (ARGMAX)

When you want a single field of the top row — its name, its id — and one row per
group, `ARGMAX(rank, yield)` reads *"rank by the first, return the second"*. It lives
inside a γ, so it composes with other aggregates.

```relix
query { γ region, ARGMAX(units, product) → top_product, MAX(units) → best (Sales) };
```

```
 region  top_product  best
 ──────  ───────────  ────
 North   yo-yo          30
 South   globe          25
(2 rows)
```

`MAX(units)` gives the number; `ARGMAX(units, product)` gives *which product*
achieved it — the row-with-the-maximum lookup SQL needs a window or a self-join for.

## Recipe 3: rank every row, then decide (WINDOW RANK)

`TOP 1` decides the tie for you. When you want **both** tied leaders, rank every row
and filter — `RANK()` gives tied rows the same rank, so `rnk ≤ 1` keeps them all:

```relix
Ranked := { WINDOW RANK() SORT units DESC PER region AS rnk (Sales) };
query { σ rnk ≤ 1 (Ranked) };
```

```
 region  product  units  rnk
 ──────  ───────  ─────  ───
 North   yo-yo       30    1
 North   kite        30    1
 South   globe       25    1
(3 rows)
```

The three ranking functions differ only on ties, and the difference is the whole
point of choosing between them:

- `ROW_NUMBER()` — `1, 2, 3` — never ties; breaks a tie arbitrarily.
- `RANK()` — `1, 1, 3` — ties share a rank, then a gap.
- `DENSE_RANK()` — `1, 1, 2` — ties share a rank, no gap. Use it for *"the Nth
  distinct value"*: `dr = 2` is the runner-up **tier**, even when several share the
  top.

## Recipe 4: the latest per key (ROW_NUMBER)

*Latest per key* is a ranking in disguise: number each key's rows newest-first, keep
number 1, drop the number. This is the SQL `ROW_NUMBER() OVER (PARTITION BY … ORDER
BY …) = 1` idiom.

```relix
PriceHistory := [
| product | at         | price |
|---------|------------|-------|
| kite    | 2026-06-01 | 8     |
| kite    | 2026-06-03 | 9     |
| kite    | 2026-06-02 | 7     |
| drum    | 2026-06-05 | 20    |
];

Latest := { WINDOW ROW_NUMBER() SORT at DESC PER product AS rn (PriceHistory) };
query { π product, at, price (σ rn = 1 (Latest)) };
```

```
 product  at          price
 ───────  ──────────  ─────
 kite     2026-06-03      9
 drum     2026-06-05     20
(2 rows)
```

Use `ROW_NUMBER` here, not `RANK`: you want exactly one row per key even if two share
a timestamp, so an arbitrary tie-break is the right behaviour.

## Variations

- **Top N% rather than top N** — `WINDOW NTILE(n)` or `PERCENT_RANK`.
- **The smallest** — `TOP … ASC`, `ARGMIN`, or a `SORT … ASC`.
- **Ranking a JDBC-backed table** pushes down to PostgreSQL/H2 as a SQL window; `TOP`
  and the windows fold into the scan.

## Pitfalls

- **`TOP N` and `RANK() ≤ N` disagree on ties.** `TOP 1` returns exactly one row;
  `RANK() = 1` returns every row tied for first. This is the single most common
  ranking bug — decide *"one, or all the tied ones?"* before you choose.
- **`RANK` skips, `DENSE_RANK` does not.** After two rows tied at 1, `RANK` gives the
  next row 3 and `DENSE_RANK` gives it 2. "The 2nd-highest distinct value" needs
  `DENSE_RANK`; `RANK = 2` would find nothing when two share first.
- **A window is blocking and adds a column; `TOP` filters.** `σ rnk ≤ 3` over a window
  runs after ranking every row — it is a filter on the output, not a shortcut through
  it. `TOP` is the one that keeps only the top rows.
- **`SORT` must be a total order for a stable answer.** Equal sort keys let a row fall
  either way; add a tie-breaker (`SORT units DESC, product ASC`) when the choice must
  be repeatable.

## Check it

- Put a **tie** in the data (as North has) and run both `TOP 1 PER` and `RANK() ≤ 1`.
  If they return different row counts — and they should — you have proven which
  question each one answers.
- The grain of `TOP n PER k` is *at most n rows per k*: `COUNT` per key must be ≤ n.
- For *latest per key*, `COUNT` of the answer equals the number of distinct keys.

## Related

- [How many, how much, per what](summary.md) — when you want the total, not the row
  that holds it.
- [Which rows, and which columns](selection.md) — `TOP` is a cousin of σ that keeps a
  ranked subset.
- [In a row, per visit, gaps](sequence.md) — `WINDOW LAG`/`LEAD` compare a row with its neighbour.
- Reference pages (`docs/reference`): `top`, `window-ranking`, `argmax`.
