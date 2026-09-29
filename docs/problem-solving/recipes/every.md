# Every: related to all of a set, or all rows pass a test

> **Grain:** one row per entity · **Class:** Universal · **Signals:** every, all, always, only, never failed · **Operators:** ÷, ∀, ▷

## The problem

*"Which suppliers hold every certification we require, and have delivered on time
every time?"*

## How to recognise it

The question says **every**, **all**, **always**, **only** or **never failed**.
It is about a set of things an entity has, or a history an entity has built up,
and it keeps the entity only when the whole set passes.

The word *every* covers three different questions, and telling them apart is most
of the work:

| The question | What it asks | Reach for |
|---|---|---|
| "holds **every** required certificate" | the entity's set **contains** a given set | ÷ |
| "delivered on time **every** time" | **each** of the entity's rows passes a test | ∀ |
| "holds **only** approved certificates" | nothing in the entity's set is **outside** a given set | − and ▷ |

*Exactly these* is the first and the third together.

## The data

A procurement team keeps the certifications each supplier holds, the ones it
requires, and a delivery log. Delta holds no certificates and has never delivered.

```relix
Certifications := [
| supplier | cert    |
|----------|---------|
| Acme     | ISO9001 |
| Acme     | HACCP   |
| Birch    | ISO9001 |
| Cobalt   | ISO9001 |
| Cobalt   | HACCP   |
| Cobalt   | Organic |
];

Required := [
| cert    |
|---------|
| ISO9001 |
| HACCP   |
];

Suppliers := [
| supplier |
|----------|
| Acme     |
| Birch    |
| Cobalt   |
| Delta    |
];

Deliveries := [
| supplier | delivery | status  |
|----------|----------|---------|
| Acme     | 1        | on time |
| Acme     | 2        | on time |
| Birch    | 3        | on time |
| Birch    | 4        | late    |
| Cobalt   | 5        | on time |
];
```

## Recipe 1: contains a set (÷)

Divide the pairs by the set that must be covered. The result keeps the columns of
the left relation that are not in the right, so here one row per supplier.

```relix
FullyCertified := { Certifications ÷ Required };
query { FullyCertified };
```

<!-- output: paste from a run. Expected: Acme, Cobalt. -->

Cobalt's extra `Organic` does not matter: division asks whether the set is
**covered**, not whether it **matches**.

## Recipe 2: every row passes (∀)

Group by the entity and keep the group only when every row satisfies the
condition.

```relix
AlwaysOnTime := { ∀ supplier : status = "on time" (Deliveries) };
query { AlwaysOnTime };
```

<!-- output: paste from a run. Expected: Acme, Cobalt. -->

## Recipe 3: only from a set (− and ▷)

"Only" is the negation turned round: an entity qualifies when **no** row falls
outside the set. Find the rows that fall outside with an anti-join, then take
their owners away from everyone.

```relix
Unapproved := { Certifications ▷ Certifications.cert = Required.cert Required };
OnlyApproved := { π supplier (Certifications) − π supplier (Unapproved) };
query { OnlyApproved };
```

<!-- output: paste from a run. Expected: Acme, Birch. -->

**Exactly the required set** is both at once:

```relix
ExactlyRequired := { FullyCertified ∩ OnlyApproved };
query { ExactlyRequired };
```

<!-- output: paste from a run. Expected: Acme. -->

## Putting it together

The question at the top had two *every*s, so it is two views and an intersection:

```relix
Approved := { FullyCertified ∩ AlwaysOnTime };
query { Approved };
```

<!-- output: paste from a run. Expected: Acme, Cobalt. -->

## Pitfalls

- **An entity with no rows is not in the answer.** Delta has never delivered, so
  it has no group, so ∀ says nothing about it. It is not "on time every time"
  vacuously. If the business rule says a supplier with no deliveries passes (or
  explicitly fails), start from `Suppliers` and say so:
  `π supplier (Suppliers) − π supplier (σ status ≠ "on time" (Deliveries))`
  gives *never late*, which includes Delta.
- **Dividing by an empty set is satisfied by everyone.** If `Required` is empty,
  every supplier in `Certifications` covers it. That is the correct logic and
  usually the wrong business answer, so check the divisor is not empty when it
  comes from a query rather than a fixed list.
- **NULL fails ∀.** A row whose condition is UNKNOWN disqualifies its group,
  so a delivery with a missing status disqualifies its supplier. Decide whether
  that is what you mean, and use `Nz` or `IsNull` to say otherwise.
- **Division needs declared headings.** A schema-on-read source (JSON, HTTP,
  MongoDB) must be projected into named columns first.

## Check it

- Try an entity with **no** rows, one with **extra** rows, and one with a single
  failing row. The three variants disagree about exactly these cases, and that
  is how you tell which one you wrote.
- The grain is one row per entity. `COUNT` the answer against
  `COUNT` of its `δ`; they should be equal.

## Related

- [Things that belong together](connected-groups.md)
- Existence and absence (planned): ⋉ is *at least one*, ▷ is *none*
- Reference pages (`docs/reference`): `division`,
  `for-all`,
  `anti-join`
