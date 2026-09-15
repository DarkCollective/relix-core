# Name: import (reuse symbols from another file)

# Syntax:
import "<path>";                              -- everything exported
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
Imports are resolved depth-first with cycle detection. All symbols from an
imported file are registered in the global symbol table regardless of import form;
named imports control only what is re-exported to files that in turn import this
one — they do not restrict what you can reference locally. The qualified forms
(`import source/relation/function Name`) disambiguate when a name is otherwise
ambiguous. Circular imports are reported as an error.

# Examples:
Bring in two named relations:
  import { Users, Orders } from "./db.relix";

Import everything a shared file exports:
  import "./shared.relix";

A single symbol, unqualified:
  import Employees from "./hr.relix";

Qualified to disambiguate kind:
  import function discount from "./pricing.relix";

# Limitations:
Circular imports are not allowed. Named imports affect re-export visibility, not
local availability (all imported symbols are globally registered). Symbols must be
declared before they are used.

# Alternatives:
Inline the source/function directly when it is only used in one script.

# See Also:
[source](source.md), [def](def.md), [def-relation](def-relation.md), [namespace](namespace.md)

# Notes:
Use named imports to keep a clean public surface for files that are themselves
imported by others.
