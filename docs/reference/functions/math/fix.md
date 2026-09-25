# Name: Fix (truncate toward zero)

# Syntax:
Fix(<number>)

π Fix(value) → truncated (Readings)

# Description:
Fix removes the fractional part of a number, rounding toward zero. 3.9 becomes 3
and −3.9 becomes −3 (it just drops the decimals). Use it when you want to discard
the fraction without changing the sign's direction.

# Technical Description:
Fix(x: NUMBER) → NUMBER. Truncates toward zero (RoundingMode.DOWN). NULL input
returns NULL. PURE, DETERMINISTIC, IDEMPOTENT.

# Examples:
Drop the fraction:
  π Fix(value) → whole (Readings)

Whole hours from a fractional hours figure:
  π Fix(hours) → whole_hours (Timesheets)

# Pushdown:
SQL: dialect-dependent, because truncation towards zero is the one rounding SQL
never settled on a single name for.
  - **MySQL**: `TRUNCATE(<e>, 0)`.
  - **Postgres** and **DuckDB**: `TRUNC(<e>)`.
  - **SQLite**: not pushed. Its `TRUNC` is a floating-point function behind a
    compile-time option.
  - **GENERIC**: not pushed. A name that has to be chosen per dialect is one an
    unidentified backend has not been confirmed to have.

# Limitations:
NUMBER only; NULL in → NULL out. Differs from Int on negatives — Fix(−3.9) = −3,
Int(−3.9) = −4.

# Alternatives:
Int floors toward negative infinity; Ceil rounds up; Round rounds to nearest.

# See Also:
[int](int.md), [ceil](ceil.md), [round](round.md)
