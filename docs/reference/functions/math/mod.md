# Name: Mod (remainder)

# Syntax:
Mod(<x>, <y>)

π Mod(ticket, 4) → lane (Tickets)

# Description:
Mod returns the remainder of dividing x by y — Mod(7, 3) is 1. Use it to wrap a
number into a range (a 0-based bucket, a day-of-week, a grid coordinate) and to test
divisibility, where Mod(x, n) = 0 means n divides x.

The remainder is truncated towards zero, so its sign follows the dividend:
Mod(-7, 3) is -1, not 2. That is the same convention SQL's MOD and the remainder
operator use. For a result that is always in the range 0 to n-1 with a positive
modulus, write Mod(Mod(x, n) + n, n).

# Technical Description:
Mod(x: NUMBER, y: NUMBER) → NUMBER. Returns the exact decimal remainder of x divided
by y, truncated towards zero (the companion of Fix, so x = y * Fix(x / y) + Mod(x, y)).
A NULL in either argument returns NULL. A zero divisor is rejected. PURE, DETERMINISTIC.

# Examples:
A zero-based bucket — map each id onto one of four lanes:
  π id, Mod(id, 4) → lane (Jobs)

Keep only the even rows:
  σ Mod(n, 2) = 0 (Rows)

Extract a bit-field — the low byte of a value, then the next three bits up:
  π Mod(w, 256) → low_byte, Mod(Int(w / 256), 8) → next_three (Seeds)

# Limitations:
NUMBER arguments; NULL in either → NULL out. A zero divisor is an error, not NULL.
Pushed down as MOD(x, y) to Postgres, DuckDB, Db2 and an unidentified SQL backend, and
as $mod in MongoDB; computed in the engine for MySQL (whose zero-divisor result depends
on sql_mode), SQLite (whose % truncates operands to integers) and SQL Server (which has
only the % operator).

# Alternatives:
Int and Fix for the quotient the remainder is left over from; Fix is the one whose
quotient pairs with this remainder on negatives.

# See Also:
[int](int.md), [fix](fix.md), [power](power.md)
