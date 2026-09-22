# Getting the library

Relix is one artifact. Everything the rest of this guide uses — the engine, the
default function library, the CSV/JSON/HTTP/JDBC connectors, the bundled solver — is
inside it, so a program that queries a database needs this dependency and a driver,
and nothing else.

## The coordinate

The artifact is `com.darkcollective.relix:relix`, on Maven Central. That is one
coordinate: the twenty-one modules the engine is assembled from are internal to the
build and are not published separately — the module graph is a claim this build
enforces about itself, not a set of names a program should depend on.

A consuming Gradle build names it:

```gradle
repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.darkcollective.relix:relix:1.0.0-rc2'
}
```

and a Maven build the same way:

```xml
<dependency>
    <groupId>com.darkcollective.relix</groupId>
    <artifactId>relix</artifactId>
    <version>1.0.0-rc2</version>
</dependency>
```

`ojalgo` is a runtime dependency of the published POM rather than a merged part of the
jar. Shading it would hide it from your dependency report and from whatever scans that
report for vulnerabilities, which is a worse trade than one visible transitive
dependency.

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

The engine ships as a set of JPMS modules and works equally on the class path or the
module path. On the module path, the module to require is
`com.darkcollective.relix.embed`.

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

Provider discovery is a classpath scan, so a missing connector or function library
fails nothing a compiler can see. Asking this relation is how the failure becomes
visible before a query depends on it — filter it by `kind` to list the connectors,
function libraries, solvers and drivers a session found.

## Where to go next

[Getting started](getting-started.md) opens a session and runs a first query.
