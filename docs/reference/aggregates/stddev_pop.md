# Name: STDDEV_POP (population standard deviation)

# Syntax:
STDDEV_POP(<expr>)

γ region, STDDEV_POP(amount) → spread (Orders)

# Description:
STDDEV_POP is the standard deviation of a group taken as the whole population, dividing by the number of values.

# Technical Description:
The square root of VAR_POP: NULL for no values and 0 for one, NULLs skipped, rounded half-up to ten places. See STDDEV for the whole family.

# Examples:
  γ region, STDDEV_POP(amount) → spread (Orders)

# See Also:
[stddev](stddev.md), [stddev_samp](stddev_samp.md), [var_pop](var_pop.md)
