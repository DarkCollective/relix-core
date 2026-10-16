# Name: STDDEV_SAMP (sample standard deviation)

# Syntax:
STDDEV_SAMP(<expr>)

γ region, STDDEV_SAMP(amount) → spread (Orders)

# Description:
STDDEV_SAMP is the standard deviation of a group taken as a sample of something larger, dividing by one fewer than the number of values. STDDEV is the same aggregate.

# Technical Description:
The square root of VAR_SAMP: NULL for fewer than two values, NULLs skipped, rounded half-up to ten places. See STDDEV for the whole family.

# Examples:
  γ region, STDDEV_SAMP(amount) → spread (Orders)

# See Also:
[stddev](stddev.md), [stddev_pop](stddev_pop.md), [var_samp](var_samp.md)
