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
package com.darkcollective.relix.processor.reference;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.exec.RelNodeExecutor;
import com.darkcollective.relix.processor.reference.ReshapeReference.Wide;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.SequencedMap;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PIVOT and UNPIVOT against an independent definition, over generated relations.
 *
 * <p>Neither operator pushes down, so no agreement suite reaches either. What asserted
 * them was thirteen hand-written cases, one of which round-trips a wide table through
 * both — and a round trip is the one check that can pass while <em>both</em> directions
 * are wrong, whenever they are wrong in mirror image. So the two are checked separately
 * against {@link ReshapeReference} first, and the round trip is the law on top.
 *
 * <p>Writing the reference forced two questions the manual leaves open, which is what a
 * reference is for. It says the cell holds the value from "the row whose keyColumn equals
 * that header", presuming there is one; when a group has two the later silently wins. And
 * a row whose key is NULL contributes no column — but it has still been seen, so its group
 * is emitted, full of NULLs. Both are now stated, in the manual and here.
 */
@DisplayName("PIVOT and UNPIVOT against an independent definition")
final class ReshapeReferenceTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    /** Named, never from the clock. */
    private static final long SEED = 20_260_914L;

    private static final int DRAWS = 120;

    private static final List<String> GROUPS = List.of("A", "B", "C");
    private static final List<String> KEYS = List.of("k1", "k2", "k3");

    // ── running a query ───────────────────────────────────────────────────────

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named -> rel(named.name());
            case ExpressionQueryTarget e -> e.expression();
        };
    }

    /**
     * The result as rows of column → value.
     *
     * <p>Read through {@code columnNames()} rather than the schema, because a pivot's
     * schema is open: which columns exist is decided by the data, which is the whole
     * reason the operator has to block.
     */
    private static List<SequencedMap<String, String>> run(String script) {
        SemanticModel model = model(script);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        List<SequencedMap<String, String>> out = new ArrayList<>();
        try (Stream<Row> rows = EXECUTOR.execute(queryNode(model.rootQueries().getFirst()), ctx)) {
            rows.forEach(row -> {
                SequencedMap<String, String> cells = new LinkedHashMap<>();
                row.columnNames().forEach(name -> {
                    var value = row.get(name);
                    cells.put(name, value.isNull() ? null : value.asDisplayString());
                });
                out.add(cells);
            });
        }
        return out;
    }

    private static List<SequencedMap<String, String>> wideCells(List<Wide> rows) {
        return rows.stream().map(Wide::cells).toList();
    }

    /**
     * Each row as an ordered list of {@code name=value}.
     *
     * <p>Map equality ignores key order, and for a pivot the key order <em>is</em> part of
     * the answer — which columns exist and in what order is decided by the data. Comparing
     * maps alone passes a heading emitted backwards, as reversing the emission loop and
     * watching nothing fail demonstrated.
     */
    private static List<List<String>> headings(List<SequencedMap<String, String>> rows) {
        return rows.stream()
                .map(row -> row.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .toList())
                .toList();
    }

    // ── the draw ──────────────────────────────────────────────────────────────

    /**
     * A long table holding each {@code (group, key)} with even odds, plus — sometimes — a
     * second row for a pair it already holds, and sometimes a row whose key is missing.
     *
     * <p>Both of those are the cases the manual does not describe, so they are drawn
     * rather than written out: a reference that answers them and an engine that answers
     * them differently is the disagreement worth finding.
     */
    private static List<SequencedMap<String, String>> draw(Random rng) {
        List<SequencedMap<String, String>> rows = new ArrayList<>();
        int value = 1;
        for (String group : GROUPS) {
            for (String key : KEYS) {
                if (rng.nextBoolean()) {
                    rows.add(longRow(group, key, String.valueOf(value++)));
                }
            }
        }
        if (rng.nextInt(4) == 0 && !rows.isEmpty()) {
            SequencedMap<String, String> existing = rows.get(rng.nextInt(rows.size()));
            rows.add(longRow(existing.get("g"), existing.get("k"), String.valueOf(value++)));
        }
        if (rng.nextInt(4) == 0) {
            rows.add(longRow(GROUPS.get(rng.nextInt(GROUPS.size())), null, String.valueOf(value)));
        }
        return rows;
    }

    private static SequencedMap<String, String> longRow(String group, String key, String value) {
        SequencedMap<String, String> row = new LinkedHashMap<>();
        row.put("g", group);
        row.put("k", key);
        row.put("v", value);
        return row;
    }

    /** The long rows as an inline table; a null cell is spelled as an empty one. */
    private static String table(List<SequencedMap<String, String>> rows) {
        StringBuilder text = new StringBuilder("L := [| g | k | v |\n");
        rows.forEach(r -> text.append("| ").append(cell(r.get("g")))
                .append(" | ").append(cell(r.get("k")))
                .append(" | ").append(cell(r.get("v"))).append(" |\n"));
        return text.append("];\n").toString();
    }

    private static String cell(String value) {
        return value == null ? " " : value;
    }

    private static String render(List<SequencedMap<String, String>> rows) {
        return rows.stream().map(r -> r.get("g") + "/" + r.get("k") + "=" + r.get("v")).toList().toString();
    }

    // ── the claims ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("120 generated long tables: PIVOT agrees with the definition, columns and all")
    void pivotAgreesWithTheDefinition() {
        Random rng = new Random(SEED);
        int withHole = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            List<SequencedMap<String, String>> rows = draw(rng);
            if (rows.isEmpty()) {
                continue;   // an empty input yields an empty output, asserted below
            }
            List<Wide> expected = ReshapeReference.pivot(rows, List.of("g"), "k", "v");
            if (expected.stream().anyMatch(w -> w.cells().containsValue(null))) {
                withHole++;
            }

            List<SequencedMap<String, String>> actual =
                    run(table(rows) + "query { PIVOT v BY k PER g (L) };");
            assertThat(actual)
                    .as("PIVOT v BY k PER g — %s", render(rows))
                    .containsExactlyElementsOf(wideCells(expected));
            // And again with the column order made part of the value, since map equality
            // is blind to it and a pivot's heading is decided by the data.
            assertThat(headings(actual))
                    .as("heading order — %s", render(rows))
                    .containsExactlyElementsOf(headings(wideCells(expected)));
        }

        assertThat(withHole)
                .as("draws with a group missing a key — the cell that has to come back NULL")
                .isGreaterThan(DRAWS / 2);
    }

    @Test
    @DisplayName("the global pivot is the same rule with one group, not a different operator")
    void theGlobalPivotIsOneGroup() {
        Random rng = new Random(SEED);
        int compared = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            List<SequencedMap<String, String>> rows = draw(rng);
            if (rows.isEmpty()) {
                continue;
            }
            List<SequencedMap<String, String>> actual = run(table(rows) + "query { PIVOT v BY k (L) };");
            List<SequencedMap<String, String>> expected =
                    wideCells(ReshapeReference.pivot(rows, List.of(), "k", "v"));
            assertThat(actual).as("PIVOT v BY k — %s", render(rows)).containsExactlyElementsOf(expected);
            assertThat(headings(actual)).as("heading order — %s", render(rows))
                    .containsExactlyElementsOf(headings(expected));
            compared++;
        }

        assertThat(compared).as("tables actually compared").isGreaterThan(DRAWS / 2);
    }

    @Test
    @DisplayName("120 generated wide tables: UNPIVOT agrees with the definition")
    void unpivotAgreesWithTheDefinition() {
        Random rng = new Random(SEED);
        int compared = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            List<SequencedMap<String, String>> rows = draw(rng);
            if (rows.isEmpty()) {
                continue;
            }
            // The wide table is built by the engine, so what is under test here is the
            // fold back — the two directions are never checked as one thing.
            String wide = table(rows) + "W := { PIVOT v BY k PER g (L) };\n";
            List<Wide> pivoted = run(wide + "query { W };").stream().map(Wide::new).toList();
            List<String> headers = pivoted.isEmpty() ? List.of()
                    : pivoted.getFirst().cells().keySet().stream().filter(c -> !c.equals("g")).toList();
            if (headers.isEmpty()) {
                continue;   // every key was NULL; there is nothing to fold
            }

            String columns = String.join(", ", headers);
            List<SequencedMap<String, String>> actual =
                    run(wide + "query { UNPIVOT (" + columns + ") AS (k, v) (W) };");
            List<SequencedMap<String, String>> expected =
                    ReshapeReference.unpivot(pivoted, headers, "k", "v");
            assertThat(actual).as("UNPIVOT (%s) — %s", columns, render(rows))
                    .containsExactlyElementsOf(expected);
            assertThat(headings(actual)).as("column order — %s", render(rows))
                    .containsExactlyElementsOf(headings(expected));
            compared++;
        }

        assertThat(compared).as("tables actually compared").isGreaterThan(DRAWS / 2);
    }

    @Test
    @DisplayName("a complete long table survives the wide round trip unchanged")
    void aCompleteTableRoundTrips() {
        Random rng = new Random(SEED);
        int compared = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            // Every group × key present exactly once: the precondition the round trip
            // needs, and not an incidental property of the draw.
            List<SequencedMap<String, String>> rows = new ArrayList<>();
            int value = rng.nextInt(10) + 1;
            for (String group : GROUPS) {
                for (String key : KEYS) {
                    rows.add(longRow(group, key, String.valueOf(value++)));
                }
            }
            String columns = String.join(", ", KEYS);

            assertThat(run(table(rows)
                           + "query { UNPIVOT (" + columns + ") AS (k, v) (PIVOT v BY k PER g (L)) };"))
                    .as("round trip — %s", render(rows))
                    .containsExactlyInAnyOrderElementsOf(rows);
            compared++;
        }

        assertThat(compared).as("tables actually round-tripped").isEqualTo(DRAWS);
    }

    @Test
    @DisplayName("an incomplete one comes back as the full grid, which is the law's precondition")
    void anIncompleteTableComesBackFilledIn() {
        // B has no k2. The round trip is not the identity here, and the way it differs is
        // exactly the way PIVOT is defined to differ: the hole becomes a NULL, and UNPIVOT
        // emits a row per column whether or not the cell holds anything.
        List<SequencedMap<String, String>> rows = List.of(
                longRow("A", "k1", "1"), longRow("A", "k2", "2"), longRow("B", "k1", "3"));

        assertThat(run(table(rows)
                       + "query { UNPIVOT (k1, k2) AS (k, v) (PIVOT v BY k PER g (L)) };"))
                .as("the missing cell comes back as a row carrying NULL")
                .containsExactly(
                        longRow("A", "k1", "1"), longRow("A", "k2", "2"),
                        longRow("B", "k1", "3"), longRow("B", "k2", null));
    }

    @Test
    @DisplayName("two rows for one cell: the later one wins, silently")
    void aDuplicateCellKeepsTheLaterRow() {
        List<SequencedMap<String, String>> rows = List.of(
                longRow("A", "k1", "1"), longRow("A", "k1", "2"));

        // Not a rule the manual states — it says "the row whose keyColumn equals that
        // header", which presumes one. This is what the engine does, pinned so that a
        // change to it is a decision rather than a surprise.
        assertThat(run(table(rows) + "query { PIVOT v BY k PER g (L) };"))
                .as("the first row's value is gone, with no diagnostic")
                .containsExactlyElementsOf(
                        wideCells(ReshapeReference.pivot(rows, List.of("g"), "k", "v")))
                .satisfies(result -> assertThat(result.getFirst().get("k1")).isEqualTo("2"));
    }

    @Test
    @DisplayName("a row whose key is NULL makes no column, but its group is still emitted")
    void aNullKeyMakesNoColumnAndKeepsTheGroup() {
        List<SequencedMap<String, String>> rows = List.of(
                longRow("A", "k1", "1"), longRow("B", null, "2"));

        assertThat(run(table(rows) + "query { PIVOT v BY k PER g (L) };"))
                .as("B is a group with nothing in it rather than a group that vanished")
                .containsExactlyElementsOf(
                        wideCells(ReshapeReference.pivot(rows, List.of("g"), "k", "v")))
                .satisfies(result -> {
                    assertThat(result).hasSize(2);
                    assertThat(result.getLast()).containsEntry("g", "B").containsEntry("k1", null);
                });
    }
}
