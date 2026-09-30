# Check your answer

<!-- skeleton -->

**The claim.** A query that returns rows is not a query that is right. The same
short checklist catches most mistakes, and every recipe in this manual ends with
the checks that apply to it.

**The checklist:**

1. **Tiny data with a known answer.** Inline tables small enough to work out by
   hand, including the awkward rows: an empty group, a NULL, a duplicate.
2. **The grain.** Is it one row per what you said? (`COUNT` against a `δ` count.)
3. **Bag or set.** Did a set operation remove duplicates the question needed, or
   did a bag keep ones it did not want?
4. **NULL.** What does each predicate do with a NULL? A comparison with NULL is
   UNKNOWN, and σ drops it.
5. **Empty inputs.** What does the answer say when an input has no rows? (÷ by an
   empty relation is vacuously satisfied.)
6. **What actually ran.** `:explain` shows the plan, and which parts ran in a
   database rather than the engine.
7. **Why a row is there.** `WHY` reifies a surprising row's lineage.
