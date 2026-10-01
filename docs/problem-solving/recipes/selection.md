# Which rows, and which columns

> **Grain:** one row per input row · **Class:** Selection · **Signals:** which, that match, filter, only the ones · **Operators:** σ, π, ⋈, ⋉

## The problem

*"Which paid orders are over £100, and can I see just the order number and the
amount with a surcharge added? And which orders come from a customer in the north?"*

## How to recognise it

The question says **which**, **that match**, **filter**, or **only the ones that
…**. It asks for a subset of rows you already have, or a subset of their columns,
and — this is the tell — **the grain does not change**. One order in, at most one
order out. If the answer would have *fewer things than one per input row* it is a
summary (γ); if it could have *more*, a join is fanning out and that is the mistake
this recipe exists to avoid.

Two operators do almost all of it:

- **σ (select)** keeps the rows a condition matches.
- **π (project)** chooses the columns to keep, and derives new ones.

A third question — *which rows are also in another table?* — is still selection, not
a summary, so it must keep each row **once**. That is a semi-join (⋉), not a plain
join.

## The data

```relix
Customers := [
| customer | region |
|----------|--------|
| Ann      | North  |
| Bo       | South  |
| Cy       | North  |
];

Orders := [
| order_id | customer | amount | status |
|----------|----------|--------|--------|
| 1        | Ann      | 120    | paid   |
| 2        | Ann      | 40     | paid   |
| 3        | Bo       | 200    | open   |
| 4        | Cy       | 90     | paid   |
| 5        | Dee      | 60     | paid   |
];
```

Dee has an order but is not a customer; Bo's big order is not paid.

## Recipe 1: keep the rows, then the columns

`σ` takes a condition — combine tests with `∧` and `∨`. Keep the paid orders over
£100:

```relix
query { σ amount > 100 ∧ status = "paid" (Orders) };
```

```
 order_id  customer  amount  status
 ────────  ────────  ──────  ──────
        1  Ann          120  paid
(1 row)
```

`π` chooses and **derives** columns; `expr → name` names a derived one. Name a view
after what its rows are, and build on it:

```relix
HighValue := { σ amount > 100 (Orders) };
query { π order_id, customer, amount (HighValue) };
query { π order_id, amount, amount * 1.1 → with_surcharge (HighValue) };
```

```
 order_id  customer  amount
 ────────  ────────  ──────
        1  Ann          120
        3  Bo           200
(2 rows)
 order_id  amount  with_surcharge
 ────────  ──────  ──────────────
        1     120             132
        3     200             220
(2 rows)
```

## Recipe 2: keep the rows that are also in another table

*Which orders come from a northern customer?* This is a filter on `Orders` — one row
per order — so reach for a **semi-join**, which keeps a left row when a match exists
and never duplicates it. A semi-join takes a condition, like every join but the
natural one:

```relix
North := { σ region = "North" (Customers) };
query { Orders ⋉ Orders.customer = North.customer North };
```

```
 order_id  customer  amount  status
 ────────  ────────  ──────  ──────
        1  Ann          120  paid
        2  Ann           40  paid
        4  Cy            90  paid
(3 rows)
```

A plain join answers a *different* question — it brings the customer's columns along,
so it is the right tool when you want them:

```relix
query { π order_id, customer, region (Orders ⋈ North) };
```

```
 order_id  customer  region
 ────────  ────────  ──────
        1  Ann       North
        2  Ann       North
        4  Cy        North
(3 rows)
```

## Variations

- **Membership against a fixed list** rather than a table: build the list as an inline
  relation and semi-join to it, or use `σ region = "North" ∨ region = "West" (…)`
  when it is two or three values.
- **The rows *not* in the other table** is the Absence class — an anti-join (▷),
  covered there.
- **A derived flag instead of a filter:** `π …, amount > 100 → is_big (Orders)` keeps
  every row and labels it, when the caller wants to see what was excluded.

## Pitfalls

- **A join to filter can fan out.** If the other table has more than one matching
  row, a plain join multiplies the left rows — turning "one row per order" into "one
  row per order *per match*". A semi-join cannot: it is the safe way to *filter*.
  With two contacts for Ann, the join doubles her orders and the semi-join does not:

  ```relix
  Contacts := [
  | customer | channel |
  |----------|---------|
  | Ann      | email   |
  | Ann      | phone   |
  | Cy       | email   |
  ];
  query { π order_id, customer, amount (Orders ⋈ Contacts) };
  query { Orders ⋉ Orders.customer = Contacts.customer Contacts };
  ```

```
 order_id  customer  amount
 ────────  ────────  ──────
        1  Ann          120
        1  Ann          120
        2  Ann           40
        2  Ann           40
        4  Cy            90
(5 rows)
 order_id  customer  amount  status
 ────────  ────────  ──────  ──────
        1  Ann          120  paid
        2  Ann           40  paid
        4  Cy            90  paid
(3 rows)
```

- **NULL is not a value.** `σ status = "paid"` drops a row whose status is NULL,
  because `NULL = "paid"` is UNKNOWN, not false. If a missing status should count,
  say so with `Nz` or a second `∨ IsNull(status)`.
- **`≠` also drops NULLs.** `σ status ≠ "open"` does **not** keep a NULL status —
  `NULL ≠ "open"` is UNKNOWN too. "Not open" and "missing" are different questions.
- **π can rename away a name you still need later.** A derived column shadows an
  input column of the same name; give it a fresh name unless you mean to replace it.

## Check it

- **The grain is one row per input row.** `COUNT` of the answer must be **≤** `COUNT`
  of the input. If it is larger, a join fanned out — that is the bug.
- Put a **NULL** in the column you filter on and decide, on tiny data, whether it
  should survive. σ will drop it; that is often right and sometimes not.
- Put a row in the input whose key is **absent** from the reference table (Dee) and
  confirm the semi-join drops it.

## Related

- [Every: related to all of a set](every.md) — when the filter is *matches all of*,
  not *matches any of*.
- [Has at least one](existence.md) and [None, never, missing](absence.md) — ⋉ is *at
  least one*, ▷ is *none*.
- [How many, how much, per what](summary.md) — when the answer has fewer rows than the
  input, it is a γ.
- Reference pages (`docs/reference`): `selection`, `projection`,
  `semi-join`, `natural join`.
