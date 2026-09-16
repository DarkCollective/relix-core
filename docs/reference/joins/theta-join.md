# Name: Theta Join (⨝ / ><)

# Syntax:
Relation1 ⨝ <condition> Relation2
Relation1 >< <condition> Relation2

Users ⨝ Users.id = Orders.user_id Orders
Users >< Users.id = Orders.user_id Orders

# Description:
A theta join combines two relations using an explicit condition that you write
out — most often "this column equals that column", but it can be any comparison.
Use it when the matching columns have different names, or when matching is not a
simple equality (e.g. a price within a range). Unlike a natural join it never
guesses; you state exactly how the two sides line up.

# Technical Description:
R ⨝_θ S returns the rows of the cross product R × S that satisfy predicate θ. θ
may reference qualified columns (Relation.column) from both sides and use any
comparison/logical operators. It keeps both sides' columns (no implicit
projection). The planner picks join algorithm (hash/merge) and build side via the
cost model; same-connection equi-joins push down to a SQL JOIN … ON.

# Qualified column references above a join:
When both sides expose a column of the same name, the joined relation keeps both.
A **relation-qualified** reference — `Relation.column` — resolves to the exact
column it names, anywhere above the join (in σ, π, γ, τ, λ), not just inside the
join condition. Each column remembers which relation it came from (its
*provenance*), and a rename re-anchors it: after `ρ R (…)` every column's
qualifier becomes `R`.

A qualified reference either resolves to precisely one column or is a **validation
error** — a stale qualifier (a relation that is not a source of that column here)
is never silently bound to an unrelated first match.

**Worked example — a name collision.** `devices` and `rooms` both have a `name`
column:

```
devices                              rooms
 device_id  room_id  name             room_id  name
 ─────────  ───────  ─────────        ───────  ─────────
 1          10       "Thermostat"     10       "Living Room"
 2          20       "Lamp"           20       "Bedroom"
```

Query — the device together with the room it is in:

```relix
query {
  π devices.name → device, rooms.name → room
    (devices ⨝ devices.room_id = rooms.room_id rooms)
};
```

Output — `devices.name` and `rooms.name` each resolve to their own side:

```
 device        room
 ───────────   ─────────────
 "Thermostat"  "Living Room"
 "Lamp"        "Bedroom"
```

**Schema-on-read inputs are the exception.** The check relies on a declared
heading, which records where each column came from. A document from a JSON, HTTP or
MongoDB source records nothing, and over it a dotted name may also be a path into one
of its fields (`address.city`). So once a view or a rename has hidden a relation's
name, a reference that still uses the hidden name is **not** refused: it is read as a
path, finds no such field, and yields NULL.

This is deliberate. Refusing the name would also refuse a genuine path into a document
field that happens to share it.

**Worked example — a hidden name over a document source.** `orders` is a declared CSV
file and `products` is a JSON file, and the view `Sales` joins them:

```
orders.csv                  products.json
 product_id  quantity        [{"product_id": 1, "name": "Novel"},
 ──────────  ────────         {"product_id": 2, "name": "Trowel"}]
 1           2
 2           5
```

```relix
source orders from csv("orders.csv") {
    header: true, schema: { product_id: NUMBER, quantity: NUMBER }
};
source products from json("products.json");

Sales := { orders ⨝ orders.product_id = products.product_id products };

query { π orders.quantity → stale, Sales.quantity → quantity, Sales.name → name (Sales) };
```

Output — outside the view its columns answer to `Sales`, so `orders.quantity`
names nothing and reads NULL:

```
 stale  quantity  name
 ─────  ────────  ──────
 NULL          2  Novel
 NULL          5  Trowel
```

Over two declared relations the same `orders.quantity` is an analysis error. Over a
document source, use the view's own name: `Sales.quantity`, and `Sales.product_id_r`
for the copy of `product_id` the join renamed. The same holds for a rename,
`ρ P (products)`, whose fields answer to `P` only.

# Legacy `_r` disambiguation:
For backward compatibility the joined schema still renames the right side's
colliding column with an `_r` suffix (`name` on the left, `name_r` on the right),
so an **unqualified** `name_r` continues to work. Prefer the qualified form
`rooms.name` — it names a real column that appears in the source schema, whereas
`name_r` is an engine-invented name. The `_r` convention is retained but
considered legacy.

# Examples:
Join when key names differ:
  Users ⨝ Users.id = Orders.user_id Orders

Compound condition — match and also require the order be active:
  Users ⨝ Users.id = Orders.user_id ∧ Orders.active = true Orders

Range / band join — price tiers:
  Sales ⨝ Sales.amount >= Tiers.min ∧ Sales.amount <= Tiers.max Tiers

# Worked Example:
The same two relations run through every join on this page, so the
results are directly comparable. Two rows do the work: **Cara** has placed no
orders, and **order 103** belongs to customer 4, who is not in `Customers`. Which
of those two survives is exactly what distinguishes one join from the next.

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

query { Customers ⨝ Customers.customer_id = Orders.customer_id Orders };
```

```
 customer_id  name   city    order_id  customer_id_r  amount
 ───────────  ─────  ──────  ────────  ─────────────  ──────
           1  Alice  London       100              1     120
           1  Alice  London       101              1      80
           2  Bob    Berlin       102              2      45
(3 rows)
```

Same three rows as the natural join — but the condition is written out, so it
works even when the keys are named differently, and the result keeps **both**
columns. The right-hand `customer_id` is renamed `customer_id_r` to keep the
output schema unambiguous.

# Limitations:
Joins work over schema-on-read sources (JSON, HTTP, MongoDB), which name their
columns per row rather than up front: a qualified reference resolves against the
document, a field neither side carries reads as NULL, and a right-hand field whose
name the left already uses is suffixed `_r`, as it is under a declared heading.
The two forms that emit an **unmatched right** row — `⟖` and `⟗` — are the
exception: that row has no left row to collide with, so the same field would be
named one way when it matched and another when it did not.

A non-equality (range/inequality) theta join cannot use a hash join and may be
expensive; the engine handles it but it is not pushed as efficiently.

Concretely, a join whose condition contains no `left.col = right.col` conjunct is
planned as a **nested loop**: every left row is compared against every right row,
O(n×m). That is not a silent fallback — `:explain` shows the algorithm
(`Join INNER/NESTED_LOOP`), and `--trace` reports it as a `PLAN/JOIN-NESTED-LOOP`
event naming the reason. If a query is unexpectedly slow, that pair is the first
thing to look at: adding one equality conjunct is what turns it into a hash (or,
over sorted inputs, a merge) join.

# Alternatives:
Natural join (⋈) when shared columns already line up by name. Outer joins
(⟕ ⟖ ⟗) when you need to keep non-matching rows too. AS-OF join (ASOF) for
nearest-in-time matching.

# See Also:
[natural-join](natural-join.md), [left-outer-join](left-outer-join.md), [right-outer-join](right-outer-join.md), [full-outer-join](full-outer-join.md), [asof-join](asof-join.md)

# Notes:
Qualify columns as Relation.column inside the condition so the planner can route
each reference to the correct side — important when both sides share a column
name.
