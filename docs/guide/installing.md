# Getting the library

The engine is one artifact. Almost everything the rest of this guide uses — the
engine, the default function library, the CSV/JSON/HTTP/JDBC connectors — is inside it,
so a program that queries a database needs this dependency and a driver, and nothing
else. Two things are optional and published beside it: the solver, and the language
reference.

## The coordinate

The engine is `com.darkcollective.relix:relix`, on Maven Central. The twenty-odd modules
it is assembled from are internal to the build and are not published separately — the
module graph is a claim this build enforces about itself, not a set of names a program
should depend on.

A consuming Gradle build names it:

```gradle
repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.darkcollective.relix:relix:1.0.0-rc8'
}
```

and a Maven build the same way:

```xml
<dependency>
    <groupId>com.darkcollective.relix</groupId>
    <artifactId>relix</artifactId>
    <version>1.0.0-rc8</version>
</dependency>
```

`relix` depends on nothing else.

## The optional artifacts

Three more coordinates are published at the same version as `relix`:

| Artifact | What it adds |
|---|---|
| `com.darkcollective.relix:relix-solver-ojalgo` | The solver that `OPTIMIZE` and `COVER EXACT` need, backed by ojalgo |
| `com.darkcollective.relix:relix-docs` | The language reference and `RelixDocs`, which reads it (see [Building tools](tooling.md)) |
| `com.darkcollective.relix:relix-all` | No code: a POM naming the engine and both of the above |

Without the solver, a query using `OPTIMIZE` or `COVER EXACT` fails before it reads any
input, with an error naming the artifact to add; every other operator runs. The solver
is found at run time, so adding it to the runtime class path or module path is enough —
nothing compiles against it:

```gradle
dependencies {
    implementation 'com.darkcollective.relix:relix:1.0.0-rc8'
    runtimeOnly 'com.darkcollective.relix:relix-solver-ojalgo:1.0.0-rc8'
}
```

`ojalgo` arrives as a dependency of `relix-solver-ojalgo` rather than merged into its
jar. Shading it would hide it from your dependency report and from whatever scans that
report for vulnerabilities, which is a worse trade than one visible transitive
dependency.

Each artifact holds packages no other one does, so any combination of them can share a
module path.

To work on Relix itself, or to depend on a change that is not released,
`./gradlew publishToMavenLocal` publishes the same coordinate to your local Maven
repository under the version in the build; a consuming build reaches it by adding
`mavenLocal()` to the repositories above.

## Java 21

Relix targets **Java 21** and the jar is compiled for it. A build on an earlier JDK
fails at class-load time, which is a confusing way to learn a version requirement, so
it is worth stating in the consuming build:

```gradle
java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}
```

A newer JDK is fine — 21 is the floor, not the ceiling.

The engine works equally on the class path or the module path. On the module path, the
module to require is `com.darkcollective.relix`, and `com.darkcollective.relix.docs` to
read the reference. The solver is not required by name: the engine uses its service,
so the module path resolves it when it is there.

## A driver is yours to add

The JDBC connector is in the jar; JDBC **drivers** are not. A session that reads
PostgreSQL needs the PostgreSQL driver on your classpath, exactly as any other JDBC
program does:

```gradle
runtimeOnly 'org.postgresql:postgresql:42.7.4'
```

A session downloads nothing on its own — a library call that fetched a jar into your
home directory would be a surprise you could not see coming. `Builder.provisioners` is
how an application that means to offer that says so.

## Checking what you have

`relix.version` is a relation listing one row per component actually present, which is
the direct way to confirm the classpath is what you think it is:

```java
import com.darkcollective.relix.embed.Relix;

try (Relix relix = Relix.open()) {
    relix.relation("π component (σ kind = 'facade' (relix.version))")
            .toList()
            .forEach(System.out::println);
}
```

```
(component=relix-embed)
```

`Relix.version()` is the engine's own version, the value the engine's row in that
relation reports, for a program that wants it without running a query:

```java
System.out.println(Relix.version());
```

```
<version>
```

Provider discovery is a classpath scan, so a missing connector or function library
fails nothing a compiler can see. Asking this relation is how the failure becomes
visible before a query depends on it — filter it by `kind` to list the connectors,
function libraries, solvers and drivers a session found.

## Where to go next

[Getting started](getting-started.md) opens a session and runs a first query.
