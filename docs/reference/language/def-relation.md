# Name: def … : RELATION (table-valued function)

# Syntax:
def <name>(<param>: <TYPE> [, ...]) : RELATION := { <relational expression> };
def <name>(<param>: RELATION(<column> [: <TYPE>], ...) [, ...]) : RELATION := { … };

def ordersFor(cid: NUMBER) : RELATION := {
    σ customer_id = cid ∧ status = "completed" (Orders)
};

def sources(E: RELATION(src, dst)) : RELATION := { π src (E) };

# Description:
A table-valued function is a parameterised view: a named query that takes
arguments and returns a whole relation. Define "the completed orders for customer
X" once, then call it with different customers wherever a relation is expected —
in a join, a projection, or as a query on its own. It keeps repeated query shapes
DRY.

A parameter can also be a **relation**. Then the function is a rule written once
and applied to whichever relation you pass: one generation of a cellular
automaton, a cleaning step, a graph measure over any edge list. The parameter
declares the columns the body reads, and any relation with those columns can be
passed.

# Technical Description:
A `def name(...) : RELATION := { body }` binds a RelationFunctionSymbol whose body
is a relational-algebra expression; its result schema is the body's inferred
schema (like a view). Binding is by substitution — the planner inlines the body
with each parameter reference replaced by the supplied argument, then re-infers
its schema. Scalar arguments must be constant expressions (no column references);
a correlated argument needs a `LATERAL` join instead. Nested TVF calls inline
transitively; recursion is rejected at plan time. The RELATION keyword is
case-insensitive.

**Relation parameters.** `E: RELATION(src, weight: NUMBER)` declares a relation
with those columns; a column with no type is `ANY`. Only a table-valued function
can take one. Inside the body, `E` is a relation with exactly that heading, so
the body is checked once, at the `def`, and a column the heading does not declare
is an error there. `E.src` qualifies a column by the parameter's name, and the
parameter means `E` even where the script defines a relation of the same name.

The argument is a relation's **name** — a table, a source or a view. A call is
checked against the heading: the relation must have every declared column, at a
compatible type, and may have more. The body sees the argument **narrowed to the
heading**: binding substitutes `ρ E (π <declared columns> (argument))`, so a
column the heading leaves out cannot reach the body, and a natural join in it can
never match on a column its author did not declare. A parameter can be passed on
to another function, which checks it against its own heading in turn.

Inside an `ITERATE` step, the argument can be the iterated name itself: the step
hands the previous round to the function, which is how a rule written once is
iterated. A `FIX` step cannot do the same, because FIX needs to see that its step
reads its name monotonically, and a function body is out of its sight.

# Examples:
A filtered, parameterised view:
```relix
def ordersFor(cid: NUMBER) : RELATION := {
    σ customer_id = cid ∧ status = "completed" (Orders)
};
query { ordersFor(2) };
```

Use it inside a join:
  query { π name, amount (Customers ⋈ ordersFor(2)) };

Zero-argument reusable query:
  def activeUsers() : RELATION := { σ active = true (Users) };

A rule over any edge list, applied to two:
```relix
def outDegree(E: RELATION(src, dst)) : RELATION := { γ src, COUNT(*) → out (E) };
query { outDegree(Edges) };
query { outDegree(Graph) };
```

The same rule iterated, a round at a time:
```relix
def extend(R: RELATION(src, dst)) : RELATION := {
    π src, dst2 → dst (R ⋈ π src → dst, dst → dst2 (Edges))
};
query { ITERATE R (π src, dst (Edges), extend(R)) ROUNDS 3 };
```

Only a table-valued function takes a relation:
```relix-invalid
def width(E: RELATION(x)) : NUMBER := { 1 };
```

# Worked Example:
One generation of Conway's Game of Life, written once and applied twice. A cell
lives if it has three live neighbours, or two and was already alive; `Offsets`
are the eight directions to a neighbour.

```relix
Offsets := [
| dx | dy |
|----|----|
| -1 | -1 |
| -1 | 0  |
| -1 | 1  |
| 0  | -1 |
| 0  | 1  |
| 1  | -1 |
| 1  | 0  |
| 1  | 1  |
];

Blinker := [
| x | y |
|---|---|
| 1 | 0 |
| 1 | 1 |
| 1 | 2 |
];

def generation(G: RELATION(x: NUMBER, y: NUMBER)) : RELATION := {
    π x, y (σ n = 3 (ρ C(x, y, n) (γ nx, ny, COUNT(*) → n (π x + dx → nx, y + dy → ny (G × Offsets)))))
  ∪ π x, y (σ n = 2 (ρ C(x, y, n) (γ nx, ny, COUNT(*) → n (π x + dx → nx, y + dy → ny (G × Offsets)))) ⋈ G)
};

Gen1 := { generation(Blinker) };
Gen2 := { generation(Gen1) };
```

A blinker is three cells in a line, and each generation turns it through a
quarter turn. The first generation lies it down:

```relix
query { τ x, y (Gen1) };
```

```
 x  y
 ─  ─
 0  1
 1  1
 2  1
(3 rows)
```

and the second stands it up again, where it started: `Gen2` holds the same three
cells as `Blinker`. `Gen2` passes `Gen1` — itself a call to `generation` — to
`generation`, which is not recursion: the argument belongs to the call, not to
the function's body.

# Limitations:
Scalar arguments must be constant — you cannot pass a column from the surrounding
query; use a `LATERAL` join for that. A relation argument is a relation's name:
to pass an expression, name it as a view first. A function with a relation
parameter cannot be called through `LATERAL`, whose arguments are the left
side's values, row by row. A TVF body cannot reference itself (recursion is
rejected). The body is not pushed down to SQL as a unit.

# Alternatives:
A plain view (`Name := { … };`) when no parameters are needed. FIX for genuine
recursion, and ITERATE for repeating a step — which can itself call a function
with a relation parameter.

# See Also:
[def](def.md), [assignment](assignment.md), [import](import.md), [natural-join](../joins/natural-join.md), [iterate](../advanced/iterate.md)

# Notes:
Because binding is by inlining, a TVF behaves exactly as if you had written its
body inline with the arguments substituted — a relation argument narrowed to the
parameter's heading, and renamed to the parameter's name.
