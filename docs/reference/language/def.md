# Name: def (scalar function definition)

# Syntax:
def <name>(<param>: <TYPE> [, ...]) : <RETURN_TYPE> := { <expression> };
private def <name>(...) : <TYPE> := { <expression> };

def double(x: NUMBER) : NUMBER := { x * 2 };
def add(a: NUMBER, b: NUMBER) : NUMBER := { a + b };

# Description:
`def` declares your own scalar function — a reusable calculation you can call by
name inside conditions and projections. Define a discount formula, a name
formatter, or a tax calculation once, then use it wherever you need it. Mark it
`private` to keep it out of what other files see when they import this one.

# Technical Description:
A scalar `def` binds a ScalarFunctionSymbol with typed parameters and a return
type (NUMBER, STRING, BOOLEAN, ANY, or a temporal type). The body is a single operand
expression over the parameters. Calls resolve in predicates and projected
expressions via the symbol table, falling back to the builtin registry. `private`
suppresses re-export on import. Functions must be declared before they are called.

# Examples:
A simple calculation:
```relix
def double(x: NUMBER) : NUMBER := { x * 2 };
query { σ double(salary) > 100000 (Employees) };
```

Multiple parameters:
```relix
def add(a: NUMBER, b: NUMBER) : NUMBER := { a + b };
query { π add(price, tax) → total (Products) };
```

Zero-argument constant:
  def vat() : NUMBER := { 0.2 };

Private helper, not exported:
  private def clean(s: STRING) : STRING := { Trim(s) };

# Limitations:
The body is a single scalar expression, not a multi-statement procedure. Parameter
and return types are scalar — the nested struct and array forms a source column may
declare are not available here. For a function that returns a whole relation, use the
RELATION return type (a table-valued function).

# Alternatives:
A table-valued function (`: RELATION`) returns a relation rather than a value. The
built-in scalar functions cover common string/math/date operations already.

# See Also:
[def-relation](def-relation.md), [import](import.md), [project](../operators/project.md), [select](../operators/select.md)

# Notes:
User functions are evaluated per row by the engine and are not pushed down to SQL.
