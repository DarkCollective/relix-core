# Name: namespace (script namespace declaration)

# Syntax:
namespace <name>;

namespace analytics;

# Description:
A namespace declaration at the top of a script labels everything it defines with
a name, helping keep symbols from different files or domains from clashing. Think
of it as the package or schema name for the script's relations and functions.

# Technical Description:
`namespace name;` sets the script's namespace, recorded in the SemanticModel.
Symbols are registered under it, but lookups fall through: `lookupRelation(name)`
searches the `default` namespace, then `builtin`, then all other registered
namespaces — so a script that declares a namespace still resolves its own and
imported symbols throughout the pipeline.

# Examples:
Label a script's symbols:
  namespace analytics;

```relix
source Orders from database { url: "${DB}", table: "orders",
    schema: { id: NUMBER, amount: NUMBER } };
query { γ SUM(amount) → total (Orders) };
```

# Limitations:
One namespace per script, declared first. Namespacing organises symbols but does
not by itself isolate them — all imported symbols remain globally resolvable.

# Alternatives:
Omit the declaration to use the default namespace.

# See Also:
[import](import.md), [source](source.md), [def](def.md)

# Notes:
The namespace appears in the IR report header (`RELIX IR namespace=…`).
