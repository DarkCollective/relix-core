# Name: Record Linkage (similarity join → best match → CLUSTER)

# Syntax:
R ⨝ <similarity>(R.col, S.col) >= <threshold> S        -- candidate pairs
ρ A (R) ⨝ A.id < B.id ∧ <similarity>(A.col, B.col) >= <threshold> ρ B (R)   -- one relation against itself
TOP 1 <score> DESC PER <left id> (Pairs)                 -- the best match per record
CLUSTER <left id>, <right id> AS <entity> (Pairs)        -- pairs into entities

ρ A (People) ⨝ A.id < B.id ∧ JaroWinkler(A.name, B.name) >= 0.85 ρ B (People)

# Description:
Record linkage finds rows that probably describe the same real-world thing although
their data differs: "John Smith, Chicago" and "Jon Smith, Chicago". It is how a
customer list is deduplicated, two product catalogues are reconciled, or a family tree
is merged with another.

Relix has no record-linkage operator. The job is assembled from parts that already
exist, which keeps each step visible and lets every one of them be changed:

| Step | How |
|---|---|
| Score candidate pairs | a theta join whose condition calls a [similarity function](../functions/similarity/editsimilarity.md) |
| Keep one score per field | a `π` with one score column each for name, city, … |
| Reject the weak ones | a `σ` on the per-field scores |
| Best match per record | `TOP 1 … PER` the left id |
| Pairs into entities | `CLUSTER` over the matched pairs |

A similarity score is evidence that two records resemble each other. It is not proof
that they describe the same entity, and nothing in this pipeline makes it so: every
threshold here is a judgment about the data in front of you.

# Technical Description:
**The join.** `R ⨝ F(R.x, S.y) >= t S` is an ordinary theta join. When `F` is one of
`EditSimilarity`, `JaroWinkler` or `Trigram` compared with `>=`, `>` or `=` against a
number, or `Levenshtein` compared with `<=`, `<` or `=`, and the two arguments are
columns of opposite inputs, the planner does not compare every pair. It indexes one
input under keys the function supplies — the string's length for the edit-based
scores, a prefix of its trigrams for `Trigram` — and looks up only the rows of the
other input that share a key. Every pair found that way is still checked against the
whole condition, so the answer is the same as comparing every pair. `explain` shows the
choice as `SimilarityJoin`. Any other condition, an outer join, or a join that also has
an equality between the inputs (which uses a hash join) runs as before.

**Self-joins.** Matching a relation against itself needs two copies told apart by
`ρ`, and a condition `A.id < B.id`. Without it each pair appears twice, once in each
order, and every row matches itself.

**Transitivity.** `CLUSTER` puts two records in one entity when a chain of matches
connects them. If A matches B and B matches C, A and C end up together even when they
are not similar. Inspect the clusters, or raise the thresholds, before trusting them.

**NULL.** A similarity function returns NULL when either argument is NULL, so a pair
with a missing value never satisfies the join condition and is not a candidate.

# Examples:
Candidate pairs between two lists, keeping each field's score:
  π Crm.id, Billing.id, EditSimilarity(Crm.name, Billing.name) → name_score (Crm ⨝ EditSimilarity(Crm.name, Billing.name) >= 0.8 Billing)

Possible duplicates within one list:
  ρ A (People) ⨝ A.id < B.id ∧ JaroWinkler(A.name, B.name) >= 0.85 ρ B (People)

Sounds-alike candidates through an equality on a phonetic key, which is a hash join:
  ρ A (People) ⨝ Soundex(A.surname) = Soundex(B.surname) ∧ A.id < B.id ρ B (People)

# Worked Example:
A contact list has collected duplicates. Each row has a name and a city.

```relix
People := [
| id | name         | city    |
|----|--------------|---------|
| 1  | John Smith   | Chicago |
| 2  | Jon Smith    | Chicago |
| 3  | Jon Smyth    | Chicago |
| 4  | Robert Brown | Miami   |
| 5  | Rob Brown    | Tampa   |
| 6  | Cathy Wu     | Denver  |
| 7  | Catherine Wu | Denver  |
| 8  | Katherine Wu | Denver  |
| 9  | Maria Garcia | Boston  |
];
```

**Why `A.id < B.id`.** Matched against itself without it, the three Denver rows give
seven pairs: each row with itself, and each real pair twice.

```relix
Wu := { σ city = "Denver" (People) };
query { π A.id → a, B.id → b (ρ A (Wu) ⨝ JaroWinkler(A.name, B.name) >= 0.85 ρ B (Wu)) };
```

```
 a  b
 ─  ─
 6  6
 6  7
 7  6
 7  7
 7  8
 8  7
 8  8
(7 rows)
```

**Candidate pairs.** Score names with JaroWinkler, which suits short personal names,
and keep a separate score for the city so that each pair can be explained:

```relix
Candidates := {
  π A.id → a, B.id → b, A.name → a_name, B.name → b_name,
    Round(JaroWinkler(A.name, B.name), 2) → name_score,
    Round(EditSimilarity(A.city, B.city), 2) → city_score
  (ρ A (People) ⨝ A.id < B.id ∧ JaroWinkler(A.name, B.name) >= 0.85 ρ B (People))
};
query { Candidates };
```

```
 a  b  a_name        b_name        name_score  city_score
 ─  ─  ────────────  ────────────  ──────────  ──────────
 1  2  John Smith    Jon Smith           0.97           1
 1  3  John Smith    Jon Smyth           0.92           1
 2  3  Jon Smith     Jon Smyth           0.96           1
 4  5  Robert Brown  Rob Brown            0.9         0.2
 6  7  Cathy Wu      Catherine Wu        0.89           1
 7  8  Catherine Wu  Katherine Wu        0.94           1
(6 rows)
```

**Rejecting a tempting pair.** "Robert Brown" and "Rob Brown" have similar names, but
one lives in Miami and the other in Tampa. A name score alone would have matched them.
Requiring the city to agree as well rejects the pair:

```relix
Matches := { σ city_score >= 0.8 (Candidates) };
query { Matches };
```

```
 a  b  a_name        b_name        name_score  city_score
 ─  ─  ────────────  ────────────  ──────────  ──────────
 1  2  John Smith    Jon Smith           0.97           1
 1  3  John Smith    Jon Smyth           0.92           1
 2  3  Jon Smith     Jon Smyth           0.96           1
 6  7  Cathy Wu      Catherine Wu        0.89           1
 7  8  Catherine Wu  Katherine Wu        0.94           1
(5 rows)
```

**The best match per record.** When a record should pair with one other at most, keep
the highest-scoring match for each. John Smith (1) matched both Jon Smith and Jon
Smyth; Jon Smith scores higher:

```relix
query { TOP 1 name_score DESC PER a (Matches) };
```

```
 a  b  a_name        b_name        name_score  city_score
 ─  ─  ────────────  ────────────  ──────────  ──────────
 1  2  John Smith    Jon Smith           0.97           1
 2  3  Jon Smith     Jon Smyth           0.96           1
 6  7  Cathy Wu      Catherine Wu        0.89           1
 7  8  Catherine Wu  Katherine Wu        0.94           1
(4 rows)
```

**Entities.** `CLUSTER` turns the matched pairs into groups, one id per entity. Joined
back to the people, it labels each record:

```relix
Entities := { CLUSTER a, b AS entity_id (Matches) };
query { π id, name, entity_id (People ⨝ People.id = Entities.a Entities) };
```

```
 id  name          entity_id
 ──  ────────────  ─────────
  1  John Smith            1
  2  Jon Smith             1
  3  Jon Smyth             1
  6  Cathy Wu              2
  7  Catherine Wu          2
  8  Katherine Wu          2
(6 rows)
```

**Transitivity at work.** Cathy Wu and Katherine Wu share entity 2, but not because
they match each other. Cathy matched Catherine and Catherine matched Katherine. Scored
directly, the two ends fall below the threshold:

```relix
query { π A.name → a_name, B.name → b_name, Round(JaroWinkler(A.name, B.name), 2) → name_score
        (ρ A (People) ⨝ A.id = 6 ∧ B.id = 8 ρ B (People)) };
```

```
 a_name    b_name        name_score
 ────────  ────────────  ──────────
 Cathy Wu  Katherine Wu        0.75
(1 row)
```

Whether that makes them the same person is a question about the data, not the
algebra. Here it may well be. In a larger list, a chain of small differences can join
people who have nothing in common.

# Limitations:
The thresholds are judgments about your data, and no single one suits every column.
Indexing applies only when the function's arguments are plain columns, one from each
input. A score computed on `LCase(name)` is compared pair by pair; project the
lower-cased column first to have it indexed. The index is built in memory over one
input.

# Alternatives:
An equality on [Soundex](../functions/similarity/soundex.md), or on any other key
computed from both sides, is a hash join and the cheapest way to find candidates when
the key fits the data. [OPTIMIZE](optimize.md) can choose a one-to-one assignment where
`TOP 1 … PER` lets two records claim the same match.

# See Also:
[jarowinkler](../functions/similarity/jarowinkler.md),
[editsimilarity](../functions/similarity/editsimilarity.md),
[trigram](../functions/similarity/trigram.md),
[levenshtein](../functions/similarity/levenshtein.md),
[soundex](../functions/similarity/soundex.md),
[theta-join](../joins/theta-join.md), [top](top.md), [cluster](cluster.md),
[why](../operators/why.md)
