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
package com.darkcollective.relix.processor;

import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import com.darkcollective.relix.symbol.table.SymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;


import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ExecutionContext — shared execution state")
final class ExecutionContextTest extends ProcessorTestSupport {

    // ── Direct construction ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Direct construction")
    class DirectConstruction {

        @Test
        @DisplayName("stores all three components")
        void storesComponents() {
            SymbolTable table = new InMemorySymbolTable();
            SchemaAnnotations annotations = SchemaAnnotations.empty();
            DataSourceConnector connector = (name, s) -> { throw new UnsupportedOperationException(); };

            var ctx = new ExecutionContext(table, annotations, connector);

            assertThat(ctx.symbolTable()).isSameAs(table);
            assertThat(ctx.nodeSchemas()).isSameAs(annotations);
            assertThat(ctx.connector()).isSameAs(connector);
        }

        @Test
        @DisplayName("defaults the event listener to NONE")
        void defaultsListenerToNone() {
            var ctx = new ExecutionContext(new InMemorySymbolTable(),
                    SchemaAnnotations.empty(), (n, s) -> null);
            assertThat(ctx.listener()).isSameAs(QueryEventListener.NONE);
        }

        @Test
        @DisplayName("rejects null listener via the canonical constructor")
        void rejectsNullListener() {
            assertThatThrownBy(() -> new ExecutionContext(
                    new InMemorySymbolTable(), SchemaAnnotations.empty(),
                    java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    (n, s) -> null, null, ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS,
                    ExecutionContext.UNLIMITED_MATERIALIZED_ROWS,
                    java.time.Clock.systemUTC(), FunctionCatalog.empty()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        /**
         * The default source is the system UTC clock, and what the context keeps is one
         * reading of it. Both halves matter: the zone is UTC, and the instant does not
         * move — which is what makes every row of a run see the same moment, and what
         * lets the planner evaluate a clock call once and push the value.
         */
        @DisplayName("reads system UTC once and keeps the instant")
        void defaultsToOneReadingOfSystemUtc() {
            var before = java.time.Instant.now();
            var ctx = new ExecutionContext(new InMemorySymbolTable(),
                    SchemaAnnotations.empty(), (n, s) -> null);
            var after = java.time.Instant.now();

            assertThat(ctx.clock().getZone()).isEqualTo(java.time.ZoneOffset.UTC);
            assertThat(ctx.clock().instant())
                    .isBetween(before, after);
            assertThat(ctx.clock().instant())
                    .as("read twice, the same moment — a live clock would have moved on")
                    .isEqualTo(ctx.clock().instant());
            assertThat(ctx.clock()).isEqualTo(
                    java.time.Clock.fixed(ctx.clock().instant(), java.time.ZoneOffset.UTC));
        }

        @Test
        @DisplayName("pinning is idempotent, so a with… copy keeps the original moment")
        void withCopiesKeepTheInstant() {
            var ctx = new ExecutionContext(new InMemorySymbolTable(),
                    SchemaAnnotations.empty(), (n, s) -> null);
            var copy = ctx.withMaxFixpointRounds(7).withListener(event -> { });

            assertThat(copy.clock().instant())
                    .as("a copy that re-read a live clock would drift away from the run "
                            + "the planner substituted a value for")
                    .isEqualTo(ctx.clock().instant());
        }

        @Test
        @DisplayName("withClock swaps the clock and shares every other field")
        void withClockPreservesOtherFields() {
            var ctx = new ExecutionContext(new InMemorySymbolTable(),
                    SchemaAnnotations.empty(), (n, s) -> null);
            var pinned = java.time.Clock.fixed(
                    java.time.Instant.parse("2026-03-04T05:06:07Z"),
                    java.time.ZoneOffset.UTC);
            var swapped = ctx.withClock(pinned);

            assertThat(swapped.clock()).isEqualTo(pinned);
            assertThat(swapped.symbolTable()).isSameAs(ctx.symbolTable());
            assertThat(swapped.nodeSchemas()).isSameAs(ctx.nodeSchemas());
            assertThat(swapped.connector()).isSameAs(ctx.connector());
            assertThat(swapped.listener()).isSameAs(ctx.listener());
            assertThat(swapped.maxFixpointRounds()).isEqualTo(ctx.maxFixpointRounds());
        }

        @Test
        @DisplayName("withClock rejects null")
        void withClockRejectsNull() {
            var ctx = new ExecutionContext(new InMemorySymbolTable(),
                    SchemaAnnotations.empty(), (n, s) -> null);
            assertThatThrownBy(() -> ctx.withClock(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the other withers preserve a pinned clock")
        void othersPreserveClock() {
            var pinned = java.time.Clock.fixed(
                    java.time.Instant.parse("2026-03-04T05:06:07Z"),
                    java.time.ZoneOffset.UTC);
            var ctx = new ExecutionContext(new InMemorySymbolTable(),
                    SchemaAnnotations.empty(), (n, s) -> null).withClock(pinned);

            assertThat(ctx.withListener(QueryEventListener.NONE).clock()).isEqualTo(pinned);
            assertThat(ctx.withMaxFixpointRounds(5).clock()).isEqualTo(pinned);
            assertThat(ctx.withMaxMaterializedRows(5).clock()).isEqualTo(pinned);
        }

        @Test
        @DisplayName("rejects null clock via the canonical constructor")
        void rejectsNullClock() {
            assertThatThrownBy(() -> new ExecutionContext(
                    new InMemorySymbolTable(), SchemaAnnotations.empty(),
                    java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    (n, s) -> null, QueryEventListener.NONE,
                    ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS,
                    ExecutionContext.UNLIMITED_MATERIALIZED_ROWS, null, FunctionCatalog.empty()))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ── withFunctions() ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("withFunctions()")
    class WithFunctions {

        @Test
        @DisplayName("swaps the catalogue and shares every other field")
        void swapsTheCatalogue() {
            var base = new ExecutionContext(new InMemorySymbolTable(),
                    SchemaAnnotations.empty(), (n, s) -> null);
            FunctionCatalog installed = base.functions();
            var swapped = base.withFunctions(FunctionCatalog.empty());

            assertThat(swapped.functions()).isNotSameAs(installed);
            assertThat(swapped.functions().isEmpty()).isTrue();
            assertThat(swapped.symbolTable()).isSameAs(base.symbolTable());
            assertThat(swapped.connector()).isSameAs(base.connector());
            assertThat(swapped.clock()).isEqualTo(base.clock());
        }

        @Test
        @DisplayName("defaults to the installed libraries, discovered once")
        void defaultsToInstalled() {
            var ctx = new ExecutionContext(new InMemorySymbolTable(),
                    SchemaAnnotations.empty(), (n, s) -> null);

            assertThat(ctx.functions()).isSameAs(ExecutionContext.installedFunctions());
            // Discovery scans the module path; doing it twice would be waste, and two
            // catalogues can disagree about what a name means.
            assertThat(ExecutionContext.installedFunctions())
                    .isSameAs(ExecutionContext.installedFunctions());
        }

        @Test
        @DisplayName("rejects a null catalogue")
        void rejectsNull() {
            var ctx = new ExecutionContext(new InMemorySymbolTable(),
                    SchemaAnnotations.empty(), (n, s) -> null);

            assertThatThrownBy(() -> ctx.withFunctions(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ── withListener() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("withListener()")
    class WithListener {

        @Test
        @DisplayName("returns a copy carrying the listener, sharing all other fields")
        void attachesListener() {
            SemanticModel model = model(INLINE_SCRIPT);
            DataSourceConnector connector = (n, s) -> { throw new UnsupportedOperationException(); };
            QueryEventListener listener = event -> { };

            var base = ExecutionContext.of(model, connector);
            var withL = base.withListener(listener);

            assertThat(withL.listener()).isSameAs(listener);
            assertThat(withL.symbolTable()).isSameAs(base.symbolTable());
            assertThat(withL.nodeSchemas()).isSameAs(base.nodeSchemas());
            assertThat(withL.connector()).isSameAs(base.connector());
            assertThat(base.listener()).isSameAs(QueryEventListener.NONE);
        }

        @Test
        @DisplayName("rejects null listener")
        void rejectsNullListener() {
            var base = ExecutionContext.of(model(INLINE_SCRIPT),
                    (n, s) -> { throw new UnsupportedOperationException(); });
            assertThatThrownBy(() -> base.withListener(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null symbolTable")
        void rejectsNullSymbolTable() {
            assertThatThrownBy(() -> new ExecutionContext(
                    null, SchemaAnnotations.empty(), (n, s) -> null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null nodeSchemas")
        void rejectsNullNodeSchemas() {
            assertThatThrownBy(() -> new ExecutionContext(
                    new InMemorySymbolTable(), null, (n, s) -> null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null connector")
        void rejectsNullConnector() {
            assertThatThrownBy(() -> new ExecutionContext(
                    new InMemorySymbolTable(), SchemaAnnotations.empty(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ── of() factory ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("of(SemanticModel, connector) factory")
    class OfFactory {

        @Test
        @DisplayName("extracts symbolTable and nodeSchemas from model")
        void extractsFromModel() {
            SemanticModel model = model(INLINE_SCRIPT);
            DataSourceConnector connector = (name, s) -> { throw new UnsupportedOperationException(); };

            var ctx = ExecutionContext.of(model, connector);

            assertThat(ctx.symbolTable()).isSameAs(model.symbolTable());
            assertThat(ctx.nodeSchemas()).isSameAs(model.nodeSchemas());
            assertThat(ctx.connector()).isSameAs(connector);
        }

        @Test
        @DisplayName("rejects null model")
        void rejectsNullModel() {
            assertThatThrownBy(() -> ExecutionContext.of(null, (n, s) -> null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null connector")
        void rejectsNullConnector() {
            SemanticModel model = model(INLINE_SCRIPT);
            assertThatThrownBy(() -> ExecutionContext.of(model, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ── inlineOnly() factory ──────────────────────────────────────────────────

    @Nested
    @DisplayName("inlineOnly(SemanticModel) factory")
    class InlineOnlyFactory {

        @Test
        @DisplayName("connector throws EvaluationException for any external relation")
        void connectorThrowsForExternalRelation() {
            SemanticModel model = model(INLINE_SCRIPT);
            var ctx = ExecutionContext.inlineOnly(model);

            var schema = schema(col("id", ScalarType.NUMBER));
            assertThatThrownBy(() -> ctx.connector().open("SomeExternalTable", schema))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("SomeExternalTable");
        }

        @Test
        @DisplayName("symbol table and node schemas are still populated from model")
        void populatesFromModel() {
            SemanticModel model = model(INLINE_SCRIPT);
            var ctx = ExecutionContext.inlineOnly(model);

            assertThat(ctx.symbolTable()).isSameAs(model.symbolTable());
            assertThat(ctx.nodeSchemas()).isSameAs(model.nodeSchemas());
        }

        @Test
        @DisplayName("rejects null model")
        void rejectsNullModel() {
            assertThatThrownBy(() -> ExecutionContext.inlineOnly(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ── withMaxFixpointRounds() ───────────────────────────────────────────────

    @Nested
    @DisplayName("withMaxFixpointRounds()")
    class WithMaxFixpointRounds {

        @Test
        @DisplayName("default context has UNLIMITED_FIXPOINT_ROUNDS")
        void defaultIsUnlimited() {
            var ctx = new ExecutionContext(new InMemorySymbolTable(),
                    SchemaAnnotations.empty(), (n, s) -> null);
            assertThat(ctx.maxFixpointRounds())
                    .isEqualTo(ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS);
        }

        @Test
        @DisplayName("returns copy with new limit, preserving all other fields")
        void returnsCopyWithNewLimit() {
            SemanticModel m = model(INLINE_SCRIPT);
            DataSourceConnector connector = (n, s) -> { throw new UnsupportedOperationException(); };
            var base = ExecutionContext.of(m, connector);

            var capped = base.withMaxFixpointRounds(42);

            assertThat(capped.maxFixpointRounds()).isEqualTo(42);
            assertThat(capped.symbolTable()).isSameAs(base.symbolTable());
            assertThat(capped.nodeSchemas()).isSameAs(base.nodeSchemas());
            assertThat(capped.connector()).isSameAs(base.connector());
            assertThat(capped.listener()).isSameAs(base.listener());
            assertThat(base.maxFixpointRounds())
                    .isEqualTo(ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS);
        }

        @Test
        @DisplayName("rejects maxFixpointRounds < 1 via the canonical constructor")
        void rejectsZeroViaCanonical() {
            assertThatThrownBy(() -> new ExecutionContext(
                    new InMemorySymbolTable(), SchemaAnnotations.empty(),
                    java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    (n, s) -> null, QueryEventListener.NONE, 0,
                    ExecutionContext.UNLIMITED_MATERIALIZED_ROWS, java.time.Clock.systemUTC(),
                    FunctionCatalog.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxFixpointRounds");
        }

        @Test
        @DisplayName("rejects negative maxFixpointRounds via the canonical constructor")
        void rejectsNegativeViaCanonical() {
            assertThatThrownBy(() -> new ExecutionContext(
                    new InMemorySymbolTable(), SchemaAnnotations.empty(),
                    java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    (n, s) -> null, QueryEventListener.NONE, -1,
                    ExecutionContext.UNLIMITED_MATERIALIZED_ROWS, java.time.Clock.systemUTC(),
                    FunctionCatalog.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("withMaxMaterializedRows()")
    class WithMaxMaterializedRows {

        /**
         * A context naming no number is capped, and the sentinel still means no cap. Those
         * are two decisions and the default used to be the sentinel — so a blocking
         * operator over a large table took the host JVM's heap with it and left an
         * {@code OutOfMemoryError} belonging to no operator. The cap buys attribution
         * rather than memory: ten million rows is a great deal of memory, and the point is
         * that past it the failure names who buffered and how to raise the limit.
         */
        @Test
        @DisplayName("a context naming no number is capped, and the sentinel still means no cap")
        void defaultIsCappedAndTheSentinelIsNot() {
            var ctx = new ExecutionContext(new InMemorySymbolTable(),
                    SchemaAnnotations.empty(), (n, s) -> null);

            assertThat(ctx.maxMaterializedRows())
                    .isEqualTo(ExecutionContext.DEFAULT_MAX_MATERIALIZED_ROWS);
            assertThat(ctx.withMaxMaterializedRows(ExecutionContext.UNLIMITED_MATERIALIZED_ROWS)
                    .maxMaterializedRows())
                    .as("asking for no cap still gets no cap")
                    .isEqualTo(Integer.MAX_VALUE);
        }

        /**
         * The round count is deliberately not given the same treatment: how many a
         * legitimate recursion needs is a property of the data, so any default refuses some
         * correct query, and a truncated answer is harder to diagnose than a hang.
         */
        @Test
        @DisplayName("the fixpoint round count is still uncapped by default")
        void fixpointRoundsStayUnlimited() {
            var ctx = new ExecutionContext(new InMemorySymbolTable(),
                    SchemaAnnotations.empty(), (n, s) -> null);
            assertThat(ctx.maxFixpointRounds())
                    .isEqualTo(ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS);
        }

        @Test
        @DisplayName("returns copy with new limit, preserving all other fields")
        void returnsCopyWithNewLimit() {
            SemanticModel m = model(INLINE_SCRIPT);
            DataSourceConnector connector = (n, s) -> { throw new UnsupportedOperationException(); };
            var base = ExecutionContext.of(m, connector);

            var capped = base.withMaxMaterializedRows(42);

            assertThat(capped.maxMaterializedRows()).isEqualTo(42);
            assertThat(capped.symbolTable()).isSameAs(base.symbolTable());
            assertThat(capped.nodeSchemas()).isSameAs(base.nodeSchemas());
            assertThat(capped.connector()).isSameAs(base.connector());
            assertThat(capped.listener()).isSameAs(base.listener());
            assertThat(capped.maxFixpointRounds()).isEqualTo(base.maxFixpointRounds());
            assertThat(base.maxMaterializedRows())
                    .as("the wither leaves the original alone")
                    .isEqualTo(ExecutionContext.DEFAULT_MAX_MATERIALIZED_ROWS);
        }

        @Test
        @DisplayName("rejects maxMaterializedRows < 1 via the canonical constructor")
        void rejectsZeroViaCanonical() {
            assertThatThrownBy(() -> new ExecutionContext(
                    new InMemorySymbolTable(), SchemaAnnotations.empty(),
                    java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    (n, s) -> null, QueryEventListener.NONE,
                    ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS, 0,
                    java.time.Clock.systemUTC(), FunctionCatalog.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxMaterializedRows");
        }
    }

    // ── DataSourceConnector as functional interface ───────────────────────────

    @Nested
    @DisplayName("DataSourceConnector — functional interface")
    class ConnectorAsFunctional {

        @Test
        @DisplayName("can be implemented as a lambda")
        void lambdaImplementation() {
            var schema = schema(col("id", ScalarType.NUMBER));
            DataSourceConnector connector = (name, s) -> java.util.stream.Stream.of(
                    row(s, num(1)),
                    row(s, num(2)));

            try (var stream = connector.open("anything", schema)) {
                assertThat(stream.count()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("openQuery is unsupported by default (non-pushdown connectors)")
        void openQueryUnsupportedByDefault() {
            DataSourceConnector connector = (name, s) -> java.util.stream.Stream.of();
            assertThatThrownBy(() -> connector.openQuery(
                    "db", "SELECT 1", schema(col("id", ScalarType.NUMBER))))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("does not support native-query pushdown");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static final String INLINE_SCRIPT =
            "Users := [| id | name |\n" +
            "           | 1  | Alice |\n" +
            "           | 2  | Bob   |];\n" +
            "query Users;";

}
