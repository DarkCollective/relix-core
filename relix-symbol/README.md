# relix-symbol

Symbol table, type system, and relation/function symbol hierarchy for the relix system.

This module provides the data structures needed during semantic analysis to record which names are in scope, what columns relations have, and what functions are available. It contains no parsing and no query-execution logic; it is a pure representation and lookup layer.

## Dependency

```groovy
dependencies {
    implementation project(':relix-symbol')
}
```

`relix-symbol` depends on `relix-ast` for `Operand` (used in function bodies) and `RelNode` (used in query relation bodies).

---

## Type system

```
ScalarType
├── ANY      — unconstrained; the default when no type annotation is present
├── STRING   — textual values
├── NUMBER   — integer or decimal numeric values
└── BOOLEAN  — true / false
```

`ANY` is the top type. A column or parameter typed `ANY` is compatible with all other types at analysis time, which keeps minimal `.relix` files free of verbosity.

```java
ScalarType.fromString("string");  // → STRING  (case-insensitive)
ScalarType.fromString("NUMBER");  // → NUMBER
```

---

## Symbol hierarchy

```
Symbol  (sealed)
├── RelationSymbol  (sealed)
│   ├── DatabaseRelationSymbol   — external table/view; schema only, data lives elsewhere
│   ├── InlineRelationSymbol     — rows declared in source (markdown tables)
│   └── QueryRelationSymbol      — named relational algebra expression (stored view)
└── FunctionSymbol  (sealed)
    └── ScalarFunctionSymbol     — scalar function; built-in or user-defined
```

Every symbol carries:

| Property | Description |
|---|---|
| `namespace()` | Namespace string; `"default"` for user symbols, `"builtin"` for runtime-provided |
| `declaredName()` | Case-preserved name as written by the user or runtime |
| `canonicalName()` | Lower-cased form; used as the lookup key (always case-insensitive) |
| `provenance()` | `BUILTIN` or `USER` |
| `shadowPolicy()` | `PERMITTED`, `FORBIDDEN`, or `WARN_AND_PERMIT` |

---

## Schemas

A `Schema` is an ordered list of named, typed `ColumnDefinition`s. Column names are case-insensitive for lookup purposes.

```java
Schema schema = new Schema(List.of(
    new ColumnDefinition("id",   ScalarType.NUMBER),
    new ColumnDefinition("name", ScalarType.STRING)
));

schema.column("ID");   // Optional.of(ColumnDefinition("id", NUMBER))
schema.width();        // 2
```

Duplicate column names (case-insensitively equal) are rejected at construction time.

---

## Relation symbols

### Database relation

Represents a table or view in an external store. Use `of()` for user-defined symbols or `builtin()` for runtime-provided ones.

```java
DatabaseRelationSymbol users = DatabaseRelationSymbol.of("Users", schema);
// namespace="default", provenance=USER, shadowPolicy=PERMITTED

DatabaseRelationSymbol syslog = DatabaseRelationSymbol.builtin("SysLog", schema);
// namespace="builtin", provenance=BUILTIN, shadowPolicy=FORBIDDEN
```

### Inline relation

Rows defined directly in source (e.g. parsed from a Markdown table). Column types are inferred by the loader: a column whose every cell parses as a number is typed `NUMBER`; otherwise `STRING`.

```java
List<Map<String, Operand>> rows = List.of(
    Map.of("id", new NumberOperand("1"), "name", new StringOperand("Alice")),
    Map.of("id", new NumberOperand("2"), "name", new StringOperand("Bob"))
);
InlineRelationSymbol r = InlineRelationSymbol.of("Employees", schema, rows);
```

### Query relation

A named relational algebra expression — a stored view.

```java
RelNode body = RelAlgebraParser.parse("σ status = \"active\" (Users)");
QueryRelationSymbol active = QueryRelationSymbol.of("ActiveUsers", schema, body);
```

---

## Function symbols

All functions are `ScalarFunctionSymbol` instances, constructed via the fluent builder. Sensible defaults apply to every optional field, so a minimal function requires only a name.

```java
// Zero-argument user function
ScalarFunctionSymbol now = ScalarFunctionSymbol.builder("now")
    .returnType(ScalarType.NUMBER)
    .build();

// Built-in, pure, deterministic length() function
ScalarFunctionSymbol length = ScalarFunctionSymbol.builder("length")
    .namespace("builtin")
    .provenance(Provenance.BUILTIN)
    .shadowPolicy(ShadowPolicy.FORBIDDEN)
    .parameter("s", ScalarType.STRING)
    .returnType(ScalarType.NUMBER)
    .property(FunctionProperty.PURE)
    .property(FunctionProperty.DETERMINISTIC)
    .build();

// User-defined function with a scalar body expression
ScalarFunctionSymbol double_ = ScalarFunctionSymbol.builder("double")
    .parameter("x", ScalarType.NUMBER)
    .returnType(ScalarType.NUMBER)
    .body(new BinaryArithmeticExpression(
        new AttributeOperand("x"), ArithmeticOperator.MULTIPLY, new NumberOperand("2")))
    .build();
```

### Function properties (optimizer hints)

| Property | Contract |
|---|---|
| `COMMUTATIVE` | `f(a, b) = f(b, a)` |
| `DETERMINISTIC` | Same inputs → same output; calls may be cached |
| `IDEMPOTENT` | `f(f(x)) = f(x)` |
| `PURE` | No side-effects, no hidden state; implies `DETERMINISTIC` |

### Function overloading

Two functions with the same name but different parameter signatures coexist as distinct overloads. The overload key is `(namespace, canonicalName, parameterSignature)`.

```java
registerOk(table, ScalarFunctionSymbol.builder("f").parameter("x", ScalarType.NUMBER).build());
registerOk(table, ScalarFunctionSymbol.builder("f").parameter("s", ScalarType.STRING).build());

table.lookupFunction("f");                          // both overloads
table.lookupFunction("f", List.of(ScalarType.NUMBER)); // the numeric overload
```

---

## Symbol table

`InMemorySymbolTable` is the standard in-process implementation. It is **not thread-safe**.

```java
SymbolTable table = new InMemorySymbolTable();

RegistrationResult r = table.register(DatabaseRelationSymbol.of("Users", schema));
r.isSuccess();  // true

// Case-insensitive lookup
table.lookupRelation("users");   // searches "default" then "builtin"
table.lookupRelation("builtin", "SysLog");

table.lookupFunction("length");                             // all overloads
table.lookupFunction("length", List.of(ScalarType.STRING)); // exact overload

table.allSymbols(); // snapshot in registration order
```

### Registration results

Registration never throws on conflict. Instead it returns a `RegistrationResult`:

| Outcome | `registered()` | `errors()` | Predicate |
|---|---|---|---|
| Success | `true` | empty | `isSuccess()` |
| Replaced with warning | `true` | `SHADOW_WARNING` | `hasWarnings()` |
| Rejected | `false` | `SHADOW_FORBIDDEN` | `isRejected()` |

### Shadow policy

The **existing** symbol's `ShadowPolicy` governs replacement:

| Policy | Behaviour |
|---|---|
| `PERMITTED` | New symbol replaces old; no diagnostic |
| `FORBIDDEN` | New symbol rejected; table unchanged |
| `WARN_AND_PERMIT` | New symbol replaces old; `SHADOW_WARNING` added to result |

Built-in symbols default to `FORBIDDEN`. User symbols default to `PERMITTED`.

---

## Persistence extension point

`SymbolRepository` is a thin interface for future durable back-ends:

```java
public interface SymbolRepository {
    void save(Symbol symbol);
    Optional<RelationSymbol> findRelation(String namespace, String canonicalName);
    List<FunctionSymbol> findFunctions(String namespace, String canonicalName);
    List<Symbol> findAll();
}
```

`InMemorySymbolTable` does not use this interface. A persistence-backed implementation of `SymbolTable` would delegate to a `SymbolRepository` implementation.
