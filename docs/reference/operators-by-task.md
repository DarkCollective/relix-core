# Name: Operators by task (the operator for a task SQL has no operator for)

# Syntax:
<the task, in a question's words>  →  <the page of the operator that does it>

# Description:
Many questions have a Relix operator made for them, which SQL answers only with a
workaround: a self-join, a correlated subquery, a recursive CTE or a frame clause.
This page lists those tasks, each in the words a question puts it in, and links the
page of the operator that does it. Before writing a script, check whether its task is
one of these; if it is, read the page.

Each link is a page's path in this reference, the path a tool that serves the
reference reads it by.

## Rows and their neighbours

- A value from another row of the same group: the previous or next row, the first or
  last in order → [Window offset](operators/window-offset.md)
- Number the rows of each group in order, or rank them, ties and all →
  [Window ranking](operators/window-ranking.md)
- The top N rows of each group: the latest per customer, the three biggest per
  category → [Top-K](advanced/top.md)
- Running totals, cumulative sums, moving averages over an ordered sequence →
  [Rolling](operators/rolling.md)
- A centred moving window: the average of each row with the one before and the one
  after it → [Rolling](operators/rolling.md)
- Split each group's ordered rows into sessions wherever the gap between two rows
  exceeds a limit: gaps and islands → [Sessionize](advanced/sessionize.md)
- One row per time bucket: per hour, per day, fewer points over a long series →
  [Downsample](advanced/downsample.md)

## Groups

- Subtotals and a grand total in one result, and which level each row is →
  [GROUPING](aggregates/grouping.md)
- The value from the row with the highest or lowest of something, per group: the name
  of each team's top scorer → [ARGMAX](aggregates/argmax.md),
  [ARGMIN](aggregates/argmin.md)
- Count or sum only the rows that meet a condition, beside the total →
  [FILTER](aggregates/filter.md)
- Gather a group's values into a list → [COLLECT](aggregates/collect.md), or into one
  string → [STRING_AGG](aggregates/string_agg.md)
- Turn the values of a column into columns of their own →
  [Pivot](operators/pivot.md); turn columns into rows →
  [Unpivot](operators/unpivot.md)
- One row per element of a list held in a column → [Unnest](operators/unnest.md)

## Hierarchies and graphs

- Every pair linked through a chain of any length: ancestors, descendants, everyone
  under a manager, everything reachable → [Closure](advanced/closure.md)
- Routes between nodes: the shortest path, how many steps apart two nodes are, what is
  reachable within N steps → [Path](advanced/path.md)
- A hierarchy as nested documents, each item with its children inside it →
  [Tree](operators/tree.md)
- A recursion the two above do not fit, built up until nothing new appears →
  [Fixpoint](advanced/fix.md)
- Repeat a computation until it settles: PageRank, a state machine, a value that
  converges → [Iterate](advanced/iterate.md)
- Group rows that are linked, directly or through others →
  [Cluster](advanced/cluster.md); rows that probably describe the same thing →
  [Record linkage](advanced/record-linkage.md)

## Matching and comparing

- Match each row to the nearest earlier row in time: the price in force when an order
  was placed → [AS-OF join](joins/asof-join.md)
- Rows whose time ranges overlap, or one inside another →
  [Interval join](joins/interval-join.md)
- Keep the rows that have a match in another relation →
  [Semi join](joins/semi-join.md), or that have none → [Anti join](joins/anti-join.md)
- Keep the rows of both sides, matched or not → [Full outer join](joins/full-outer-join.md)
- Rows related to every one of something: students who took all the required courses
  → [Division](set-operations/division.md), [Universal](operators/forall.md)
- Rows in one of two relations but not both → [Symmetric difference](set-operations/symmetric-difference.md)
- Put two relations' rows together, keeping the repeats →
  [Union all](set-operations/union-all.md)

## Dates

- The time between two dates as an age, in years, months and days →
  [AGE](functions/datetime/age.md)

## Choosing and explaining

- Values that satisfy a set of constraints: a puzzle, an assignment →
  [Solve](advanced/solve.md)
- The best choice or quantities under limits: maximise or minimise an objective →
  [Optimize](advanced/optimize.md)
- The fewest combinations that still cover every pair of values →
  [Cover](advanced/cover.md)
- Which input rows produced each result row → [Why](operators/why.md)
- A random sample of the rows → [Sample](advanced/sample-bernoulli.md),
  [Reservoir](advanced/sample-reservoir.md)

# Examples:
"Each order's amount beside the customer's previous one" is a value from another row
of the same group, so it is Window offset's:
  WINDOW LAG(amount) SORT order_date ASC PER customer_id AS prev_amount (Orders)

"The three biggest orders of each customer" is the top N rows of each group:
  TOP 3 amount DESC PER customer_id (Orders)

"Customers who placed no order" keeps the rows that have no match:
  Customers ▷ Customers.customer_id = Orders.customer_id Orders

# See Also:
[spellings](language/spellings.md), [glossary](glossary.md),
[getting started](getting-started.md)
