# Name: env (environment declaration)

# Syntax:
env;
env from "<file>";
env using "<profile>";
env from "<file>" using "<profile>";

env from ".relix-env.json" using "development";

# Description:
`env` names the file and the profile that a script's `${VAR}` placeholders are meant
to be resolved from, so the script says where its configuration comes from instead of
leaving it to whoever runs it. It carries no values itself: a token, a password or a
URL stays out of the script, and the script holds `${NAME}` where it goes (see
[connection](connection.md) and [http source](http-source.md)).

The statement is optional. When present it comes first, after a `namespace` if there
is one, and before every other statement; there is at most one.

# Technical Description:
`from` takes a string giving the file's path, and `using` a string giving a profile
within it. Each is optional, and both may be left out (`env;`). Both must be written as
double-quoted strings.

**The engine records the statement and does nothing else with it.** It is parsed,
printed back with the rest of the script, and ignored by analysis, planning and
execution: no file is read, no profile is selected, and a script that names a file
that does not exist is not an error. Where a `${NAME}` placeholder gets its value is
decided by the program that runs the script, not by the engine. A program embedding
Relix gives the session a resolver of its own; the `relix` command resolves a
placeholder from a profile (`-P`, `-D`) or the environment.

A session built with a sealed sandbox refuses the statement, as it refuses any
statement that reaches outside the session.

# Examples:
A script that names its environment file and profile, and otherwise runs as usual:

```relix
env from ".relix-env.json" using "development";

Tiers := [
| tier   | rate |
|--------|------|
| bronze | 5    |
| silver | 8    |
];

query { Tiers };
```

The shortest form, which names neither:

```relix
env;

Tiers := [
| tier   | rate |
|--------|------|
| bronze | 5    |
];

query { Tiers };
```

# Limitations:
The engine reads no environment file, so a `from` path does not by itself make a
placeholder resolve, and nothing in this statement can be used to check that the
file exists or that the profile is defined in it.

# Alternatives:
Give the session a placeholder resolver, which is how an embedding program supplies
values (see the programming guide's chapter on secrets), or bind a name for one run
with the `relix` command's `-D NAME=VALUE`, or put the values in a profile and choose
it with `-P`.

# See Also:
[connection](connection.md), [http source](http-source.md), [source](source.md), [import](import.md)

# Notes:
`${NAME}` is resolved when a query runs rather than when the script is loaded, so
the value never appears in `definitions()` or in an error message.
