# Name: Null Test (⊥ / NULL)

# Syntax:
attr = ⊥   attr = NULL   attr IS NULL       -- IS NULL
attr ≠ ⊥   attr != NULL  attr IS NOT NULL   -- IS NOT NULL

σ email = NULL (Users)
σ email IS NULL (Users)
σ email IS NOT NULL (Users)

# Description:
The null test checks whether a value is missing. Comparing a column to ⊥ (or the
ASCII keyword NULL, or the SQL-prior `IS NULL`) keeps rows where the value is
absent; the not-equal / `IS NOT NULL` form keeps rows where a value is present.
This is how you find records with missing fields, or filter them out.

# Technical Description:
`attr = ⊥` / `attr = NULL` / `attr IS NULL` form a NullPredicate testing IS NULL;
`attr ≠ ⊥` / `attr != NULL` / `attr IS NOT NULL` test IS NOT NULL — all three
spellings parse to the identical AST. This is distinct from an
ordinary comparison:
because a normal `= value` against a NULL yields UNKNOWN, missing values can only
be detected with the dedicated null test. ⊥ is U+22A5.

# Examples:
Rows with a missing email:
  σ email = NULL (Users)

Rows that DO have a value:
  σ phone != NULL (Contacts)

Combine — present email but missing phone:
  σ email != NULL ∧ phone = NULL (Contacts)

Find unmatched rows after a left outer join:
```relix
σ Orders.order_id = NULL
  (Customers ⟕ Customers.customer_id = Orders.customer_id Orders)
```


# Worked Example:
A left outer join is the usual way a NULL appears in the middle of a query, so it
is also the clearest place to see what a null test does. Cara has never ordered:

```relix
Customers := [
| customer_id | name  | city   |
|-------------|-------|--------|
| 1           | Alice | London |
| 2           | Bob   | Berlin |
| 3           | Cara  | Lisbon |
];

Orders := [
| order_id | customer_id | amount |
|----------|-------------|--------|
| 100      | 1           | 120    |
| 101      | 1           | 80     |
| 102      | 2           | 45     |
];

WithOrders := { Customers ⟕ Customers.customer_id = Orders.customer_id Orders };

query { σ order_id = NULL (WithOrders) };
```

```
 customer_id  name  city    order_id  customer_id_r  amount
 ───────────  ────  ──────  ────────  ─────────────  ──────
           3  Cara  Lisbon  NULL      NULL           NULL
(1 row)
```

That is the "customers who have never ordered" idiom: outer join, then keep the
rows the right side failed to fill in.

**Why an ordinary comparison cannot do this.** A comparison never matches a NULL,
whichever way round it is written. Ask for the rows whose amount *is* 120:

```relix
query { σ amount = 120 (WithOrders) };
```

```
 customer_id  name   city    order_id  customer_id_r  amount
 ───────────  ─────  ──────  ────────  ─────────────  ──────
           1  Alice  London       100              1     120
(1 row)
```

then for the rows whose amount is *not* 120:

```relix
query { σ amount ≠ 120 (WithOrders) };
```

```
 customer_id  name   city    order_id  customer_id_r  amount
 ───────────  ─────  ──────  ────────  ─────────────  ──────
           1  Alice  London       101              1      80
           2  Bob    Berlin       102              2      45
(2 rows)
```

One row and two rows — and Cara is in neither, though every row of `WithOrders`
is in one or the other of those two questions as a human would read them. Her
amount is missing, so neither "is it 120?" nor "is it something else?" has an
answer, and `σ` keeps only rows that answer *yes*. A missing value is invisible to
comparison, which is why the null test exists.


# Limitations:
You must use the null test for missing values — an ordinary `= value` comparison
never matches a NULL (it is UNKNOWN). The Unicode ⊥, the keyword NULL, and the
SQL-prior `IS [NOT] NULL` are interchangeable.

# Alternatives:
The IsNull() scalar function returns a BOOLEAN you can use in expressions; Nz()
and Coalesce() substitute a default for a NULL.

A null test also applies to a **parenthesised condition**, and that is how you ask
whether a comparison came out UNKNOWN rather than false:

    (amount > 100) = ⊥        -- the comparison could not be decided

A null test on a *value* asks whether the value is missing; a null test on a
*condition* asks about its truth value, which is NULL exactly when the condition is
UNKNOWN. The two stop being the same question as soon as the condition reads more
than one column. `IsNull((amount > 100))` is the same test written as a function
call, for a place that wants a BOOLEAN rather than a predicate.

It matters because negation does not give back the rows a filter dropped.
`σ ¬(amount > 100)` keeps the rows whose amount is 100 or less and **not** the
rows whose amount is missing, because ¬UNKNOWN is UNKNOWN and a selection keeps
only what is true. To keep both:

```relix
σ ¬(amount > 100) ∨ (amount > 100) = ⊥ (Orders)
```

For a condition over one column you can spell the same thing with a null test on
that column (`amount = NULL ∨ ¬(amount > 100)`). Testing the condition itself is
the general form, and the one that stays right as the condition grows: a comparison
between two columns is UNKNOWN when *either* side is missing, and a conjunction is
UNKNOWN in more cases than either of its parts.

# See Also:
[comparison](comparison.md), [isnull](../functions/typecheck/isnull.md), [nz](../functions/conditional/nz.md), [coalesce](../functions/conditional/coalesce.md), [left-outer-join](../joins/left-outer-join.md)

# Notes:
The "find the missing ones" idiom — outer join then `σ key = NULL` — is the most
common use; an ANTI join expresses the same thing more directly.
