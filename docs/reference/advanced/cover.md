# Name: Combinatorial Covering (COVER)

# Syntax:
COVER <strength-t> (Relation)

COVER 2 (Substrate × Temperature × Coating)   -- all-pairs (pairwise) coverage
COVER 3 (Candidates)                            -- all-triples coverage

# Description:
COVER picks a small subset of rows that still covers every combination of values
up to a chosen "strength" — the technique behind all-pairs (pairwise) coverage.
Whenever you face an exploding number of combinations but can only afford to try a
few, COVER keeps just enough rows that every PAIR of values still appears together
at least once. Think of a coatings lab that can run only a handful of expensive
physical trials: rather than build all 3 × 3 × 3 = 27 combinations of substrate,
curing temperature and coating, COVER 2 finds a far smaller set in which every
pair of factor levels is still exercised at least once.

# Description (continued):
The strength `t` controls how thorough: t=1 means every single value appears at
least once; t=2 (pairwise) means every pair of column values appears together;
t=w (the number of columns) means every distinct row appears (equivalent to δ).

# Technical Description:
COVER keeps a subset of candidate rows such that every distinct t-column value
combination occurring in the input occurs in at least one output row (a windowed
filter — output ⊆ input, schema = input). Strength t is a required integer ≥ 1.
The coverage universe is derived from the (already-constrained) candidate set, so
excluded combinations are never demanded — constraints come free. Greedy
algorithm with a deterministic earliest-buffered tie-break; order the input via τ
to bias which candidates win.

# Diagram:
Strength `t` interpolates between "every value once" and "every row":

```
            t = 1                t = 2 (pairwise)            t = w (all cols)
      every value appears     every PAIR of values         every distinct row
         at least once         appears together               (≡ DISTINCT)
      ┌──────────────────┐   ┌────────────────────┐      ┌──────────────────┐
      │  small subset    │   │  small-ish subset  │      │  full input set  │
      │  (cheapest)      │ → │  (the sweet spot)  │  →   │  (most thorough) │
      └──────────────────┘   └────────────────────┘      └──────────────────┘
```

# Examples:
All-pairs coverage of a parameter space:
  COVER 2 (Type × Format × Size)

All-triples coverage:
  COVER 3 (Candidates)

Bias selection toward high-priority candidates:
  COVER 2 (τ priority DESC (Candidates))

Federation — derive the factor domains live, then cover:
```relix
Devices := { δ (π device (Sessions)) };
Plan    := { COVER 2 (Devices × Networks × Locales) };
```

Verify coverage in-language (this is empty when fully covered):
  Missing := { (π device, network (Devices × Networks)) − (π device, network (Plan)) };

# Worked Example:
A materials lab is screening a new protective coating. Three factors each have
three levels, so the exhaustive design is 3 × 3 × 3 = 27 physical samples — too
many to fabricate. The team only needs every *pair* of factor levels tried
together at least once, so they use pairwise (strength-2) coverage.

```relix
Substrate := [
| substrate |
|-----------|
| Steel     |
| Aluminium |
| Titanium  |
];

Temperature := [
| temp |
|------|
| 120  |
| 150  |
| 180  |
];

Coating := [
| coating |
|---------|
| Epoxy   |
| Polyurethane |
| Ceramic |
];

-- 27 exhaustive combinations collapse to a pairwise-covering subset
Trials := { COVER 2 (Substrate × Temperature × Coating) };
```

`Trials` contains ten rows instead of 27, yet every (substrate, temperature),
(substrate, coating) and (temperature, coating) pair is present somewhere in the
set — the row `Titanium / 180 / Epoxy`, for instance, covers both the
(Titanium, 180) and (180, Epoxy) pairs at once:

```relix
query { τ substrate, temp (Trials) };
```

```
 substrate  temp  coating
 ─────────  ────  ────────────
 Aluminium   120  Polyurethane
 Aluminium   150  Epoxy
 Aluminium   150  Ceramic
 Aluminium   180  Polyurethane
 Steel       120  Epoxy
 Steel       150  Polyurethane
 Steel       180  Ceramic
 Titanium    120  Ceramic
 Titanium    150  Polyurethane
 Titanium    180  Epoxy
(10 rows)
```

Ten is what the greedy search finds; nine is the known optimum for three
3-level factors, so the design is one row off ideal and still under 40% of the
exhaustive 27. The `τ` sorts the output for readability — the generator emits
rows in the order it selects them.

Prove the design is complete without an external oracle — each of these is empty
when every pair is covered:

```relix
MissingST := { (π substrate, temp (Substrate × Temperature)) − (π substrate, temp (Trials)) };
MissingSC := { (π substrate, coating (Substrate × Coating)) − (π substrate, coating (Trials)) };
MissingTC := { (π temp, coating (Temperature × Coating)) − (π temp, coating (Trials)) };
```

To make the lab fabricate its preferred substrate first when there is a tie, order
the candidates before covering:

```relix
Trials := { COVER 2 (τ substrate ASC (Substrate × Temperature × Coating)) };
```

# Limitations:
Strength t has no default — it is required. The algorithm is greedy
(near-minimal, not provably minimal). Output is a subset of the input rows
(schema unchanged); it does not push down.

# Alternatives:
δ (DISTINCT) is the t = (all columns) extreme. A raw cross product (×) gives every
combination when you actually want the full exhaustive set.

# See Also:
[cross](../set-operations/cross.md), [distinct](../operators/distinct.md), [sort](../operators/sort.md), [difference](../set-operations/difference.md)

# Notes:
Coverage is verifiable in-language with a difference (−) of projected column
combinations — no external oracle needed.
