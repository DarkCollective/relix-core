# Grain first

<!-- skeleton: the argument and the running example are settled; prose to be written -->

**The claim.** Most wrong queries are wrong about the grain before they are wrong
about anything else. Decide *"one row per ___"* before choosing an operator, and
write the answer's heading down.

**To cover:**

- The grain decides the first operator. One row per customer means a γ keyed on
  the customer. One row per customer and month means a γ with a derived key. One
  row per pair means a join. One row per group of connected things means `CLUSTER`.
- Sketch the expected answer as an inline table before writing the query, and use
  it as the test once the query exists.
- How a grain goes wrong: a join that fans rows out (grain silently becomes
  "per order line"), a γ keyed on too much, a set operation that removes
  duplicates the question needed.
- Checking the grain you actually got: `COUNT` against `COUNT` of `δ π key`, and
  `relix.keys` for what the engine can prove.
