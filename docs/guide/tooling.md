# Building tools on Relix

An editor, a notebook, a command-line shell and an assistant all need more from the engine
than a query's rows. They read scripts from somewhere of their own, colour text as the
user types it, and learn the joins a user keeps writing. This page covers the three
methods that serve them. The command-line tool and the interactive shell that ship with
Relix are built on these methods and nothing else.

## Parsing a script

`Relix.parse` turns text into a `Script`, the tree `define(Statement...)` takes, without
analysing it. Nothing is resolved, so a script may name relations nothing declares:

```java
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.Statement;

Script script = Relix.parse("""
        Large := { σ amount > 100 (Orders) };
        query Large;
        """);
for (Statement statement : script.statements()) {
    System.out.println(statement.getClass().getSimpleName());
}
```

```
AssignmentStatement
QueryStatement
```

Text that does not parse raises a `ScriptParseException`, which carries the line and
column of the failure:

```java
import com.darkcollective.relix.lang.ast.ScriptParseException;

try {
    Relix.parse("query { σ amount > (Orders) };");
} catch (ScriptParseException e) {
    System.out.println("line " + e.line() + ", column " + e.column());
}
```

```
line 1, column 29
```

A `ScriptLoader` is where parsing usually happens. A session resolves each `import`
through its loader, and a loader reads the file and parses it, naming the file so a
failure inside it points there. This one serves files from memory:

```java
import java.util.Map;

Map<String, String> files = Map.of("orders.relix", """
        Orders := [| id | amount |
                   | 1  | 120    |
                   | 2  | 80     |];
        """);

try (Relix withImports = Relix.builder()
        .scriptLoader(path -> Relix.parse(files.get(path), path))
        .build()) {
    withImports.define("import \"orders.relix\";");
    System.out.println(withImports.relation("Orders").count());
}
```

```
2
```

## Highlighting as the user types

`Relix.tokens` splits text into classified tokens for syntax highlighting. It is lenient,
because an editor asks on every keystroke: the well-formed part is tokenized as usual and
the rest becomes one `INCOMPLETE` token. Comments are tokens and whitespace is not.
Offsets count `char`s, as `String.charAt` does.

```java
import com.darkcollective.relix.embed.Token;

String line = "σ amount > 100 (Orders) -- the large ones";
for (Token token : Relix.tokens(line)) {
    System.out.println(token.kind() + "  " + line.substring(token.start(), token.end()));
}
```

```
OPERATOR  σ
IDENTIFIER  amount
COMPARISON  >
NUMBER  100
BRACKET  (
IDENTIFIER  Orders
BRACKET  )
COMMENT  -- the large ones
```

Half-typed text still colours up to the point it stops making sense:

```java
String typing = "π name (\"unfinished";
for (Token token : Relix.tokens(typing)) {
    System.out.println(token.kind() + "  " + typing.substring(token.start(), token.end()));
}
```

```
OPERATOR  π
IDENTIFIER  name
BRACKET  (
INCOMPLETE  "unfinished
```

## Learning the joins a user writes

A session's schema graph records how relations join: a `relate` statement declares an
edge, and `Builder.relationships` supplies more. A tool can also learn edges from the
queries its user writes. `demonstratedRelationships()` on a `SemanticModel` lists the
column equalities written across a join, in a query or a view, that the graph does not
already record:

```java
import com.darkcollective.relix.symbol.graph.Relationship;

String data = """
        Users := [| id | name |
                  | 1  | Ada  |
                  | 2  | Bo   |];
        Issues := [| id | title | assignee | reporter |
                   | 10 | Crash | 1        | 2        |];
        """;

List<Relationship> learned;
try (Relix first = Relix.open()) {
    first.define(data + "Assigned := { Issues ⨝ Issues.assignee = Users.id Users };");
    learned = first.model().demonstratedRelationships();
}
for (Relationship edge : learned) {
    System.out.println(edge.name() + "  " + edge.origin());
}
```

```
Issues_assignee  LEARNED
```

Nothing ran to learn that: an analysis alone says which columns a join equated.
`resolveJoins` goes the other way, assembling the join between relations from the edges
the graph records. Given the learned edge, a later session can write the join without its
condition:

```java
import com.darkcollective.relix.symbol.graph.JoinResolution;
import com.darkcollective.relix.symbol.graph.SchemaGraph;

try (Relix later = Relix.builder()
        .relationships(SchemaGraph.EMPTY.with(learned.getFirst()))
        .build()) {
    later.define(data);
    JoinResolution resolution =
            later.model().resolveJoins(later.relation("Issues ⋈ Users").node());
    if (resolution instanceof JoinResolution.Resolved resolved) {
        System.out.println(resolved.program());
    }
}
```

```
(Issues) ⨝ Issues.assignee = Users.id (Users)
```

When more than one edge could join the relations, the answer is `Ambiguous` and lists the
edges by name, which is a question a user can answer. With both of an issue's links to a
user declared:

```java
try (Relix tracker = Relix.open()) {
    tracker.define(data + """
            relate "Assignee" Issues.assignee -> Users.id;
            relate "Reporter" Issues.reporter -> Users.id;
            """);
    JoinResolution resolution =
            tracker.model().resolveJoins(tracker.relation("Issues ⋈ Users").node());
    if (resolution instanceof JoinResolution.Ambiguous ambiguous) {
        for (JoinResolution.Alternative alternative : ambiguous.alternatives()) {
            System.out.println(alternative.label() + ": " + alternative.description());
        }
    }
}
```

```
Assignee: Issues.assignee = Users.id
Reporter: Issues.reporter = Users.id
```

`Relationship.edgeIdentity()` says when two edges join the same columns whatever they are
called. A tool that lets its user reject a learned edge keeps the identities it rejected
and skips any candidate that matches one.

## The language reference

The language reference ships in the artifact, so a tool can show a user the page for what
they typed with nothing else installed. `Relix.referencePages()` lists every page with the
words it is found by, and `Relix.referencePage(path)` returns its markdown:

```java
import com.darkcollective.relix.embed.ReferencePage;

ReferencePage selection = Relix.referencePages().stream()
        .filter(page -> page.keys().contains("σ"))
        .findFirst().orElseThrow();
System.out.println(selection.title() + " (" + selection.symbol() + "): " + selection.summary());
System.out.println(Relix.referencePage(selection.path()).orElseThrow().lines().findFirst().orElseThrow());
```

```
Selection (σ): Filter rows by a condition
# Name: Selection (σ / SELECT)
```

A function's page is not among them: it belongs to the library that offers the function,
and `FunctionCatalog.documentation(docKey)` serves it, so a function library installed
later is documented the same way the shipped one is.

## Connectors and drivers

A tool that manages plugins asks where they live and which are installed, and a
command-line tool registers the JDBC drivers an earlier run downloaded before it opens a
session:

```java
import com.darkcollective.relix.connectors.std.DriverProvisioner;
import com.darkcollective.relix.processor.connector.ConnectorProvisioner;

DriverProvisioner.loadInstalled();
System.out.println(new java.util.TreeSet<>(ConnectorProvisioner.installedTypes()));
```

```
[clf, csv, gedcom, log]
```

`ConnectorProvisioner.defaultDirectory()` is where a plugin is installed, and
`loadInstalled()` returns one description per driver it registered, for a startup log.
