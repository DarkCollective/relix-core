# Name: SHA1 (a string's SHA-1 digest, in hex)

# Syntax:
SHA1(<string>)

π customer_id, SHA1(email) → digest (Customers)

# Description:
SHA1 gives the SHA-1 digest of a string as 40 lowercase hexadecimal characters:
`SHA1('a')` is `'86f7e437faa5a7fce15d1ddcb9eaeaea377667b8'`. Like [MD5](md5.md), it
fingerprints a value to find what changed.

# Technical Description:
SHA1(s: STRING) → STRING. The digest of the UTF-8 bytes of `s`, in lowercase hex.
NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
A digest of each customer's email:
  π customer_id, SHA1(email) → digest (Customers)

# Pushdown:
SQL: `SHA1(…)` on MySQL. Elsewhere it runs in the engine; PostgreSQL has no SHA-1
without an extension.

# Limitations:
SHA-1 is no longer collision-resistant; use [SHA256](sha256.md) where that matters.

# SQL names:
`SHA1(s)` in MySQL. SQL Server's `HASHBYTES('SHA1', s)` and Oracle's
`STANDARD_HASH(s)` are [HASHBYTES](hashbytes.md) and [STANDARD_HASH](standard_hash.md).

# See Also:
[md5](md5.md), [sha256](sha256.md), [sha2](sha2.md)
