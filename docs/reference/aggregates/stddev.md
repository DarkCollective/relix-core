# Name: STDDEV (standard deviation)

# Syntax:
STDDEV(<expr>)          -- the sample standard deviation
STDDEV_SAMP(<expr>)     -- the same
STDDEV_POP(<expr>)      -- the population standard deviation

γ region, STDDEV(amount) → spread (Orders)

# Description:
STDDEV measures how spread out a group's values are: the typical distance of a value
from the group's average, in the values' own units. A small standard deviation means
the values sit close together; a large one, that they range widely.

# Technical Description:
STDDEV_SAMP(x) is the square root of the sample variance, dividing by n − 1;
STDDEV_POP(x) of the population variance, dividing by n. STDDEV is STDDEV_SAMP, as in
PostgreSQL. NULLs are skipped. Over no values both are NULL, and over one value
STDDEV_SAMP is NULL and STDDEV_POP 0. The arithmetic is exact decimal up to the square
root, which is rounded half-up to ten places. The argument must be a NUMBER. DISTINCT
and FILTER apply, and all three roll in ROLLING. STDDEV, STDDEV_SAMP and STDDEV_POP
are matched by their text, so none is reserved.

# Examples:
Spread of order amounts per region:
  γ region, STDDEV(amount) → spread (Orders)

A rolling standard deviation over the last 20 readings:
  ROLLING STDDEV(delta) OVER 20 ROWS SORT at AS volatility (Readings)

# Pushdown:
Runs in the engine: a backend computes it in its own precision.

# See Also:
[variance](variance.md), [avg](avg.md), [percentile](percentile_cont.md), [group](../operators/group.md), [rolling](../operators/rolling.md)
