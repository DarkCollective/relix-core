/*
 * Copyright 2026 Darkcollective, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * The embedding API — what a plain Java program depends on to build, optimise, inspect
 * and run a Relix query.
 *
 * <p>Entry point: {@link com.darkcollective.relix.embed.Relix}, which is the session, and
 * {@link com.darkcollective.relix.embed.Relation}, which is the value.
 *
 * <p>A <em>frontend</em>: it produces an AST, which is the side of the engine boundary
 * everything that builds a tree sits on. That is why naming the {@code .relix} grammar is
 * allowed here and forbidden in every engine module, and why nothing here spends the core
 * artifact's compatibility budget.
 *
 * <p>It is also batteries included, and carries the provider edges so no engine module
 * has to — the shape {@code relix-cli} and {@code relix-repl} already have, for the same
 * reason.
 */
module com.darkcollective.relix.embed {
    requires transitive com.darkcollective.relix.ast;
    requires transitive com.darkcollective.relix.lang.ast;
    requires transitive com.darkcollective.relix.semantic;
    requires transitive com.darkcollective.relix.symbol;
    requires transitive com.darkcollective.relix.value;
    requires transitive com.darkcollective.relix.processor;

    // The .relix grammar. A frontend may name a frontend. The lexer too, which
    // Relix.tokens makes lenient for highlighting.
    requires com.darkcollective.relix.lang;
    requires com.darkcollective.relix.parser;
    // Sandbox.load reads its configuration with it.
    requires com.darkcollective.relix.json;

    // The phases the terminals expose, and the types they answer with. `transitive`
    // where a public signature names one, so a caller who depends on the facade can use
    // what the facade hands back without naming an engine module — which is what
    // ReExportSurfaceTest enumerates and what "one dependency" means.
    requires transitive com.darkcollective.relix.optimizer;
    requires transitive com.darkcollective.relix.plan;
    requires transitive com.darkcollective.relix.events;
    requires transitive com.darkcollective.relix.provenance;
    requires transitive com.darkcollective.relix.function;
    requires com.darkcollective.relix.cost;

    // The DataSource registry and the ConnectionProvider seam, plus the connectors
    // themselves — discovered at runtime, and required so the provider stays in the
    // resolved graph rather than being dropped (ADR-0026 D9).
    requires transitive com.darkcollective.relix.connectors.std;

    // The batteries: discovered at runtime, named here because a module nothing
    // `requires` is dropped from the resolved graph — and because "batteries included"
    // has to mean the facade installs them, not that someone else must.
    requires com.darkcollective.relix.function.builtin;
    requires com.darkcollective.relix.solver.ojalgo;

    // javax.sql.DataSource is in the public signature of Relix.Builder.jdbc.
    requires transitive java.sql;

    exports com.darkcollective.relix.embed;
}
