# Name: STANDARD_HASH (Oracle's digest by algorithm name, in hex)

# Syntax:
STANDARD_HASH(<string> [, <algorithm>])

π customer_id, STANDARD_HASH(email, 'SHA256') → digest (Customers)

# Description:
STANDARD_HASH is Oracle's hash function, which names its algorithm second and uses
SHA-1 when none is named. It gives the same digest as the function for that
algorithm: `STANDARD_HASH(s, 'MD5')` is [MD5](md5.md)`(s)`.

# Technical Description:
STANDARD_HASH(s: STRING [, algorithm: STRING]) → STRING. The algorithm is `MD5`,
`SHA1`, `SHA256`, `SHA384` or `SHA512`, in any case, and `SHA1` when it is left out;
another is an error naming these. The digest is of the UTF-8 bytes of `s`, in
lowercase hex. A NULL `s` or algorithm returns NULL. PURE, DETERMINISTIC.

# Examples:
A SHA-256 digest of each customer's email:
  π customer_id, STANDARD_HASH(email, 'SHA256') → digest (Customers)

# Pushdown:
It runs in the engine.

# SQL names:
Oracle's `STANDARD_HASH`, which returns `RAW`; here the digest is hex text, as
`LOWER(RAWTOHEX(STANDARD_HASH(s, a)))` gives it there for a string in a UTF-8 database.

# See Also:
[md5](md5.md), [sha1](sha1.md), [sha256](sha256.md), [sha2](sha2.md), [hashbytes](hashbytes.md)
