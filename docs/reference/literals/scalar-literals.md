# Name: String, number and boolean literals

# Syntax:
"<text>"          -- a string, in double quotes
'<text>'          -- the same string, in single quotes
<digits>[.<digits>]   -- a number: 42, 3.25, 0.5
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
of the other kind needs no escape, so `"it's"` and `'say "hi"'` are both fine. A doubled
quote does not escape one, as it does in SQL: write `'it\'s'` or `"it's"`, since
`'it''s'` is a syntax error.

A number literal is a run of digits with an optional fraction, such as `42`, `3.25` or
`0.5`. A fraction needs a digit on each side of the point, so `.5` and `5.` are syntax
errors. Exponent notation such as `1e3` is not part of the grammar. A negative number is
the minus operator applied to a literal: `-7` is `-(7)`. Numbers are exact decimals
of any size, so `0.1 + 0.2 = 0.3` holds and a 64-bit id keeps every digit.

A boolean literal is `TRUE` or `FALSE`, matched in any case.

There is no NULL literal in an expression. A missing value is written as an empty
cell, `⊥` or `NULL` in an inline table, and tested with `IS NULL` or `IS NOT NULL`.

In an inline table, cells are bare values and are not written as literals: a cell
holding `"Asha"` is the text with its quotes.

```relix-invalid
π price * 1e3 → milli (Products)
```

```relix-invalid
π .5 → half (Products)
```

```relix-invalid
π 'it''s' → note (Products)
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

Numbers in arithmetic and a comparison:
  π name, price * 1.2 + 0.5 → gross (σ price > 10 (Products))

A negative number and a boolean column:
  π name, -1 → sentinel, FALSE → archived (Products)

# Limitations:
No exponent notation, no hexadecimal, and no NULL literal in expressions. A string
cannot span lines except by writing `\n`.

# Alternatives:
The temporal literals for dates and times; an inline table for a set of rows; a `$name`
parameter for a value supplied when the query runs.

# See Also:
[date-literal](date-literal.md), [timestamp-literal](timestamp-literal.md), [duration-literal](duration-literal.md), [inline-table](../language/inline-table.md), [is-null](../predicates/is-null.md)

# Notes:
TRUE and FALSE are case-insensitive, like every keyword.
