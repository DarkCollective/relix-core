# Reconciling two systems

*Comparison + Federation*

## The problem

*"Our ledger and the bank statement should agree. Show every reference where the two
disagree — a different amount, or present in one and missing from the other."*

## Classifying it

- *"where the two disagree … missing from the other"* — a
  **[comparison](../recipes/comparison.md)**: align the two sources by key and keep the
  rows that differ. A full outer join keeps every reference from both sides, and a null
  test catches the missing ones.
- **Federation** is the difficulty axis, not a class of its own: the two sides are
  *different systems* — our database and the bank's export. In the engine that is two
  `connection`s, and the reconciliation query is identical to the one below; the inline
  tables here stand in for the two live sources so the example runs.

## The data

```relix
Ours := [
| ref | amount |
|-----|--------|
| A   | 100    |
| B   | 200    |
| C   | 300    |
];

Bank := [
| ref | amount |
|-----|--------|
| A   | 100    |
| B   | 250    |
| D   | 400    |
];
```

A agrees; B differs (200 vs 250); C is only in our ledger; D is only on the statement.

## Stage 1: align by key (comparison)

Rename the amount on each side so both survive the join, then full-outer-join on the
reference:

```relix
O     := { ρ (amount → ours) (Ours) };
Bk    := { ρ (amount → bank) (Bank) };
Recon := { O ⟗ O.ref = Bk.ref Bk };
```

The right key comes back as `ref_r`, and an unmatched side is NULL.

## Stage 2: keep the discrepancies

The disagreements are the rows where the amounts differ **or** one side is NULL — and
because a comparison with NULL is UNKNOWN, the NULL sides need an explicit null test:

```relix
query {
    π Nz(ref, ref_r) → ref, ours, bank (
        σ ours ≠ bank ∨ ours = NULL ∨ bank = NULL (Recon))
};
```

```
 ref  ours  bank
 ───  ────  ────
 B     200   250
 C     300  NULL
 D    NULL   400
(3 rows)
```

`Nz(ref, ref_r)` collapses the two key columns into one — for a ledger-only row the key is
on the left, for a bank-only row on the right.

## Federation: the same query across two systems

Nothing about Stage 1 or 2 changes when the two sides are live systems. Declare a
connection for each and read the ledger tables through them:

```
connection erp   from postgres("…");
connection bankfeed from mysql("…");

O  := { ρ (amount → ours) (erp.ledger) };
Bk := { ρ (amount → bank) (bankfeed.statement) };
```

The full outer join and the null tests are identical. What the planner does differ: each
side's scan (and any σ/π above it) pushes down into its own database, and the join itself
runs in the engine, because it spans two connections. `--explain` shows exactly which part
ran where — the subject of [Part IV](../README.md#engineering-the-solution).

## What this shows

Reconciliation is the comparison recipe with the difficulty axis turned up: the operators
are the same (`⟗`, a null test, `Nz`), and *federation* only changes where the work runs,
not what the query says. That separation — one query, many backends — is the point of a
relational front end over a pile of systems.

The trap is the one comparison always carries: `ours ≠ bank` is UNKNOWN when either side
is NULL, so it silently misses the added and removed references. The `∨ … = NULL` arms are
not optional — they are what turn a *difference* into a *reconciliation*.

## Recipes drawn on

- [What changed, what differs](../recipes/comparison.md) — `⟗`, the null tests, `Nz`.
- [None, never, missing](../recipes/absence.md) — a one-directional view (only-in-ours,
  only-in-bank).
