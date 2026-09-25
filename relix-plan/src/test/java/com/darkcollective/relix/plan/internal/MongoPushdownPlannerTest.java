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
package com.darkcollective.relix.plan.internal;

import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit tests for {@link MongoPushdownPlanner}, exercising each fold rule in
 * isolation by constructing the planner from a {@link SemanticModel} and calling
 * {@link MongoPushdownPlanner#tryPush}/{@link MongoPushdownPlanner#tryPushOrdered}
 * straight on the logical tree — the document-store counterpart of
 * {@link SqlPushdownPlannerTest}.
 *
 * <p>The integration counterpart lives in {@code PlannerTest.Pushdown}; these tests
 * pin down the individual {@code $match}/{@code $unwind}/{@code $project}/{@code $limit}
 * fold paths and the fallbacks in isolation.
 */
@DisplayName("MongoPushdownPlanner — per-rule pipeline folding")
final class MongoPushdownPlannerTest {

    /** A mongodb connection + a collection-backed source with a declared schema. */
    private static final String DOCS =
            "connection mg from mongodb { uri: \"mongodb://localhost:27017\", database: \"app\" };\n" +
            "source Docs from mg { table: \"docs\", schema: { id: NUMBER, tags: ANY } };\n";

    /** Builds the planner from a model and returns the pushed scan (or empty) for its first query. */
    private static Optional<PhysicalNode.PushedScan> push(String src) {
        SemanticModel model = model(src);
        return planner(model).tryPush(logical(model));
    }

    private static MongoPushdownPlanner planner(SemanticModel model) {
        return new MongoPushdownPlanner(model.nodeSchemas(), model.sources(), model.connections(), model.functions());
    }

    private static RelNode logical(SemanticModel model) {
        return ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
    }

    private static PhysicalNode.PushedScan scanOf(Optional<PhysicalNode.PushedScan> pushed) {
        assertThat(pushed).isPresent();
        return pushed.get();
    }

    @Nested
    @DisplayName("single-operator folds")
    class SingleFold {

        @Test
        @DisplayName("base: a bare collection → an empty pipeline carrying the collection name")
        void baseCollection() {
            PhysicalNode.PushedScan s = scanOf(push(DOCS + "query { Docs };"));
            assertThat(s.connectorType()).isEqualTo("mongodb");
            assertThat(s.connection()).isEqualTo("mg");
            assertThat(s.nativeQuery()).isEqualTo("{\"collection\": \"docs\", \"pipeline\": []}");
        }

        @Test
        @DisplayName("selection → $match")
        void selection() {
            assertThat(scanOf(push(DOCS + "query { σ id > 0 (Docs) };")).nativeQuery()).isEqualTo(
                    "{\"collection\": \"docs\", \"pipeline\": [{\"$match\": {\"id\": {\"$gt\": 0}}}]}");
        }

        @Test
        @DisplayName("unnest → $unwind")
        void unnest() {
            assertThat(scanOf(push(DOCS + "query { μ tags (Docs) };")).nativeQuery()).isEqualTo(
                    "{\"collection\": \"docs\", \"pipeline\": [{\"$unwind\": \"$tags\"}]}");
        }

        @Test
        @DisplayName("projection of bare columns → $project inclusion")
        void projection() {
            assertThat(scanOf(push(DOCS + "query { π id (Docs) };")).nativeQuery()).isEqualTo(
                    "{\"collection\": \"docs\", \"pipeline\": [{\"$project\": {\"id\": 1, \"_id\": 0}}]}");
        }

        @Test
        @DisplayName("limit → $limit, with $skip for an offset")
        void limit() {
            assertThat(scanOf(push(DOCS + "query { λ 5 (Docs) };")).nativeQuery()).isEqualTo(
                    "{\"collection\": \"docs\", \"pipeline\": [{\"$limit\": 5}]}");
            assertThat(scanOf(push(DOCS + "query { λ 5, 10 (Docs) };")).nativeQuery()).isEqualTo(
                    "{\"collection\": \"docs\", \"pipeline\": [{\"$skip\": 5}, {\"$limit\": 10}]}");
        }
    }

    @Nested
    @DisplayName("fold order and stacking")
    class Stacking {

        /** A timestamp column, so a *computed* (non-pruning) π has something to fold. */
        private static final String EVENTS =
                "connection mg from mongodb { uri: \"mongodb://localhost:27017\", database: \"app\" };\n" +
                "source Events from mg { table: \"events\", schema: { id: NUMBER, at: TIMESTAMP } };\n";

        @Test
        @DisplayName("σ then μ then π then λ fold in pipeline order")
        void fullStack() {
            assertThat(scanOf(push(DOCS + "query { λ 5 (π id (μ tags (σ id > 0 (Docs)))) };")).nativeQuery())
                    .isEqualTo("{\"collection\": \"docs\", \"pipeline\": ["
                            + "{\"$match\": {\"id\": {\"$gt\": 0}}}, {\"$unwind\": \"$tags\"}, "
                            + "{\"$project\": {\"id\": 1, \"_id\": 0}}, {\"$limit\": 5}]}");
        }

        @Test
        @DisplayName("a $match after a *computed* projection is not folded")
        void matchAfterProjectFallsBack() {
            // σ over a computed π: the selection would have to name the new field.
            assertThat(push(EVENTS + "query { σ yr > 2000 (π YEAR(at) → yr (Events)) };")).isEmpty();
        }

        @Test
        @DisplayName("a projection after a computed projection is not folded")
        void doubleProjectionFallsBack() {
            assertThat(push(EVENTS + "query { π yr (π YEAR(at) → yr (Events)) };")).isEmpty();
        }

        @Test
        @DisplayName("a $match *after* a column-pruning $project is folded — the field survives")
        void matchAfterPruningProject() {
            // The pipeline is ordered, and a pruning π introduces no new field name, so
            // the later $match still names a real document field. Without this, the
            // optimizer's PROJ-004 would cost the pushdown it was meant to narrow.
            assertThat(scanOf(push(DOCS + "query { σ id > 0 (π id (Docs)) };")).nativeQuery())
                    .isEqualTo("{\"collection\": \"docs\", \"pipeline\": ["
                            + "{\"$project\": {\"id\": 1, \"_id\": 0}}, {\"$match\": {\"id\": {\"$gt\": 0}}}]}");
        }
    }

    @Nested
    @DisplayName("fallbacks")
    class Fallbacks {

        @Test
        @DisplayName("a bare column with an alias (field rename) is not pushed")
        void aliasedProjectionFallsBack() {
            // A bare attribute with an alias would need {"key": "$id"} — deferred;
            // only computed temporal expressions with aliases are supported.
            assertThat(push(DOCS + "query { π id → key (Docs) };")).isEmpty();
        }

        @Test
        @DisplayName("an untranslatable predicate is not pushed")
        void untranslatablePredicateFallsBack() {
            String docs =
                    "connection mg from mongodb { uri: \"mongodb://h\", database: \"app\" };\n" +
                    "source Docs from mg { table: \"docs\", schema: { a: NUMBER, b: NUMBER } };\n";
            assertThat(push(docs + "query { σ a > b (Docs) };")).isEmpty();
        }

        @Test
        @DisplayName("a WITH ORDINALITY μ is left in-engine — $unwind cannot number the elements")
        void ordinalityUnnestIsNotPushed() {
            // The guard's other arm is the OUTER form, which the reference page records as
            // programmatic-only: it has no surface syntax, so a text-driven test cannot
            // reach it.
            assertThat(push(DOCS + "query { μ tags WITH ORDINALITY n (Docs) };")).isEmpty();
        }

        @Test
        @DisplayName("μ after a $limit is not folded — $unwind must precede it")
        void unnestAfterLimitIsNotPushed() {
            // The other arm of the μ fold-order guard: the computed-projection case is
            // tested above, the limit case only here.
            assertThat(push(DOCS + "query { μ tags (λ 5 (Docs)) };")).isEmpty();
        }

        @Test
        @DisplayName("π after a $limit is not folded either")
        void projectionAfterLimitIsNotPushed() {
            assertThat(push(DOCS + "query { π id (λ 5 (Docs)) };")).isEmpty();
        }

        @Test
        @DisplayName("a non-connection (inline) source is not pushed")
        void inlineSourceFallsBack() {
            assertThat(push("Nums := [| n |\n       | 1 |];\n"
                    + "connection mg from mongodb { uri: \"mongodb://h\", database: \"app\" };\n"
                    + "query { σ n > 0 (Nums) };")).isEmpty();
        }

        @Test
        @DisplayName("aggregation is not pushed (deferred per ADR-0011)")
        void aggregationFallsBack() {
            assertThat(push(DOCS + "query { γ id, COUNT(id) → n (Docs) };")).isEmpty();
        }

        @Test
        @DisplayName("tryPushOrdered never folds an order into a Mongo pipeline (deferred)")
        void orderedNeverPushed() {
            SemanticModel model = model(DOCS + "query { Docs };");
            Optional<PhysicalNode.PushedScan> ordered = planner(model).tryPushOrdered(
                    logical(model), List.of(asc("id")));
            assertThat(ordered).isEmpty();
        }

        @Test
        @DisplayName("a collection whose connection is not one of this renderer's is not pushed")
        void unknownConnectionFallsBack() {
            // The planner is handed an empty connection map, so the source's connection
            // resolves to no declaration → fall back.
            SemanticModel model = model(DOCS + "query { σ id > 0 (Docs) };");
            Optional<PhysicalNode.PushedScan> pushed =
                    new MongoPushdownPlanner(model.nodeSchemas(), model.sources(), java.util.Map.of(), model.functions())
                            .tryPush(logical(model));
            assertThat(pushed).isEmpty();
        }
    }

    @Nested
    @DisplayName("temporal function expressions in $project (ADR-0013)")
    class TemporalProjections {

        /** A mongodb connection with a TIMESTAMP column. */
        private static final String EVENTS =
                "connection mg from mongodb { uri: \"mongodb://localhost:27017\", database: \"app\" };\n" +
                "source Events from mg { table: \"events\", schema: { id: NUMBER, at: TIMESTAMP } };\n";

        @Test
        @DisplayName("YEAR(at) → yr folds as {\"yr\": {\"$year\": \"$at\"}}")
        void yearFunction() {
            assertThat(scanOf(push(EVENTS + "query { π YEAR(at) → yr (Events) };")).nativeQuery())
                    .isEqualTo("{\"collection\": \"events\", \"pipeline\": "
                            + "[{\"$project\": {\"yr\": {\"$year\": \"$at\"}, \"_id\": 0}}]}");
        }

        @Test
        @DisplayName("DAY(at) → d folds as $dayOfMonth (not $day)")
        void dayFunction() {
            assertThat(scanOf(push(EVENTS + "query { π DAY(at) → d (Events) };")).nativeQuery())
                    .contains("{\"$dayOfMonth\": \"$at\"}");
        }

        @Test
        @DisplayName("DATE_TRUNC('hour', at) → hr folds as $dateTrunc")
        void dateTrunc() {
            assertThat(scanOf(push(EVENTS + "query { π DATE_TRUNC('hour', at) → hr (Events) };")).nativeQuery())
                    .isEqualTo("{\"collection\": \"events\", \"pipeline\": "
                            + "[{\"$project\": {\"hr\": {\"$dateTrunc\": {\"date\": \"$at\", \"unit\": \"hour\"}}, \"_id\": 0}}]}");
        }

        @Test
        @DisplayName("mixing bare columns and temporal expressions folds both into one $project")
        void mixedProjection() {
            assertThat(scanOf(push(EVENTS + "query { π id, YEAR(at) → yr (Events) };")).nativeQuery())
                    .isEqualTo("{\"collection\": \"events\", \"pipeline\": "
                            + "[{\"$project\": {\"id\": 1, \"yr\": {\"$year\": \"$at\"}, \"_id\": 0}}]}");
        }

        @Test
        @DisplayName("an untranslatable computed expression with alias falls back")
        void untranslatableComputedFallsBack() {
            // UCase is not a pushable function — the whole projection falls back
            assertThat(push(EVENTS + "query { π UCase(id) → u (Events) };")).isEmpty();
        }

    }

    @Nested
    @DisplayName("fallbacks over a non-pushable input")
    class OverNonPushable {

        // γ is never pushed by the Mongo renderer, so each operator sitting over it sees
        // an empty input and falls back — covering the input-empty guard of every fold.
        private static final String AGG = "γ id, COUNT(id) → n (Docs)";

        @Test
        @DisplayName("σ over a non-pushable input falls back")
        void selectionOverAggregate() {
            assertThat(push(DOCS + "query { σ id > 0 (" + AGG + ") };")).isEmpty();
        }

        @Test
        @DisplayName("μ over a non-pushable input falls back")
        void unnestOverAggregate() {
            assertThat(push(DOCS + "query { μ id (" + AGG + ") };")).isEmpty();
        }

        @Test
        @DisplayName("π over a non-pushable input falls back")
        void projectionOverAggregate() {
            assertThat(push(DOCS + "query { π id (" + AGG + ") };")).isEmpty();
        }

        @Test
        @DisplayName("λ over a non-pushable input falls back")
        void limitOverAggregate() {
            assertThat(push(DOCS + "query { λ 5 (" + AGG + ") };")).isEmpty();
        }
    }

    @Nested
    @DisplayName("pipeline-order guards")
    class OrderGuards {

        @Test
        @DisplayName("σ after a limit is not folded ($match must precede $limit)")
        void selectionAfterLimit() {
            assertThat(push(DOCS + "query { σ id > 0 (λ 5 (Docs)) };")).isEmpty();
        }

        @Test
        @DisplayName("μ after a limit is not folded ($unwind must precede $limit)")
        void unnestAfterLimit() {
            assertThat(push(DOCS + "query { μ tags (λ 5 (Docs)) };")).isEmpty();
        }

        @Test
        @DisplayName("μ after a column-pruning projection is folded — $unwind's field survives it")
        void unnestAfterProjection() {
            assertThat(scanOf(push(DOCS + "query { μ tags (π tags (Docs)) };")).nativeQuery())
                    .isEqualTo("{\"collection\": \"docs\", \"pipeline\": ["
                            + "{\"$project\": {\"tags\": 1, \"_id\": 0}}, {\"$unwind\": \"$tags\"}]}");
        }

        @Test
        @DisplayName("μ WITH ORDINALITY is not folded ($unwind index semantics differ)")
        void unnestWithOrdinalityFallsBack() {
            assertThat(push(DOCS + "query { μ tags WITH ORDINALITY idx (Docs) };")).isEmpty();
        }

        @Test
        @DisplayName("π after a limit is not folded ($project must precede $limit)")
        void projectionAfterLimit() {
            assertThat(push(DOCS + "query { π id (λ 5 (Docs)) };")).isEmpty();
        }

        @Test
        @DisplayName("a second limit is not folded")
        void doubleLimit() {
            assertThat(push(DOCS + "query { λ 5 (λ 3 (Docs)) };")).isEmpty();
        }
    }
}
