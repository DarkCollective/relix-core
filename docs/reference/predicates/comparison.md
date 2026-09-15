# Name: Comparison Operators (= ≠ < ≤ > ≥)

# Syntax:
attr = value      attr != value   (or  attr ≠ value)
attr < value      attr > value
attr <= value     attr >= value   (or  attr ≤ / ≥ value)

σ age >= 18 (Users)
σ status != "cancelled" (Orders)

# Description:
Comparison operators are the building blocks of conditions in selection (σ) and
join conditions. They test how one value relates to another: equal, not equal,
less than, greater than, and the "or equal to" variants. The values can be
columns, numbers, strings, booleans, or temporal values.

# Technical Description:
Comparisons form ComparisonPredicates over two operands. = and ≠ test equality;
< ≤ > ≥ require ordered types and work on numbers, strings (lexicographic), and
temporal types (one temporal type on both sides, or a temporal against a
text cell holding its ISO-8601 form). A comparison involving a NULL evaluates to
UNKNOWN (three-valued logic), so the row is dropped by σ. ASCII forms: != for ≠,
<= for ≤, >= for ≥.

# Examples:
Numeric thresholds:
  σ age >= 18 (Users)

And the strict form:
  σ price < 100 (Products)

Exact text match:
  σ status = "active" (Orders)

Mismatch — note that a NULL status matches neither form:
  σ status != "cancelled" (Orders)

Boolean flag:
  σ verified = true (Accounts)

Temporal comparison — created_at may be a TIMESTAMP column, or a text column
holding ISO-8601 timestamps (as every inline-table, CSV, and JSON cell is):
  σ created_at >= TIMESTAMP '2026-01-01T00:00:00Z' (Events)

Compare two columns:
  σ spent > budget (Departments)

# Limitations:
Ordered comparisons require both operands to be the same comparable type — you
cannot compare a NUMBER to a STRING, or two different temporal types (a DATE
against a TIMESTAMP). Text that spells a value counts as that value, not as a
STRING, so a temporal-shaped or boolean-shaped string compares with the typed
form; by the same rule it does *not* compare with plain text, and ordering
"2024-01-15" against "pending" is a DATE against a STRING and is an error. Cast
the column to settle what it holds. NULL on either side makes the comparison
UNKNOWN (row excluded); test for NULL explicitly with = NULL / = ⊥.

# Alternatives:
For "is this missing" use the NULL test (= ⊥ / = NULL). For set membership use
IN (∈).

# See Also:
[select](../operators/select.md), [and](and.md), [or](or.md), [not](not.md), [is-null](is-null.md), [in](in.md)

# Notes:
String comparison is lexicographic; temporal comparison is chronological. Which
of the two a text cell gets is decided by what the text spells: a cell holding
`2024-01-15T11:00:00+01:00` is the instant it denotes and is ordered
chronologically, so it sorts *before* `2024-01-15T10:30:00Z` even though it sorts
after it as text. Only text that spells no value is ordered as text.

A comparison is a boolean **value**, so besides forming the condition of σ / a
join it may appear in **operand position** — most usefully as a function argument.
That is what lets a conditional receive its test directly, e.g.
`IIf(price > 100, "expensive", "cheap")`. The comparison (optionally combined with
∧ / ∨ / ¬, or written as IN / LIKE / `= ⊥`) is evaluated per row to true/false with
the same semantics σ uses. A bare column argument stays a plain operand — the
comparison form is only entered when a comparison-style operator follows.

A boolean-shaped STRING **is** a BOOLEAN. Inline-table, CSV, and JSON cells
holding `true`/`false` infer as STRING, but a query naturally writes
`= true`/`= false` (a boolean literal), so the text is read as the boolean it
names, case-insensitively — `true`/`TRUE`/`True` are one value. Hence
`σ is_active = true (Rules)` matches a row whose `is_active` cell is the string
`"true"`. A string that names no boolean stays a STRING and simply does not match
(`=`/`≠` never error, whatever the types). This mirrors the string→temporal
reading for ISO-8601 cells.

What a string means does **not** depend on what it is compared against: it is read
the same way whether its partner is typed, another string, or nothing at all (a
hash-join key is canonicalised before it is known which row it will meet). That is
what makes the ordering a genuine one — under the older, partner-dependent rule a
TIMESTAMP equalled two spellings that did not equal each other, which left the
result of `τ` over such a column dependent on the order the rows arrived in.
