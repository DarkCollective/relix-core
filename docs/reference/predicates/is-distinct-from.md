# Name: Null-safe Comparison (IS [NOT] DISTINCT FROM)

# Syntax:
a IS DISTINCT FROM b
a IS NOT DISTINCT FROM b

σ shipped_to IS DISTINCT FROM billed_to (Orders)
σ Old.email IS NOT DISTINCT FROM New.email (Old ⨝ Old.id = New.id New)

# Description:
`IS DISTINCT FROM` asks whether two values differ, counting a missing value as a
value of its own: two NULLs are not distinct, and a NULL is distinct from any
present value. `IS NOT DISTINCT FROM` is the opposite, an equality under which
NULL equals NULL. They are the comparison to use when "missing on both sides" is a
match, as when comparing two versions of a record.

# Technical Description:
The answer is always true or false, never UNKNOWN, which is the difference from
`≠` and `=`. `a IS DISTINCT FROM b` is true when exactly one of the two is NULL,
or neither is and they differ. `a IS NOT DISTINCT FROM b` is true when both are
NULL, or neither is and they are equal. Each parses to null tests and a comparison
joined by ∧ and ∨, written so that no part of it can be UNKNOWN:
`(a = b ∧ a ≠ ⊥ ∧ b ≠ ⊥) ∨ (a = ⊥ ∧ b = ⊥)` for `IS NOT DISTINCT FROM`. There is
no separate node, so pushdown sends that expansion, which every backend reads
alike. MySQL's `<=>` is the same test as `IS NOT DISTINCT FROM`.

# Examples:
Records whose email changed, including one added or removed:
  σ Old.email IS DISTINCT FROM New.email (Old ⨝ Old.id = New.id New)

A null-safe join condition:
  Left ⨝ Left.code IS NOT DISTINCT FROM Right.code Right

# Worked Example:
Two snapshots of a contact list, compared field by field. A plain `≠` misses the
two rows where one side is missing:

```relix
Contacts := [
| id | before        | after         |
|----|---------------|---------------|
| 1  | ann@a.example | ann@a.example |
| 2  | bo@b.example  | bo@c.example  |
| 3  | NULL          | cy@d.example  |
| 4  | di@e.example  | NULL          |
| 5  | NULL          | NULL          |
];

query { σ before ≠ after (Contacts) };
```

```
 id  before        after
 ──  ────────────  ────────────
  2  bo@b.example  bo@c.example
(1 row)
```

```relix
query { σ before IS DISTINCT FROM after (Contacts) };
```

```
 id  before        after
 ──  ────────────  ────────────
  2  bo@b.example  bo@c.example
  3  NULL          cy@d.example
  4  di@e.example  NULL
(3 rows)
```

Contact 5 is missing on both sides, so nothing changed, and
`IS NOT DISTINCT FROM` keeps it with the unchanged contact 1:

```relix
query { σ before IS NOT DISTINCT FROM after (Contacts) };
```

```
 id  before         after
 ──  ─────────────  ─────────────
  1  ann@a.example  ann@a.example
  5  NULL           NULL
(2 rows)
```

# Limitations:
Both sides are evaluated more than once. `DISTINCT FROM` must follow `IS`
directly:

```relix-invalid
query { σ before DISTINCT FROM after (Contacts) };
```

# Alternatives:
`=` and `≠` when NULL should match nothing. The null test (`IS NULL`) for a
single value.

# See Also:
[comparison](comparison.md), [is-null](is-null.md), [or](or.md), [and](and.md)
