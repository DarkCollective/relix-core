# What value makes this true

> **Grain:** one row per input row · **Class:** Equation · **Signals:** fill in the blank, complete, what value, given … find, goal-seek, back out · **Operators:** `SOLVE`

## The problem

*"An invoice import has gaps: some rows know the quantity and unit price but not the
line total; others know the total and one of the two factors. The relationship is
always `line_total = qty × unit_price`. Fill in whichever value is missing on each
row."*

## How to recognise it

The question gives a **fixed relationship** and asks for the **one value that makes it
hold** — *fill in the blank*, *complete the row*, *given these, find that*, *back out
the rate*. It is a spreadsheet goal-seek: rearrange one equation to solve for its one
unknown. The tell is that *which* value is unknown may vary from row to row, and the
relationship stays the same.

`SOLVE` is this and only this. It is **not** `OPTIMIZE` — there is no search, no
choice, no constraint; it inverts one arithmetic equation per row. (It does not even
use the mathematical-programming solver — it is a deterministic tree-walk, and
streams.)

## The data

A blank cell is NULL.

```relix
Invoice := [
| item     | qty | unit_price | line_total |
|----------|-----|------------|------------|
| Widget   | 3   | 5.00       |            |
| Gadget   |     | 12.00      | 60.00      |
| Sprocket | 4   |            | 10.00      |
];
```

Each row is missing a different one of the three participating columns.

## Recipe: fill the one blank (SOLVE)

Write the equation; `SOLVE` decides *per row* which value is missing and rearranges to
find it.

```relix
query { SOLVE line_total = qty * unit_price (Invoice) };
```

```
 item      qty  unit_price  line_total
 ────────  ───  ──────────  ──────────
 Widget      3           5          15
 Gadget      5          12          60
 Sprocket    4         2.5          10
(3 rows)
```

Widget multiplies; Gadget and Sprocket divide. The direction is chosen per row from
whichever column is NULL — one statement fills a different hole in each.

## Variations

- **Any invertible arithmetic** — `SOLVE area = width * height`, `SOLVE total =
  principal + interest`. The equation may use `+ − × ÷` and unary minus, each column
  appearing at most once per side.
- **Compute a column when nothing is missing** is not this recipe — that is a plain π
  with arithmetic (`π …, qty * unit_price → line_total`). `SOLVE` earns its place only
  when the unknown varies.

## Pitfalls

- **Exactly one blank, or nothing happens.** A row with **no** NULL among the
  participating columns, or with **two or more**, passes through unchanged — `SOLVE`
  acts only when there is precisely one hole. It does not *check* a fully-populated
  row, and it cannot solve for two unknowns.

  ```relix
  Check := [
  | item     | qty | unit_price | line_total |
  |----------|-----|------------|------------|
  | Full     | 2   | 4.00       | 8.00       |
  | TwoBlank | 3   |            |            |
  ];
  query { SOLVE line_total = qty * unit_price (Check) };
  ```

```
 item      qty  unit_price  line_total
 ────────  ───  ──────────  ──────────
 Full        2           4           8
 TwoBlank    3  NULL        NULL
(2 rows)
```

- **It does not validate.** A `Full` row whose values are inconsistent (`2 × 4 ≠ 9`)
  is *not* corrected or flagged — there is no blank to fill. Checking a relationship is
  a σ (`σ line_total ≠ qty * unit_price`), a different job.
- **Only invertible arithmetic.** A column appearing twice, or a non-arithmetic
  function, cannot be inverted deterministically and is rejected.
- **Division by zero, or a NULL factor that should have been the unknown**, gives NULL
  — check the inputs the inversion divides by.

## Check it

- For each row, plug the filled value back into the equation by hand on tiny data — a
  goal-seek is only right if the completed row satisfies the relationship.
- Put a **fully-populated** row and a **two-blank** row in and confirm both pass through
  untouched — that is the boundary of what `SOLVE` does.
- If you also need to *catch* rows that violate the relationship, add a σ; `SOLVE` fills
  gaps, it does not audit.

## Related

- [The best combination within limits](optimization.md) — `OPTIMIZE` chooses values
  under constraints; `SOLVE` inverts a single fixed equation.
- [Which rows, and which columns](selection.md) — computing a column when nothing is
  missing is a π; validating a relationship is a σ.
- Reference pages (`docs/reference`): `solve`, `optimize`.
