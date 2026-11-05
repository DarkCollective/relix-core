# Name: import (reuse symbols from another file)

# Syntax:
import "<path>";                              -- everything not private
import { Name1, Name2 } from "<path>";        -- specific symbols
import <Name> from "<path>";                  -- a single symbol
import source|relation|function <Name> from "<path>";   -- qualified

import { Users, Orders } from "./db.relix";

# Description:
Import pulls definitions — relations, sources, functions — from another .relix
file into the current one, so you can share common sources and helper functions
across scripts instead of copying them. Paths are relative to the file doing the
importing.

# Technical Description:
Imports are resolved depth-first with cycle detection. Every declaration of an
imported file that is not marked `private` is available to the importing file, and
so, through it, to the files that import that one; the import form decides only
which names the file brings in by name. A declaration marked `private` is visible
to no other file, whatever the import form: using one from an importer is an
error that names the file it is private to, and so is asking for it in a named
import. The file that declares it uses it freely, and so does everything it
defines: a view of the imported file that reads one of its private sources keeps
working for the importer. The qualified forms (`import source/relation/function
Name`) disambiguate when a name is otherwise ambiguous. Circular imports are
reported as an error.

The files of a script share one set of names; `private` hides a name but does not
give its file a scope of its own. A name declared in two files, whether both
declare it or one imports it, is an error reported where the second one is, and
declaring the same name twice in one file is not: the later declaration replaces
the earlier.

# Examples:
Bring in two named relations:
  import { Users, Orders } from "./db.relix";

Import everything a shared file exports:
  import "./shared.relix";

A single symbol, unqualified:
  import Employees from "./hr.relix";

Qualified to disambiguate kind:
  import function discount from "./pricing.relix";

A private declaration stays inside its file. With this `shared.relix`:

```
private Rates := [
| currency | rate |
|----------|------|
| EUR      | 1.1  |
];
Prices := { π currency, rate (Rates) };
```

an importer may query `Prices`, which reads `Rates`, but naming `Rates` itself is
an error ("The relation 'Rates' is private to 'shared.relix' ..."), after
`import "shared.relix";` as after `import { Rates } from "shared.relix";`.

# Limitations:
Circular imports are not allowed. `private` hides a name from every other file but
does not let two files declare the same name: a second file declaring a name that
a private declaration of the first already holds is an error too. Symbols must be
declared before they are used. The path must not be blank:

```relix-invalid
import "";
```

# Alternatives:
Inline the source/function directly when it is only used in one script.

# See Also:
[source](source.md), [def](def.md), [def-relation](def-relation.md), [namespace](namespace.md)

# Notes:
Mark helpers `private` to keep a clean public surface for files that are themselves
imported by others; the marker applies to every declaration kind (source, connection,
view, inline table, `def`).
