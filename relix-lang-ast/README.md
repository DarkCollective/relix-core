# relix-lang-ast

Immutable AST node types for parsed `.relix` script files. This module contains
only data structures — no parser, no lexer, no runtime logic — and may be used
independently by semantic analysis, tooling, or pretty-printing layers.

## Dependency

```groovy
dependencies {
    implementation project(':relix-lang-ast')
}
```

`relix-lang-ast` depends transitively on `relix-symbol` (for `ScalarType` and
`ParameterDefinition`) and on `relix-ast` (for `RelNode` and `Operand`).

---

## Script root

`Script` is the top-level AST node returned by the parser.

```java
record Script(Optional<String> namespace, List<Statement> statements)
```

| Field | Description |
|---|---|
| `namespace` | Declared namespace; absent means `"default"` |
| `statements` | Ordered top-level statements; may be empty |

---

## Statement hierarchy

```
Statement  (sealed)
├── EnvStatement
├── ImportStatement
├── SourceDeclaration
├── AssignmentStatement
├── DefStatement
└── QueryStatement
```

All statement types are records and therefore immutable. Pattern matching on
`Statement` is exhaustive at compile time.

### `EnvStatement`

Declares the environment file and active profile for credential resolution.

```
env from './.relix-env.json' using 'development';
env from './.relix-env.json';     // no profile → defers to CLI flag
env using 'production';            // no path → uses default location
env;                               // all defaults
```

| Field | Description |
|---|---|
| `filePath` | Path to the JSON env file; absent uses `.relix-env.json` beside the script |
| `activeEnv` | Active profile name; absent defers to `--env` CLI flag or `"development"` |

### `ImportStatement`

Brings symbols from another `.relix` file into the current namespace.

```
import './common.relix';                              // BULK — all exported symbols
import { Users, Orders } from './db.relix';           // UNQUALIFIED
import source current_weather from './api.relix';     // SOURCE
import relation Users, Orders from './db.relix';      // RELATION
import function tax, discount from './calc.relix';    // FUNCTION
```

| Field | Description |
|---|---|
| `kind` | `BULK`, `UNQUALIFIED`, `SOURCE`, `RELATION`, or `FUNCTION` |
| `names` | Symbol names to import; empty for `BULK` |
| `sourcePath` | File path of the target `.relix` file |

### `SourceDeclaration`

Declares a virtual relation backed by an external data source.

```
source current_weather from http { ... };
private source _internal from database { ... };
```

| Field | Description |
|---|---|
| `exported` | `false` when prefixed with `private` |
| `name` | Relation name |
| `config` | Transport and schema config — see [Source configs](#source-configs) |

### `AssignmentStatement`

Gives a name to a relational algebra expression or an inline table.

```
ActiveUsers := { σ status = "active" (Users) };

Cities := [
| name         | country |
|--------------|---------|
| Chicago, IL  | US      |
];

Codes := csv[
    code, label
    "US", "United States"
    "UK", "United Kingdom"
];

private Temp := { σ deleted = false (Raw) };
```

| Field | Description |
|---|---|
| `exported` | `false` when prefixed with `private` |
| `name` | Symbol name |
| `body` | `QueryAssignmentBody` (RA expression) or `InlineTableBody` (table) |

### `DefStatement`

Defines a scalar user-defined function.

```
def double(x: NUMBER): NUMBER := { x * 2 };

def label(score: NUMBER): STRING := {
    CASE WHEN score >= 90 THEN "A" WHEN score >= 70 THEN "B" ELSE "C" END
};

private def helper(x: NUMBER): NUMBER := { x + 1 };
```

| Field | Description |
|---|---|
| `exported` | `false` when prefixed with `private` |
| `name` | Function name |
| `parameters` | Typed parameter list (`List<ParameterDefinition>`) |
| `returnType` | Declared return type (`ScalarType`) |
| `body` | Fully-parsed scalar `Operand` expression |

### `QueryStatement`

Produces output from a named symbol or an inline RA expression.

```
query USCities;
query { π name, country (Cities) };
```

| Field | Description |
|---|---|
| `target` | `NamedQueryTarget` (name reference) or `ExpressionQueryTarget` (inline RA) |

---

## Assignment body types

```
AssignmentBody  (sealed)
├── QueryAssignmentBody  — RelNode from the embedded RA expression
└── InlineTableBody      — InlineTable parsed from [ ... ] or csv[ ... ]
```

---

## Query target types

```
QueryTarget  (sealed)
├── NamedQueryTarget       — record(name: String)
└── ExpressionQueryTarget  — record(expression: RelNode)
```

---

## Source configs

```
SourceConfig  (sealed)
├── HttpSourceConfig       — HTTP/REST API
├── DatabaseSourceConfig   — JDBC table or view
└── CsvFileSourceConfig    — local CSV file
```

### `HttpSourceConfig`

```
source weather from http {
    url:     "https://api.example.com/data/2.5/weather",
    method:  GET,
    headers: { "X-API-Key": "${API_KEY}" },
    extract: json("$."),
    paginate: {
        limit:  query("limit")  [default: 100],
        offset: query("offset") [default: 0]
    },
    schema: {
        city:  in  STRING as query("q")         [required],
        units: in  STRING as query("units")     [default: "metric"],
        temp:  out NUMBER at "$.main.temp",
        desc:  out STRING at "$.weather[0].description"
    }
};
```

| Field | Description |
|---|---|
| `url` | URL template; may contain `${ENV_VAR}` and `{path_param}` references |
| `method` | `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, or `HEAD` |
| `headers` | Static request headers map; values may contain `${VAR}` references |
| `extract` | `JsonExtractSpec` or `CsvExtractSpec` |
| `paginate` | Optional `PaginateSpec` describing offset/page parameter mapping |
| `columns` | Ordered `ColumnSpec` list; must not be empty |

### `DatabaseSourceConfig`

```
source Users from database {
    url:   "${DB_URL}",
    table: "users",
    schema: {
        id:    NUMBER,
        name:  STRING,
        email: STRING
    }
};
```

| Field | Description |
|---|---|
| `url` | JDBC connection URL; may contain `${VAR}` references |
| `table` | Database table or view name |
| `columns` | Ordered `ColumnSpec` list; all columns are implicitly `OUT` |

### `CsvFileSourceConfig`

```
source Products from csv("./products.csv") {
    schema: {
        id:    NUMBER,
        name:  STRING,
        price: NUMBER
    }
};

source Raw from csv("./data.csv", header: false) { ... };
```

| Field | Description |
|---|---|
| `path` | File system path to the CSV file |
| `hasHeader` | `true` if the first row contains column names; `false` for positional matching |
| `columns` | Ordered `ColumnSpec` list |

---

## Column specs and bindings

`ColumnSpec` describes one column in a source schema:

| Field | Description |
|---|---|
| `direction` | `IN` (pushed into request) or `OUT` (extracted from response) |
| `name` | Column name as used in RA queries |
| `type` | `ScalarType`: `NUMBER`, `STRING`, or `ANY` |
| `binding` | Optional `ColumnBinding` — how the column maps to the transport |
| `required` | `true` if a query must supply an equality predicate (IN columns only) |
| `defaultValue` | Literal default when no predicate is given (IN columns only) |

### Column bindings

```
ColumnBinding  (sealed)
├── QueryParamBinding    — as query("q")        → appended as ?q=value
├── PathParamBinding     — as path("id")        → substituted into /items/{id}
├── HeaderBinding        — as header("X-City")  → sent as request header
└── ExtractPathBinding   — at "$.main.temp"     → JSONPath into response body
```

`OUT` columns without an explicit binding are matched by column name against
top-level response fields.

### Factory helpers on `ColumnSpec`

```java
ColumnSpec.out("temp", ScalarType.NUMBER);                        // no binding
ColumnSpec.out("temp", ScalarType.NUMBER, "$.main.temp");         // JSONPath binding
ColumnSpec.requiredIn("city", ScalarType.STRING, "q");            // required query param
ColumnSpec.optionalIn("units", ScalarType.STRING, "units", "metric"); // with default
```

---

## Extract specs

```
ExtractSpec  (sealed)
├── JsonExtractSpec  — record(path: String)    — extract: json("$.items[*]")
└── CsvExtractSpec   — record(hasHeader: bool) — extract: csv(header: true)
```

---

## Pagination spec

`PaginateSpec` holds an ordered list of `PaginateEntry` values:

```
record PaginateEntry(String logicalName, String paramName, Optional<String> defaultValue)
```

Example entries for offset-based pagination:

| `logicalName` | `paramName` | `defaultValue` |
|---|---|---|
| `limit` | `"limit"` | `Optional.of("100")` |
| `offset` | `"offset"` | `Optional.of("0")` |

Lookup by logical name:

```java
spec.entry("limit");   // Optional<PaginateEntry>
```

---

## Inline tables

```
InlineTable  (sealed)
├── MarkdownInlineTable  — pipe-delimited table parsed from [ | ... ]
└── CsvInlineTable       — comma-delimited table parsed from csv[ ... ]
```

Both expose `headers()` and `rows()` — an ordered column name list and an
ordered list of rows, where each row is an ordered list of raw string cell values.

---

## Design notes

* All node types are Java **records** — immutable, value-based, structurally equal.
* Constructor validation throws `NullPointerException` for null required fields
  and `IllegalArgumentException` for blank strings or empty required lists.
* Lists are defensively copied to unmodifiable views in compact constructors.
* Sealed hierarchies are exhaustive: pattern matching on any sealed interface
  produces a compile-time error if a permitted type is unhandled.
