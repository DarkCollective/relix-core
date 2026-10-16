# Name: VAR_POP (population variance)

# Syntax:
VAR_POP(<expr>)

γ region, VAR_POP(amount) → v (Orders)

# Description:
VAR_POP is the variance of a group taken as the whole population, dividing by the number of values.

# Technical Description:
Σ(x − mean)² / n over the non-NULL values, NULL for none and 0 for one, exact and then rounded half-up to ten places. See VARIANCE for the whole family.

# Examples:
  γ region, VAR_POP(amount) → v (Orders)

# See Also:
[variance](variance.md), [var_samp](var_samp.md), [stddev_pop](stddev_pop.md)
