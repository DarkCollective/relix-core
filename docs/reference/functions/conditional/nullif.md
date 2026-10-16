# Name: NULLIF (value-to-null)

# Syntax:
NULLIF(<value>, <sentinel>)

π id, NULLIF(phone, 'n/a') → phone (Contacts)

# Description:
NULLIF turns a placeholder back into a missing value: it returns NULL where the
value equals the sentinel, and the value otherwise. It is the inverse of Nz, and
the usual way to stop a stand-in such as `-999`, `0` or `'n/a'` from being counted,
summed or averaged as though it were real.

# Technical Description:
NULLIF(value, sentinel) → the type of value. NULL when value equals sentinel under
the engine's equality (the one `=` uses, so `5` equals `5.0`), else value. A NULL
value returns NULL; a NULL sentinel equals nothing, so value comes back. Values that
cannot be compared, a number and a word, are not equal. PURE, DETERMINISTIC.

# Examples:
A placeholder phone number becomes missing:
  π id, NULLIF(phone, 'n/a') → phone (Contacts)

Avoid dividing by zero — a zero divisor gives NULL rather than an error:
  π order_id, amount / NULLIF(qty, 0) → unit_price (Orders)

# Worked Example:
A sensor writes `-999` when it has no reading. Averaged as it stands, the
placeholder drags each mean far below anything the sensor measured:

```relix
Readings := [
| sensor | value |
|--------|-------|
| a      | 21    |
| a      | -999  |
| a      | 23    |
| b      | -999  |
| b      | 18    |
];

query { τ sensor (γ sensor, AVG(value) → naive, AVG(NULLIF(value, -999)) → mean,
                  COUNT(NULLIF(value, -999)) → readings (Readings)) };
```

```
 sensor  naive            mean  readings
 ──────  ───────────────  ────  ────────
 a       -318.3333333333    22         2
 b                -490.5    18         1
(2 rows)
```

AVG and COUNT skip NULLs, so once the placeholder is NULL it is no longer a value:
the mean is over real readings and the count says how many there were.

# Pushdown:
SQL: folds to `NULLIF(<value>, <sentinel>)` on PostgreSQL, DuckDB, SQLite and Db2.
MySQL's and SQL Server's default collations ignore case, so their `NULLIF('a', 'A')`
is NULL where the engine's is `'a'`; there, and on an unidentified backend, it runs
in the engine.

# Alternatives:
Nz and Coalesce go the other way, from NULL to a value. `IIf(c, x, NULL)` keeps a
value only where a condition holds.

# See Also:
[nz](nz.md), [coalesce](coalesce.md), [iif](iif.md), [is-null](../../predicates/is-null.md)
