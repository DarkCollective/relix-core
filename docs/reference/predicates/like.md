# Name: Pattern Match (LIKE / NOT LIKE)

# Syntax:
attr LIKE "pattern"
attr NOT LIKE "pattern"
attr LIKE <string expression>     -- the pattern can also be a column or expression
attr LIKE "pattern" ESCAPE "c"    -- c makes the character after it literal

σ name LIKE "A%" (Customers)
σ email NOT LIKE "%@example.com" (Users)

# Description:
LIKE keeps rows where a string column matches a wildcard pattern. The pattern can
use `%` to stand for any sequence of characters (including none) and `_` to stand
for exactly one character. NOT LIKE keeps rows that do NOT match. This is the
standard way to filter by a partial string without needing a full regular expression.

# Technical Description:
LIKE forms a PatternPredicate over two operands: the value and the pattern, which
may be any string expression.
Wildcards: `%` translates to `.*` (any run of characters) and `_` to `.` (any
single character); all other regex metacharacters in the pattern are escaped, so the
pattern is treated as a literal substring except for `%` and `_`. Matching is
case-sensitive and anchored (the whole value must match). With `ESCAPE "c"`, the
character after each `c` matches only itself, so `"50!%"` with `ESCAPE "!"` is the
literal `50%`; an escape before an ordinary character makes it match itself too,
and a pattern that ends with its escape character is an error when it runs. The
escape is one character, written as a string literal. NULL on either side makes
the predicate UNKNOWN, and a selection keeps only rows that are true — so a NULL
value is dropped by `LIKE` and by `NOT LIKE` alike, rather than falling through to
the negative side.

# Examples:
Names starting with a capital A:
  σ name LIKE "A%" (Customers)

Email addresses that end with a specific domain:
  σ email LIKE "%@acme.com" (Users)

Exactly five-character product codes:
  σ code LIKE "_____" (Products)

Codes containing the substring "ERR":
  σ message LIKE "%ERR%" (Logs)

Exclude internal test accounts:
  σ email NOT LIKE "%@test.%" (Accounts)

Combine with other predicates:
  σ name LIKE "Smith%" ∧ active = true (Customers)

A literal percent sign, escaped:
  σ label LIKE "%!%%" ESCAPE "!" (Discounts)


# Worked Example:
The three wildcards side by side on one set of product codes:

```relix
Products := [
| sku       | name          |
|-----------|---------------|
| AB-1001   | Widget        |
| AB-1002   | Widget Large  |
| XY-1001   | Bracket       |
| AB-2001-X | Widget Deluxe |
];

query { σ sku LIKE "AB-%" (Products) };
```

```
 sku        name
 ─────────  ─────────────
 AB-1001    Widget
 AB-1002    Widget Large
 AB-2001-X  Widget Deluxe
(3 rows)
```

`%` stands for any run of characters, including none. Matching is **anchored** —
the whole value must match the pattern — which is why `"AB-%"` means "starts with
AB-" rather than "contains it". To ask for a substring, put a `%` on both ends.

`_` stands for exactly one character, so a pattern of fixed length filters by
width:

```relix
query { σ sku LIKE "AB-____" (Products) };
```

```
 sku      name
 ───────  ────────────
 AB-1001  Widget
 AB-1002  Widget Large
(2 rows)
```

Seven characters exactly, and the right seven. `XY-1001` is the correct length
but the wrong prefix; `AB-2001-X` has the right prefix but is too long. Both are
dropped, because an anchored pattern has to account for the whole value.

The pattern does not have to be a literal. It can be any string expression, such
as a column, so the patterns can come from data. Here each family's prefix lives
in a table of rules:

```relix
Rules := [
| family | pattern |
|--------|---------|
| AB     | AB-%    |
| XY     | XY-%    |
];

query { π family, sku (σ sku LIKE pattern (Products × Rules)) };
```

```
 family  sku      
 ──────  ─────────
 AB      AB-1001  
 AB      AB-1002  
 XY      XY-1001  
 AB      AB-2001-X
(4 rows)
```

A `%` or `_` that should match itself is escaped. Without `ESCAPE`, `"%0%"`
matches every label holding a zero; with it, `"%0!%%"` matches only a zero followed
by a percent sign:

```relix
Discounts := [
| label        |
|--------------|
| 10% off      |
| 100 points   |
| save_20      |
| save 20      |
];

query { σ label LIKE "%0!%%" ESCAPE "!" (Discounts) };
```

```
 label
 ───────
 10% off
(1 row)
```

`_` is escaped the same way: `"save!_%"` matches `save_20` and not `save 20`.

Left unescaped, `_` is easy to misread. `"%_%"`, written for "the labels that
contain an underscore", matches every label of one character or more. Analysis
warns, without refusing, when a pattern has no `ESCAPE` and every `_` in it stands
beside a `%`, where one is almost never meant as "any one character", and rewrites
the pattern:

```
Selection σ: '_' in LIKE matches any one character; to match an underscore,
write LIKE '%!_%' ESCAPE '!'
```

A `_` between other characters or at an end, as in `"A_C"`, `"___"` or `"AB-_"`, is
taken as meant.

# Limitations:
A pattern that is not a string literal is not pushed down to MongoDB, so that
predicate runs in the engine. SQL sources receive it as written. Matching is case-sensitive; use UCase() or LCase() on both sides for
case-insensitive matching:
  σ UCase(name) LIKE "ALICE%" (Users)

The only special characters are `%` (any run) and `_` (any one character), and
without `ESCAPE` no character escapes, so a backslash matches a backslash. The
escape character is a string literal one character long; `ESCAPE "!!"` is refused
as the script is read, and a column cannot supply it.

# Alternatives:
For an exact match, use the equality comparison `= "value"`. For membership in a
fixed set of values, use IN (`∈`). For more complex patterns such as word boundaries
or alternation, the InStr() function can test for substring presence.

# See Also:
[comparison](comparison.md), [in](in.md), [and](and.md), [select](../operators/select.md), [instr](../functions/string/instr.md), [ucase](../functions/string/ucase.md), [lcase](../functions/string/lcase.md)

# Notes:
LIKE predicates push down to SQL backends as native `LIKE` / `NOT LIKE` clauses —
except on SQLite, whose `LIKE` ignores case for ASCII letters whatever the column's
collation. There a literal pattern is rewritten as the case-sensitive `GLOB`, `%`
becoming `*` and `_` becoming `?` (`code LIKE 'AB-%'` is sent as `code GLOB 'AB-*'`),
and a pattern that is not a literal runs in the engine. SQL Server's `LIKE` reads `[`
as the start of a character class, so a literal pattern is sent with each `[` written
`[[]`, and a pattern that is not a literal runs in the engine there too. An `ESCAPE`
is sent as SQL's own `ESCAPE` clause; on SQLite and SQL Server, where the pattern is
rewritten, the escapes are resolved in the rewrite instead (an escaped `%` is sent to
SQL Server as `[%]`).
For MongoDB connections the pattern is converted to an anchored regular expression
and pushed as a `$regex` / `$not $regex` aggregation filter — the negated form
paired with a null check, since `$not` alone also matches a document that has no
such field, which is not what a NULL value means here. In both cases a
fallback to in-engine evaluation occurs when the operands are not translatable
(e.g. a computed expression on the left-hand side).
