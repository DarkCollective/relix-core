# Name: PERCENTILE_DISC (discrete percentile)

# Syntax:
PERCENTILE_DISC(<fraction>) WITHIN GROUP (ORDER BY <expr>)
PERCENTILE_DISC(<fraction>, <expr>)

γ customer_id, PERCENTILE_DISC(0.5, order_date) → halfway (Orders)

# Description:
PERCENTILE_DISC is the first actual value of a group that a given fraction of its values reaches, so it is always one of the values, and works on dates and strings as well as numbers.

# Technical Description:
The value at position ⌈p × n⌉ − 1, at least 0, among the n non-NULL values in ascending order; NULL for none. The fraction must be between 0 and 1 and the same in every row; the order is ascending only. See PERCENTILE_CONT for both percentiles and a worked example.

# Examples:
  γ customer_id, PERCENTILE_DISC(0.5, order_date) → halfway (Orders)

# See Also:
[percentile_cont](percentile_cont.md), [median](median.md)
