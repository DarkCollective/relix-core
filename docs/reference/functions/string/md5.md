# Name: MD5 (a string's MD5 digest, in hex)

# Syntax:
MD5(<string>)

π customer_id, MD5(name + '|' + email) → fingerprint (Customers)

# Description:
MD5 gives the MD5 digest of a string as 32 lowercase hexadecimal characters:
`MD5('a')` is `'0cc175b9c0f1b6a831c399e269772661'`. A digest is a fingerprint. Two
rows whose columns hash to different digests differ, so comparing digests finds what
changed between two copies of a table, even when the copies are held in different
places.

# Technical Description:
MD5(s: STRING) → STRING. The digest of the UTF-8 bytes of `s`, in lowercase hex, as
PostgreSQL's `md5` gives it. NULL input returns NULL. PURE, DETERMINISTIC.

To fingerprint several columns, join them into one string first, with a separator
that cannot appear in them, so that `('ab', 'c')` and `('a', 'bc')` do not hash
alike. A NULL column makes the joined string NULL; write
[Coalesce](../conditional/coalesce.md)`(col, '')` to hash it as empty.

# Examples:
A fingerprint of each customer's details:
  π customer_id, MD5(name + '|' + email) → fingerprint (Customers)

# Worked Example:
Two copies of a customer table, one of them edited:

```relix
Before := [
| id | name | email         |
|----|------|---------------|
| 1  | Ada  | ada@a.example |
| 2  | Bo   | bo@b.example  |
];
After := [
| id | name | email         |
|----|------|---------------|
| 1  | Ada  | ada@a.example |
| 2  | Bo   | bo@c.example  |
];
Old := { π id, MD5(name + '|' + email) → digest (Before) };
New := { π id, MD5(name + '|' + email) → digest (After) };

query { π id (New − Old) };
```

```
 id
 ──
  2
(1 row)
```

Customer 2's email changed, so its digest did; customer 1 hashes the same in both.

# Pushdown:
SQL: `md5(convert_to(…, 'UTF8'))` on PostgreSQL, which hashes the UTF-8 bytes
whatever the database's encoding, and `MD5(…)` on MySQL and DuckDB. Elsewhere it runs
in the engine: SQL Server's `HASHBYTES` hashes an `NVARCHAR` as UTF-16, so its
digest of the same text differs.

# Limitations:
MD5 is a fingerprint, not a protection: it is unsuitable for passwords or for
anything an adversary chooses. Use [SHA256](sha256.md) where collisions matter.

# SQL names:
`MD5(s)` in PostgreSQL, MySQL and DuckDB. SQL Server's `HASHBYTES('MD5', s)` and
Oracle's `STANDARD_HASH(s, 'MD5')` give the same digest as bytes; here they are
[HASHBYTES](hashbytes.md) and [STANDARD_HASH](standard_hash.md), which give it as hex.

# See Also:
[sha1](sha1.md), [sha256](sha256.md), [sha2](sha2.md), [hashbytes](hashbytes.md), [standard_hash](standard_hash.md)
