# Name: connection … from log (web server access log source)

# Syntax:
connection <Name> from log { path: "<file or directory>" | url: "<https or file URL>",
                             format: "common" | "combined" | "<server format string>",
                             onError: "refuse" | "skip" };

source <Name> from <connection> { table: "<file>", schema: { <col>: <TYPE>, … } };

connection logs from log { path: "/var/log/nginx", format: "combined" };

# Description:
An access log is a record of every request a web server answered, one line each. A
`log` connection reads it as a relation with typed columns: the request time is a
`TIMESTAMP`, the status and byte count are numbers, and the request line is split into
`method`, `path` and `protocol`.

The typing is the point. Read as text, the time `10/Oct/2026:13:02:11 +0000` is a
string no temporal function accepts, so every query would begin by converting it. Read
through this connection it is already a timestamp, and the windowing operators
(`SESSIONIZE`, `WINDOW`, `ROLLING`, `DOWNSAMPLE`, `TOP`) apply to it directly.

`clf` is another name for the `log` type.

# Technical Description:
**`format`** is `common`, `combined`, or a format string pasted from the server's own
configuration: Apache's `LogFormat` (`%h %l %u %t "%r" %>s %b`) or nginx's `log_format`
(`$remote_addr - $remote_user [$time_local] "$request" $status …`). It defaults to
`common`. A string mixing the two vocabularies is refused, and so is a directive this
connector does not read, rather than guessed at.

| Apache | nginx | Column | Type |
|---|---|---|---|
| `%h` | `$remote_addr` | `host` | STRING |
| `%a` | | `address` | STRING |
| `%l` | | `ident` | STRING |
| `%u` | `$remote_user` | `user` | STRING |
| `%t` | `$time_local`, `$time_iso8601` | `at` | TIMESTAMP |
| `%r` | `$request` | `method`, `path`, `protocol` | STRING |
| `%m` | `$request_method` | `method` | STRING |
| `%U` | `$request_uri` | `path` | STRING |
| `%q` | | `query` | STRING |
| `%H` | `$server_protocol` | `protocol` | STRING |
| `%>s`, `%s` | `$status` | `status` | NUMBER |
| `%b`, `%B` | `$body_bytes_sent` | `bytes` | NUMBER |
| | `$bytes_sent` | `bytes_sent` | NUMBER |
| `%D` | | `duration_us` | NUMBER |
| `%T` | `$request_time` | `duration_s` | NUMBER |
| `%v` | `$host` | `server` | STRING |
| `%{Name}i` | `$http_name` | the header's name, lower case, `-` as `_` | STRING |

`combined` is `common` followed by `"%{Referer}i" "%{User-Agent}i"`, so it adds
`referer` and `agent`: `User-Agent` is read as `agent`.

**`-` is NULL** wherever it appears, which is how a log writes "nothing": no user, no
referer, no body.

**The declared schema decides the heading.** Every column is filled by name, and a
column the format does not produce is NULL. A dotted reference with nothing declared
gets every column the format produces, at the types above. A declared type different
from a field's own converts the field's text: `at: STRING` keeps the time as written.

**A directory holds several logs.** When `path` names a directory, `table` names a file
in it, so a live `access.log` and a rotated `access.log.2.gz` are two tables of one
connection. When `path` names a file, `table` names that file. A gzip file is read
transparently, recognised by its content rather than its name. `url` names a file to
fetch over `https`, as for any file connection (see [connection](connection.md)).

**A relative `path` resolves against the script's directory**, as a `csv("…")` source's
does.

**A line the format does not match is an error** naming its line number, because a line
dropped without notice makes every count over the log wrong. `onError: "skip"` leaves
such lines out instead, and the number skipped is written to the application log when
the file is closed. Blank lines are not records and are passed over.

A log is read a line at a time as the query asks for rows, so a large log is never held
in memory whole.

# Examples:
Read an nginx server's live log and yesterday's rotated copy, with the format pasted from
its configuration:
```relix
connection nginx from log {
    path:   "/var/log/nginx",
    format: "$remote_addr - $remote_user [$time_local] \"$request\" $status $body_bytes_sent"
};
source Today     from nginx { table: "access.log",
    schema: { host: STRING, at: TIMESTAMP, path: STRING, status: NUMBER } };
source Yesterday from nginx { table: "access.log.1.gz",
    schema: { host: STRING, at: TIMESTAMP, path: STRING, status: NUMBER } };
query { γ status, COUNT(*) → hits (Today ⊎ Yesterday) };
```

# Worked Example: visits
The examples on this page read one small log: 26 requests from three clients over two
hours, in `combined` format. A line looks like this:

```
203.0.113.7 - - [10/Oct/2026:13:02:11 +0000] "GET /index.html HTTP/1.1" 200 5120 "-" "Mozilla/5.0 (X11; Linux x86_64) Firefox/131.0"
```

A visit is a run of requests from one client with no long silence in it. `SESSIONIZE`
finds the silences by itself:

```relix
connection logs from log { path: "access.log", format: "combined" };
source Access from logs { table: "access.log",
    schema: { host: STRING, at: TIMESTAMP, method: STRING, path: STRING,
              status: NUMBER, bytes: NUMBER, referer: STRING, agent: STRING } };

Sessions := { SESSIONIZE at GAP DURATION 'PT30M' PER host AS session (Access) };
query { τ host ASC, session ASC (γ host, session, COUNT(*) → hits, MIN(at) → started, MAX(at) → ended (Sessions)) };
```

```
 host           session  hits  started               ended
 ─────────────  ───────  ────  ────────────────────  ────────────────────
 192.0.2.44           1     4  2026-10-10T13:07:01Z  2026-10-10T13:07:09Z
 192.0.2.44           2     1  2026-10-10T14:52:00Z  2026-10-10T14:52:00Z
 198.51.100.22        1     6  2026-10-10T13:03:40Z  2026-10-10T13:29:14Z
 198.51.100.22        2     3  2026-10-10T14:47:31Z  2026-10-10T15:08:26Z
 203.0.113.7          1     7  2026-10-10T13:02:11Z  2026-10-10T13:24:57Z
 203.0.113.7          2     5  2026-10-10T14:41:02Z  2026-10-10T15:03:09Z
(6 rows)
```

# Worked Example: a scanner
The same operator, with a one-minute gap, is a burst detector. A client asking for four
missing pages inside a minute is probing for software to attack:

```relix
connection logs from log { path: "access.log", format: "combined" };
source Access from logs { table: "access.log",
    schema: { host: STRING, at: TIMESTAMP, method: STRING, path: STRING,
              status: NUMBER, bytes: NUMBER, referer: STRING, agent: STRING } };

Misses := { σ status = 404 (Access) };
Bursts := { SESSIONIZE at GAP DURATION 'PT1M' PER host AS burst (Misses) };
query { σ misses ≥ 4 (γ host, burst, COUNT(*) → misses, MIN(at) → started (Bursts)) };
```

```
 host        burst  misses  started
 ──────────  ─────  ──────  ────────────────────
 192.0.2.44      1       4  2026-10-10T13:07:01Z
(1 row)
```

The same client's single missing page an hour later is a burst of one, and is left out.

# Worked Example: where visitors go
Each client's previous request, found with `WINDOW LAG`, turns the log into the edges of
a navigation graph. `PATH` then answers *what can a visitor reach from the front page,
and in how many clicks*:

```relix
connection logs from log { path: "access.log", format: "combined" };
source Access from logs { table: "access.log",
    schema: { host: STRING, at: TIMESTAMP, method: STRING, path: STRING,
              status: NUMBER, bytes: NUMBER, referer: STRING, agent: STRING } };

Nav   := { WINDOW LAG(path) SORT at ASC PER host AS from_path (Access) };
Steps := { σ from_path IS NOT NULL (Nav) };
Edges := { δ (π from_path → src, path → dst (Steps)) };
query { τ depth ASC, dst ASC (σ src = "/index.html" (PATH src, dst HOPS 1 TO 3 AS depth (Edges))) };
```

```
 src          dst              depth
 ───────────  ───────────────  ─────
 /index.html  /docs/faq            1
 /index.html  /docs/guide          1
 /index.html  /static/app.css      1
 /index.html  /api/export          2
 /index.html  /api/report          2
 /index.html  /docs/intro          2
 /index.html  /index.html          2
 /index.html  /api/search          3
 /index.html  /docs/operators      3
(9 rows)
```

In SQL that is a window function feeding a recursive CTE.

# Worked Example: think time
How long each client paused before each request. The subtraction of two timestamps is a
`DURATION`, with no conversion in the query:

```relix
connection logs from log { path: "access.log", format: "combined" };
source Access from logs { table: "access.log",
    schema: { host: STRING, at: TIMESTAMP, method: STRING, path: STRING,
              status: NUMBER, bytes: NUMBER, referer: STRING, agent: STRING } };

Gaps := { WINDOW LAG(at) SORT at ASC PER host AS prev_at (Access) };
query { τ at ASC (π at, path, (at - prev_at) → since_previous (σ host = "198.51.100.22" (Gaps))) };
```

```
 at                    path             since_previous
 ────────────────────  ───────────────  ──────────────
 2026-10-10T13:03:40Z  /index.html      NULL
 2026-10-10T13:03:41Z  /static/app.css  PT1S
 2026-10-10T13:06:15Z  /docs/faq        PT2M34S
 2026-10-10T13:12:03Z  /api/export      PT5M48S
 2026-10-10T13:20:48Z  /api/report      PT8M45S
 2026-10-10T13:29:14Z  /index.html      PT8M26S
 2026-10-10T14:47:31Z  /docs/guide      PT1H18M17S
 2026-10-10T14:58:02Z  /docs/intro      PT10M31S
 2026-10-10T15:08:26Z  /docs/operators  PT10M24S
(9 rows)
```

The first request has no previous one, so its pause is NULL.

# Worked Example: traffic over time
Requests per quarter-hour, and a moving average over three of them:

```relix
connection logs from log { path: "access.log", format: "combined" };
source Access from logs { table: "access.log",
    schema: { host: STRING, at: TIMESTAMP, method: STRING, path: STRING,
              status: NUMBER, bytes: NUMBER, referer: STRING, agent: STRING } };

PerQuarter := { DOWNSAMPLE at BY '15m' USING COUNT (Access) };
query { τ bucket ASC (ROLLING AVG(count) OVER 3 ROWS SORT bucket ASC AS smoothed (PerQuarter)) };
```

```
 bucket                count  smoothed
 ────────────────────  ─────  ────────────
 2026-10-10T13:00:00Z     12            12
 2026-10-10T13:15:00Z      5           8.5
 2026-10-10T14:30:00Z      2  6.3333333333
 2026-10-10T14:45:00Z      5             4
 2026-10-10T15:00:00Z      2             3
(5 rows)
```

**Read the buckets before the average.** `DOWNSAMPLE` emits no row for a quarter-hour
with no requests, so nothing appears between 13:15 and 14:30, and `OVER 3 ROWS` averages
three buckets that are *present* rather than three consecutive quarter-hours. The row
for 14:30 averages 13:00, 13:15 and 14:30. That is what the query says, and it is not a
moving average over wall-clock time.

# Worked Example: per client
The heaviest single response each client received, and the bandwidth each has used so
far at every request:

```relix
connection logs from log { path: "access.log", format: "combined" };
source Access from logs { table: "access.log",
    schema: { host: STRING, at: TIMESTAMP, method: STRING, path: STRING,
              status: NUMBER, bytes: NUMBER, referer: STRING, agent: STRING } };

query { τ host ASC (π host, path, bytes (TOP 1 bytes DESC PER host (Access))) };
```

```
 host           path           bytes
 ─────────────  ─────────────  ─────
 192.0.2.44     /wp-login.php    196
 198.51.100.22  /api/export    65536
 203.0.113.7    /api/export    65536
(3 rows)
```

```relix
Running := { ROLLING SUM(bytes) OVER ALL ROWS SORT at ASC PER host AS used (Access) };
query { τ at ASC (π at, path, bytes, used (σ host = "198.51.100.22" (Running))) };
```

```
 at                    path             bytes  used
 ────────────────────  ───────────────  ─────  ──────
 2026-10-10T13:03:40Z  /index.html       5120    5120
 2026-10-10T13:03:41Z  /static/app.css   2048    7168
 2026-10-10T13:06:15Z  /docs/faq         7168   14336
 2026-10-10T13:12:03Z  /api/export      65536   79872
 2026-10-10T13:20:48Z  /api/report      32768  112640
 2026-10-10T13:29:14Z  /index.html          0  112640
 2026-10-10T14:47:31Z  /docs/guide      18432  131072
 2026-10-10T14:58:02Z  /docs/intro       9216  140288
 2026-10-10T15:08:26Z  /docs/operators  24576  164864
(9 rows)
```

# Limitations:
Nothing is pushed down: a log file has no query language, so every operator runs in the
engine over the rows the file yields. Only access logs are read. An application log in
another shape (logback, logfmt, JSON lines) needs a connector of its own, or conversion
to CSV first.

# See Also:
[connection](connection.md), [SESSIONIZE](../advanced/sessionize.md),
[WINDOW LAG](../operators/window-offset.md), [ROLLING](../operators/rolling.md),
[DOWNSAMPLE](../advanced/downsample.md), [TOP](../advanced/top.md),
[csv source](source.md)
