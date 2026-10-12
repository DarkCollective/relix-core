# Name: EditSimilarity (edit distance as a score from 0 to 1)

# Syntax:
EditSimilarity(<a>, <b>)

π EditSimilarity(a.name, b.name) → name_score (Pairs)

# Description:
EditSimilarity scores how alike two strings are on a scale from 0 (nothing in common)
to 1 (identical), based on how many single-character edits separate them. Because the
count is divided by the length of the longer string, one typo in a long name costs less
than one typo in a short code, so a single threshold such as `>= 0.8` can be used across
a column whose values vary in length.

# Technical Description:
EditSimilarity(a, b) → NUMBER in [0, 1], equal to
`1 − Levenshtein(a, b) / max(Len(a), Len(b))`. Two empty strings score 1. An empty string
against a non-empty one scores 0. It is symmetric. PURE, DETERMINISTIC.

Lengths and edits are counted in **code points**. Case is not folded, so compose LCase
to ignore it. A NULL argument returns NULL, which means that a join condition built on it
is UNKNOWN for that pair and the pair is not kept.

# Examples:
Names that are at least 80% alike:
  σ EditSimilarity(name, alias) >= 0.8 (People)

Candidate pairs between two customer lists, scoring each field separately:
  π Crm.id, Billing.id, EditSimilarity(Crm.name, Billing.name) → name_score (Crm ⨝ EditSimilarity(Crm.name, Billing.name) >= 0.8 Billing)

# Pushdown:
Not pushed down. It is computed in the engine.

# Limitations:
A score is evidence that two strings look alike, not proof that they refer to the same
thing: "Rob Brown" and "Bob Brown" score highly. Keep one score per field (name, city,
date of birth) so that a reader can see why a pair was matched. Each call takes time
proportional to the product of the two lengths. A join on
`EditSimilarity(R.x, S.y) >= t`, with columns from each side, compares only the pairs
whose lengths could reach t (see [record linkage](../../advanced/record-linkage.md)).

# Alternatives:
Levenshtein for the raw edit count; JaroWinkler for short personal names, where a
shared beginning matters most; Trigram when words may be reordered.

# See Also:
[levenshtein](levenshtein.md), [jarowinkler](jarowinkler.md), [trigram](trigram.md),
[soundex](soundex.md), [theta-join](../../joins/theta-join.md)
