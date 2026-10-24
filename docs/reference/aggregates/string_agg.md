# Name: STRING_AGG (join a group's values into one string, with GROUP_CONCAT and LISTAGG)

# Syntax:
STRING_AGG([DISTINCT] <expr>, <separator> [ORDER BY <key> [ASC|DESC], …])
GROUP_CONCAT([DISTINCT] <expr> [ORDER BY <key> [ASC|DESC], …] [SEPARATOR <separator>])
LISTAGG([DISTINCT] <expr> [, <separator>]) [WITHIN GROUP (ORDER BY <key> [ASC|DESC], …)]

γ category, STRING_AGG(name, ', ' ORDER BY name) → names (Products)

# Description:
STRING_AGG joins the values of a group into one string with a separator between
them: every product name in a category as `'chair, desk, lamp'`. Its `ORDER BY`
says which value comes first. It is the aggregate SQL engines spell three ways,
and all three are accepted: PostgreSQL's and SQL Server's `STRING_AGG`, MySQL's
`GROUP_CONCAT` and Oracle's `LISTAGG`.

# Technical Description:
STRING_AGG(value, separator) → STRING. Each non-NULL value is written as it
displays and joined to the one before by the separator; NULL values are skipped
with their separator, and a group with no values is NULL. A NULL separator joins
with nothing. `DISTINCT` keeps each value once.

Without an `ORDER BY` the values come in the order the group's rows arrive, which
a relation does not promise, so a result to be relied on names its order. The
`ORDER BY` takes sort keys as `τ` does, NULLs last unless a key says `NULLS FIRST`;
rows tied on every key keep their input order.

`GROUP_CONCAT` joins with a comma unless it says `SEPARATOR`, and `LISTAGG` with
nothing unless it names a separator; both build the same tree as STRING_AGG, which
prints back as STRING_AGG. The three names are matched by their text where they are
called, so none is reserved. `COLLECT` takes an `ORDER BY` in the same place, and
`ARRAY_AGG` is another name for it.

# Examples:
Each category's products, alphabetically:
  γ category, STRING_AGG(name, ', ' ORDER BY name) → names (Products)

Each customer's order statuses, oldest first, in MySQL's spelling:
  γ customer_id, GROUP_CONCAT(status ORDER BY order_date SEPARATOR ' / ') → history (Orders)

Oracle's spelling, with the separator it defaults to written out:
  γ region, LISTAGG(name, ', ') WITHIN GROUP (ORDER BY name) → customers (Customers)

# Worked Example:
Five reps in three regions, two with no bonus recorded:

```relix
Sales := [
| region | rep  | amount | bonus |
|--------|------|--------|-------|
| east   | Ada  | 120    | 10    |
| east   | Bo   | 80     | NULL  |
| west   | Dee  | 50     | 25    |
| west   | Cy   | 200    | NULL  |
| north  | Eve  | 30     | NULL  |
];

query { τ region (γ region, STRING_AGG(rep, ', ' ORDER BY amount DESC) → reps,
                     STRING_AGG(bonus, '/') → bonuses (Sales)) };
```

```
 region  reps     bonuses
 ──────  ───────  ───────
 east    Ada, Bo  10
 north   Eve      NULL
 west    Cy, Dee  25
(3 rows)
```

West's reps come by amount, not in the order they were listed. The missing bonuses
are left out, so each of east and west has one, and north, with none, is NULL — as
PostgreSQL's `string_agg` answers.

# Pushdown:
Pushed to PostgreSQL and DuckDB as `STRING_AGG(x, sep ORDER BY …)`, and to SQL
Server as `STRING_AGG(x, sep) WITHIN GROUP (ORDER BY …)`, when the value is a
string, the separator a literal, each ordering key a column, and there is no
`DISTINCT`. A number or a date is written as text differently by each backend, so
joining one runs in the engine. MySQL's `GROUP_CONCAT` is never used: it cuts its
result short at `group_concat_max_len` without an error.

# SQL names:
String aggregation, joining a group's values into one string: PostgreSQL's and SQL
Server's `STRING_AGG`, MySQL's `GROUP_CONCAT` and Oracle's `LISTAGG`, each spelled as
it is there. An array of the values rather than a string is [COLLECT](collect.md).

# See Also:
[collect](collect.md), [array_to_string](../functions/nested/array_to_string.md), [concat_ws](../functions/string/concat_ws.md), [group](../operators/group.md)
