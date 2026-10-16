# Name: Arithmetic and concatenation

# Syntax:
<x> + <y>    <x> - <y>    <x> * <y>    <x> / <y>    -<x>
<x> % <y>                 -- SQL's remainder: Mod(x, y)
<s> + <t>    <s> || <t>   -- two strings joined

π name, price * qty → total, qty % 12 → loose, first || ' ' || last → full (Orders)

# Description:
Arithmetic works on numbers as written: `price * qty`, `total - discount`,
`amount / 100`. A minus sign in front of a value negates it. `+` between two strings
joins them, so `first + ' ' + last` is a full name.

Two SQL spellings are read too. `||` is SQL's way of joining strings, and `x % y` is
the remainder of dividing x by y. Write whichever you are used to: `a || b` is
`a + b`, and `x % y` is `Mod(x, y)`.

# Technical Description:
`*`, `/` and `%` bind tighter than `+` and `-`, which bind tighter than `||`. Each
level associates to the left, and parentheses group as usual. `||` binds more loosely
than `+` as it does in PostgreSQL, so `'n = ' || n + 1` adds before it joins.

The arithmetic is exact decimal: `0.1 + 0.2` is `0.3`. Division keeps ten places and
drops trailing zeros, and dividing by zero is an error. A NULL on either side makes
the result NULL, as in SQL. `+`, `-`, `*` and `/` also apply to dates, times and
durations, a month added on the calendar, so `DATE '2026-01-31' + INTERVAL '1 month'`
is 28 February; see the duration literal page.

`a || b` parses to `a + b`, so it is the same expression from then on and is printed
back as `+`. It joins two strings and adds two numbers. To join a number to text,
convert it first: `'#' || CStr(id)`.

`x % y` parses to `Mod(x, y)` and is that function in every respect: the remainder is
exact and takes the sign of x, so `-7 % 3` is `-1`, and a zero divisor is an error.

# Examples:
A unit price and the amount with tax:
  π order_id, amount / qty → unit_price, amount * 1.2 → gross (Orders)

Join two columns with SQL's operator:
  π first || ' ' || last → full_name (People)

Every third row, by id:
  σ id % 3 = 0 (Rows)

# Worked Example:
A label built with `||`, and the boxes of twelve an order fills, with what is left:

```relix
Orders := [
| id | item   | qty |
|----|--------|-----|
| 1  | apples | 30  |
| 2  | pears  | 12  |
| 3  | plums  | 7   |
];

query { π id, '#' || CStr(id) || ' ' || item → label, Fix(qty / 12) → boxes,
          qty % 12 → loose (Orders) };
```

```
 id  label      boxes  loose
 ──  ─────────  ─────  ─────
  1  #1 apples      2      6
  2  #2 pears       1      0
  3  #3 plums       0      7
(3 rows)
```

`CStr(id)` turns the number into text first; `#` || id would add a string to a number
and is refused.

# Pushdown:
`+`, `-`, `*` and `/` are sent to SQL as themselves, and string `+` as the dialect's
concatenation. `%` is Mod, and is pushed down where Mod is.

# Limitations:
`||` is `+`, so between two numbers it adds rather than joining their digits. There
is no exponent operator; use Power.

# See Also:
[mod](../functions/math/mod.md), [cstr](../functions/conversion/cstr.md),
[power](../functions/math/power.md), [literals](../literals/scalar-literals.md),
[duration literal](../literals/duration-literal.md), [operator spellings](spellings.md)
