# Name: SHA2 (a string's SHA-2 digest of a given length, in hex)

# Syntax:
SHA2(<string>, <bits>)

π customer_id, SHA2(email, 512) → digest (Customers)

# Description:
SHA2 gives a SHA-2 digest of a string in lowercase hexadecimal, of the length its
second argument names: 224, 256, 384 or 512 bits. `SHA2(s, 256)` is
[SHA256](sha256.md)`(s)`.

# Technical Description:
SHA2(s: STRING, bits: NUMBER) → STRING. The digest of the UTF-8 bytes of `s`, in
lowercase hex. `bits` is 224, 256, 384 or 512, or 0, which is 256 as in MySQL; any
other length is an error naming the ones there are. A NULL `s` or `bits` returns NULL.
PURE, DETERMINISTIC.

# Examples:
A 512-bit digest of each customer's email:
  π customer_id, SHA2(email, 512) → digest (Customers)

# Pushdown:
SQL: `SHA2(…, n)` on MySQL, where `n` is written as 224, 256, 384 or 512. Elsewhere,
or with any other `n`, it runs in the engine.

# SQL names:
MySQL's `SHA2(s, n)`. MySQL gives NULL for a length it does not have, where the
engine stops with an error.

# See Also:
[sha256](sha256.md), [md5](md5.md), [sha1](sha1.md)
