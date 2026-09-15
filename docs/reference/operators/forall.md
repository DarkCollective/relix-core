# Name: Universal Quantification (∀ / FORALL)

# Syntax:
∀ <grouping-keys> : <condition> (Relation)
FORALL <grouping-keys> : <condition> (Relation)
∀ : <condition> (Relation)               -- no key: whole-relation test

∀ customer_id : status = "completed" (Orders)
FORALL region, dept : active = true (Staff)

# Description:
For-all keeps a group only when EVERY row in that group satisfies the condition.
It answers questions of the form "which customers have ALL their orders
completed?" or "which departments have every member active?" — the cases SQL
expresses awkwardly with NOT EXISTS (… NOT …). The result is just the grouping
keys of the qualifying groups.

With no grouping key, `∀ : P (R)` asks the yes/no question "does every row
satisfy P?" and returns a one-row truth relation (present = yes, empty = no).

# Technical Description:
∀_keys:P(R) groups R by the key tuple and emits a key tuple iff all rows in its
group satisfy P. NULL semantics are strict: a row whose predicate is UNKNOWN
disqualifies its group. Output schema = the grouping keys (or the empty,
zero-column schema for the no-key form, which yields DEE/DUM — one empty tuple
for true, none for false; vacuously true on empty input). It is a blocking
([bag]) grouped operator. A single-connection keyed ∀ pushes down as
GROUP BY … HAVING COUNT(*) = COUNT(CASE WHEN P THEN 1 END) on every SQL dialect.

# Examples:
Customers whose orders are all completed:
  ∀ customer_id : status = "completed" (Orders)

Departments where everyone is active:
  FORALL dept : active = true (Staff)

Suppliers who only ever shipped on time:
  ∀ supplier_id : delivered_on_time = true (Shipments)

Whole-relation check — did every test pass?
  ∀ : passed = true (TestRuns)

# Worked Example:
Customer success wants the customers whose orders are *all* completed — accounts
with nothing outstanding. The trap with a plain selection is that filtering to
`status = "completed"` and grouping would also pick up customers who happen to
have *one* completed order alongside open ones. ∀ asks the stricter question.

```relix
Orders := [
| customer | order_id | status     |
|----------|----------|------------|
| Alice    | 1        | completed  |
| Alice    | 2        | completed  |
| Bob      | 3        | completed  |
| Bob      | 4        | pending    |
| Cara     | 5        | completed  |
];

AllDone := { ∀ customer : status = "completed" (Orders) };
```

Grouping by `customer`, a group survives only if **every** row in it satisfies the
predicate:

```
  Alice → completed, completed   → all match   → kept
  Bob   → completed, pending     → one fails   → dropped
  Cara  → completed             → all match   → kept
```

The output is just the qualifying grouping keys:

```relix
query { AllDone };
```

```
 customer
 ────────
 Alice
 Cara
(2 rows)
```

Contrast the quantifiers: ∀ is "all orders completed"; a SEMI join (⋉) answers
"has *at least one* completed order" (which would also include Bob); an ANTI join
(▷) answers "has *no* completed order". The no-key form
`∀ : status = "completed" (Orders)` collapses this to a single yes/no: it returns
one (empty) row meaning "every order in the whole table is completed" — here, no
row, because Bob's order #4 is pending.

# Limitations:
Only the keyed form pushes down: the no-key whole-relation form has no natural
SQL shape and is computed in the engine.

# Alternatives:
For "at least one row matches" use a SEMI join (⋉); for "no row matches" use an
ANTI join (▷). Division (÷) expresses "relates to all of" set-style.

Written out by hand, ∀ is **not** "every key, less the keys of a row that fails".
That spelling keeps a group it could not evaluate: `¬P` over a missing value is
itself unknown, so the selection drops the row instead of counting it against the
group, and the group survives on a row that never satisfied anything.

```relix
π customer_id (Orders) − π customer_id (σ ¬(status = "completed") (Orders))
```

The equivalent has to subtract the unknowns alongside the failures — which is the
whole of what ∀ spares you, and the reason it is an operator rather than sugar:

```relix
π customer_id (Orders) − π customer_id (σ ¬(status = "completed") ∨ status IS NULL (Orders))
```

# See Also:
[semi-join](../joins/semi-join.md), [anti-join](../joins/anti-join.md), [division](../set-operations/division.md), [group](group.md), [and](../predicates/and.md)

# Notes:
∀ is first-class (not desugared) and pushes down in the keyed, single-connection
case; otherwise it runs in the engine. Every dialect is pushed the same strict
form, so a group holding an UNKNOWN row is dropped wherever the query runs — a
native bool_and would skip that row like any aggregate and keep the group, which
is a second answer to the question ∀ already answers.
