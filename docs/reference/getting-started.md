# Name: Getting started (your first script)

# Syntax:
source Name from csv("...") { ... };   -- 1. bring data in
Name := { <expression> };              -- 2. name a result
query { <expression> };                -- 3. ask for one back

# Description:
A Relix script is a text file holding three kinds of statement: declarations that
bring data in, assignments that give a name to a result, and `query` statements
that say which results you actually want back. Nothing is written anywhere — a
script reads data and produces answers.

You never say *how* to compute an answer. You write the shape of the result you
want and the engine decides the order of work: which filters to push down into
the database, which join algorithm to use, what to evaluate once and reuse.

Every operator has two spellings, a mathematical one and a keyword one — `σ` and
`SELECT`, `⋈` and `JOIN`, `∪` and `UNION`. They parse to exactly the same thing,
so use whichever you can type. This manual leads with the symbols because they
are shorter to read.

# Technical Description:
The whole script is analysed before any of it runs: imports are resolved, every
relation and function name is registered, and each expression's output columns and
types are inferred and checked against the operators using them. A mistyped column
is an analysis error naming the line, not a failure halfway through a result set.

An assignment (`:=`) declares a *view*, not a copy of the rows. It names an
expression, and the optimizer folds that expression into whatever reads it — so
splitting a long query into named steps costs nothing at run time and is the
normal way to write one.

# Examples:
The same two relations read from CSV files rather than written inline:
```relix
source Customers from csv("./data/customers.csv") {
    header: true,
    schema: { customer_id: NUMBER, name: STRING, city: STRING }
};

source Orders from csv("./data/orders.csv") {
    header: true,
    schema: { order_id: NUMBER, customer_id: NUMBER, amount: NUMBER }
};

query { γ name, SUM(amount) → total (Customers ⋈ Orders) };
```

Reading from a database instead — only the declaration changes, never the query:
```relix
source Orders from database {
    url: "${DB_URL}", table: "orders",
    schema: { order_id: NUMBER, customer_id: NUMBER, amount: NUMBER }
};

query { σ amount ≥ 100 (Orders) };
```

The same expression typed in ASCII, for a keyboard without the glyphs:
```relix
Orders := [
| order_id | customer_id | amount |
|----------|-------------|--------|
| 100      | 1           | 120    |
];

query { SELECT amount >= 100 (Orders) };
```

# Worked Example:
A first script, built up one statement at a time. The data is written inline so
that the whole thing runs with no database and no files — copy any block into a
script and run it.

These two relations recur throughout the manual: three customers, four orders,
and two deliberate awkward rows — **Cara** has never ordered, and **order 103**
belongs to customer 4, who is not in `Customers`.

```relix
Customers := [
| customer_id | name  | city   |
|-------------|-------|--------|
| 1           | Alice | London |
| 2           | Bob   | Berlin |
| 3           | Cara  | Lisbon |
];

Orders := [
| order_id | customer_id | amount |
|----------|-------------|--------|
| 100      | 1           | 120    |
| 101      | 1           | 80     |
| 102      | 2           | 45     |
| 103      | 4           | 60     |
];
```

An inline table is a relation like any other, so the shortest possible query asks
for one back unchanged:

```relix
query { Orders };
```

```
 order_id  customer_id  amount
 ────────  ───────────  ──────
      100            1     120
      101            1      80
      102            2      45
      103            4      60
(4 rows)
```

**Keep some rows, then keep some columns.** `σ` (SELECT) filters rows by a
condition; `π` (PROJECT) picks the columns to keep, and renames one on the way out
with `→`. Read the expression from the inside out — the relation is in the
brackets, and each operator wraps the one before it:

```relix
query { π order_id, amount → value (σ amount ≥ 80 (Orders)) };
```

```
 order_id  value
 ────────  ─────
      100    120
      101     80
(2 rows)
```

**Bring the two relations together.** `⋈` (JOIN) matches rows on the columns the
two sides share — here `customer_id`, which is why the result carries it only
once. Alice appears twice because she has two orders: a join multiplies rows, it
does not look values up. Cara and order 103 both disappear, having nothing on the
other side to match:

```relix
query { Customers ⋈ Orders };
```

```
 customer_id  name   city    order_id  amount
 ───────────  ─────  ──────  ────────  ──────
           1  Alice  London       100     120
           1  Alice  London       101      80
           2  Bob    Berlin       102      45
(3 rows)
```

**Summarise, and name the step.** `γ` (GROUP) collapses rows that share a key into
one row per key, with an aggregate over each group. `:=` names the result so the
next expression can read it — that name is a view, not a stored copy, so the
engine still runs this as a single query:

```relix
CustomerTotals := { γ name, SUM(amount) → total (Customers ⋈ Orders) };

query { τ total DESC (CustomerTotals) };
```

```
 name   total
 ─────  ─────
 Alice    200
 Bob       45
(2 rows)
```

That is the whole shape of a Relix script: declare data, filter and combine it,
name the interesting steps, and ask for what you want.

# Limitations:
A script only reads. There is no statement that writes rows back to a source,
creates a table, or changes one.

# See Also:
[glossary](glossary.md), [inline-table](language/inline-table.md),
[source](language/source.md), [assignment & query](language/assignment.md),
[select](operators/select.md), [project](operators/project.md),
[group](operators/group.md), [natural-join](joins/natural-join.md)

# Notes:
Where to go next: read chapter 1 for the rest of the statements a script can hold
(database sources, imports, function definitions), or jump to the operator you
need from the tables in the index. Every operator page carries a worked example
that runs on inline data, so any of them can be tried without a database.
