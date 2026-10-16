# Name: AGE (the years, months and days between two dates)

# Syntax:
AGE(<end>, <start>)

π name, AGE(DATE '2026-10-16', born_on) → age (People)

# Description:
AGE gives the time from one date to another in years, months and days, as a person's
age is reckoned: from 13 June 1957 to 10 April 2001 is 43 years, 9 months and 27 days.
Subtracting the dates gives one number of days. AGE gives the calendar reading, and
YEAR or MONTH of that is not needed.

# Technical Description:
AGE(end: DATE | TIMESTAMP, start: DATE | TIMESTAMP) → DURATION, PostgreSQL's `age`.
Both are read at UTC. Each field is subtracted on its own, and a negative field
borrows from the next one up: an hour short borrows a day, and a day short borrows the
length of the start's month. The result has calendar months, its years being twelve
months each, and an exact part of days and time, so it prints as
`P43Y9MT648H`: 43 years, 9 months and 648 hours, which is 27 days. When `end` is
earlier than `start`, every field is negative, and the borrowed month is the end's. A
NULL argument returns NULL. PURE, DETERMINISTIC.

# Examples:
Each person's age today:
  π name, AGE(DATE '2026-10-16', born_on) → age (People)

Subscriptions older than a year:
  σ AGE(DATE '2026-10-16', started_on) ≥ DURATION 'P1Y' (Subscriptions)

# Pushdown:
Runs in the engine.

# See Also:
[datediff](datediff.md), [dateadd](dateadd.md), [duration-literal](../../literals/duration-literal.md)
