# Name: InStr (find substring position)

# Syntax:
InStr(<string>, <find>)
InStr(<start>, <string>, <find>)

π InStr(email, "@") → at_pos (Users)

# Description:
InStr finds where one string appears inside another and returns its position
(counting from 1), or 0 if it is not found. Use it to locate a delimiter — the @
in an email, a separator in a code — often as the input to Mid/Left/Right.

# Technical Description:
InStr(s, find) → NUMBER returns the 1-based index of the first occurrence of find
in s, or 0 if absent. The three-argument form InStr(start, s, find) begins
searching at the 1-based start position. NULL arguments return NULL. PURE,
DETERMINISTIC.

# Examples:
Position of the @ in an email:
  π InStr(email, "@") → at_pos (Users)

Split a "name:value" pair — get the name before the colon:
  π Left(pair, InStr(pair, ":") - 1) → name (Pairs)

Find a later occurrence (search from position 5):
  π InStr(5, path, "/") → next_slash (Paths)

# Limitations:
Returns 0 (not NULL) when the substring is absent — guard for 0 before using it as
an index. STRING arguments; a NULL argument returns NULL.

# Alternatives:
IsNumeric / pattern checks for validation; Replace to substitute rather than
locate.

# See Also:
[mid](mid.md), [left](left.md), [right](right.md), [replace](replace.md), [len](len.md)
