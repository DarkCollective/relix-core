# Grain first

Most wrong queries are wrong about the grain before they are wrong about anything
else. So before you choose an operator, finish one sentence — *"one row per ___"* —
and write the answer's heading down. That sentence is the grain, and it is the single
most useful thing to decide first, because everything else follows from it and almost
nothing corrects it.

## The grain decides the first operator

The grain is not a detail you settle at the end; it is the shape of the answer, and
the shape of the answer chooses the operator that produces it.

- *One row per customer* is a summary keyed on the customer — a **γ**.
- *One row per customer and month* is the same γ with a **derived key**; the extra
  key is the extra word in the grain.
- *One row per pair* — this order with that shipment, this record with its duplicate
  — is a **join**.
- *One row per group of connected things* — a household, a fraud ring — is
  **`CLUSTER`**, because the group is not a column any row already carries.

Read those the other way and the method appears: say the grain out loud, and the
first operator is the one whose output has that grain. A question you cannot phrase
as *"one row per ___"* is usually two questions, which is the subject of
[decompose into views](decompose.md).

## Sketch the answer before you write it

Once the grain is decided, sketch the answer as a tiny inline table — the columns it
has, and three or four rows you can work out by hand. This costs a minute and pays
twice. It forces the grain to be concrete: if you cannot say what one row looks like,
you have not decided the grain yet. And it becomes the test — the known answer you
check the finished query against, which is where [checking your answer](verify.md)
begins.

## How a grain goes wrong

Grain rarely goes wrong loudly. It goes wrong quietly, and the query still returns
rows — the wrong ones. Three failures account for most of it:

- **A join fans rows out.** Join to a table with more than one matching row and every
  left row multiplies. *One row per order* silently becomes *one row per order line*,
  and a `SUM` over it double-counts. This is the commonest grain bug, and the reason
  to reach for a semi-join (⋉) when you mean to *filter* rather than *combine*.
- **A γ is keyed on too much.** An extra column in the grouping list splits every
  group; the answer has more rows than there are things, and each aggregate is
  computed over a sliver.
- **A set operation removes duplicates the question needed.** `∪`, `∩` and `−` are
  set operations — they deduplicate. If the grain was a bag (a row *per occurrence*),
  a set operation quietly collapses the occurrences the question was counting.

## Check the grain you got

The grain you *intended* and the grain you *got* are different claims, and the gap
between them is where the bug lives. Two checks close it:

- **Count against distinct.** `COUNT` of the answer should equal `COUNT` of `δ` over
  the key columns. If the total is larger, a join fanned out; if smaller, a set
  operation deduplicated. Either way the grain is not what you said.
- **Ask the engine what it can prove.** `relix.keys` lists the candidate keys the
  engine has established for a relation — the column sets that identify a row. If the
  grain you wrote down is not among them, the engine cannot see the uniqueness you are
  assuming, and neither, probably, can the data.

Decide the grain, sketch its answer, and these checks turn a query that *runs* into a
query that is *right*. The rest of the method — the quantifier, the decomposition, the
final checklist — all assume the grain is settled first.
