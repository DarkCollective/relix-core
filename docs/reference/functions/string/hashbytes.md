# Name: HASHBYTES (SQL Server's digest by algorithm name, in hex)

# Syntax:
HASHBYTES(<algorithm>, <string>)

π customer_id, HASHBYTES('SHA2_256', email) → digest (Customers)

# Description:
HASHBYTES is SQL Server's hash function, which names its algorithm first. It gives
the same digest as the function for that algorithm: `HASHBYTES('MD5', s)` is
[MD5](md5.md)`(s)`.

# Technical Description:
HASHBYTES(algorithm: STRING, s: STRING) → STRING. The algorithm is `MD5`, `SHA`,
`SHA1`, `SHA2_256` or `SHA2_512`, in any case; another is an error naming these. The
digest is of the UTF-8 bytes of `s`, in lowercase hex. A NULL algorithm or `s` returns
NULL. PURE, DETERMINISTIC.

# Examples:
A SHA-256 digest of each customer's email:
  π customer_id, HASHBYTES('SHA2_256', email) → digest (Customers)

# Pushdown:
It runs in the engine. SQL Server's own `HASHBYTES` hashes an `NVARCHAR` as UTF-16 and
returns bytes, so it would answer differently.

# SQL names:
SQL Server's `HASHBYTES`, which returns `VARBINARY`; here the digest is hex text, as
`LOWER(CONVERT(VARCHAR(128), HASHBYTES(a, s), 2))` gives it there for a `VARCHAR`
in a UTF-8 collation. `MD2` and `MD4` are not taken.

# See Also:
[md5](md5.md), [sha1](sha1.md), [sha256](sha256.md), [sha2](sha2.md), [standard_hash](standard_hash.md)
