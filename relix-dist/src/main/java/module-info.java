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
 * Relix — a relational algebra engine, as one dependency.
 *
 * <p>Start at {@link com.darkcollective.relix.embed.Relix}, the session, and
 * {@link com.darkcollective.relix.embed.Relation}, the value.
 *
 * <p>This module is assembled from the engine's internal modules, which are not
 * published separately: their boundaries are claims this project's own build enforces,
 * not a contract with anyone outside it. What is exported here is what a caller can use
 * and what the compatibility promise covers — the facade, and the engine types its
 * signatures hand back.
 */
module com.darkcollective.relix {

    // The API, and the engine types its signatures reach. This list is the published
    // surface: it is generated from the same set ReExportSurfaceTest computes, so a
    // package a caller can reach is exported and nothing else is.
    exports com.darkcollective.relix.embed;
    exports com.darkcollective.relix.ast;
    exports com.darkcollective.relix.ast.visitor;
    exports com.darkcollective.relix.connectors.std;
    exports com.darkcollective.relix.events;
    exports com.darkcollective.relix.function;
    exports com.darkcollective.relix.lang.ast;
    exports com.darkcollective.relix.optimizer;
    exports com.darkcollective.relix.plan;
    exports com.darkcollective.relix.processor;
    exports com.darkcollective.relix.processor.connector;
    exports com.darkcollective.relix.processor.exec;
    exports com.darkcollective.relix.processor.provenance;
    exports com.darkcollective.relix.provenance;
    exports com.darkcollective.relix.semantic;
    exports com.darkcollective.relix.symbol;
    exports com.darkcollective.relix.symbol.graph;
    exports com.darkcollective.relix.symbol.relation;
    exports com.darkcollective.relix.symbol.table;
    exports com.darkcollective.relix.value;

    // javax.sql.DataSource is in the public signature of Relix.Builder.jdbc; the JDBC
    // connector and its catalog introspection are the rest of the reason.
    requires transitive java.sql;
    requires java.net.http;
    requires java.logging;
    // The shipped solver. Bundled rather than optional: OPTIMIZE and COVER EXACT need
    // one, and a client who has to install a solver to run a documented operator has
    // been handed assembly work this artifact exists to spare them.
    requires ojalgo;

    // The provider seams, and the implementations that ship inside. A modular runtime
    // reads these; a class path reads the merged META-INF/services beside them.
    uses com.darkcollective.relix.function.FunctionLibrary;
    uses com.darkcollective.relix.processor.connector.RelixConnector;
    uses com.darkcollective.relix.solver.MathProgrammingSolver;
    uses java.sql.Driver;

    provides com.darkcollective.relix.function.FunctionLibrary
            with com.darkcollective.relix.function.builtin.BuiltinFunctionLibrary;
    provides com.darkcollective.relix.processor.connector.RelixConnector
            with com.darkcollective.relix.connectors.std.CsvConnector;
    provides com.darkcollective.relix.solver.MathProgrammingSolver
            with com.darkcollective.relix.solver.ojalgo.OjAlgoSolver;
}
