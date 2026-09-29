# Has at least one

> **Grain:** one row per entity · **Class:** Existence · **Signals:** any, at least one, has, ever, some, with a · **Operators:** ⋉

## The problem

*"Which customers have placed at least one order? Which have ever placed one over
£100?"*

## How to recognise it

The question says **any**, **at least one**, **has**, **ever**, **some**, or **with
a …**. It keeps an entity when a matching row **exists** on the other side, and the
tell is that the answer is *one row per entity* — the same customer, once, no matter
how many orders they placed. If a plain join would return the customer once per
order, existence wants them once: that is a **semi-join** (⋉), not a join.

A semi-join is the join you reach for when you want to *filter by existence* rather
than *bring the other table's columns in*.

## The data

```relix
Customers := [
| customer |
|----------|
| Ann      |
| Bo       |
| Cy       |
| Dee      |
];

Orders := [
| order_id | customer | amount |
|----------|----------|--------|
| 1        | Ann      | 120    |
| 2        | Ann      | 40     |
| 3        | Bo       | 200    |
| 4        | Cy       | 30     |
];
```

Ann has two orders, Cy has one small one, Dee has none.

## Recipe: keep the entity when a match exists (⋉)

A semi-join takes a condition and keeps each left row at most once — Ann does not
appear twice for her two orders:

```relix
query { Customers ⋉ Customers.customer = Orders.customer Orders };
```

```
 customer
 ────────
 Ann
 Bo
 Cy
(3 rows)
```

**At least one that also passes a test** puts the test in the join condition. Which
customers have *ever* ordered over £100?

```relix
query { Customers ⋉ Customers.customer = Orders.customer ∧ Orders.amount > 100 Orders };
```

```
 customer
 ────────
 Ann
 Bo
(2 rows)
```

The condition is *exists an order that is both this customer's and over £100* — one
qualifying order is enough to keep the customer.

## Variations

- **At least N**, not at least one, is a Summary with a filter: `σ n ≥ 2 (γ customer,
  COUNT(*) → n (Orders))`, then semi-join back if you need the customer's other
  columns.
- **The entities *without* a match** is the [Absence](absence.md) class — an anti-join
  (▷). Existence and absence are the two halves of the same question.
- **Existence across three tables** chains: semi-join to the second, then the result
  to the third.

## Pitfalls

- **A join is not a semi-join.** A plain join to test existence duplicates the entity
  once per match and drags the other table's columns along. To *filter by existence*,
  ⋉ is the operator — it keeps each entity once and adds nothing.
- **"At least one over £100" is not "every order over £100".** The semi-join keeps a
  customer with *one* qualifying order even if their others are small. If you mean
  *all* their orders qualify, that is the [Universal](every.md) class (∀).
- **NULL never matches.** A row whose join key is NULL matches nothing, so it is
  treated as *no match exists* — usually right, but decide it.

## Check it

- **The grain is one row per entity.** `COUNT` of the answer must be ≤ the number of
  distinct entities, and equal to `COUNT` of `δ` on the entity key. If it is larger, a
  join fanned out where a semi-join was meant.
- Put an entity with **two** matches (Ann) and confirm it appears **once**.
- Put an entity with **no** match (Dee) and confirm it is absent — then check the
  [Absence](absence.md) recipe returns exactly Dee.

## Related

- [None, never, missing](absence.md) — the other half: ▷ keeps the entities with no
  match.
- [Which rows, and which columns](selection.md) — semi-join as a membership filter.
- [Every: related to all of a set](every.md) — *every*, not *any*.
- Reference pages (`docs/reference`): `semi-join`, `anti-join`.
