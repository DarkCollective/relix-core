# When the rule is recursive

> **Grain:** one row per derived pair · **Class:** Graph · **Signals:** ancestors, explosion, all descendants, parts of parts, keep expanding, until nothing new · **Operators:** `FIX`

## The problem

*"Our bill of materials stores 'assembly contains part' as direct edges. Give me every
component of a bike at any depth — the full parts explosion."*

## How to recognise it

The question needs a rule applied **to its own output, repeatedly** — *all descendants*,
*parts of parts*, *ancestors*, *keep expanding until nothing new appears*. It is the
same "follow the chain" shape as [reachability](reachability.md), but reach for `FIX`
when the two-column `CLOSURE` cannot say it: the step needs **extra columns**, a
**filter**, or a **join against another relation** each round. `FIX` is the general
recursion operator — SQL's `WITH RECURSIVE`, one Datalog rule.

You give it a **base** (where to start) and a **step** (how to derive more from what
you have so far); it repeats the step until the answer stops growing.

## The data

```relix
Contains := [
| assembly | part  |
|----------|-------|
| Bike     | Frame |
| Bike     | Wheel |
| Wheel    | Rim   |
| Wheel    | Spoke |
| Wheel    | Tyre  |
| Tyre     | Tube  |
];
```

A bike contains a wheel, a wheel contains a tyre, a tyre contains a tube — three levels
deep.

## Recipe: base and step to a fixpoint (FIX)

`FIX name (base, step)` binds `name` inside the step to *everything derived so far*. The
step joins what you have to the next edge and re-labels the result to stay
union-compatible with the base:

```relix
Explosion := { FIX BOM (
  Contains,
  π assembly, sub → part (BOM ⋈ ρ Edge(part, sub) (Contains))
) };
query { τ assembly, part (Explosion) };
```

<!-- output: paste from a run. Expected: 11 (assembly, part) pairs — six direct edges plus five derived; Bike gains Rim, Spoke, Tyre via Wheel and Tube two levels down. -->

Read the step: renaming `Contains` to `Edge(part, sub)` makes its first column share
the name `part`, so `BOM ⋈ Edge` links each known part to what *it* contains; the
projection relabels the deeper `sub` back to `part`. *"Everything to order for a bike"*
is then a selection — folded into the recursion by the optimiser, since `assembly` is
carried through unchanged:

```relix
query { π part (σ assembly = "Bike" (Explosion)) };
```

<!-- output: paste from a run. Expected: Frame, Wheel, Rim, Spoke, Tyre, Tube. -->

## Variations

- **Two-column reachability is `CLOSURE`.** This exact query is plain reachability, so
  `CLOSURE assembly, part (Contains)` is shorter and faster — `FIX` earns its keep only
  when the step needs the extra columns, filter, or join. See
  [reachability](reachability.md).
- **Carry a value down** — accumulate a depth, a running cost, or a path. That is a step
  that *computes*, which is exactly what `FIX` can do and `CLOSURE` cannot (see the
  termination pitfall).
- **Bound a recursion you are unsure of** — `--max-fixpoint-rounds` caps the depth,
  `--max-materialized-rows` caps the memory.

## Pitfalls

- **The step must be monotone and linear.** Allowed: σ π ρ δ μ and the joins, ∪, ∩. Not
  allowed: difference, division, aggregation, outer joins — and exactly **one** mention
  of the bound name. These are what guarantee a least fixpoint.
- **A step that carries values through terminates; one that *computes* may not.** The
  bill of materials finishes because every value it emits already appears in the input —
  there are finitely many rows to derive. A step like `π n + 1 → m (N)` invents a new
  value every round and never stops. That is not a bug to avoid — counting, accumulating
  cost, numbering rounds are all worth doing — but bound them, because whether such a
  query finishes cannot be decided without running it.
- **Cyclic *data* is fine.** Going round a cycle re-derives a row already held, so the
  round adds nothing and the loop closes — under set semantics. It is *computed values*,
  not cycles, that run away.
- **Reach for `CLOSURE`/`PATH`/`TRACE` first.** For binary reachability they are simpler
  and use a faster adjacency loop; `FIX` is the general, heavier form.

## Check it

- Put a **three-level** nesting (Bike → Wheel → Tyre → Tube) in and confirm the deepest
  part reaches the top assembly — one level would pass a plain join too, so depth is the
  test that proves the recursion.
- Put a **cycle** in the data and confirm the query still terminates.
- If you wrote a step that computes, run it on tiny data **with a round cap** first, and
  reason about why it stops before removing the cap.

## Related

- [Reachable, how far, and by what route](reachability.md) — `CLOSURE`/`PATH`/`TRACE`,
  the specialised two-column forms `FIX` generalises.
- [As columns, as a list, as a tree](reshaping.md) — `TREE` folds the same parent edges
  into nested documents.
- [How many, how much, per what](summary.md) — an aggregation is *not* allowed inside a
  `FIX` step; do it after.
- Reference pages (`docs/reference`): `fix`, `closure`.
