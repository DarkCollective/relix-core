# relix-lang

Hand-written lexer and recursive-descent parser for `.relix` scripting language
source files. Parses the full script grammar and returns an immutable
[`Script`](../relix-lang-ast/README.md) AST ready for semantic analysis or
execution.

## Dependency

```groovy
dependencies {
    implementation project(':relix-lang')
}
```

`relix-lang` depends on `relix-lang-ast` (the AST types it produces) and on
`relix-parser` (for delegating embedded RA expressions to `RelAlgebraParser`).

---

## Usage

```java
// From a string
Script script = ScriptParser.parse(sourceText);

// From an InputStream (UTF-8)
try (InputStream in = Files.newInputStream(path)) {
    Script script = ScriptParser.parse(in);
}
```

Both entry points throw `LangParseException` on syntax errors and
`NullPointerException` if the argument is null. The `InputStream` variant
additionally throws `IOException` if reading fails.

---

## Error handling

`LangParseException` is an unchecked exception carrying the source position of
the error:

```java
try {
    Script script = ScriptParser.parse(source);
} catch (LangParseException e) {
    System.err.printf("Parse error at line %d, col %d: %s%n",
            e.line(), e.column(), e.getMessage());
}
```

The message describes what was unexpected and, where applicable, what was
expected instead.

---

## Script grammar

```
script
    ::= namespace_decl? env_decl? statement* EOF

namespace_decl
    ::= 'namespace' IDENT ';'

env_decl
    ::= 'env' ('from' STRING)? ('using' STRING)? ';'

statement
    ::= import_stmt
      | source_decl
      | assignment_stmt
      | def_stmt
      | query_stmt
      | 'private' ( source_decl | assignment_stmt | def_stmt )
```

### Import

```
import_stmt
    ::= 'import' STRING ';'                                   -- bulk
      | 'import' '{' name (',' name)* '}' 'from' STRING ';'  -- unqualified
      | 'import' 'source'   name (',' name)* 'from' STRING ';'
      | 'import' 'relation' name (',' name)* 'from' STRING ';'
      | 'import' 'function' name (',' name)* 'from' STRING ';'
```

### Assignment

```
assignment_stmt
    ::= name ':=' '{' RA_EXPR '}' ';'          -- RA expression (→ QueryAssignmentBody)
      | name ':=' '[' MARKDOWN_TABLE ']' ';'   -- Markdown table (→ InlineTableBody)
      | name ':=' 'csv' '[' CSV_TABLE ']' ';'  -- CSV table     (→ InlineTableBody)
```

### Function definition

```
def_stmt
    ::= 'def' name '(' param_list? ')' ':' TYPE ':=' '{' OPERAND_EXPR '}' ';'

param_list
    ::= param (',' param)*

param
    ::= name ':' TYPE

TYPE
    ::= 'NUMBER' | 'STRING' | 'ANY'
```

### Query

```
query_stmt
    ::= 'query' name ';'           -- named target
      | 'query' '{' RA_EXPR '}' ';' -- inline RA expression
```

### Source declaration

```
source_decl
    ::= 'source' name 'from' source_kind '{' source_body '}' ';'

source_kind
    ::= 'http'
      | 'database'
      | 'csv' '(' STRING (',' 'header' ':' BOOL)? ')'
```

#### HTTP source body

```
source_body (http)
    ::= http_field (',' http_field)* ','?

http_field
    ::= 'url'      ':' STRING
      | 'method'   ':' HTTP_METHOD
      | 'headers'  ':' '{' (STRING ':' STRING (',' STRING ':' STRING)*)? '}'
      | 'extract'  ':' extract_spec
      | 'paginate' ':' '{' paginate_entry (',' paginate_entry)* ','? '}'
      | 'schema'   ':' '{' column_spec (',' column_spec)* ','? '}'

extract_spec
    ::= 'json' '(' STRING ')'
      | 'csv'  '(' 'header' ':' BOOL ')'

paginate_entry
    ::= name ':' 'query' '(' STRING ')' ('[' 'default' ':' NUMBER_LIT ']')?

column_spec
    ::= name ':' direction? TYPE binding? modifier?

direction
    ::= 'in' | 'out'

binding
    ::= 'as' 'query'  '(' STRING ')'
      | 'as' 'path'   '(' STRING ')'
      | 'as' 'header' '(' STRING ')'
      | 'at' STRING

modifier
    ::= '[' 'required' ']'
      | '[' 'default' ':' STRING ']'

HTTP_METHOD
    ::= 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD'
```

#### Database source body

```
source_body (database)
    ::= 'url' ':' STRING ','
        'table' ':' STRING ','
        'schema' ':' '{' simple_column_spec (',' simple_column_spec)* ','? '}'

simple_column_spec
    ::= name ':' TYPE
```

#### CSV file source body

```
source_body (csv-file)
    ::= 'schema' ':' '{' simple_column_spec (',' simple_column_spec)* ','? '}'
```

---

## Embedded sub-languages

Two sub-languages are handled by delegation rather than inline grammar rules:

| Context | Parser called | Entry point |
|---|---|---|
| `name := { … }` and `query { … }` | `RelAlgebraParser` | `RelAlgebraParser.parse(text, line, col)` |
| `def … := { … }` function body | `RelAlgebraParser` | `RelAlgebraParser.parseOperand(text, line, col)` |

The raw text between the braces (exclusive) is extracted by the lexer's
`consumeRawBraceBlock()` method and passed directly to the RA parser. Position
offsets are threaded through so error messages from the sub-language reference
the correct line and column in the original source file.

Similarly, `[ … ]` and `csv[ … ]` blocks are extracted by
`consumeRawBracketBlock()` and parsed as Markdown or CSV tables.

---

## Lexer details

`LangLexer` is a hand-written, single-pass lexer operating on an in-memory
string. It produces `LangToken` values on demand via `next()`.

### Comment syntax

| Style | Syntax |
|---|---|
| Line comment | `-- comment to end of line` (SQL style) |
| Block comment | `/* multi-line comment */` (C style) |

### String literals

Double-quoted strings: `"hello"`. The only recognised escape sequence inside a
string literal is `\"`. `${VAR}` placeholders are preserved verbatim in the
token value and resolved at runtime.

### Number literals

Positive integers and decimals only: `42`, `3.14`. Negative numbers are not part
of this grammar; `-` is always a `DASH` token.

### Keyword case sensitivity

Keyword matching is **case-insensitive**: the scanner lower-cases each lexeme
before the keyword lookup, so `source`, `SOURCE`, and `Source` are the same
token, and a keyword is reserved regardless of how it is spelled. The original
casing is preserved on the token for display. This matches the RA expression
parser in `relix-parser`.

The cases below are the **conventional** spellings (stylistic only — any casing
parses identically):

| Token group | Conventional case |
|---|---|
| HTTP method tokens (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`) | Uppercase |
| Scalar type tokens (`NUMBER`, `STRING`, `ANY`) | Uppercase |
| All other keywords | Lowercase |

### Name-compatible keywords

Many keywords (e.g. `url`, `table`, `schema`, `source`, `header`) may appear as
column names in schema blocks without quoting. `LangTokenType.isNameCompatible()`
returns `true` for these tokens. HTTP method tokens (`GET`, `POST`, …) are the
primary non-name-compatible keywords.

---

## Public API

| Class | Role |
|---|---|
| `ScriptParser` | Static entry points `parse(String)` and `parse(InputStream)` |
| `LangParseException` | Thrown on syntax errors; carries `line()` and `column()` |
| `LangToken` | Token value object: `type()`, `value()`, `line()`, `column()`, `describe()` |
| `LangTokenType` | Enum of all token types; `isNameCompatible()` for name contexts |

`LangLexer` is package-private; it is not part of the public API.
