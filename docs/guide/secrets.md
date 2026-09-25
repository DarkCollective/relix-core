# Secrets in declarations

A source that calls an API needs a token, and a connection to a database needs a password.
Where the value comes from is the program's business: an environment variable, a vault, a
key-management service, a configuration file. What the session needs is a way to be handed
it without the value becoming part of the script.

## A placeholder, resolved when a query runs

Write `${NAME}` wherever a declaration holds the value, and give the session a function
that answers for each name:

```java
import java.util.Optional;

Map<String, String> vault = Map.of("ORDERS_TOKEN", "tok-4f9a");

Relix secured = Relix.builder()
        .placeholders(name -> Optional.ofNullable(vault.get(name)))
        .build();

secured.define("""
        source Orders from http {
            url:     "https://api.example.com/orders",
            headers: { "Authorization": "Bearer ${ORDERS_TOKEN}" },
            schema:  { id: NUMBER, status: STRING }
        };
        """);

System.out.println(secured.definitions());
```

```
source Orders from http { url: "https://api.example.com/orders", method: GET, headers: { "Authorization": "Bearer ${ORDERS_TOKEN}" }, schema: { id: number, status: string } };
```

The placeholder stays in the declaration. It is resolved each time a query runs, for the
declarations that query reaches, and the value goes to the connector for that run and
nowhere else. Three things follow from that:

- **Nothing to escape.** The value never passes through the parser, so a token holding a
  quote or a backslash is sent exactly as the resolver returned it.
- **Nothing to leak.** `definitions()`, `ir()` and an analysis error print the session's
  declarations, and those hold `${ORDERS_TOKEN}`, not the token.
- **Rotation works.** The resolver is asked again on the next run, so a credential that
  changed is picked up without redefining anything.

For environment variables the resolver is one line:
`name -> Optional.ofNullable(System.getenv(name))`.

What is resolved is the string **values** of a declaration: URLs, file paths, table names,
header values, request bodies, the credentials in `auth:`, column defaults and a
connection's properties. Names (of a header, a column, a property) are not. Within one run
each name is asked for once. Analysis asks too, when it introspects a connection's tables.

A session built without `placeholders(...)` leaves `${…}` exactly as written.

## A name with no value

When the resolver returns empty for a name a query needs, the query fails before anything
is sent, and the error names both the placeholder and the declaration holding it:

```java
secured.define("""
        source Invoices from http {
            url:     "https://api.example.com/invoices",
            auth:    bearer("${INVOICES_TOKEN}"),
            schema:  { id: NUMBER }
        };
        """);

try {
    secured.relation("Invoices").toList();
} catch (RelixException e) {
    System.out.println(e.getMessage());
}
```

```
placeholder ${INVOICES_TOKEN} in source 'Invoices' has no value: the session's placeholder resolver returned none
```

Only the declarations a query reaches are resolved, so a missing value for one source does
not stop a query over another. `Orders` above still runs.

## Declarations built in Java

A program that already holds the value can build the declaration with `ScriptBuilders`
and hand it over with `define(Statement...)`. The header map is a plain Java map, so
nothing needs escaping:

```java
import com.darkcollective.relix.lang.ast.source.HttpMethod;
import static com.darkcollective.relix.lang.ast.ScriptBuilders.httpSource;
import static com.darkcollective.relix.lang.ast.ScriptBuilders.source;

Relix typed = Relix.open();
typed.define(source("Customers",
        httpSource("https://api.example.com/customers", HttpMethod.GET,
                Map.of("X-Api-Key", "key-\"quoted\""),
                Optional.empty(), Optional.empty(),
                List.of(), Optional.empty(), Optional.empty())));

System.out.println(typed.definitions());
```

```
private source Customers from http { url: "https://api.example.com/customers", method: GET, headers: { "X-Api-Key": "key-\"quoted\"" } };
```

The value is now part of the session's declarations, and `definitions()` shows it
(`private` because `source(name, config)` builds an unexported declaration). That
is the difference between the two routes: prefer a placeholder for a secret, and
`define(Statement...)` for a value that is not one.
