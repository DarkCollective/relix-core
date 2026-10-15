# Name: SPLIT_PART (one field of a delimited string)

# Syntax:
SPLIT_PART(<string>, <delimiter>, <n>)

π SPLIT_PART(email, '@', 2) → domain (Users)

# Description:
SPLIT_PART cuts a string at each occurrence of a delimiter and gives one of the
pieces: `SPLIT_PART('a,b,c', ',', 2)` is `'b'`. A negative number counts from the
end, so -1 is the last piece.

# Technical Description:
SPLIT_PART(s: STRING, delimiter: STRING, n: NUMBER) → STRING. Fields count from 1,
or from the end for a negative n; past the last field the result is `''`. An empty
delimiter leaves one field, the whole string. A field number of zero is an error.
The delimiter is matched exactly, case included. A NULL in any argument returns
NULL. PURE, DETERMINISTIC.

# Examples:
The domain of an address:
  π SPLIT_PART(email, '@', 2) → domain (Users)

The last segment of a path:
  π SPLIT_PART(code, '/', -1) → leaf (Products)

# Pushdown:
SQL: `SPLIT_PART(…)` on PostgreSQL, which counts from the end for a negative n from
version 14; in the engine elsewhere.

# See Also:
[strpos](strpos.md), [substring](substring.md)
