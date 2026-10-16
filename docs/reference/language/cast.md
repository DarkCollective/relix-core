# Name: CAST (convert a value to a type)

# Syntax:
CAST(<operand> AS <type>)

<type> ::= NUMBER | STRING | BOOLEAN | DATE | TIME | TIMESTAMP | TIMESTAMPTZ
         | TIMESTAMP WITH TIME ZONE

π id, CAST(amount_text AS NUMBER) → amount (Imports)

# Description:
CAST converts a value to another type: text to a number, a timestamp to its date,
`'yes'` to TRUE. It is SQL's spelling, and it reads as the conversion functions do,
except that a missing value stays missing: `CAST(NULL AS NUMBER)` is NULL.

# Technical Description:
`CAST(x AS type)` parses to the conversion it stands for:

| Type | Conversion |
|------|------------|
| `NUMBER` | `CDbl(x)`: text is parsed, TRUE and FALSE are 1 and 0 |
| `STRING` | `CStr(x)` |
| `BOOLEAN` | `CBool(x)`: zero is FALSE, other numbers TRUE; `t`, `yes`, `on`, `1` and their opposites |
| `DATE` | `to_date(x)`: ISO text, a timestamp's date at UTC, or a TIMESTAMPTZ's local date |
| `TIME` | `to_time(x)`: ISO text, a timestamp's time of day at UTC, or a TIMESTAMPTZ's local time |
| `TIMESTAMP` | `to_timestamp(x)`: ISO text, a date's midnight at UTC, or a TIMESTAMPTZ's instant |
| `TIMESTAMPTZ`, `TIMESTAMP WITH TIME ZONE` | `to_timestamptz(x)`: ISO text keeping its offset, or a timestamp at UTC |

CStr, CDbl and CBool refuse a NULL, so a cast to NUMBER, STRING or BOOLEAN is written
`IIf(x IS NULL, NULL, CDbl(x))` and prints back that way; the temporal conversions
pass a NULL through themselves. A value the type cannot hold is an error naming it,
such as `CDbl: cannot convert "n/a" to a number`. The type is a word, in any case.

# Examples:
Text imported from a CSV, read as numbers and dates:
  π id, CAST(qty_text AS NUMBER) * CAST(price_text AS NUMBER) → total (Imports)

A timestamp's calendar day:
  γ CAST(placed_at AS DATE) → day, COUNT(*) → orders (Orders)

# Worked Example:
A raw import with a missing quantity, a yes/no flag and a timestamp:

```relix
Raw := [
| id | qty | active | placed_at            |
|----|-----|--------|----------------------|
| 1  | 3   | yes    | 2026-07-08T13:40:30Z |
| 2  |     | no     | 2026-07-09T08:00:00Z |
];

query { π id, CAST(qty AS NUMBER) * 2 → twice, CAST(placed_at AS DATE) → day (Raw) };
```

```
 id  twice  day
 ──  ─────  ──────────
  1      6  2026-07-08
  2  NULL   2026-07-09
(2 rows)
```

The missing quantity casts to NULL, and doubling it leaves it NULL. The flag reads
as a boolean:

```relix
query { π id (σ CAST(active AS BOOLEAN) = TRUE (Raw)) };
```

```
 id
 ──
  1
(1 row)
```

A type CAST does not know is a syntax error:

```relix-invalid
query { π CAST(amount AS MONEY) → m (Orders) };
```

# Limitations:
The six types above. There is no cast to DURATION, to a nested type, or to SQL's
sized types such as `VARCHAR(10)` or `DECIMAL(10, 2)`: use NUMBER and STRING, and
Round for a number of places.

# See Also:
[cdbl](../functions/conversion/cdbl.md), [cstr](../functions/conversion/cstr.md), [cbool](../functions/conversion/cbool.md), [to_date](../functions/datetime/to_date.md), [to_time](../functions/datetime/to_time.md), [to_timestamp](../functions/datetime/to_timestamp.md), [EXTRACT](extract.md)
