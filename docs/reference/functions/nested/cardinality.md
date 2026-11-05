# Name: CARDINALITY (the number of elements in an array)

# Syntax:
CARDINALITY(<array>)
ARRAY_LENGTH(<array> [, 1])

π g, CARDINALITY(ns) → k (γ g, COLLECT(n) → ns (T))

# Description:
CARDINALITY counts the elements of an array: `['red', 'large']` is 2. It is the
length of a list, where [Len](../string/len.md) is the length of a string, and it
counts an array however it arrived: built by [COLLECT](../../aggregates/collect.md)
in the same query, or held in the data, as a JSON or document field.

# Technical Description:
CARDINALITY(array) → NUMBER. The count of the array's elements, NULL elements
included, as PostgreSQL counts them. An empty array is 0. A NULL array gives NULL;
anything else that is not an array is an error, as it is for
[ARRAY_TO_STRING](array_to_string.md). PURE, DETERMINISTIC.

ARRAY_LENGTH(array [, dimension]) is the same function under PostgreSQL's other
name. A Relix array has one dimension, so the only dimension it takes is `1`; any
other is refused with a message saying so, and a NULL dimension gives NULL.

# Examples:
How many orders each customer placed, from a collected list:
```relix
query { π customer_id, CARDINALITY(order_ids) → orders
          (γ customer_id, COLLECT(order_id) → order_ids (Orders)) };
```

Orders with more than three tags, where `tags` is an array in the data:
  σ CARDINALITY(tags) > 3 (Orders)

A NULL element still counts, so this is 3:
  π CARDINALITY(["a", NULL, "b"]) → n (Dual)

# Pushdown:
Runs in the engine: the list is built in the engine, by COLLECT or by reading a
document, so there is nothing for a backend to count.

# Limitations:
PostgreSQL's `array_length` of an empty array is NULL, while its `cardinality` is 0.
Relix answers 0 by either name: two answers to "how long is it" would be worse than
one difference from PostgreSQL. `LENGTH` of an array is refused, and the message
names CARDINALITY.

# SQL names:
PostgreSQL's `ARRAY_LENGTH` is this function under its other name: `ARRAY_LENGTH(arr, 1)` is
`CARDINALITY(arr)`, except that an empty array is 0 rather than PostgreSQL's NULL.

# See Also:
[collect](../../aggregates/collect.md), [array_to_string](array_to_string.md), [unnest](../../operators/unnest.md), [Len](../string/len.md)
