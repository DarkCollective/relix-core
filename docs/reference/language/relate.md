# Name: relate (schema relationship declaration)

# Syntax:
```relix
relate "Name" Source.col -> Target.col;
relate "Name" / "Inverse Name" Source.col -> Target.col;
relate symmetric "Name" Rel.col -> Rel.other_col;
relate "Name" Source(col_a, col_b) -> Target(col_a, col_b) [1..50];
```

As a `references:` block on a source (database, connection-table, csv, json),
or a trailing `references` clause on an inline table:

```relix
source Orders from database { url: "${DB}", table: "orders",
    schema: { order_id: NUMBER, customer_id: NUMBER },
    references: { customer_id -> Customers.customer_id } };

Cities := [
| name    | country |
|---------|---------|
| Chicago | US      |
] references { country -> Countries.code };
```

# Description:
`relate` tells relix how two relations connect — which columns link them, what
the relationship is called, and how many rows can match on each side. Declaring
relationships builds a *schema graph* the engine can use to work out join paths
mechanically: when a request touches columns from several tables, the engine can
find the connecting path itself instead of guessing (or making you spell out
every join). The `references:` block is the compact form for ordinary foreign
keys, written directly on the source that holds the referencing column.

Names matter: with two links from `Issues` into `Users` ("assignee" and
"reporter"), a named edge turns a silent wrong guess into an answerable
question — *"join users as assignee or reporter?"*.

# Technical Description:
A relationship is a named edge between two endpoints. Each endpoint is a
relation plus an ordered column list with numeric multiplicity bounds:

- **Endpoints** — `Rel.col` names one column; `Rel(col_a, col_b)` names several
  (a composite key is *one* edge; columns pair positionally). In dot form the
  last dotted segment is the column, so a namespace-qualified relation uses the
  parenthesised form: `analytics.Orders(order_id)`.
- **Bounds** — `[min..max]` after an endpoint states how many rows of that
  endpoint's relation may match one row of the other side; `*` means unbounded.
  Omitted bounds default to `[0..*]`, the unconstrained reading — an omitted
  bound asserts nothing. `min = 0` signals that an inner join may drop
  opposite-side rows; a finite `max` bounds fan-out (a selectivity fact the
  cost model can use).
- **Inverse name** — `relate "Manages" / "Reports To" …` names the reverse
  traversal; its presence records that the directions are distinct roles.
- **symmetric** — for self-referential edges only (cross-sells, friend graphs):
  the relationship has no direction at the data level. Mutually exclusive with
  an inverse name.
- **references:** — each `col -> Target.col` entry is a foreign-key arrow. The
  target side gets an implied `max` of 1; the edge's name defaults to the
  referencing column list (e.g. `"customer_id"`). Accepted on every
  schema-bearing source kind (`database`, connection-table, `csv`, `json`) and,
  as a trailing `references { … }` clause, on inline tables. For an open `json`
  source the referencing columns resolve dynamically (schema-on-read); the
  target side is validated as usual. Views take no `references` — use a
  standalone `relate` (a view already *is* an expression; its relationships are
  declared, not embedded).

Every edge is validated at analysis time — both relations must resolve, every
column must exist, and column lists must pair up — as hard errors, because an
unchecked edge is a confidently wrong structural claim. Edges may target views
(`ActiveOrders := { σ status = "active" (Orders) }`) exactly as they target
tables, which is how a relationship carries a filter: put the selection in a
view, not in the edge. Every edge is walkable in both directions regardless of
which side declares it. Declared relationships appear in the IR report's
RELATIONSHIPS section.

# Examples:
An order-processing schema, declared once so the engine knows the join paths:

```relix
source Customers from database { url: "${DB}", table: "customers",
    schema: { customer_id: NUMBER, name: STRING } };
source Orders from database { url: "${DB}", table: "orders",
    schema: { order_id: NUMBER, customer_id: NUMBER, status: STRING },
    references: { customer_id -> Customers.customer_id } };
source OrderItems from database { url: "${DB}", table: "order_items",
    schema: { order_id: NUMBER, product_id: NUMBER, qty: NUMBER } };

relate "Order Line Items" Orders.order_id -> OrderItems.order_id [1..50];
```

With this graph, a request touching `Customers.name` and `OrderItems.qty` has
exactly one connecting path (`Customers ← Orders ← OrderItems`), so the engine
can assemble the two joins itself. The `[1..50]` bound also tells it each order
fans out to at most 50 items.

The two-role case — two foreign keys into the same table, disambiguated by
name:

```relix
source Users from database { url: "${DB}", table: "users",
    schema: { id: NUMBER, name: STRING } };
source Issues from database { url: "${DB}", table: "issues",
    schema: { id: NUMBER, assignee_id: NUMBER, reporter_id: NUMBER } };

relate "Assignee" Issues.assignee_id -> Users.id [0..1];
relate "Reporter" Issues.reporter_id -> Users.id [1..1];
```

A request naming a user column now has two *named* paths, so the ambiguity is
askable ("assignee or reporter?"). The `[0..1]` on `Assignee` additionally
records that unassigned issues exist — an inner join through it would drop
them.

An org hierarchy (one self-edge, two role names), a symmetric self-edge, and a
composite multi-tenant foreign key (one edge, never two):

```relix
source Employees from database { url: "${DB}", table: "employees",
    schema: { id: NUMBER, manager_id: NUMBER } };
source Products from database { url: "${DB}", table: "products",
    schema: { id: NUMBER, related_id: NUMBER } };
source WarehouseSlot from database { url: "${DB}", table: "slots",
    schema: { tenant_id: NUMBER, product_id: NUMBER } };
source Product from database { url: "${DB}", table: "product",
    schema: { tenant_id: NUMBER, id: NUMBER } };

relate "Manages" / "Reports To" Employees.id -> Employees.manager_id;
relate symmetric "Cross Sells" Products.id -> Products.related_id;
relate "Slot Product"
    WarehouseSlot(tenant_id, product_id) -> Product(tenant_id, id);
```

A relationship to a *view* — the filter lives in the view, never in the edge:

```relix
source Customers from database { url: "${DB}", table: "customers",
    schema: { customer_id: NUMBER, name: STRING } };
source Orders from database { url: "${DB}", table: "orders",
    schema: { order_id: NUMBER, customer_id: NUMBER, status: STRING } };

ActiveOrders := { σ status = "active" (Orders) };
relate "Customer Active Orders"
    Customers.customer_id -> ActiveOrders.customer_id;
```

## Bounds inheritance across a filtering view

When an edge targets a view that only *filters* a base relation (a σ chain over
a single relation), the engine **derives** the derived endpoint's bounds from the
base relationship instead of trusting the author. A σ can only remove rows, so
`max` is preserved and `min` relaxes to 0: given `Customers —[1..10]→ Orders`,
the edge to `ActiveOrders` above is read as `Customers —[0..10]→ ActiveOrders` —
a customer with at least one order may have zero *active* ones, but never more
than the ten they started with. Any bounds written on such an edge are ignored
(with a warning), because an inherited `min > 0` would be a false claim about row
preservation.

Inheritance applies only through a filter and only when a base relationship
exists to inherit from. Where the view aggregates (γ) or joins rather than
filters, or the base relation carries no matching edge, no rule applies and the
edge keeps its declared — or `[0..*]` default — bounds.

A derived (view) endpoint also enters automatic path resolution only when the
request *nominates* it (names or matches the view); the base relation is the
default. Left free, every filtered view would double the parallel paths through
its region — so a view is opt-in vocabulary, not a graph-wide multiplier.

# Limitations:
Relationships describe equijoin structure only; range and temporal
relationships (see the as-of and interval joins) are not graph edges. A
many-to-many pair through a junction table is declared as two edges (one per
foreign key), not one. Bounds are declarations, not enforced constraints — the
engine trusts them for planning and explanation but does not verify data
against them. Join-path *resolution* over the graph (finding and correcting the
join a query should build) is implemented on top of these declarations.

A relationship's name, and its inverse name, must hold more than whitespace. A blank
one is a parse error:

```relix-invalid
relate "" Orders.customer_id -> Customers.customer_id;
```

# Alternatives:
The `references:` block on a `source` declaration for plain foreign keys — same
edge, terser, named after the referencing column. Without any declarations, the
engine still executes whatever joins you write; the graph adds names, bounds,
and the basis for automatic path resolution.

# See Also:
[source](source.md), [connection](connection.md),
[natural join](../joins/natural-join.md)

# Notes:
Declared edges are ground truth (origin `DECLARED`). The graph also admits
session-learned edges (origin `LEARNED`, e.g. supplied conversationally) and
data-inferred suggestions (`INFERRED`); non-declared edges stay visibly tagged
in the IR report and are dropped with a warning — never a hard error — if the
session's schema moves out from under them.
