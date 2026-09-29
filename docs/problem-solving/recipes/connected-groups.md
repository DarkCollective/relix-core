# Things that belong together

> **Grain:** one row per member, labelled with its group · **Class:** Graph · **Signals:** connected, linked, same as, belong together, duplicates, rings, households · **Operators:** ⨝, ∪, `CLUSTER`

## The problem

*"Our customer table has duplicates. Two records are the same customer if they
share an email address or a phone number. Which records belong together?"*

## How to recognise it

The question says **same as**, **linked**, **belong together**, **duplicates**,
**rings** or **households**, and the rule for "together" is about **pairs**: two
records are together if *something* connects them. What makes it a graph problem
rather than a join is the chain: if A is linked to B and B to C, then A and C are
together even though nothing connects them directly. A join finds the links; only
a traversal finds the groups.

This is a two-step recipe, and it is the same two steps whatever the domain:

1. **Links.** Write the rule for "these two are connected" as a join, and keep
   one row per connected pair.
2. **Groups.** Hand the pairs to `CLUSTER`, which follows every chain and labels
   each member with its group.

## The data

```relix
Customers := [
| id | name       | email       | phone    |
|----|------------|-------------|----------|
| 1  | Ann Lee    | ann@x.com   | 555-0101 |
| 2  | A. Lee     | ann@x.com   | 555-0199 |
| 3  | Annie Lee  | annie@y.com | 555-0199 |
| 4  | Bob Ray    | bob@z.com   | 555-0300 |
| 5  | Robert Ray | rray@z.com  | 555-0300 |
| 6  | Cy Tan     | cy@w.com    | 555-0400 |
];
```

Records 1 and 2 share an email, 2 and 3 share a phone, 4 and 5 share a phone, and
6 shares nothing.

## Step 1: the links

Join the table to a renamed copy of itself. `Customers.id < Other.id` keeps each
pair once, and stops a record linking to itself.

```relix
Other := { ρ Other (Customers) };

Links := {
    π Customers.id → a, Other.id → b (
        Customers ⨝ Customers.id < Other.id
                  ∧ (Customers.email = Other.email ∨ Customers.phone = Other.phone)
        Other)
};
query { Links };
```

<!-- output: paste from a run. Expected pairs: (1,2), (2,3), (4,5). -->

## Step 2: the groups

`CLUSTER` only sees records that appear in a link, so record 6 would disappear.
Linking every record to itself keeps the ones that match nothing as groups of one.

```relix
SelfLinks := { π id → a, id → b (Customers) };

Entities := { CLUSTER a, b AS entity_id (Links ∪ SelfLinks) };
query { Entities };
```

<!-- output: paste from a run. Expected: {1,2,3} one entity, {4,5} another, {6} a third. -->

Records 1 and 3 are in the same entity although they share neither an email nor a
phone. That is the chain through record 2, and it is the reason to use `CLUSTER`
rather than the join alone.

## Using the answer

Put the names back, and count how many records each entity has:

```relix
Grouped := {
    τ entity_id, id (
        π entity_id, id, name (Entities ⨝ Entities.a = Customers.id Customers))
};
query { Grouped };
```

<!-- output: paste from a run. -->

```relix
EntitySizes := { γ entity_id, COUNT(a) → records (Entities) };
query { σ records > 1 (EntitySizes) };
```

<!-- output: paste from a run. Expected: the two entities with duplicates. -->

## Pitfalls

- **Chains merge more than you expect.** One shared value is enough to join two
  groups. A family sharing a landline, or a placeholder such as `n/a` or
  `000-0000` in the phone column, links everyone who has it into one giant
  group. Remove junk values before building the links:
  `σ phone ≠ "000-0000" (Customers)`. Look at the size of the largest group
  first; it is where this goes wrong.
- **NULL links nothing.** `NULL = NULL` is UNKNOWN, so two records with no email
  are not linked by it. That is almost always what you want.
- **Records outside every link vanish** unless you add the self-links in step 2.
- **The group label is a label, not an identity.** Groups are numbered by their
  smallest member, so adding a record can renumber every group after it. Do not
  store `entity_id` as a key; recompute it, or key an entity by its smallest
  member's `id`.
- **`CLUSTER` ignores direction.** "A reports to B" is a direction; for "who is
  above whom" use `CLOSURE`, not `CLUSTER`.
- **A pair of records is evidence, not proof.** Two records sharing a phone may
  be two people. Keep the rule that made each link visible (for example as a
  `reason` column in `Links`) so that a reviewer can see why a group formed.

## Check it

- The grain is one row per record: `COUNT(a)` over `Entities` should equal the
  number of customers.
- Put a chain in your test data (A–B–C with no A–C link) and check A and C land
  together. Put a lone record in and check it survives.
- Check the largest group by hand before trusting the rest.

## Related

- [Every: related to all of a set](every.md)
- Graph recipes (planned): reachable from (`CLOSURE`), shortest route (`PATH`)
- Reference pages (`docs/reference`): `CLUSTER`,
  `theta join`,
  `rename`
