# Name: Lateral Join (LATERAL)

# Syntax:
left LATERAL fn(arg, …)

Customers LATERAL ordersFor(customer_id)

# Description:
A LATERAL join invokes a table-valued function (TVF) once per row of the left
relation, with arguments that may reference columns from that row. The output is
the left row concatenated with every row the TVF returns for it. A left row that
matches no TVF rows simply produces no output rows (inner-join semantics).

This is the correlated cousin of a plain TVF call: where `ordersFor(2)` always
returns the same orders, `Customers LATERAL ordersFor(customer_id)` personalises
the call for each customer.

# Technical Description:
For each row r in left:
  1. Evaluate the argument expressions against r (they may reference any left column).
  2. Call fn(evaluated args) to obtain a relation TVF_r.
  3. Emit one output row for each row t in TVF_r: r || t (left columns then TVF columns).

The output schema is the left schema concatenated with the TVF's return schema.
If the TVF returns an empty relation for a given left row, that left row contributes
no output rows: LATERAL has inner-join semantics, and there is no LATERAL LEFT JOIN form.

Arguments must be scalar expressions; argument expressions referencing columns from
the left relation are fully supported. Arguments may also be constants or arithmetic
expressions combining constants and left-side columns.

Every scalar type may be an argument: NUMBER, STRING, BOOLEAN, and the four temporal
types DATE, TIME, TIMESTAMP and DURATION. The value is carried into the function body
as a literal of its own type, so a DATE argument arrives as a DATE rather than as its
text, and a boolean argument may be received by a `BOOLEAN` parameter.

LATERAL shares the same precedence as ⋈ (natural join) and is left-associative:
  A ⋈ B LATERAL f(x)  →  (A ⋈ B) LATERAL f(x)

The TVF is defined with `def name(p: T, …): RELATION := { … };` (see
[def … : RELATION](../language/def-relation.md)). The planner inlines the TVF body
per-row at execution time.

# Examples:
## Golden path: each customer's orders

```relix
def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };

Orders := [| order_id | customer_id | amount |
           | 1        | 2           | 100    |
           | 2        | 3           | 50     |
           | 3        | 2           | 75     |];

Customers := [| customer_id | name  |
              | 2           | Alice |
              | 3           | Bob   |];

query { Customers LATERAL ordersFor(customer_id) };
```

Result — Alice is paired with her two orders; Bob with his one:

| customer_id | name  | order_id | amount |
|-------------|-------|----------|--------|
| 2           | Alice | 1        | 100    |
| 2           | Alice | 3        | 75     |
| 3           | Bob   | 2        | 50     |

## Filtering the LATERAL output

```relix
query { σ amount > 60 (Customers LATERAL ordersFor(customer_id)) };
```

Result — only Alice's large orders survive:

| customer_id | name  | order_id | amount |
|-------------|-------|----------|--------|
| 2           | Alice | 1        | 100    |
| 2           | Alice | 3        | 75     |

## Projecting over a LATERAL join

```relix
query { π name, amount (Customers LATERAL ordersFor(customer_id)) };
```

## Two-parameter correlated TVF

```relix
def topOrders(cid: NUMBER, lim: NUMBER): RELATION :=
    { σ customer_id = cid ∧ amount >= lim (Orders) };

Customers := [| customer_id | name  | min_amount |
              | 2           | Alice | 80         |
              | 3           | Bob   | 40         |];

query { Customers LATERAL topOrders(customer_id, min_amount) };
```

Each customer's `min_amount` column is passed as the second argument,
giving each customer a personalised threshold.

## Left-associativity with other joins

```relix
def f(v: NUMBER) : RELATION := { σ amount = v (Orders) };

query { (A ⋈ B) LATERAL f(x) };   -- explicit grouping
query { A ⋈ B LATERAL f(x) };     -- same: left-associative at join precedence
```

## What the engine does with a repeated or constant argument

A LATERAL join is expensive in a way its syntax hides: the TVF body is *re-planned*
for each left row, not just re-executed. Two things cut that down, and neither
changes an answer.

**An uncorrelated LATERAL is decorrelated.** When no argument reads a left column
*and* the function body is reproducible, every left row would instantiate the same
relation, so the optimizer rewrites the join to a plain product over a single TVF
call (`LATERAL-001`):

```
Customers LATERAL ordersFor(2)   →   Customers × ordersFor(2)
```

One plan, one execution. The bigger gain is that a `×` is a shape every other rule
understands: a selection above it can become a theta join, filters push into the
left input, and the planner costs it normally — none of which it can do with a
LATERAL, whose function body is invisible to tree traversal. See
[optimizer](../advanced/optimizer.md) for the rule's exact conditions.

**A repeated argument tuple is memoized.** When the arguments genuinely are
correlated, the engine caches the planned body — and, for a reproducible body within
a bounded budget, the rows it produced — under the argument tuple. A lateral over a
low-cardinality foreign key hits the same values on row after row, and each repeat
then costs a map lookup instead of a planner run. The cache is per query execution
and bounded in both entries and retained rows, so a correlation whose values are
nearly all distinct falls back to the plain per-row path rather than growing.

**Neither applies to a function whose body is not reproducible.** A body reading
`Rand()` or `NOW()`, drawing an unseeded `SAMPLE`, or calling a view or nested TVF
that does, is evaluated once per left row exactly as written — the decorrelation rule
declines to fire, and the memo keeps only the *plan*, which is reusable because
planning evaluates nothing. Add a `SEED` to a sampling body if you want it treated as
reproducible.


# Worked Example:
The manual's usual customers and orders, joined through a function instead of a
predicate:

```relix
Customers := [
| customer_id | name  |
|-------------|-------|
| 1           | Alice |
| 2           | Bob   |
| 3           | Cara  |
];

Orders := [
| order_id | customer_id | amount |
|----------|-------------|--------|
| 100      | 1           | 120    |
| 101      | 1           | 80     |
| 102      | 2           | 45     |
];

def ordersFor(cid: NUMBER) : RELATION := { σ customer_id = cid (Orders) };

query { Customers LATERAL ordersFor(customer_id) };
```

```
 customer_id  name   order_id  customer_id_r  amount
 ───────────  ─────  ────────  ─────────────  ──────
           1  Alice       100              1     120
           1  Alice       101              1      80
           2  Bob         102              2      45
(3 rows)
```

`ordersFor` was called once per customer, each time with that customer's own
`customer_id` — which is the whole difference from `ordersFor(1)`, a call that
would return the same rows no matter which row it was joined to. Cara produced no
rows and therefore contributes none: a lateral join is an inner join.

**The case a plain join cannot do.** Because the function body is evaluated per
outer row, a `λ` inside it means "per outer row", not "overall" — so the
top-order-per-customer question needs no window and no self-join:

```relix
def topOrderFor(cid: NUMBER) : RELATION := {
    λ 1 (τ amount DESC (σ customer_id = cid (Orders)))
};

query { Customers LATERAL topOrderFor(customer_id) };
```

```
 customer_id  name   order_id  customer_id_r  amount
 ───────────  ─────  ────────  ─────────────  ──────
           1  Alice       100              1     120
           2  Bob         102              2      45
(2 rows)
```

One row per customer who has any order, each the largest. Written as an ordinary
join with `λ 1` above it, the limit would apply to the joined result and return a
single row overall.

# Limitations:
- **Inner-join semantics only**: left rows with no TVF matches are dropped. There is
  no `LATERAL LEFT JOIN` form.
- **Arguments must be scalar and non-NULL**: an argument evaluating to NULL, or to a
  nested struct/array value, produces a runtime error naming the value's kind. There is
  no literal that denotes NULL, and substituting one that did not mean NULL would run
  the function on a value the outer row never held.
- **No pushdown**: LATERAL joins are always executed in-engine. The TVF body may
  itself reference pushed-down sources; only the LATERAL operator stays in-engine.
- **No recursion**: a TVF may not call itself (directly or transitively) via LATERAL.
- **A base relation is assumed stable for the duration of a query.** The rewrites
  above reuse one evaluation of the function body, which re-reads whatever tables it
  scans. That is the same assumption `×`, every hash join, `÷` and the set operations
  already make when they materialise one side and replay it.

# Alternatives:
A plain TVF call (`Relation ⋈ myFn(constant)`) works when the argument does not
depend on the outer row. Use LATERAL when the per-row column reference is the point.

# See Also:
[def … : RELATION](../language/def-relation.md), [natural-join](natural-join.md),
[theta-join](theta-join.md), [semi-join](semi-join.md)
