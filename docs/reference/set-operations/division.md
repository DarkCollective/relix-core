# Name: Relational Division (÷ / DIV)

# Syntax:
Relation1 ÷ Relation2
Relation1 DIV Relation2

Enrolments ÷ RequiredCourses

# Description:
Division answers "which X are related to ALL of the Y?" — the textbook example
being "which students are enrolled in every required course?" or "which suppliers
can supply all the parts we need?". You divide a relation of pairs (student,
course) by a relation of the required courses, and you get back the students that
cover the entire set.

# Technical Description:
For R(A,B) ÷ S(B), division returns the values of A that are paired with every
value of B present in S. Formally the largest set Q such that Q × S ⊆ R. It is a
first-class blocking ([bag]) operator (not re-expressed via ∀). The divisor's
columns must be a subset of the dividend's.

# Examples:
Students enrolled in every required course:
```relix
Enrolments ÷ RequiredCourses
-- Enrolments(student, course) ÷ RequiredCourses(course) → (student)
```

Suppliers that can supply all needed parts:
  Supplies ÷ NeededParts

Users who have every required permission:
  UserPermissions ÷ RequiredPermissions

# Worked Example:
A university wants the students who have enrolled in *every* required course. The
dividend is the (student, course) enrolment relation; the divisor is the set of
required courses.

```relix
Enrolments := [
| student | course  |
|---------|---------|
| Ann     | Math    |
| Ann     | Physics |
| Ann     | Art     |
| Ben     | Math    |
| Ben     | Art     |
| Cara    | Math    |
| Cara    | Physics |
];

Required := [
| course  |
|---------|
| Math    |
| Physics |
];

Qualified := { Enrolments ÷ Required };
```

It helps to see it as a coverage matrix — division keeps the rows of the left
margin whose required cells (Math AND Physics) are all ticked:

```
              Math   Physics   |  has ALL required?
        Ann    ✓       ✓       |  ✓  → kept
        Ben    ✓       ✗       |  ✗  (no Physics)
        Cara   ✓       ✓       |  ✓  → kept
```

The result keeps only the *quotient* column (`student` — the dividend columns not
in the divisor), and Ann's extra `Art` enrolment is irrelevant; division asks
"covers all of the divisor", not "matches it exactly":

```relix
query { Qualified };
```

```
 student
 ───────
 Ann
 Cara
(2 rows)
```

The same shape answers "suppliers who can supply every needed part"
(`Supplies ÷ NeededParts`) or "users who hold every required permission"
(`UserPermissions ÷ RequiredPermissions`).

# Limitations:
The result heading is the left relation's columns minus the right's, so both must
be declared. A schema-on-read input (JSON, HTTP, MongoDB) is rejected; project it
into a declared heading first.

The divisor relation's columns must be a subset of the dividend's columns. The
result keeps only the "quotient" columns (those in the dividend but not the
divisor). It materialises rather than streams.

# Alternatives:
∀ (FORALL) expresses related "every row satisfies" group tests; division is the
specialised set-algebra form of "relates to all of these values".

# See Also:
[forall](../operators/forall.md), [semi-join](../joins/semi-join.md), [group](../operators/group.md)

# Notes:
A classic use is requirement-coverage checks: divide a "has" relation by a
"needs" relation to get the entities that fully cover the requirement.
