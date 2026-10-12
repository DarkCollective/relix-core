# Name: Soundex (phonetic key for a name)

# Syntax:
Soundex(<s>)

π surname, Soundex(surname) → sound (People)

# Description:
Soundex turns a name into a short code based on how it sounds in English, so that
spelling variants get the same code: "Robert" and "Rupert" are both `R163`, and
"Ashcraft" and "Ashcroft" are both `A261`. Because the code is an ordinary string, two
relations can be joined with `=` on it. This is a quick way to find candidate pairs
before scoring them with a slower similarity function.

# Technical Description:
Soundex(s) → STRING, the American Soundex code. The first letter is kept, in upper
case. Each following consonant is given a digit: B F P V → 1, C G J K Q S X Z → 2,
D T → 3, L → 4, M N → 5, R → 6. Adjacent letters with the same digit count once, and so
do letters with the same digit separated only by H or W. A vowel (A E I O U Y) between
them keeps both. The result is padded with zeros or cut to four characters.

Only the letters A–Z take part, in upper or lower case. Every other character,
including accented letters, is skipped, so "Müller" is `M460`. A string with no letter
A–Z gives the empty string. A NULL argument returns NULL. PURE, DETERMINISTIC.

# Examples:
The phonetic key of each surname:
  π surname, Soundex(surname) → sound (People)

Candidate pairs: surnames that sound alike, then scored on first name:
  π A.id, B.id, JaroWinkler(A.first, B.first) → first_score (ρ A (People) ⨝ Soundex(A.surname) = Soundex(B.surname) ∧ A.id < B.id ρ B (People))

# Pushdown:
Not pushed down. MySQL's `SOUNDEX()` does not cut the code to four characters, so it
gives a different value for long names.

# Limitations:
It is designed for English names. Names that sound alike in other languages may get
different codes, and different names can share one: "Lee" and "Lowe" are both `L000`.
Matching codes are a reason to compare two records, not evidence that they match.

# Alternatives:
JaroWinkler or EditSimilarity to score spelling rather than sound; Trigram for longer
text.

# See Also:
[jarowinkler](jarowinkler.md), [editsimilarity](editsimilarity.md),
[levenshtein](levenshtein.md), [trigram](trigram.md)
