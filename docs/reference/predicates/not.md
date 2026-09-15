# Name: Logical NOT (¬ / NOT)

# Syntax:
¬(<condition>)
NOT (<condition>)

σ ¬(status = "inactive") (Users)
σ NOT (status = "inactive") (Users)

# Description:
NOT flips a condition: it keeps the rows that do NOT satisfy it. Use it to
exclude — "everything except inactive users", "anything that isn't a refund".

# Technical Description:
¬ forms a NotPredicate and is the tightest-binding logical operator (applied
before AND/OR). Under three-valued logic, NOT TRUE = FALSE, NOT FALSE = TRUE, NOT
UNKNOWN = UNKNOWN — so negating a comparison against a NULL still yields UNKNOWN
and the row is dropped. A double negation is simplified away by the optimizer
(PRED-002).

# Examples:
Exclude a status:
  σ ¬(status = "inactive") (Users)

Negate a compound condition:
  σ NOT (region = "EMEA" ∧ amount < 100) (Orders)

Often clearer to use the opposite comparison:
  σ status != "inactive" (Users)   -- same intent, no NOT


# Worked Example:
Negation is the place NULL stops behaving like an ordinary value, so it is worth
seeing once. Cara has never ordered, so her `amount` after the outer join is NULL:

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
| 101      | 2           | 45     |
];

WithOrders := { Customers ⟕ Customers.customer_id = Orders.customer_id Orders };

query { σ amount > 100 (WithOrders) };
```

```
 customer_id  name   order_id  customer_id_r  amount
 ───────────  ─────  ────────  ─────────────  ──────
           1  Alice       100              1     120
(1 row)
```

```relix
query { σ ¬(amount > 100) (WithOrders) };
```

```
 customer_id  name  order_id  customer_id_r  amount
 ───────────  ────  ────────  ─────────────  ──────
           2  Bob        101              2      45
(1 row)
```

One row and one row, out of three. **Cara is in neither result.** `amount > 100`
is UNKNOWN for her — there is no amount to compare — and `¬UNKNOWN` is still
UNKNOWN, which a selection drops just as it drops false. Negating a condition does
not turn "we do not know" into "no".

This is worth internalising because it breaks an assumption that holds everywhere
else: `σ p` and `σ ¬p` do **not** partition a relation between them. If you need
the missing rows on one side, ask for them:

```relix
query { σ ¬(amount > 100) ∨ amount = NULL (WithOrders) };
```

```
 customer_id  name  order_id  customer_id_r  amount
 ───────────  ────  ────────  ─────────────  ──────
           2  Bob        101              2      45
           3  Cara  NULL      NULL           NULL
(2 rows)
```

Now the two halves add up.

# Limitations:
NOT does not turn NULLs into matches — `NOT (x = 5)` excludes rows where x is
NULL. To include NULLs, add an explicit `∨ x = NULL`. Parenthesise the operand.

# Alternatives:
Use the direct negative comparison (≠, <, >) where possible. For "no match in
another relation" an ANTI join (▷) is the relational form of NOT EXISTS.

# See Also:
[and](and.md), [or](or.md), [comparison](comparison.md), [anti-join](../joins/anti-join.md), [is-null](is-null.md)

# Notes:
`¬(¬ p)` collapses to `p`; the optimizer removes such double negations.
