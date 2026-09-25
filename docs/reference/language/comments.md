# Name: Comments (-- and /* … */)

# Syntax:
-- a line comment, to the end of the line
/* a block comment,
   which may span lines */

σ amount > 100 (Orders)   -- keep the large orders

# Description:
A comment is text the engine ignores. Relix uses the same two forms SQL does: `--`
runs to the end of the line, and `/* … */` covers everything between the two
markers, however many lines that is.

Both forms work anywhere whitespace does — before a statement, between the
operators of an expression, at the end of a line. A script and the algebra inside
its braces are commented the same way, so there is nothing to remember about where
you are when you write one.

`//` is not a comment. It is two division operators, and it is a syntax error
wherever it appears, after a statement:

```relix-invalid
query { Orders }; // every order
```

and inside braces:

```relix-invalid
query { Orders // every order
};
```

# Technical Description:
Comments are removed by the lexer, so they are invisible to every phase above it.
They carry no source position into the tree, appear in no rendering of an
expression, and cannot be recovered from a parsed script: a view printed back with
`:source` or by a session's own report comes back without them.

A `--` is a comment marker before it is an operator, which is the one place the two
forms interact with the rest of the grammar. `- -amount` is arithmetic — negation
applied twice — while `--amount` opens a comment and swallows the rest of the line.
The space is what tells them apart, and this is the rule SQL follows for the same
reason.

An unterminated `/*` runs to the end of the input rather than raising: the text
after it was never going to be code.

# Examples:
Explain a filter at the point it is applied:

```relix
-- Large orders only: everything below assumes they are the interesting ones.
BigOrders := { σ amount > 100 (Orders) };
```

Comment inside the algebra, not only around it:

```relix
Recent := {
    σ status = "shipped" (      -- shipped, not merely placed
        σ amount > 50 (Orders)  -- and worth looking at
    )
};
```

Take a step out of a pipeline without deleting it:

```relix
Totals := {
    γ customer_id, SUM(amount) → total (
        /* σ region = "east" ( */
            Orders
        /* ) */
    )
};
```

Head a script with what it is for:

```relix
/*
   Customer revenue by region.
   Reads Orders and Customers; nothing here writes anything.
*/
ByRegion := { γ region, SUM(amount) → revenue (Orders) };
```

# Limitations:
A comment is discarded, not attached, so it cannot be used to annotate a view for
anything that reads the model back. A name and a `--` line above the definition is
how a script explains itself to the next reader; the engine's own reports describe
the expression, not the prose around it.

There is no nesting: the first `*/` closes a block comment, whatever came before it.
A block comment wrapped around a region that already contains one therefore ends
early, and the remainder of the outer comment is read as code.

# See Also:
- [assignment & query](assignment.md) — naming a view, which is the other half of
  making a script readable
- [delimited identifier](delimited-identifier.md) — the other lexical rule worth
  knowing before writing a script
