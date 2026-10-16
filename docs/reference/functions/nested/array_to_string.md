# Name: ARRAY_TO_STRING (join an array's elements into one string)

# Syntax:
ARRAY_TO_STRING(<array>, <separator> [, <null_text>])

π order_id, ARRAY_TO_STRING(tags, ', ') → tag_list (Orders)

# Description:
ARRAY_TO_STRING writes an array's elements as one string with a separator between
them: `['red', 'large']` becomes `'red, large'`. It is to an array what
[STRING_AGG](../../aggregates/string_agg.md) is to a group of rows, and the usual
way to show a `COLLECT`ed array as text.

# Technical Description:
ARRAY_TO_STRING(array, separator: STRING [, null_text: STRING]) → STRING. Each
element is written as it displays, in the array's order. A NULL element is left
out, or written as `null_text` where one is given, as PostgreSQL's
`array_to_string` does. A NULL array or separator gives NULL; anything else that is
not an array is an error. PURE, DETERMINISTIC.

# Examples:
An order's tags as a list:
  π order_id, ARRAY_TO_STRING(tags, ', ') → tag_list (Orders)

A missing element shown rather than dropped:
  π order_id, ARRAY_TO_STRING(tags, ', ', '?') → tag_list (Orders)

# Pushdown:
Runs in the engine: a backend writes numbers and dates as text its own way.

# See Also:
[string_agg](../../aggregates/string_agg.md), [collect](../../aggregates/collect.md), [concat_ws](../string/concat_ws.md), [Entries](entries.md)
