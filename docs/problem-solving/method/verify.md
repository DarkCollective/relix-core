# Check your answer

A query that returns rows is not a query that is right. It ran, it produced a table,
and the table looks plausible — and none of that is evidence. The same short checklist
catches most of the ways a plausible answer is wrong, and every recipe in this manual
ends with the checks that apply to it. Run it before you trust a result, and
especially before someone else does.

## The checklist

1. **Tiny data with a known answer.** Write inline tables small enough to work the
   answer out by hand, and include the awkward rows on purpose: an empty group, a
   NULL, a duplicate, a boundary. A query that is right on ten carefully chosen rows
   is far more trustworthy than one that looks right on ten thousand.

2. **The grain.** Is it one row per what you said in [grain first](grain.md)? `COUNT`
   the answer against `COUNT` of `δ` over the key. If they differ, a join fanned out or
   a set operation deduplicated — the answer is at the wrong grain, whatever else it
   gets right.

3. **Bag or set.** Did a `∪`/`∩`/`−`/`δ` remove duplicates the question needed to keep,
   or did a bag keep duplicates it wanted gone? Counting questions are bags; membership
   questions are sets. Using the wrong one is invisible until you check the count.

4. **NULL.** What does each predicate do with a NULL? A comparison with NULL is
   UNKNOWN, not false, and σ keeps only what is *true* — so `σ status ≠ "open"` drops a
   NULL status, and `¬(amount > 100)` does not keep a missing amount. Decide, per
   predicate, whether that is what you meant.

5. **Empty inputs.** What does the answer say when an input has no rows? The edge cases
   are where the surprises live: `÷` by an empty relation is satisfied by *everyone*,
   ∀ over an entity with no rows says nothing about it, and an inner join drops a key
   with no match. Feed an empty input and confirm the answer is the one you want.

6. **What actually ran.** `:explain` shows the plan — the operators, and which parts
   ran inside a database rather than in the engine. A query can be right and still do
   far too much work; the plan is where you find out, and the subject of
   [engineering the solution](../engineering.md).

7. **Why a row is there.** When a row is surprising — present when you expected it gone,
   or gone when you expected it present — `WHY` reifies its lineage as data, naming the
   exact source rows that produced it. Trace a row you *understand* first, then the one
   that puzzles you.

Not every check applies to every query, which is why each recipe names the ones that
matter to it. But the discipline is the same everywhere: a result is a claim, and a
claim is worth checking before you act on it.
