# Name: MEDIAN (the middle value)

# Syntax:
MEDIAN(<expr>)

γ region, MEDIAN(amount) → typical (Orders)

# Description:
MEDIAN is the middle value of a group: half the values are below it and half above.
Unlike the average it is not pulled about by a few very large or very small values,
which makes it the better "typical" figure for skewed data such as prices or times.

# Technical Description:
MEDIAN(x) is PERCENTILE_CONT(0.5, x): with an even number of values, the average of
the two in the middle. NULLs are skipped, and a group with no values gives NULL. The
argument must be a NUMBER. MEDIAN is matched by its text, so it is not reserved.

# Examples:
Typical order size per region:
  γ region, MEDIAN(amount) → typical (Orders)

# Pushdown:
Runs in the engine.

# See Also:
[percentile](percentile_cont.md), [avg](avg.md), [group](../operators/group.md)
