# Name: Trigram (overlap of three-character pieces)

# Syntax:
Trigram(<a>, <b>)

π Trigram(a.title, b.title) → title_score (Pairs)

# Description:
Trigram scores how alike two strings are, from 0 to 1, by cutting each into overlapping
three-character pieces and measuring how many pieces they share. It is not affected much
by where in the string a difference falls, so it suits longer values such as product
titles and addresses, where a word may be added, dropped or moved.

# Technical Description:
Trigram(a, b) → NUMBER in [0, 1]: the Jaccard similarity `|A ∩ B| / |A ∪ B|` of the two
strings' sets of trigrams. Before it is cut, each string is padded with two spaces in
front and one behind, so "cat" yields `"  c"`, `" ca"`, `"cat"` and `"at "`. A trigram
that occurs more than once in a string is counted once.

Identical strings score 1, including two empty strings. An empty string against a
non-empty one scores 0. It is symmetric. PURE, DETERMINISTIC.

Trigrams are made of **code points**. Case is not folded, so compose LCase to ignore it.
A NULL argument returns NULL.

# Examples:
Product titles that share most of their pieces:
  σ Trigram(LCase(title), LCase(feed_title)) >= 0.6 (Listings)

Score titles alongside an exact brand match:
  π Ours.sku, Theirs.sku, Trigram(Ours.title, Theirs.title) → title_score (Ours ⨝ Ours.brand = Theirs.brand Theirs)

# Pushdown:
Not pushed down. PostgreSQL's `similarity()` from the `pg_trgm` extension uses the same
padding, but it lower-cases and splits the text into words first. It therefore gives a
different score for text with capitals or several words.

# Limitations:
Short strings have few trigrams, so one changed character moves the score a long way.
JaroWinkler or EditSimilarity is usually better for short names and codes. A high score
is evidence of resemblance, not of identity.

# Alternatives:
EditSimilarity for an edit-based score; JaroWinkler for short names; Soundex for an
exact key that can be joined on.

# See Also:
[editsimilarity](editsimilarity.md), [jarowinkler](jarowinkler.md),
[levenshtein](levenshtein.md), [soundex](soundex.md)
