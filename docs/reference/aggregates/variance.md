# Name: VARIANCE (variance)

# Syntax:
VARIANCE(<expr>)        -- the sample variance
VAR_SAMP(<expr>)        -- the same
VAR_POP(<expr>)         -- the population variance

γ region, VARIANCE(amount) → v (Orders)

# Description:
VARIANCE is the average squared distance of a group's values from their mean — the
square of the standard deviation. It is the measure to add and compare when combining
sources of spread.

# Technical Description:
VAR_SAMP(x) is Σ(x − mean)² / (n − 1) and VAR_POP(x) is Σ(x − mean)² / n, over the
non-NULL values. VARIANCE is VAR_SAMP, as in PostgreSQL. Over no values both are
NULL; over one value VAR_SAMP is NULL and VAR_POP 0. The count, sum and sum of squares
are kept as exact decimals, so nothing is lost to cancellation, and the quotient is
rounded half-up to ten places. The argument must be a NUMBER. DISTINCT and FILTER
apply, and all three roll in ROLLING.

# Examples:
Variance of order amounts per region:
  γ region, VAR_POP(amount) → v (Orders)

# Pushdown:
Runs in the engine: a backend computes it in its own precision.

# See Also:
[stddev](stddev.md), [avg](avg.md), [group](../operators/group.md)
