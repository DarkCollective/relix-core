# Name: String, number and boolean literals

# Syntax:
"<text>"          -- a string, in double quotes
'<text>'          -- the same string, in single quotes
<digits>[.<digits>][e[±]<digits>]   -- a number: 42, 3.25, 0.5, 1e6, 2.5E-3
TRUE | FALSE      -- a boolean, in any case

π name, "in stock" → status, price * 1.2 → gross, TRUE → listed (Products)

# Description:
A literal is a value written straight into a query: a piece of text to compare a
column with, a number to multiply by, a flag to add. Text goes in quotes, either
double or single, numbers are written as plain digits, and the two booleans are
TRUE and FALSE. Dates, times, timestamps and durations have literals of their own,
written as a keyword followed by quoted text, such as DATE '2026-01-31'.

# Technical Description:
A string literal is text between two double quotes or two single quotes. The two forms
are the same string, so `"Asha"` and `'Asha'` compare equal. Inside either, a backslash
escapes the next character: `\"` and `\'` write a quote, `\\` a backslash, `\n` a
newline and `\t` a tab. Any other character after a backslash is a syntax error. A quote
of the other kind needs no escape, so `"it's"` and `'say "hi"'` are both fine. As in SQL,
the string's own quote doubled is one quote: `'it''s'` is the same string as `'it\'s'`
and `"it's"`, and `''''` is a single quote.

A number literal is a run of digits with an optional fraction, such as `42`, `3.25` or
`0.5`. A fraction needs a digit on each side of the point, so `.5` and `5.` are syntax
errors. A number may end in an exponent, `e` or `E` and a power of ten with an optional
sign: `1e6` is `1000000`, `2.5E-3` is `0.0025` and `1.5e+2` is `150`. It is read as
the number written out in full, so it is as exact as any other. A negative number is
the minus operator applied to a literal: `-7` is `-(7)`. Numbers are exact decimals
of any size, so `0.1 + 0.2 = 0.3` holds and a 64-bit id keeps every digit.

A boolean literal is `TRUE` or `FALSE`, matched in any case.

`NULL`, or `⊥`, is the missing value itself, written where a value goes:
`IIf(late, NULL, fee)`, `COUNT(IIf(paid, id, NULL))`. It takes its type from where it
stands, the other branch of a conditional, and is `ANY` alone. In a null test it is
not a value but part of the test: `x = NULL` and `x IS NULL` both ask whether `x` is
missing. Comparing with it in any other way, `x < NULL` or `NULL = x`, is refused,
since such a comparison is never true. In an inline table a missing value is an empty
cell, `⊥` or `NULL`.

In an inline table, cells are bare values and are not written as literals: a cell
holding `"Asha"` is the text with its quotes.

```relix-invalid
π .5 → half (Products)
```

```relix-invalid
π "a\qb" → s (Products)
```

# Examples:
Compare a column with a string:
  σ name = "Asha" (Users)

The same comparison in single quotes:
  σ name = 'Asha' (Users)

A quote of the other kind inside a string, and an escaped one:
  π name, "it's" → note, 'say "hi"' → greeting, "say \"hi\"" → same (Users)

SQL's doubled quote, the same string as 'it\'s':
  π name, 'it''s' → note (Users)

Numbers in exponent notation:
  π name, price * 1e3 → milli, tax * 2.5E-3 → scaled (Products)

Numbers in arithmetic and a comparison:
  π name, price * 1.2 + 0.5 → gross (σ price > 10 (Products))

A negative number and a boolean column:
  π name, -1 → sentinel, FALSE → archived (Products)

# Limitations:
No hexadecimal. An exponent may be at most 1000 either way. A column named `null` is written with
backticks, `` `null` ``, since the bare word is the NULL literal. A string
cannot span lines except by writing `\n`.

# Alternatives:
The temporal literals for dates and times; an inline table for a set of rows; a `$name`
parameter for a value supplied when the query runs.

# See Also:
[date-literal](date-literal.md), [timestamp-literal](timestamp-literal.md), [duration-literal](duration-literal.md), [inline-table](../language/inline-table.md), [is-null](../predicates/is-null.md)

# Notes:
TRUE and FALSE are case-insensitive, like every keyword.
