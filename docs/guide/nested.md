# Nested data

A Relix column can hold a struct or an array, not only a scalar. The algebra is defined
over that from the bottom up — flat relational algebra is the all-scalar special case — so
nesting is not a document mode bolted on beside the operators. It is the same operators,
with a wider idea of what a value is.

This page is about **producing** nested shapes: constructing them, gathering them,
flattening them, and folding a hierarchy into one. Reading them back out in Java — the
`struct` and `array` accessors on a row — is [Reading results](results.md#nested-values).

```java
Relix relix = Relix.open();

relix.table("Employees", List.of("id", "manager_id", "name", "team", "salary"), List.of(
        Map.of("id", 1,                        "name", "Ada",     "team", "exec",     "salary", 200),
        Map.of("id", 2, "manager_id", 1,       "name", "Grace",   "team", "platform", "salary", 150),
        Map.of("id", 3, "manager_id", 1,       "name", "Lin",     "team", "data",     "salary", 150),
        Map.of("id", 4, "manager_id", 2,       "name", "Edsger",  "team", "platform", "salary", 120),
        Map.of("id", 5, "manager_id", 2,       "name", "Barbara", "team", "platform", "salary", 130),
        Map.of("id", 6, "manager_id", 3,       "name", "Alan",    "team", "data",     "salary", 110)));

relix.relation("Employees").toList().forEach(System.out::println);
```

```
(id=1, manager_id=NULL, name=Ada, team=exec, salary=200)
(id=2, manager_id=1, name=Grace, team=platform, salary=150)
(id=3, manager_id=1, name=Lin, team=data, salary=150)
(id=4, manager_id=2, name=Edsger, team=platform, salary=120)
(id=5, manager_id=2, name=Barbara, team=platform, salary=130)
(id=6, manager_id=3, name=Alan, team=data, salary=110)
```

Ada's row omits `manager_id`, so hers is NULL — which is what makes her the root of the
tree at the end of this page.

## Building a nested value

Two constructors put nesting into a projection. `structOf(field(name, expr), …)` builds a
struct and `arrayOf(expr, …)` builds an array, and both are ordinary operands: they take
expressions, they can be nested inside each other, and they can be aliased like any other
projected column.

```java
Relation boxed = relix.relation("Employees").project(List.of(
        projected(attr("id")),
        projected(structOf(field("name", attr("name")),
                           field("pay", structOf(field("base", attr("salary")),
                                                 field("banded", times(attr("salary"), num(1.1)))))),
                  "person")));

System.out.println(boxed.render());
boxed.limit(2).toList().forEach(System.out::println);
```

```
π id, {name: name, pay: {base: salary, banded: salary * 1.1}} → person (Employees)
(id=1, person={name: Ada, pay: {base: 200, banded: 220}})
(id=2, person={name: Grace, pay: {base: 150, banded: 165}})
```

The heading is known before anything runs, and it is nested too — the constructors are
expressions, so their type is inferred from what they are built out of, all the way down:

```java
System.out.println(boxed.schema().columns().stream()
        .map(c -> c.name() + ":" + c.type().display())
        .toList());
```

```
[id:number, person:struct{name: string, pay: struct{base: number, banded: number}}]
```

`banded` is a `number` because `salary * 1.1` is, and the analyser knows that two levels
down. Nesting a value does not put it beyond type inference.

`arrayOf` is the other constructor, and it takes expressions in the same way — an array is
a list of values, not a list of columns:

```java
relix.relation("Employees")
        .project(List.of(projected(attr("name")),
                         projected(arrayOf(attr("team"), func("LCase", attr("name"))), "tags")))
        .limit(3)
        .toList()
        .forEach(System.out::println);
```

```
(name=Ada, tags=[exec, ada])
(name=Grace, tags=[platform, grace])
(name=Lin, tags=[data, lin])
```

## COLLECT and UNNEST

`COLLECT` is the aggregate that does not reduce. Where `SUM` turns a group's values into
one number, `COLLECT` turns them into one array, so the group survives as a value instead
of collapsing into a statistic:

```java
import com.darkcollective.relix.ast.AggregateOperator;

Relation teams = relix.relation("Employees")
        .aggregate(List.of("team"), List.of(agg(AggregateOperator.COLLECT, "name", "members")));

System.out.println(teams.render());
teams.toList().forEach(System.out::println);
```

```
γ team, COLLECT(name) → members (Employees)
(team=exec, members=[Ada])
(team=platform, members=[Grace, Edsger, Barbara])
(team=data, members=[Lin, Alan])
```

`unnest` is its inverse: it flattens an array column into one row per element, keeping the
rest of the row alongside each one.

```java
teams.unnest("members").toList().forEach(System.out::println);
```

```
(team=exec, members=Ada)
(team=platform, members=Grace)
(team=data, members=Lin)
(team=platform, members=Edsger)
(team=platform, members=Barbara)
(team=data, members=Alan)
```

That round trip is not a coincidence, and the engine knows it. Gathering a group and
immediately flattening it again is a great deal of work to arrive back where you started,
so the optimizer removes both operators and leaves the projection they amount to:

```java
System.out.println(teams.unnest("members").optimized().render());
```

```
π team, name → members (Employees)
```

Which is a good reason to write the obvious thing. The law holds in the algebra, so stating
it plainly costs nothing at run time.

### Positions, and rows the array cannot fill

Flattening loses two things unless you ask for them: where each element sat, and what
happened to a row whose array was empty. The three-argument form restores both.

```java
import java.util.Optional;

Relation ranked = teams.unnest("members", false, Optional.of("seat"));

System.out.println(ranked.render());
ranked.toList().forEach(System.out::println);
```

```
μ members WITH ORDINALITY seat (γ team, COLLECT(name) → members (Employees))
(team=exec, members=Ada, seat=1)
(team=platform, members=Grace, seat=1)
(team=platform, members=Edsger, seat=2)
(team=platform, members=Barbara, seat=3)
(team=data, members=Lin, seat=1)
(team=data, members=Alan, seat=2)
```

`seat` is the 1-based position within the array. The `false` is the *outer* flag: left as
it is, a row whose array is empty contributes nothing, and set to `true` it survives once
with a NULL where an element would have gone — the same choice an outer join makes, applied
to a column instead of a relation.

### COLLECT and NULLs

Every other aggregate follows the SQL rule and skips a row whose argument is NULL.
`COLLECT` deliberately does not — an array that silently dropped its holes would not be the
group any more:

```java
relix.relation("Employees")
        .aggregate(List.of(), List.of(agg(AggregateOperator.COLLECT, "manager_id", "managers"),
                                      agg(AggregateOperator.COUNT, "manager_id", "counted")))
        .toList()
        .forEach(System.out::println);
```

```
(managers=[NULL, 1, 1, 2, 2, 3], counted=5)
```

Six employees, five of whom have a manager. `COUNT` reports five because it skips Ada's
NULL; `COLLECT` returns six elements because it keeps it.

## TREE: folding a hierarchy

`COLLECT` gathers one level. `TREE` follows a parent pointer to the bottom and gathers all
of them, turning an adjacency list into a forest of nested documents in a single pass.

```
   adjacency (flat)                 forest (nested)

   id  manager_id                   Ada
    1     —                          ├─ Grace
    2     1            ⇒             │   ├─ Edsger
    3     1                          │   └─ Barbara
    4     2                          └─ Lin
    5     2                              └─ Alan
    6     3
```

```java
Relation org = relix.relation("Employees").tree("id", "manager_id", "reports");

System.out.println(org.render());
System.out.println(org.count() + " root(s)");

// Walk the forest the way the operator built it.
class Chart {
    static void print(String indent, Value node) {
        Map<String, Value> fields = ((com.darkcollective.relix.value.StructValue) node).fields();
        System.out.println(indent + fields.get("name").asDisplayString());
        ((com.darkcollective.relix.value.ArrayValue) fields.get("reports")).elements()
                .forEach(child -> print(indent + "    ", child));
    }
}

Tuple root = org.toList().getFirst();
System.out.println(root.string("name"));
root.array("reports").forEach(child -> Chart.print("    ", child));
```

```
TREE id BY manager_id AS reports (Employees)
1 root(s)
Ada
    Grace
        Edsger
        Barbara
    Lin
        Alan
```

One output row per **root**, and a root is a row whose parent pointer is NULL or points at
an id that is not in the relation — so a forest with several roots is normal, and a
relation with none at all is a cycle rather than an empty answer. Every node carries its
own columns plus its `reports` array, recursively; a leaf carries an empty one.

Both malformed shapes are refused rather than guessed at. A duplicate key is a key
violation, and a cycle is an error rather than a loop:

```java
relix.table("Cyclic", List.of("id", "manager_id", "name"), List.of(
        Map.of("id", 1, "manager_id", 2, "name", "Ada"),
        Map.of("id", 2, "manager_id", 1, "name", "Grace")));

try {
    relix.relation("Cyclic").tree("id", "manager_id", "reports").toList();
} catch (RuntimeException e) {
    System.out.println(e.getClass().getSimpleName() + ": " + e.getMessage());
}
```

```
EvaluationException: TREE: cycle detected in the 'id' → 'manager_id' graph; a tree requires an acyclic, single-parent forest
```

Sibling order is input order unless you ask for something else, and asking is a clause
rather than an argument — which is where writing the expression is easier to read than a
fifth parameter:

```java
Relation ordered = relix.relation("""
        TREE id BY manager_id ORDER name DESC AS reports (Employees)
        """);

Tuple desc = ordered.toList().getFirst();
System.out.println(desc.string("name"));
desc.array("reports").forEach(child -> Chart.print("    ", child));
```

```
Ada
    Lin
        Alan
    Grace
        Edsger
        Barbara
```

`TREE` has to see the whole relation before it can emit a root, so it buffers, and it is
never folded into a backend query.

## Declaring a nested schema

Everything above builds nesting out of flat rows. The other direction is a source that is
already nested — a document store, a JSON payload — and there the interesting decision is
whether to say what the shape is.

A declared schema nests exactly as the values do: `{ … }` is a struct and `[ … ]` an array,
composing to any depth. Here it is against a connector supplying the documents, which is
the shape [Bringing your own data](data-in.md#a-backend-of-your-own) sets up:

```java
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.connector.RelixConnector;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import java.util.Set;

class Directory implements RelixConnector {
    public Set<String> handles() {
        return Set.of("directory");
    }

    // A struct keeps the field order it is given, so build it in one.
    static StructValue struct(String k1, Value v1, String k2, Value v2) {
        Map<String, Value> fields = new java.util.LinkedHashMap<>();
        fields.put(k1, v1);
        fields.put(k2, v2);
        return new StructValue(fields);
    }

    public Stream<Row> open(ConnectorConfig config, String table, Schema schema) {
        return Stream.of(ArrayRow.of(schema,
                new StringValue("Grace"),
                struct("city", new StringValue("Cambridge"),
                       "country", new StringValue("GB")),
                new ArrayValue(List.of(
                        struct("name", new StringValue("Java"), "years", NumberValue.of("9")),
                        struct("name", new StringValue("SQL"), "years", NumberValue.of("4"))))));
    }
}

relix.connector(new Directory());
relix.define("""
        connection people from directory { };
        source Profiles from people { table: "profiles", schema: {
            name: STRING,
            location: { city: STRING, country: STRING },
            skills: [{ name: STRING, years: NUMBER }] } };
        """);

relix.relation("Profiles").toList().forEach(System.out::println);
```

```
(name=Grace, location={city: Cambridge, country: GB}, skills=[{name: Java, years: 9}, {name: SQL, years: 4}])
```

A path through a declared column is typed, which is the whole point of declaring one. A
dotted reference reaches inside the struct and infers the field's own type:

```java
Relation located = relix.relation("π name, location.city → city (Profiles)");

System.out.println(located.schema().columns().stream()
        .map(c -> c.name() + ":" + c.type().display()).toList());
located.toList().forEach(System.out::println);
```

```
[name:string, city:string]
(name=Grace, city=Cambridge)
```

`city` is a `STRING`, not an `ANY`. That difference is not cosmetic: it is what lets the
analyser keep checking your query once the data stops being flat. A field the declaration
does not mention is caught while analysing rather than arriving as a NULL at run time:

```java
relix.validate("Bad := { π location.postcode → pc (Profiles) };")
        .forEach(d -> System.out.println(d.severity() + ": " + d.message()));
```

```
ERROR: Projection π: attribute 'location.postcode' not found in input schema (available: name, location, skills)
```

The array is the other half. `μ` over an array of structs gives one row per element, with
the element in place of the array — and the declaration types those elements too:

```java
Relation flattened = relix.relation("Profiles").unnest("skills");

System.out.println(flattened.schema().columns().stream()
        .map(c -> c.name() + ":" + c.type().display()).toList());
flattened.toList().forEach(System.out::println);
```

```
[name:string, location:struct{city: string, country: string}, skills:struct{name: string, years: number}]
(name=Grace, location={city: Cambridge, country: GB}, skills={name: Java, years: 9})
(name=Grace, location={city: Cambridge, country: GB}, skills={name: SQL, years: 4})
```

Which means the element's fields are reachable from the query, by the same dotted path a
declared struct column takes — the unnest changed the column's *type*, not its status:

```java
Relation skills = relix.relation("""
        π name, skills.name → skill, skills.years → years
          (τ skills.years DESC (μ skills (Profiles)))
        """);

System.out.println(skills.schema().columns().stream()
        .map(c -> c.name() + ":" + c.type().display()).toList());
skills.toList().forEach(System.out::println);
```

```
[name:string, skill:string, years:number]
(name=Grace, skill=Java, years=9)
(name=Grace, skill=SQL, years=4)
```

`skills.name` is worth a second look. `name` is also a top-level column here, and the path
still reads the *skill's* name — a dotted reference is a relation qualifier first and a
struct path second, so `skills.name` can only be a path, because no column called `name`
comes from a relation called `skills`.

The same value is reachable from Java instead, through the `struct` accessor on the row,
which is the route to reach for when the shape is being explored rather than queried:

```java
flattened.toList().forEach(row -> System.out.println(
        row.string("name") + " knows " + row.struct("skills").get("name").asDisplayString()
                + " (" + row.struct("skills").get("years").asDisplayString() + " years)"));
```

```
Grace knows Java (9 years)
Grace knows SQL (4 years)
```

Declare `ANY` where the shape genuinely varies — schema-on-read is what a document store
often wants, and an `ANY` column still reads back through `struct` and `array` at run time.
What it cannot do is tell you at analysis time that you misspelled a field.

