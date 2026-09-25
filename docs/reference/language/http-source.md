# Name: source … from http (HTTP/JSON API source)

# Syntax:
source <Name> from http {
    url:     "<url>",                 // required; may contain ${ENV} and {pathParam}
    method:  GET | POST | QUERY,      // optional, default GET (Relix is read-only)
    headers: { "<Name>": "<value>" }, // optional static request headers
    auth:    bearer("…") | basic("…","…")
             | apikey("…","…") | apikey(query("…"),"…"),
    body:    "<request body>",        // for POST/QUERY reads; required by QUERY
    extract: json("<jsonpath>"),      // optional; locates the records array
    schema:  { <col>: <TYPE> at "<jsonpath>", … }  // optional; omit for open
};

# Description:
An HTTP source turns a JSON REST/GraphQL endpoint into a relation. Relix issues
the request, parses the JSON response, and yields one row per record. It is the
network sibling of the local `json("…")` file source.

Relix is **read-only by design**: only `GET`, `QUERY` and `POST` are allowed.
`QUERY` (RFC 10008) is the method made for a read whose query travels in the
request body: it is safe and idempotent by definition, and a `QUERY` source must
declare a `body`. `POST` is used *solely* to carry a read query in the body for
the many endpoints that do not accept `QUERY` (the GraphQL/search idiom). The
mutating methods (PUT/PATCH/DELETE) and the bodyless HEAD are rejected:

```relix-invalid
source Items from http { url: "https://api.example.com/items", method: PUT };
```

By default a source is **open** (schema-on-read): each JSON object becomes a
document row you navigate by path at query time (e.g. `user.name`, `items[0].id`).
Declare a `schema { … }` to make it a **closed, typed** relation whose columns are
extracted from each record by an `at "$.path"` binding (or the column name).

# Technical Description:
`url` is the only required field. `${NAME}` placeholders (in the URL, header
values, body, and auth values) keep secrets out of the source text. They are
resolved when a query runs: from the active environment in the `relix` command and
the REPL, and through the resolver its session was built with in a program embedding
relix. The declaration keeps the placeholder, so nothing that prints the session
shows the value, and a placeholder with no value fails the query with an error naming
it and the source.

`extract: json("$.path")` locates the records: the value at the path must be a
JSON array (one record per element) or a single object (one record). When omitted,
the response body itself is treated as the records — a top-level array or object,
exactly like the local JSON file source. JSONPath roots (`$`, `$.`) and the
trailing `[*]` are accepted and normalised.

`auth` shorthands expand to the standard wire form: `bearer(t)` →
`Authorization: Bearer t`; `basic(u,p)` → `Authorization: Basic base64(u:p)`;
`apikey(name,v)` → the header `name: v`; `apikey(query(name),v)` → the URL
parameter `?name=v`. Equivalent raw headers work too.

**A header is set in one place.** `headers`, `auth` and an `IN` column bound
`as header("…")` all set request headers, and naming the same header in two of
them is an analysis error naming the source and the header. Names are compared
case-insensitively, as HTTP compares them, so `auth: bearer(…)` beside a declared
`"authorization"` header is refused too. Neither of the two values is an obvious
winner, so relix does not pick one.

**Some headers cannot be declared.** The HTTP client sets `Connection`,
`Content-Length`, `Expect`, `Host` and `Upgrade` itself, and declaring one is an
analysis error. So is a header name that is not a valid HTTP token, or a value
containing a line break. A value that only becomes invalid once a `${…}`
placeholder is substituted is reported when the request is built, as an error
naming the source and the header.

**A request with a body is sent as JSON unless it says otherwise.** When a
request carries a body (a declared `body`, or the generated GraphQL document
below), relix adds `Content-Type: application/json`. A `Content-Type` declared in
`headers`, in any capitalisation, is sent instead. A request with no body, such
as an ordinary `GET`, carries no `Content-Type`.

A redirect is followed. A 301, 302, 307 or 308 answer to a `QUERY` resends the
`QUERY` with its body, and a 303 fetches the result with `GET`, as RFC 10008
specifies.

Typed columns coerce the extracted value to the declared type (NUMBER, STRING,
BOOLEAN, ANY, DATE/TIME/TIMESTAMP/DURATION). A missing path navigates to NULL rather
than erroring.

# Examples:
Fetch two columns from an open (schema-less) endpoint, navigating the JSON by path:
```relix
source Users from http { url: "https://jsonplaceholder.typicode.com/users" };
query { π name, email (Users) };
```

Pin named, typed columns and pull a value out of a nested object with an `at "$.path"` binding:
```relix
source Users from http {
    url: "https://jsonplaceholder.typicode.com/users",
    schema: { id: NUMBER, name: STRING, email: STRING, city: STRING at "$.address.city" }
};
query { τ id (Users) };
```

Locate a records array nested under a key, and page through results:
```relix
source Products from http {
    url:      "https://dummyjson.com/products",
    extract:  json("$.products[*]"),
    paginate: { limit: query("limit") [default: 5] },
    schema:   { id: NUMBER, title: STRING, price: NUMBER, brand: STRING }
};
query { τ price DESC (σ price > 10 (Products)) };
```

Send an authenticated POST read with a JSON body. The body goes out as
`application/json` without a header saying so:
```relix
source Echo from http {
    url:     "https://httpbin.org/anything",
    method:  POST,
    auth:    bearer("${API_TOKEN}"),
    body:    "{ \"hello\": \"relix\" }",
    extract: json("$.json")
};
query Echo;
```

Read with `QUERY`, sending a query in a language the endpoint names. The
declared `Content-Type` replaces the JSON default:
```relix
source OpenOrders from http {
    url:     "https://api.example.com/orders",
    method:  QUERY,
    headers: { "Content-Type": "application/sql" },
    body:    "SELECT id, total FROM orders WHERE status = 'OPEN'",
    extract: json("$.rows"),
    schema:  { id: NUMBER, total: NUMBER }
};
query { τ total DESC (OpenOrders) };
```

# Live Examples:
All four examples below are real, public, no-auth endpoints and run as-is with
`relix --exec`.

(1) OPEN source — no schema; navigate the JSON by path at query time.
The endpoint returns a top-level array of user objects:
```relix
source Users from http { url: "https://jsonplaceholder.typicode.com/users" };
query { π name, email (λ 2 (Users)) };
```

Result:
```
 name           email
 ─────────────  ─────────────────
 Leanne Graham  Sincere@april.biz
 Ervin Howell   Shanna@melissa.tv
(2 rows)
```

(2) TYPED source — pin named, typed columns; pull a value out of a nested object
with an `at "$.path"` binding. Each user object looks like
`{"id":1,"name":"Leanne Graham","email":"…","address":{"city":"Gwenborough",…}}`,
so `city` is extracted from `address.city`:
```relix
source Users from http {
    url: "https://jsonplaceholder.typicode.com/users",
    schema: {
        id:    NUMBER,
        name:  STRING,
        email: STRING,
        city:  STRING at "$.address.city"
    }
};
query { τ id (λ 3 (Users)) };
```

Result:
```
 id  name              email               city
 ──  ────────────────  ──────────────────  ─────────────
  1  Leanne Graham     Sincere@april.biz   Gwenborough
  2  Ervin Howell      Shanna@melissa.tv   Wisokyburgh
  3  Clementine Bauch  Nathan@yesenia.net  McKenziehaven
(3 rows)
```

(3) EXTRACT path + pagination — the records live under a key, not at the root.
The response is `{"products":[ … ],"total":…,"limit":…}`, so `extract` locates the
array and the pagination default adds `?limit=5` to the request URL. Ordinary
σ/τ then filter and sort the fetched rows:
```relix
source Products from http {
    url:     "https://dummyjson.com/products",
    extract: json("$.products[*]"),
    paginate: { limit: query("limit") [default: 5] },
    schema:  { id: NUMBER, title: STRING, price: NUMBER, brand: STRING }
};
query { τ price DESC (σ price > 10 (Products)) };
```

Result:
```
 id  title                          price  brand
 ──  ─────────────────────────────  ─────  ──────────────
  2  Eyeshadow Palette with Mirror  19.99  Glamour Beauty
  3  Powder Canister                14.99  Velvet Touch
  4  Red Lipstick                   12.99  Chic Cosmetics
(3 rows)
```

(4) POST read with a body + auth — httpbin.org echoes the request back, so this
shows the body and a bearer token reaching the server. The echoed `json` field
holds the body we sent:
```relix
source Echo from http {
    url:     "https://httpbin.org/anything",
    method:  POST,
    auth:    bearer("${API_TOKEN}"),
    headers: { "Content-Type": "application/json" },
    body:    "{ \"hello\": \"relix\" }",
    extract: json("$.json")
};
query Echo;
```

This issues `POST https://httpbin.org/anything` with header
`Authorization: Bearer <API_TOKEN>` and the given body. With no schema declared,
`extract: json("$.json")` makes the echoed body the single open document row:

```
 hello
 ─────
 relix
(1 row)
```

To prove the token and body reached the server, extract httpbin's echo directly —
`$.headers.Authorization` is `Bearer <API_TOKEN>` and `$.data` is the sent body.

# GraphQL — the request asks for what the query reads:
A `POST` source that declares an `extract` path and **no** `body` has its request
generated: relix sends a GraphQL document selecting exactly the columns the query
reads, and nothing else.

That is projection pushdown, and GraphQL takes a different shape to SQL's. There is
no `SELECT` list to render, because a GraphQL selection set *is* the field list —
so the fold is simply that the scan is asked for fewer columns and the document
asks for those.

```relix
source Users from http {
    url:     "https://api.example.com/graphql",
    method:  POST,
    extract: json("$.data.users"),
    schema:  { id: NUMBER, name: STRING, email: STRING, bio: STRING }
};

query { π id, name (Users) };
```

sends, as `Content-Type: application/json`,

```
{"query":"{ users { id name } }"}
```

The `extract` path says where the rows sit and therefore what to select from —
`$.data.users` selects `users`, GraphQL's `data` envelope being a wrapper rather
than a field. A column's `at` binding nests the same way: `at "$.author.name"`
becomes `author { name }`, and two columns under one parent share its selection set.

**A declared `body` is sent verbatim.** Generation only ever fills an absence, so a
query you wrote is the query that goes — relix never rewrites it to something
narrower. A `POST` with no body could not have been reaching a GraphQL endpoint
before, so no working source changes meaning. Generation is for `POST` alone: the
GraphQL-over-HTTP convention is `POST`, and a `QUERY` source always declares its
body.

**Only the projection folds.** A `σ` stays in the engine, because GraphQL has no
general predicate language: filtering is whatever arguments each schema happens to
define (`where:`, `filter:`, a Relay connection), and there is nothing in the
protocol to render a comparison into. A `λ` stays in the engine for the same reason
— `first:` is a widely-followed convention rather than part of GraphQL.

An `IN` column is a request parameter and not a field, so it is never selected even
when the query reads it back. An **open** source declares no columns at all, and a
GraphQL request must name its fields up front, so nothing is generated for one.

# Limitations:
Predicates are not pushed into request parameters: a `[required]` `IN` column
with no `[default: …]` is an error. Provide a default, or filter the result with
`σ` after the fetch. A pagination default is a whole number:

```relix-invalid
source Products from http { url: "https://dummyjson.com/products",
    paginate: { limit: query("limit") [default: 2.5] } };
```

The response is read fully into memory (no streaming
cursor). `extract: csv(…)` over HTTP parses but does not execute — use JSON.
PUT/PATCH/DELETE/HEAD are not available (Relix never mutates data). A generated
GraphQL request carries no arguments — no filter, no page size, no variables — so a
source needing them declares its own `body` and gives up the generated projection.

# See Also:
[source](source.md), [connection](connection.md), [assignment & query](assignment.md)

# Notes:
Keep tokens and keys out of the script with `${VAR}` (see `relix help env` for the
command line). They are resolved before the request is built, and their values are
never logged.
