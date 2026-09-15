# Name: Set Difference (− / DIFF / MINUS / EXCEPT)

# Syntax:
Relation1 − Relation2
Relation1 DIFF Relation2
Relation1 MINUS Relation2      -- SQL-prior synonym (Oracle-style)
Relation1 EXCEPT Relation2     -- SQL-prior synonym (Postgres-style)

AllCustomers − CustomersWhoOrdered

# Description:
Difference keeps the rows that are in the first relation but NOT in the second.
It is the "what's left after removing these" operator — for example all
customers minus those who ordered gives customers who never ordered. Both
relations must have the same number of columns, with compatible types position by
position; what the columns are called need not match.

Note the symbol is the true minus sign − (U+2212), not the ASCII hyphen; use the
keyword DIFF (or the SQL-prior MINUS / EXCEPT) if that is easier to type. The
MINUS keyword is set difference; the arithmetic `-` operator inside expressions
is unaffected.

# Technical Description:
Compatibility and row matching are both **positional**: a row of R is removed when
S holds the same values in the same order, whatever each side calls its columns.

R − S requires union-compatible schemas and returns the rows of R that do not
appear in S (set semantics). It is a blocking ([set]) operator. In recursion it
is non-monotone, so it is forbidden in the recursive step of a FIX.

# Examples:
Customers who never ordered (set-style):
  AllCustomers − CustomersWhoOrdered

Products in the catalogue but not in stock:
  (π sku (Catalogue)) − (π sku (InStock))

Which expected files are missing from the upload:
  ExpectedFiles − UploadedFiles

# Worked Example:
Two mailing lists with one row in common — `bo@example.com`, who appears in
both, identically. Every set operation on this page is the same question about
that overlap, asked differently.

```relix
Subscribers := [
| email           | plan |
|-----------------|------|
| ada@example.com | pro  |
| bo@example.com  | free |
| cy@example.com  | pro  |
];

TrialUsers := [
| email           | plan  |
|-----------------|-------|
| bo@example.com  | free  |
| dee@example.com | trial |
];

query { Subscribers − TrialUsers };
```

```
 email            plan
 ───────────────  ────
 ada@example.com  pro
 cy@example.com   pro
(2 rows)
```

The subscribers who are not also trial users. Bo is removed; Dee is **not**
added — difference only ever removes rows from the left, never contributes from
the right, so the result is a subset of `Subscribers`. Reverse the operands and
you get a different answer (`dee@example.com` alone).

# Limitations:
Both sides must be union-compatible. To "subtract" based on a key while keeping
extra columns from the left, an ANTI join (▷) is usually the better tool.

# Alternatives:
Anti join (▷) when matching is by key and you want to keep the left's full
columns. Symmetric difference (∆) for "in one or the other but not both".

# See Also:
[anti-join](../joins/anti-join.md), [union](union.md), [intersection](intersection.md), [symmetric-difference](symmetric-difference.md)

# Notes:
Because difference is non-monotone it cannot appear in a recursive FIX step — use
monotone operators there.
