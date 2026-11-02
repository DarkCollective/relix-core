# Name: COLLATE (compare, sort and group strings under a collation)

# Syntax:
<string> COLLATE BINARY
<string> COLLATE NOCASE
<string> COLLATE '<locale>'

σ name COLLATE NOCASE = 'ada' (Users)
τ name COLLATE 'en-US' (Users)
γ name COLLATE NOCASE, COUNT(*) → n (Users)

# Description:
Strings compare by code point, so case matters: `'Ada' = 'ada'` is false, and `'B'`
sorts before `'a'`. A collation says otherwise for one comparison, sort or grouping.
`NOCASE` ignores case, so `'Ada'`, `'ada'` and `'ADA'` are one name. A locale such as
`'en-US'` or `'de'` orders strings as that language's dictionary does, case aside and
`É` beside `E`. `BINARY` is the code-point order every comparison has without one,
written out.

It is how a query from a database whose default collation ignores case, as MySQL's and
SQL Server's do, keeps its meaning here.

# Technical Description:
`COLLATE` follows the value it applies to and binds tightest: `a || b COLLATE NOCASE`
collates `b`, and `(a || b) COLLATE NOCASE` the whole. It applies in five places, and
analysis refuses it anywhere else:

- **A comparison**, either side: `=`, `≠`, `<`, `≤`, `>`, `≥`, and so `BETWEEN`. The
  comparison is made under the collation written on either side; two sides written
  under different ones are refused.
- **IN**, on its subject: `s COLLATE NOCASE IN {'ada', 'bob'}`.
- **LIKE**, on either side, under `BINARY` or `NOCASE`: a locale has no rule for a
  wildcard.
- **A sort key**: `τ`, `TOP`, a window's order. Two strings the collation calls equal
  are a tie, and so peers to a ranking.
- **A grouping key**: every string the collation calls equal is one group, and the group
  shows its least member by code point, `'ADA'` for `'Ada'`, `'ada'` and `'ADA'`, as a
  database answers `GROUP BY LOWER(s)` with `MIN(s)`.

`NOCASE` calls two strings equal when they are equal lower-cased in the root locale,
and orders them by their lower-cased code points. A locale is any language Java's
`java.text.Collator` has, named by its tag, `'en-US'`, or with an underscore, `'en_US'`.
The value is the string's own: a collation changes how it compares, not what it is. It
applies to a string; a NULL compares as NULL does without one.

# Examples:
A name matched ignoring case:
  σ name COLLATE NOCASE = 'ada' (Users)

Names in dictionary order:
  τ name COLLATE 'en-US' (Users)

Customers whose name starts with an a of either case:
  σ name COLLATE NOCASE LIKE 'a%' (Users)

One row per name, whatever its case:
  γ name COLLATE NOCASE, COUNT(*) → n (Users)

# Worked Example:
A sign-up list where one person registered three times, each in a different case:

```relix
Signups := [
| id | name  |
|----|-------|
| 1  | Ada   |
| 2  | ada   |
| 3  | Bob   |
| 4  | ADA   |
| 5  | Émile |
];

query { γ name COLLATE NOCASE, COUNT(*) → signups (Signups) };
```

```
 name   signups
 ─────  ───────
 ADA          3
 Bob          1
 Émile        1
(3 rows)
```

Sorted by code point, the capitals come first and `É` after every unaccented letter; under `'en-US'`
the names read as a dictionary lists them:

```relix
query { π name (τ name COLLATE 'en-US' (Signups)) };
```

```
 name
 ─────
 ada
 Ada
 ADA
 Bob
 Émile
(5 rows)
```

# Pushdown:
SQL: a comparison under `BINARY` is sent as written, in the exact comparison the
connection already renders strings with. `NOCASE` and a locale run in the engine over
the rows the database returns: a database's case-insensitive collations fold `ß`,
accents and dotted capitals by rules of their own, so none answers as the engine does.
A grouping or join written under a collation runs in the engine too.

# Limitations:
A collation belongs to the comparison it is written in, not to a column: a projection
does not carry one forward, and `δ`, the set operations and a natural join compare
strings by code point. Write a case-insensitive distinct as a grouping,
`γ name COLLATE NOCASE, COUNT(*) → n (R)`. A join condition written under a collation compares row by
row rather than by a hash of its keys.

# Alternatives:
`LCase(a) = LCase(b)` compares ignoring case and gives the lower-cased value, and
`s ~* '^ada$'` matches a regular expression ignoring case.

# SQL names:
`s COLLATE "C"` in PostgreSQL and `COLLATE utf8mb4_bin` in MySQL are `BINARY`;
SQLite's `COLLATE NOCASE` is `NOCASE`, which here folds every letter rather than ASCII
alone; SQL Server's `Latin1_General_CI_AS` and MySQL's `utf8mb4_0900_ai_ci` ignore
case and accents, which `NOCASE` does not.

# See Also:
[comparison](../predicates/comparison.md), [sort](../operators/sort.md), [group](../operators/group.md), [like](../predicates/like.md)
