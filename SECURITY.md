# Security policy

## Reporting a vulnerability

**Please do not report a security problem in a public issue, discussion or pull
request.**

Report it privately through GitHub instead:
[**Report a vulnerability**](https://github.com/DarkCollective/relix-core/security/advisories/new)
(the *Security* tab of this repository → *Report a vulnerability*). The report
stays visible only to you and the maintainers until a fix is published.

Useful things to include:

- the version or commit you tested against;
- what an attacker controls — the query text, a source's configuration, a
  remote response, a file on disk — and what they gain;
- steps or a minimal script that reproduces it.

## What to expect

- **Acknowledgement within 5 working days.**
- An initial assessment — whether we consider it a vulnerability and how
  severe — within 14 days.
- Progress updates on the private advisory until it is resolved.
- A fix released, and the advisory published, crediting you unless you would
  rather not be named. We ask that you keep the details private until then;
  if a fix will take longer than 90 days we will agree a disclosure date with
  you rather than hold it indefinitely.

## Supported versions

Relix has not yet had a stable release. Until it does, **security fixes are made
only on `main` and shipped in the next release**; earlier pre-releases do not
receive fixes.

Once a stable release exists, fixes are made on the latest minor release of the
current major version.

## Scope

Relix is an embeddable engine, and in its shipped configuration it does more than
compute. Reports are especially welcome for:

- **query and data parsing** — the query language, JSON documents, CSV files and
  remote responses, where malformed input causes more than a clean error;
- **the connectors** — JDBC connections opened from configuration, the HTTP
  connector's requests, headers and credentials, and the MongoDB plugin;
- **driver and plugin loading** — JDBC driver JARs and connector plugins loaded
  from disk or downloaded on request, including checksum verification of what is
  downloaded;
- **provider discovery** — what a session executes being influenced by something
  other than what the host application installed;
- **credential exposure** — a secret from a connection's configuration appearing
  in an error message, a plan, an event or a log.

Out of scope:

- a query that is merely expensive, when the host application passed it an
  untrusted query without limits — bounding the work an untrusted query may do
  is the embedding application's decision;
- vulnerabilities in a third-party JDBC driver or database, which belong with
  that project (though we would like to hear if Relix makes one reachable);
- findings that require an attacker who can already modify the host
  application's classpath or configuration.

If you are unsure whether something is in scope, report it privately anyway.
