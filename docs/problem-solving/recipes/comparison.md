# What changed, what differs

> **Grain:** one row per thing that differs · **Class:** Comparison · **Signals:** what changed, what differs, reconcile, added, removed, drift, mismatch · **Operators:** −, ∆, ⟗

## The problem

*"What changed between yesterday's price list and today's? And when I reconcile the
two, which items were added, which were removed, and which had their price changed?"*

## How to recognise it

The question compares **two** relations of the same kind of thing and asks what is
**different** — *what changed*, *what was added or removed*, *where do these two
systems disagree*, *has this drifted*. There are two depths of answer, and the tell is
whether you need to see the values side by side:

- **Just the rows that differ**, no alignment — `∆` (symmetric difference): the rows
  in one relation or the other but not both.
- **Aligned by a key, so you can see old and new together** and label each as added /
  removed / changed — a full outer join (`⟗`) with a null test.

## The data

Yesterday's and today's price list — one changed price, one removed item, one added.

```relix
Yesterday := [
| sku | price |
|-----|-------|
| A   | 10    |
| B   | 20    |
| C   | 30    |
];

Today := [
| sku | price |
|-----|-------|
| A   | 10    |
| B   | 25    |
| D   | 40    |
];
```

A is unchanged, B changed 20 → 25, C was removed, D was added.

## Recipe 1: just the discrepancies (∆)

When the two relations already share the same columns and you only want the rows that
disagree, symmetric difference is the whole answer:

```relix
query { Yesterday ∆ Today };
```

<!-- output: paste from a run. Expected: B/20, C/30 (gone from today) and B/25, D/40 (new today). A is in both, so it is absent. -->

`∆` is `(R − S) ∪ (S − R)` — everything in exactly one side. Note that a *changed* row
shows up as **two** rows: B's old `20` and its new `25`, unaligned. That is the limit
of `∆` — it tells you B is involved, not that 20 became 25.

## Recipe 2: reconcile by key (⟗)

To see old and new **side by side** and classify each item, align the two on their key
with a full outer join. Rename the value column on each side first, so both survive
the join:

```relix
Y := { ρ (price → price_y) (Yesterday) };
T := { ρ (price → price_t) (Today) };
Recon := { Y ⟗ Y.sku = T.sku T };
query { Recon };
```

<!-- output: paste from a run. Expected: A 10/10; B 20/25; C 30/NULL; NULL/D 40. The right key is auto-renamed sku_r. -->

A full outer join keeps every key from both sides, filling the missing side with NULL.
Now the discrepancies are exactly the rows where the two prices disagree **or** one
side is NULL — and because a comparison with NULL is UNKNOWN, the NULL sides need an
explicit null test:

```relix
query {
    π Nz(sku, sku_r) → sku, price_y, price_t (
        σ price_y ≠ price_t ∨ price_y = NULL ∨ price_t = NULL (Recon))
};
```

<!-- output: paste from a run. Expected: B 20→25 (changed), C 30→NULL (removed), D NULL→40 (added). A is unchanged, so it drops out. -->

`Nz(sku, sku_r)` collapses the two key columns into one — for a removed row the key is
on the left, for an added row on the right. The row now reads as a diff: a value on
both sides that disagree is a **change**, a left-only value is a **removal**, a
right-only value is an **addition**.

## Variations

- **Only additions**, or only removals: a one-directional difference — `π sku (Today) −
  π sku (Yesterday)` is the added keys, and the reverse is the removed keys. That is the
  [Absence](absence.md) shape.
- **A status column** instead of reading the NULLs by eye: derive it, e.g.
  `price_y = NULL → was_added`, and project it.
- **Reconciling across two databases** is this recipe with each side a connection — the
  full outer join and the null tests are identical; only the sources change. That is the
  *reconciling two systems* case study (Part III).

## Pitfalls

- **`∆` shows a change as two rows.** For *what changed*, that is often enough; for
  *what did it change from and to*, you need the keyed join. Choosing `∆` when you
  needed the alignment is the common mistake.
- **A full outer join renames the shared key.** The right side's `sku` comes back as
  `sku_r`; the unmatched rows have NULL on one side, so `Nz(sku, sku_r)` (or a null
  test) is how you recover a single key. Do not project `sku` alone — it is NULL for
  every added row.
- **NULL breaks the disagreement test.** `price_y ≠ price_t` is UNKNOWN when either side
  is NULL, so it silently misses added and removed rows. The `∨ … = NULL` arms are not
  optional.
- **`∆` and `−` need union-compatible headings.** Same columns, same order; project both
  sides first if they differ.

## Check it

- **Every key falls into exactly one bucket.** unchanged + changed + added + removed
  should account for every distinct key across both sides, with no overlap.
- Reverse the operands of `∆` and confirm the answer is unchanged — symmetric difference
  is symmetric, which is what distinguishes it from `−`.
- Put a row that is **identical** on both sides (A) and confirm it appears in neither
  the `∆` nor the filtered reconciliation.

## Related

- [None, never, missing](absence.md) — a one-directional difference is *added* or
  *removed* alone.
- [Which rows, and which columns](selection.md) — the null tests here are ordinary σ.
- Reference pages (`docs/reference`): `symmetric-difference`, `difference`,
  `full-outer-join`, `null test`.
