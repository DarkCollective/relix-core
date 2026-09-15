# Name: Sgn (sign)

# Syntax:
Sgn(<number>)

π Sgn(change) → direction (Prices)

# Description:
Sgn reports the sign of a number: −1 for negatives, 0 for zero, and 1 for
positives. Use it to classify values by direction — up/down/flat, gain/loss — in a
single tidy number.

# Technical Description:
Sgn(x: NUMBER) → NUMBER. Returns the signum of x (−1, 0, or 1). NULL input returns
NULL. PURE, DETERMINISTIC, IDEMPOTENT.

# Examples:
Direction of a price change:
  π Sgn(close - open) → direction (Prices)

Count gainers vs losers:
  γ Sgn(change) → dir, COUNT(id) → n (Stocks)

# Pushdown:
SQL: folds to `SIGN(<e>)` on all dialects.

# Limitations:
NUMBER only; NULL in → NULL out.

# See Also:
[abs](abs.md), [round](round.md)
