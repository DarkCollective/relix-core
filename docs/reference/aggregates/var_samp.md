# Name: VAR_SAMP (sample variance)

# Syntax:
VAR_SAMP(<expr>)

γ region, VAR_SAMP(amount) → v (Orders)

# Description:
VAR_SAMP is the variance of a group taken as a sample, dividing by one fewer than the number of values. VARIANCE is the same aggregate.

# Technical Description:
Σ(x − mean)² / (n − 1) over the non-NULL values, NULL for fewer than two, exact and then rounded half-up to ten places. See VARIANCE for the whole family.

# Examples:
  γ region, VAR_SAMP(amount) → v (Orders)

# See Also:
[variance](variance.md), [var_pop](var_pop.md), [stddev_samp](stddev_samp.md)
