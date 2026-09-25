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
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.processor.exec.RelNodeExecutor;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.SequencedMap;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TREE against an independent definition, over generated forests.
 *
 * <p>TREE never pushes down, so no agreement suite reaches it. What asserted it was nine
 * hand-written cases, and nesting is the shape hand-written assertions are worst at: a
 * subtree is checked by looking for a substring of its rendering, which passes for a tree
 * that is right in the part quoted and wrong below it. {@link TreeReference} builds the
 * forest from the manual's own sentences and renders <em>both</em> sides canonically, so
 * the comparison is the whole tree rather than a piece of its text.
 *
 * <p>The inputs are drawn because a forest's interesting cases are shapes: several roots,
 * a chain, a wide fan, a parent pointer to a key the relation does not hold, a root with
 * no children at all. A node's parent is drawn from the nodes before it, which makes every
 * draw acyclic by construction — so the cycle and duplicate-key rejections are stated
 * separately, being the two inputs a well-formed draw cannot produce.
 */
@DisplayName("TREE against an independent definition")
final class TreeReferenceTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    /** Named, never from the clock. */
    private static final long SEED = 20_260_914L;

    private static final int DRAWS = 120;

    private static final int NODES = 7;

    // ── running a query ───────────────────────────────────────────────────────

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named -> rel(named.name());
            case ExpressionQueryTarget e -> e.expression();
        };
    }

    private static List<Row> run(String script) {
        SemanticModel model = model(script);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        try (Stream<Row> rows = EXECUTOR.execute(queryNode(model.rootQueries().getFirst()), ctx)) {
            return rows.toList();
        }
    }

    /** Each output row rendered the way {@link TreeReference} renders a node: its own columns, then the subtree. */
    private static List<String> forest(String script, String childrenName) {
        return run(script).stream().map(row -> {
            StringBuilder text = new StringBuilder("{");
            row.columnNames().forEach(name ->
                    text.append(name).append("=")
                            .append(TreeReference.canonical(row.get(name))).append(","));
            text.setLength(text.length() - 1);
            return text.append("}").toString();
        }).toList();
    }

    // ── the draw ──────────────────────────────────────────────────────────────

    /**
     * A forest of seven nodes: each one's parent is drawn from the nodes already placed,
     * from nothing at all, or from a key no row carries.
     *
     * <p>Drawing the parent from earlier nodes only is what makes every draw acyclic
     * without having to check — and the dangling pointer is drawn rather than written out
     * because "a parent that is not there" is a root by the manual's rule and the kind of
     * thing an implementation quietly turns into a lost subtree.
     */
    private static List<SequencedMap<String, String>> draw(Random rng) {
        List<SequencedMap<String, String>> rows = new ArrayList<>();
        for (int id = 1; id <= NODES; id++) {
            String parent = switch (rng.nextInt(6)) {
                case 0 -> null;                       // an explicit root
                case 1 -> String.valueOf(99);         // a pointer to nothing — also a root
                default -> id == 1 ? null : String.valueOf(rng.nextInt(id - 1) + 1);
            };
            SequencedMap<String, String> row = new LinkedHashMap<>();
            row.put("id", String.valueOf(id));
            row.put("parent", parent);
            row.put("label", "n" + id);
            rows.add(row);
        }
        return rows;
    }

    private static String table(List<SequencedMap<String, String>> rows) {
        StringBuilder text = new StringBuilder("Nodes := [| id | parent | label |\n");
        rows.forEach(r -> text.append("| ").append(r.get("id") == null ? " " : r.get("id"))
                .append(" | ").append(r.get("parent") == null ? " " : r.get("parent"))
                .append(" | ").append(r.get("label")).append(" |\n"));
        return text.append("];\n").toString();
    }

    private static String render(List<SequencedMap<String, String>> rows) {
        return rows.stream().map(r -> r.get("id") + "←" + r.get("parent")).toList().toString();
    }

    /** Ascending by the numeric id, which is what {@code ORDER id} asks for. */
    private static final Comparator<SequencedMap<String, String>> BY_ID =
            Comparator.comparingInt(row -> Integer.parseInt(row.get("id")));

    // ── the claims ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("120 generated forests: the whole nesting agrees with the definition")
    void agreesWithTheDefinition() {
        Random rng = new Random(SEED);
        int multiRoot = 0;
        int deep = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            List<SequencedMap<String, String>> rows = draw(rng);
            List<String> expected = TreeReference.forest(rows, "id", "parent", "children", BY_ID);
            if (expected.size() > 1) {
                multiRoot++;
            }
            if (expected.stream().anyMatch(tree -> tree.contains("children=[{"))) {
                deep++;
            }

            assertThat(forest(table(rows) + "query { TREE id BY parent ORDER id AS children (Nodes) };",
                    "children"))
                    .as("TREE id BY parent ORDER id — %s", render(rows))
                    .containsExactlyElementsOf(expected);
        }

        assertThat(multiRoot)
                .as("draws with more than one root — a forest, which is what the operator returns")
                .isGreaterThan(DRAWS / 2);
        assertThat(deep)
                .as("draws nesting at least two levels — a flat answer would agree with a "
                    + "reference that never recursed")
                .isGreaterThan(DRAWS / 2);
    }

    @Test
    @DisplayName("with no ORDER clause the input order is kept, at every level")
    void withoutAnOrderClauseInputOrderIsKept() {
        Random rng = new Random(SEED);
        int compared = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            // Reversed, so input order and id order disagree: ordering by neither and
            // ordering by the wrong one are then different answers.
            List<SequencedMap<String, String>> rows = new ArrayList<>(draw(rng)).reversed();

            assertThat(forest(table(rows) + "query { TREE id BY parent AS children (Nodes) };",
                    "children"))
                    .as("TREE id BY parent — %s", render(rows))
                    .containsExactlyElementsOf(
                            TreeReference.forest(rows, "id", "parent", "children", null));
            compared++;
        }

        assertThat(compared).as("forests actually compared").isEqualTo(DRAWS);
    }

    @Test
    @DisplayName("ORDER DESC reverses siblings and roots alike")
    void orderDescendingReversesEveryLevel() {
        Random rng = new Random(SEED);
        int compared = 0;

        for (int draw = 0; draw < 40; draw++) {
            List<SequencedMap<String, String>> rows = draw(rng);

            assertThat(forest(table(rows)
                              + "query { TREE id BY parent ORDER id DESC AS children (Nodes) };",
                    "children"))
                    .as("TREE id BY parent ORDER id DESC — %s", render(rows))
                    .containsExactlyElementsOf(
                            TreeReference.forest(rows, "id", "parent", "children", BY_ID.reversed()));
            compared++;
        }

        assertThat(compared).as("forests actually compared").isEqualTo(40);
    }

    @Test
    @DisplayName("a leaf carries an empty array, not a missing column")
    void aLeafCarriesAnEmptyArray() {
        List<SequencedMap<String, String>> rows = List.of(node("1", null), node("2", "1"));

        assertThat(forest(table(rows) + "query { TREE id BY parent ORDER id AS children (Nodes) };",
                "children"))
                .as("node 2 is a leaf and says so")
                .containsExactlyElementsOf(
                        TreeReference.forest(rows, "id", "parent", "children", BY_ID))
                .allSatisfy(tree -> assertThat(tree).contains("children=[]"));
    }

    @Test
    @DisplayName("a parent that is not there makes a root, not a lost subtree")
    void aDanglingParentIsARoot() {
        // Node 2 points at 99, which no row carries. Both rows are roots, and node 2's own
        // child comes with it — dropping the subtree is the failure this rules out.
        List<SequencedMap<String, String>> rows =
                List.of(node("1", null), node("2", "99"), node("3", "2"));

        assertThat(forest(table(rows) + "query { TREE id BY parent ORDER id AS children (Nodes) };",
                "children"))
                .as("two roots, and node 3 still hangs off node 2")
                .containsExactlyElementsOf(
                        TreeReference.forest(rows, "id", "parent", "children", BY_ID))
                .hasSize(2);
    }

    @Test
    @DisplayName("a row whose own key is NULL is a leaf wherever it lands")
    void aNullKeyHasNoIdentity() {
        // The middle row has no key at all. It is placed by its parent pointer like any
        // other row, so it hangs under node 1 — but nothing can ever be *its* child, and
        // not because the operator refuses: a parent cell holding NULL means "root", so
        // there is no way to write a pointer at it. A null is not an identity.
        List<SequencedMap<String, String>> rows =
                List.of(node("1", null), node(null, "1"), node("3", null));

        assertThat(forest(table(rows) + "query { TREE id BY parent AS children (Nodes) };",
                "children"))
                .as("two roots; the keyless row is a leaf under node 1")
                .containsExactlyElementsOf(
                        TreeReference.forest(rows, "id", "parent", "children", null));
    }

    @Test
    @DisplayName("a duplicate key and a cycle are both refused, rather than guessed at")
    void malformedInputIsRefused() {
        // Neither can be drawn: a parent taken from the nodes already placed is acyclic by
        // construction, and the ids are generated distinct. They are the two shapes a
        // well-formed generator cannot reach, so they are written out.
        String duplicate = table(List.of(node("1", null), node("1", null)));
        assertThatThrownBy(() -> run(duplicate + "query { TREE id BY parent AS children (Nodes) };"))
                .as("two rows claiming one node")
                .isInstanceOf(EvaluationException.class);

        String cycle = table(List.of(node("1", "2"), node("2", "1")));
        assertThatThrownBy(() -> run(cycle + "query { TREE id BY parent AS children (Nodes) };"))
                .as("a component with no root would otherwise be an endless descent")
                .isInstanceOf(EvaluationException.class);
    }

    private static SequencedMap<String, String> node(String id, String parent) {
        SequencedMap<String, String> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("parent", parent);
        row.put("label", "n" + id);
        return row;
    }
}
