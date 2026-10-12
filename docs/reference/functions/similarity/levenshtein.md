# Name: Levenshtein (edit distance between two strings)

# Syntax:
Levenshtein(<a>, <b>)

π Levenshtein(typed, expected) → edits (Answers)

# Description:
Levenshtein counts how many single-character changes turn one string into another:
inserting a character, deleting one, or replacing one with another. "kitten" becomes
"sitting" in three. Use it to catch typos and near-misses, such as "Jon" for "John" or a
transposed digit in a code.

The count is not scaled to the strings' length, so one edit means a lot in a
three-letter code and very little in a long address. For a score from 0 to 1 that
allows for length, use EditSimilarity.

# Technical Description:
Levenshtein(a, b) → NUMBER, a non-negative integer: the minimum number of insertions,
deletions and substitutions of one character each that turn a into b. It is symmetric,
is 0 exactly when the strings are equal, and is at most the length of the longer
string. PURE, DETERMINISTIC.

Characters are **code points**, as in Len and Mid, so an emoji counts as one
character and replacing it is one edit. Case is not folded: "Smith" and "smith" are one
edit apart. Write `Levenshtein(LCase(a), LCase(b))` to ignore case.

A NULL argument returns NULL, so a join condition built on it is UNKNOWN for that pair
and the pair is not kept.

# Examples:
Answers within two edits of the expected spelling:
  σ Levenshtein(typed, expected) <= 2 (Answers)

Distance ignoring case:
  π Levenshtein(LCase(name), LCase(alias)) → edits (People)

Candidate pairs of product codes that differ by one character:
  Catalog ⨝ Levenshtein(Catalog.code, Feed.code) = 1 Feed

# Pushdown:
Not pushed down. PostgreSQL's `levenshtein()` is part of the optional `fuzzystrmatch`
extension, which a database might not have installed. A filter on Levenshtein runs in the
engine.

# Limitations:
Each call compares every character of one string against every character of the
other, so it takes time proportional to the product of their lengths. In a join, every
pair of rows is scored. A small distance means the strings look alike. It does not mean
they name the same thing.

# Alternatives:
EditSimilarity for the same measure on a 0–1 scale; JaroWinkler for short names;
Trigram for longer text where words move around; Soundex for names that sound alike.

# See Also:
[editsimilarity](editsimilarity.md), [jarowinkler](jarowinkler.md),
[trigram](trigram.md), [soundex](soundex.md), [len](../string/len.md)
