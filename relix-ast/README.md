# relix-ast

Immutable AST node types, predicates, operands, and visitor infrastructure for
relational algebra expressions. This module is parser-free and carries no runtime
dependencies beyond the JDK.

## Purpose

`relix-ast` provides the in-memory representation used by the `relix-parser` module
when it parses a relational algebra expression, and by any downstream consumer that
wants to build, inspect, or transform such an expression programmatically.

## Package structure

| Package | Contents |
|---------|----------|
| `com.darkcollective.relix.ast` | Sealed node hierarchies, enums, and value objects |
| `com.darkcollective.relix.ast.visitor` | Visitor interfaces and `PrettyPrinter` implementation |

## Node hierarchies

The AST is built from three sealed interface hierarchies.

### `RelNode` — relational operations

| Node | Symbol | Description |
|------|--------|-------------|
| `RelationNode` | _R_ | Base relation (table) reference |
| `SelectionNode` | σ | Filter rows by a predicate |
| `ProjectionNode` | π | Choose and optionally rename/compute columns |
| `RenameNode` | ρ | Rename a relation and/or its attributes |
| `DistinctNode` | δ | Eliminate duplicate rows |
| `SortNode` | τ | Order rows by one or more attributes |
| `LimitNode` | λ | Restrict to at most _n_ rows, with optional offset |
| `AggregationNode` | γ | Group and aggregate (SUM, AVG, COUNT, MIN, MAX) |
| `NaturalJoinNode` | ⋈ | Natural join on shared attribute names |
| `ThetaJoinNode` | ⨝ | Inner join on an explicit predicate |
| `LeftOuterJoinNode` | ⟕ | Left outer join |
| `RightOuterJoinNode` | ⟖ | Right outer join |
| `FullOuterJoinNode` | ⟗ | Full outer join |
| `SemiJoinNode` | ⋉ | Semi-join (keep left tuples that match right) |
| `AntiJoinNode` | ▷ | Anti-join (keep left tuples that do not match right) |
| `ProductNode` | × | Cartesian product |
| `UnionNode` | ∪ | Set union |
| `UnionAllNode` | ⊎ | Multiset union (duplicates preserved) |
| `DifferenceNode` | − | Set difference |
| `IntersectionNode` | ∩ | Set intersection |
| `DivisionNode` | ÷ | Relational division |

### `Predicate` — filter conditions

| Node | Description |
|------|-------------|
| `ComparisonPredicate` | Binary comparison (=  ≠  <  ≤  >  ≥) |
| `AndPredicate` | Logical conjunction (∧) |
| `OrPredicate` | Logical disjunction (∨) |
| `NotPredicate` | Logical negation (¬) |
| `NullPredicate` | Null test: `attr = ⊥` (IS NULL) or `attr ≠ ⊥` (IS NOT NULL) |
| `ElementOfPredicate` | Set membership: `expr ∈ set` or `expr ∉ set` |

### `Operand` — scalar value expressions

| Node | Description |
|------|-------------|
| `AttributeOperand` | Column reference, optionally qualified (`Users.id`) |
| `NumberOperand` | Numeric literal stored as a string (`42`, `3.14`) |
| `StringOperand` | String literal |
| `BooleanOperand` | Boolean literal (`true` / `false`) |
| `BinaryArithmeticExpression` | Binary arithmetic (+  −  *  /) with precedence |
| `UnaryOperand` | Unary negation (−) |
| `FunctionCall` | Named function with argument list |
| `SetLiteralOperand` | Explicit set `{a, b, c}` used with ∈/∉ |

## Design notes

* All node types are Java **records** — immutable, value-based, and structurally
  equal. Two nodes with the same fields always compare as equal via `equals`.
* Lists are defensively copied to unmodifiable views in compact constructors.
* Constructor validation throws `NullPointerException` for missing required fields
  and `IllegalArgumentException` for semantically invalid values.

## Visitor pattern

Every node exposes an `accept` method; call it with an implementation of the
corresponding visitor interface:

```java
// RelNodeVisitor<R>  — for RelNode trees
// OperandVisitor<R>  — for Operand expressions
// PredicateVisitor<R> — for Predicate conditions

RelNode tree = ...;
String pretty = tree.accept(new PrettyPrinter());
// or equivalently:
String pretty = tree.prettyPrint();
```

Because the sealed hierarchies are exhaustive, any visitor that does not implement
every `visit` overload will produce a compile-time error.

## Pretty printing

`PrettyPrinter` serialises the entire tree to a Unicode relational algebra string.
The output is designed to round-trip through the `relix-parser` module:

```
π id, name → full_name (σ age ≥ 18 (Users))
```

Call `RelNode.prettyPrint()` as a convenience shorthand.

## Test fixtures

The `testFixtures` source set exports `AstBuilders`, a class of static factory
helpers (e.g. `attr("name")`, `num("42")`, `cmp(...)`, `arith(...)`) used by both
`relix-ast` and `relix-parser` tests. Add the dependency with:

```groovy
testImplementation(testFixtures(project(':relix-ast')))
```
