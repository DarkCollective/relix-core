# Name: LPAD (pad on the left)

# Syntax:
LPAD(<string>, <length> [, <fill>])

π LPAD(CStr(order_id), 8, '0') → padded (Orders)

# Description:
LPAD brings a string to a given length by adding characters on its left, spaces
unless you name others: `LPAD('42', 5, '0')` is `'00042'`. A string longer than the
length is cut to it, from its end, as SQL's LPAD does.

# Technical Description:
LPAD(s: STRING, length: NUMBER [, fill: STRING]) → STRING. Lengths count
characters. The fill repeats and is cut to fit; an empty fill leaves a short string
as it is, and a length of zero or less gives `''`. A NULL in any argument returns
NULL. PURE, DETERMINISTIC.

# Examples:
Zero-padded codes:
  π LPAD(CStr(order_id), 8, '0') → padded (Orders)

Right-aligned names in a fixed width:
  π LPAD(name, 20) → name (Users)

# Pushdown:
SQL: `LPAD(…)` on PostgreSQL. MySQL's LPAD returns NULL for a negative length, so
there and elsewhere it runs in the engine.

# See Also:
[rpad](rpad.md), [repeat](repeat.md), [left](left.md)
