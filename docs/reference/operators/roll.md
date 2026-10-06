# Name: Roll (ROLL)

# Syntax:
ROLL (Relation)
ROLL SEED <integer> (Relation)
ROLL BY <weightExpr> (Relation)
ROLL BY <weightExpr> SEED <integer> (Relation)

ROLL (Die)                -- an endless stream of random faces (non-deterministic)
ROLL SEED 7 (Die)         -- a reproducible endless stream
ROLL BY weight (Loot)     -- a loaded die: faces weighted by `weight`
ROLL BY weight SEED 7 (Loot)

# Description:
ROLL turns a finite relation of "faces" into an **endless** stream of uniformly
random draws **with replacement** — a die roll. Each row of the input is a face;
each pull of the result rolls the die again and emits one face, chosen uniformly at
random, forever. A six-row input is a six-sided die.

It is the with-replacement counterpart of the other random operators: `SAMPLE n
ROWS` takes a random *subset* without replacement, `SHUFFLE` is a random
*permutation* (every row once), and ROLL keeps drawing from the same faces
indefinitely, so the same face can come up again and again. It is also the
random-source sibling of the generator relations (`Naturals`, `Primes`, `Range`):
where they produce a fixed sequence, ROLL produces random draws from a face set you
give it.

Because the stream is endless, you compose `LIMIT` to take a finite number of
rolls:

    LIMIT 3 (ROLL SEED 7 (Die))   -- roll the die three times

The optional `SEED <integer>` clause makes the sequence reproducible: the same seed
and the same faces give the identical sequence of draws every run. Without a seed,
each run draws fresh randomness — exactly the contract `SAMPLE` and `SHUFFLE` use.

By default every face is equally likely. The optional `BY <weightExpr>` clause makes
it a **loaded die**: each face is drawn with probability proportional to a
non-negative `NUMBER` expression evaluated over that face's columns. A weighted
encounter or loot table — a `common` face ten times as likely as a `rare` one — is a
weight column rather than ten copies of a row:

    ROLL BY weight SEED 7 (Loot)

A zero-weight face is never drawn, a negative weight is an error, and a face set whose
weights are all zero (like an empty one) yields no rows. `BY` precedes `SEED` when
both are given.

Keyword-only, with no glyph, like the rest of the random family.

# Technical Description:
ROLL buffers its (finite) face set once, then emits an unbounded stream in which
each element is an independent uniform draw from the buffered faces. The optional
SEED clause initialises a seeded `java.util.Random`; without it,
`ThreadLocalRandom.current()` supplies fresh randomness. The output schema equals
the input schema; it carries no ordering, and it is `[bag]` by nature (a draw can
repeat). It never pushes down to a backend.

With a `BY` weight, ROLL builds a cumulative-weight table over the buffered faces once
and draws by a binary search for a uniform point in `[0, Σw)`: each face's probability
is `w / Σw`. A zero-weight face adds nothing to the table and so is never selected; a
negative weight is a runtime error; and an all-zero (or empty) face set has total
weight zero and yields no rows. A NULL weight counts as zero.

ROLL is the one operator whose **output is unbounded regardless of its input** — it
is where unboundedness originates when there is no unbounded generator in the tree.
Its **input, by contrast, must be bounded**: the faces are buffered to be indexed,
so an endless face set (`ROLL (Naturals)`) is a plan-time error, the same error a
blocking operator raises over an unbounded input. And because ROLL's own output is
unbounded, a blocking operator above it — `SORT`, `GROUP`, `SHUFFLE`, a collecting
consumer — is rejected too, exactly as over any endless generator. An empty face
set has nothing to draw, so it yields no rows rather than an endless stream.

# Examples:
Roll one six-sided die:
  LIMIT 1 (ROLL (Die))

Roll two dice (a Cartesian face set), reproducibly:
  LIMIT 2 (ROLL SEED 7 (Die × Die))

A reproducible stream of random customers, with repeats:
  LIMIT 100 (ROLL SEED 42 (Customers))

Draw from a filtered face set:
  LIMIT 5 (ROLL (σ active = true (Players)))

A loaded die — draw faces weighted by a column:
  LIMIT 10 (ROLL BY weight SEED 7 (Loot))

A weight that is a computed expression over the face:
  LIMIT 10 (ROLL BY rarity * bonus (Encounters))

# Worked Example:
A six-sided die, rolled five times with a fixed seed:

```relix
Die := [
| face |
|------|
| 1    |
| 2    |
| 3    |
| 4    |
| 5    |
| 6    |
];

query { LIMIT 5 (ROLL SEED 7 (Die)) };
```

```
 face
 ────
    5
    3
    4
    5
    5
(5 rows)
```

Five independent draws from the same six faces — note that `5` comes up three
times: the draws are with replacement, so a face is never "used up". Run it again
with the same seed and you get the identical five; drop the seed and each run rolls
afresh. Without the `LIMIT` the stream would never end, which is exactly why a
blocking operator is not allowed directly above a `ROLL`.

# Limitations:
The result is unbounded, so it can only be consumed by streaming operators: a
`LIMIT` (or another bound) must stand between a `ROLL` and any operator that buffers
its input — `SORT`, `GROUP`, `SHUFFLE`, `SAMPLE n ROWS` — or that collects the whole
result. The face set itself must be bounded; `ROLL` over an endless generator is
rejected at plan time. Draws are independent — uniform by default, or weighted by a
non-negative `BY` expression (a negative weight is an error). It never pushes down to a
backend.

# Alternatives:
SHUFFLE for a random *permutation* (every row once, bounded). SAMPLE n ROWS for a
random *subset* without replacement. The generator relations (`Range`, `Naturals`)
for a fixed, non-random endless sequence. `LIMIT n (ROLL SEED k (R))` for a
reproducible length-`n` sample *with* replacement, in draw order.

# See Also:
[shuffle](shuffle.md), [sample-reservoir](../advanced/sample-reservoir.md),
[limit](limit.md)

# Notes:
The SEED value is an integer literal written in the script; the k-th emitted row is
a function of the seed and k, so the same seed with a different face set produces a
different sequence.
