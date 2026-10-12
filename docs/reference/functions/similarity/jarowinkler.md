# Name: JaroWinkler (similarity of short strings, favouring a shared start)

# Syntax:
JaroWinkler(<a>, <b>)

π JaroWinkler(a.surname, b.surname) → surname_score (Pairs)

# Description:
JaroWinkler scores how alike two short strings are, from 0 to 1. It was designed for
personal names in census records. It forgives characters that are slightly out of
place, as in "Martha" and "Marhta", and it gives extra weight to strings that start the
same way, because spelling variants of a name usually keep the first few letters.

# Technical Description:
JaroWinkler(a, b) → NUMBER in [0, 1]. The Jaro score counts the characters the two strings
share within a window of `max(Len(a), Len(b)) / 2 − 1` positions of each other, and the
transpositions among them. If the Jaro score is 0.7 or more, Winkler's adjustment adds
`p · 0.1 · (1 − jaro)`, where p is the length of the common prefix, up to 4 characters.
A score below 0.7 is returned unchanged.

Identical strings score 1, including two empty strings. An empty string against a
non-empty one scores 0. It is symmetric. PURE, DETERMINISTIC.

Characters are **code points**. Case is not folded, so compose LCase to ignore it. A
NULL argument returns NULL.

# Examples:
Surnames that are close variants:
  σ JaroWinkler(surname, alt_surname) >= 0.9 (People)

Pairs whose first names are similar and whose cities are the same:
  Left ⨝ JaroWinkler(Left.first, Right.first) >= 0.85 ∧ Left.city = Right.city Right

# Pushdown:
Not pushed down. It is computed in the engine.

# Limitations:
It is meant for short strings. On long text that shares a prefix, such as two addresses
on the same street, the prefix adjustment can make unrelated values score highly. A high
score is evidence of resemblance, not of identity.

# Alternatives:
EditSimilarity for a length-scaled edit distance; Trigram for longer text; Soundex for
names that sound alike but are spelled differently.

# See Also:
[editsimilarity](editsimilarity.md), [levenshtein](levenshtein.md),
[trigram](trigram.md), [soundex](soundex.md)
