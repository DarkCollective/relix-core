# Sandboxing a session

A session is open by default: it accepts every declaration the language has, including a
`source` that reads any file the process can read and a `connection` to any database it
can reach. That is right when you wrote the script. It is not right when the script comes
from someone else, such as a learner in a tutorial or a language model answering a
question, and you want them to explore a fixed set of data and nothing more.

A closed `Sandbox` handles that case. User text can still declare inline tables, views
and functions. It can only reach outside the session through the sources and connections
the sandbox itself provides, and every query runs under the sandbox's limits.

## A closed sandbox

The sandbox's declarations are the data it offers. Here that is one CSV file, and the
sandbox also caps a result at two rows:

```java
import com.darkcollective.relix.embed.Sandbox;
import java.nio.file.Files;
import java.nio.file.Path;

Path data = Files.createTempDirectory("sandbox");
Files.writeString(data.resolve("orders.csv"), """
        id,customer,amount
        1,Ada,120
        2,Bo,80
        3,Cy,200
        """);

Sandbox sandbox = Sandbox.builder()
        .baseDirectory(data)
        .declarations("""
                source Orders from csv("orders.csv") {
                    schema: { id: NUMBER, customer: STRING, amount: NUMBER }
                };
                """)
        .maxOutputRows(2)
        .build();

Relix learner = Relix.builder().sandbox(sandbox).build();

learner.define("Large := { σ amount > 100 (Orders) };");
System.out.println(learner.relation("Large").toList());
```

```
[(id=1, customer=Ada, amount=120), (id=3, customer=Cy, amount=200)]
```

`Orders` was declared by the sandbox, so it is there to query. The view `Large` is the
user's own, and a view reads nothing outside the session, so it is accepted.

## What is refused

A declaration that reaches outside the session is refused unless the sandbox declares
the same one. That covers a `source` over a file, a database or an HTTP endpoint, a
`connection`, an `import` and an `env` statement. The refusal is a
`SandboxViolationException`, and nothing from the refused call is installed:

```java
import com.darkcollective.relix.embed.SandboxViolationException;

try {
    learner.define("""
            source Secrets from csv("/etc/passwd") { schema: { line: STRING } };
            """);
} catch (SandboxViolationException e) {
    System.out.println(e.getMessage());
}
```

```
this session's sandbox does not permit the source 'Secrets'; it accepts inline tables, views, functions and the sources and connections it was configured with
```

A user may repeat one of the sandbox's own declarations, for instance because their
script is a complete file that declares what it reads. The comparison is on the
declaration as printed, so layout and comments do not matter, but every value must
match:

```java
learner.define("""
        -- the same source, laid out differently
        source Orders from csv("orders.csv") { schema: { id: NUMBER,
                                                       customer: STRING,
                                                       amount: NUMBER } };
        """);
System.out.println(learner.relation("Orders").count());
```

```
3
```

A generator source reads nothing, so it is accepted like an inline table.

`validate(...)` reports a refusal as a diagnostic instead of throwing, which suits a tool
that shows problems to its user:

```java
System.out.println(learner.validate("connection wh from jdbc { url: \"jdbc:h2:mem:x\" };"));
```

```
[error: this session's sandbox does not permit the connection 'wh'; it accepts inline tables, views, functions and the sources and connections it was configured with]
```

The rules apply to text and statements given to `define`, `script`, `relation` and
`validate`. The Java registration methods (`table`, `source`, `connector`, and the
builder's `jdbc` bindings) are the host program's own, and are not restricted.

## Limits

The output limit cuts a longer result at that many rows. `run()` says when it did:

```java
Rows all = learner.relation("Orders").run();
System.out.println(all.size() + " rows, truncated: " + all.truncated());
```

```
2 rows, truncated: true
```

`toList()` and `stream()` are cut the same way, but they carry no events, so a host that
must tell its user about the cut uses `run()`, or `stream(listener)` and watches for the
`EXECUTE`/`TRUNCATED` event. `count()` returns a single row, so it counts the whole
relation.

| Limit | What it bounds |
|---|---|
| `maxInputChars` | The length of the text one call may pass. Longer text is refused before it is parsed. |
| `maxOutputRows` | The rows one query returns. |
| `maxMaterializedRows` | The rows one blocking operator may buffer. |
| `maxFixpointRounds` | The rounds one recursion may run. |
| `maxProcessedRows` | The work one execution may do, counted as rows passed from one operator to the next. |
| `timeoutMillis` | How long one execution may run. |

The last four are also the builder's own caps. When both the builder and the sandbox set
one, the smaller wins.

The output limit cannot stop a query that works for a long time and produces little. A
selection over an endless generator that matches nothing never yields a row, so it never
reaches the output limit. The last two limits are for that case:

```java
Relix bounded = Relix.builder()
        .sandbox(Sandbox.builder().maxProcessedRows(10_000).build())
        .build();
bounded.define("source Naturals from generator { name: \"Naturals\" };");

try (Stream<Tuple> rows = bounded.relation("σ Len(CStr(n)) = 0 (Naturals)").stream()) {
    rows.toList();
} catch (RelixException e) {
    System.out.println(e.getMessage());
}
```

```
query stopped: it processed more than 10000 rows, the limit for one execution
```

`maxProcessedRows` counts every row an operator hands to the one above it, so the same
query over the same data stops at the same point on any machine. The timeout is checked
at the same points, so it catches anything that moves rows, but it depends on the
machine. It cannot interrupt a call that never returns to the engine, such as a JDBC
driver waiting on its database. Prefer `maxProcessedRows` for a reproducible limit and
use the timeout as a backstop.

## A configuration file

A sandbox is usually configured rather than coded, so the data on offer can change
without a rebuild. `Sandbox.load` reads a JSON file:

```java
Files.writeString(data.resolve("sandbox.relix"), """
        source Orders from csv("orders.csv") {
            schema: { id: NUMBER, customer: STRING, amount: NUMBER }
        };
        """);
Files.writeString(data.resolve("sandbox.json"), """
        {
          "declarations": "sandbox.relix",
          "limits": { "maxInputChars": 20000, "maxOutputRows": 1000,
                      "maxProcessedRows": 10000000, "timeoutMillis": 30000 }
        }
        """);

Sandbox configured = Sandbox.load(data.resolve("sandbox.json"));
System.out.println(configured);
```

```
Sandbox[closed, maxInputChars=20000, maxOutputRows=1000, maxMaterializedRows=unlimited, maxFixpointRounds=unlimited, maxProcessedRows=10000000, timeout=PT30S]
```

`declarations` names a `.relix` file relative to the configuration file, and relative
paths inside it resolve against the configuration file's directory. Every key is
optional, and an unknown key is refused, so a misspelt limit fails instead of silently
not applying. The declarations may use `${NAME}` placeholders, which keeps credentials
out of the file (see [Secrets in declarations](secrets.md)).

A session built without a sandbox, or with `Sandbox.open()`, enforces nothing.
