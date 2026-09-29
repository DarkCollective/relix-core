# None, never, missing

> **Grain:** one row per entity · **Class:** Absence · **Signals:** none, never, no, without, missing, not in, orphaned · **Operators:** ▷, −

## The problem

*"Which customers have never placed an order? Which catalogue items are not stocked
at all?"*

## How to recognise it

The question says **none**, **never**, **no**, **without**, **missing**, **not in**,
or **orphaned**. It keeps an entity when **no** matching row exists — the mirror of
[existence](existence.md). Two operators express it, and which one you want depends on
what you already have:

- **▷ (anti-join)** — keep the left rows that have **no** match on the right, on a
  condition. Keeps the left row's columns.
- **− (difference)** — the rows in one relation that are not in another, when both are
  lists of the *same shape* (a reference list against an actual list).

The trap that defines this class is NULL: *absence* and *unknown* are different, and a
careless test conflates them.

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

Dee has never ordered.

## Recipe 1: never (anti-join)

`▷` keeps the left rows with **no** matching right row. Which customers have never
ordered?

```relix
query { Customers ▷ Customers.customer = Orders.customer Orders };
```

```
 customer
 ────────
 Dee
(1 row)
```

**"None over £100"** is not the same as "never ordered". *No order over £100* means
everyone except the customers who have **at least one** such order — so build the
positive set and take it away. Ann (120) and Bo (200) qualify as big spenders; the
rest do not:

```relix
BigSpenders := { δ (π customer (σ amount > 100 (Orders))) };
query { Customers − BigSpenders };
```

```
 customer
 ────────
 Cy
 Dee
(2 rows)
```

Note the shape: *none* is *everyone minus the ones with at least one*. Reaching
straight for `σ amount ≤ 100` would be wrong — it would keep Cy's small order but say
nothing about Dee, and it would wrongly admit a customer who has *both* a large and a
small order.

## Recipe 2: missing from a reference list (−)

When you have two lists of the same kind of thing — what *should* be there and what
*is* — the entries missing from the actual list are the set difference. Both sides
must be projected to the same heading first.

```relix
Catalogue := [
| sku |
|-----|
| A   |
| B   |
| C   |
| D   |
];

Stocked := [
| sku | qty |
|-----|-----|
| A   | 5   |
| C   | 0   |
| D   | 2   |
];

query { π sku (Catalogue) − π sku (Stocked) };
```

```
 sku
 ───
 B
(1 row)
```

**Missing is not the same as present-but-empty.** C is stocked with a quantity of
zero — it *has* a row, so it is not in the difference. "Not stocked" (B) and "out of
stock" (C) are different questions:

```relix
query { π sku (σ qty = 0 (Stocked)) };
```

```
 sku
 ───
 C
(1 row)
```

## Variations

- **Unavailable = missing ∪ out of stock** — the union of the two sets above, when the
  business cares about "cannot ship" rather than the reason.
- **Present in neither list** or **symmetric difference** (in one or the other but not
  both) is the [Comparison](comparison.md) class (∆).
- **Anti-join keeps columns; difference does not need a key match.** Use ▷ when you
  want the left row's other columns; use − when you have two clean lists.

## Pitfalls

- **`▷` and `σ … ≠` are different.** An anti-join asks *does any matching row exist*; a
  `σ` with `≠` tests one row at a time and cannot see "none across the group". *No
  order over £100* needs the anti-join / difference shape, not `σ amount ≤ 100`.
- **NULL is not absence.** A NULL join key matches nothing, so an anti-join **keeps** a
  left row whose key is NULL (it has "no match"). If NULL means "not yet known" rather
  than "definitely none", filter the NULLs first and decide deliberately.
- **`−` needs identical headings.** Project both sides to the same columns; a stray
  column on one side makes the difference empty or wrong.
- **`−` is set difference — it removes duplicates.** If the question is about counts
  (a bag), − is the wrong tool.

## Check it

- **Existence and absence must partition the whole.** The customers from the
  [existence](existence.md) recipe plus the customers from Recipe 1 here should be
  *every* customer, with no overlap. `COUNT` them and check they sum to the total.
- Put a row with a **NULL** key in the left relation and watch the anti-join keep it —
  then decide whether that is what you meant.
- Put an entity in the reference list that is **present but zero** (C) and confirm the
  difference does *not* flag it.

## Related

- [Has at least one](existence.md) — the mirror image; ⋉ keeps the entities with a
  match.
- [What changed, what differs](comparison.md) — ∆ for *in one or the other but not
  both*.
- [Every: related to all of a set](every.md) — *only from a set* is built from an
  anti-join too.
- Reference pages (`docs/reference`): `anti-join`, `difference`.
