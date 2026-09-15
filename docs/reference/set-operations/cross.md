# Name: Cartesian Product (× / CROSS)

# Syntax:
Relation1 × Relation2
Relation1 CROSS Relation2

Colours × Sizes

# Description:
A cross product pairs every row of the left relation with every row of the right
— all possible combinations. With 4 colours and 3 sizes you get 12 colour/size
pairs. It is how you generate combinations from scratch, and it is the raw
material a join is built from (a join is a cross product followed by a filter).

# Technical Description:
R × S returns every (r, s) pair, with the combined schema of both inputs. Output
size is |R| · |S|, so it grows fast. A selection applied on top of a product over
both sides is rewritten by the optimizer into a theta join (JOIN-001).

# Examples:
All combinations of test parameters:
  Browsers × OperatingSystems × Versions

Build a colour/size matrix:
  Colours × Sizes

Cross then filter (the optimizer turns this into a join):
  σ Employees.dept = Departments.dept (Employees × Departments)

# Worked Example:
The Cartesian product pairs every row on the left with every row on the right.
Its most common honest use is generating combinations — here, every size/colour
variant of a product.

```relix
Sizes := [
| size |
|------|
| S    |
| M    |
];

Colours := [
| colour |
|--------|
| red    |
| blue   |
];

query { Sizes × Colours };
```

```
 size  colour
 ────  ──────
 S     red
 S     blue
 M     red
 M     blue
(4 rows)
```

2 × 2 = 4 rows, and that multiplication is the thing to keep in mind: two
thousand-row relations produce a million rows. A cross product followed by a `σ`
that compares the two sides is just a theta join written the long way — and the
optimizer will usually turn it into one — but writing the join directly is clearer
and never risks materialising the full product.

# Limitations:
The output can be enormous — guard it with a selection (which the optimizer folds
into a join) or by keeping inputs small. A blocking step downstream over a large
product can be costly.

# Alternatives:
If you immediately filter on a matching condition, write a theta join (⨝)
directly. For combinatorial test coverage use COVER to keep a small representative
subset.

# See Also:
[theta-join](../joins/theta-join.md), [natural-join](../joins/natural-join.md), [cover](../advanced/cover.md)

# Notes:
Cross products feed the COVER operator's "all combinations" idiom, e.g.
`COVER 2 (Type × Format × Size)` for all-pairs test generation.
