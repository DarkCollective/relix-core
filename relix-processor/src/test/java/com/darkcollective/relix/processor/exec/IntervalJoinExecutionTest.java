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
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.ast.AllenRelation;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;

@DisplayName("Interval join (IJOIN) execution")
final class IntervalJoinExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named  -> rel(named.name());
            case ExpressionQueryTarget e -> e.expression();
        };
    }

    private static List<Row> collect(String src) {
        SemanticModel m = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(m);
        var query = m.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.toList();
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────
    //
    // Events:  E1=[1,5)  E2=[6,10)
    // Bookings: B10=[3,7)  B20=[8,12)
    //
    // INTERSECTS (ls < re AND rs < le):
    //   E1 vs B10: 1<7 AND 3<5  → true
    //   E1 vs B20: 1<12 AND 8<5 → false
    //   E2 vs B10: 6<7 AND 3<10 → true
    //   E2 vs B20: 6<12 AND 8<10 → true
    //
    // PRECEDES (le <= rs):
    //   E1 vs B10: 5<=3 → false
    //   E1 vs B20: 5<=8 → true
    //   E2 vs B10: 10<=3 → false
    //   E2 vs B20: 10<=8 → false

    // Numeric IDs so asDisplayString() comparisons are straightforward
    private static final String EVENTS =
            "Events := [| eid | estart | eend |\n" +
            "            | 1   | 1      | 5    |\n" +
            "            | 2   | 6      | 10   |];\n";

    private static final String BOOKINGS =
            "Bookings := [| bid | bstart | bend |\n" +
            "              | 10  | 3      | 7    |\n" +
            "              | 20  | 8      | 12   |];\n";

    @Test
    @DisplayName("INTERSECTS finds all pairs of overlapping intervals")
    void intersectsFindsOverlappingPairs() {
        var rows = collect(EVENTS + BOOKINGS +
                "query { Events IJOIN INTERSECTS (Events.estart, Events.eend, Bookings.bstart, Bookings.bend) Bookings };");

        // E1-B10, E2-B10, E2-B20; E1-B20 excluded
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.get("eid").asDisplayString() + "-" + r.get("bid").asDisplayString())
                .containsExactlyInAnyOrder("1-10", "2-10", "2-20");
    }

    @Test
    @DisplayName("PRECEDES finds pairs where left ends at or before right starts")
    void precedesFindsNonOverlappingPairs() {
        var rows = collect(EVENTS + BOOKINGS +
                "query { Events IJOIN PRECEDES (Events.estart, Events.eend, Bookings.bstart, Bookings.bend) Bookings };");

        // Only E1-B20: eend=5 <= bstart=8
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).hasValue("eid", "1")
                .hasValue("bid", "20");
    }

    @Test
    @DisplayName("no matching pairs produces an empty result")
    void noMatchEmptyResult() {
        // MEETS requires exact equality of eend and bstart; neither holds here
        var rows = collect(EVENTS + BOOKINGS +
                "query { Events IJOIN MEETS (Events.estart, Events.eend, Bookings.bstart, Bookings.bend) Bookings };");
        // E1.eend=5 vs B10.bstart=3: 5=3? no  |  E1.eend=5 vs B20.bstart=8: 5=8? no
        // E2.eend=10 vs B10.bstart=3: no        |  E2.eend=10 vs B20.bstart=8: no
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("output schema is the concatenation of left and right schemas")
    void schemaIsConcatenationOfBothSides() {
        var rows = collect(EVENTS + BOOKINGS +
                "query { Events IJOIN INTERSECTS (Events.estart, Events.eend, Bookings.bstart, Bookings.bend) Bookings };");
        assertThat(rows).isNotEmpty();
        var colNames = rows.getFirst().schema().columns().stream()
                .map(c -> c.name()).toList();
        assertThat(colNames).containsExactly("eid", "estart", "eend", "bid", "bstart", "bend");
    }

    @Test
    @DisplayName("DURING: left strictly inside right (rs < ls and le < re); shared boundary excluded")
    void duringTest() {
        // Short=[2,4) DURING Long_=[1,5): 1<2 and 4<5 → true
        // Short=[2,4) DURING Long_=[5,10): 5<2 → false
        // Short=[2,4) DURING Long_=[2,6): rs<ls (2<2) → false  (shared start is STARTS, not DURING)
        var rows = collect(
                "Short := [| sid | ss | se |\n" +
                "           | 1   | 2  | 4  |];\n" +
                "Long_ := [| lid | ls | le |\n" +
                "           | 10  | 1  | 5  |\n" +
                "           | 20  | 5  | 10 |\n" +
                "           | 30  | 2  | 6  |];\n" +
                "query { Short IJOIN DURING (Short.ss, Short.se, Long_.ls, Long_.le) Long_ };");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).hasValue("lid", "10");
    }

    @Test
    @DisplayName("CONTAINS: left strictly contains right (ls < rs and re < le); shared boundary excluded")
    void containsTest() {
        // Long_=[1,5) CONTAINS Short1=[2,4): 1<2 and 4<5 → true
        // Long_=[1,5) CONTAINS Short2=[0,6): 1<0 → false
        // Long_=[1,5) CONTAINS Short3=[1,4): ls<rs (1<1) → false  (shared start is STARTED_BY, not CONTAINS)
        var rows = collect(
                "Long_ := [| lid | ls | le |\n" +
                "           | 10  | 1  | 5  |];\n" +
                "Short := [| sid | ss | se |\n" +
                "           | 1   | 2  | 4  |\n" +
                "           | 2   | 0  | 6  |\n" +
                "           | 3   | 1  | 4  |];\n" +
                "query { Long_ IJOIN CONTAINS (Long_.ls, Long_.le, Short.ss, Short.se) Short };");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).hasValue("sid", "1");
    }

    @Test
    @DisplayName("OVERLAPS: left starts before right and ends within it (not symmetric)")
    void overlapsTest() {
        // L=[1,5) OVERLAPS R1=[3,7): ls<rs (1<3) and le>rs (5>3) and le<re (5<7) → true
        // L=[1,5) OVERLAPS R2=[0,7): ls<rs (1<0) → false
        var rows = collect(
                "L := [| lid | ls | le |\n" +
                "       | 1   | 1  | 5  |];\n" +
                "R := [| rid | rs | re |\n" +
                "       | 10  | 3  | 7  |\n" +
                "       | 20  | 0  | 7  |];\n" +
                "query { L IJOIN OVERLAPS (L.ls, L.le, R.rs, R.re) R };");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).hasValue("rid", "10");
    }

    // ── full 13-relation Allen set (ADR-0014 slice 4) ────────────────────────
    //
    // Helper-free, single-row left interval against a two-row right relation so
    // each test pins down exactly one matching right id.

    /** L=[ls,le) joined against two right rows; returns the matching `rid`s. */
    private static List<String> matchingRids(String relation, int ls, int le,
                                             int rs1, int re1, int rs2, int re2) {
        var rows = collect(
                "L := [| lid | ls | le |\n" +
                "       | 1   | " + ls + "  | " + le + "  |];\n" +
                "R := [| rid | rs | re |\n" +
                "       | 10  | " + rs1 + "  | " + re1 + "  |\n" +
                "       | 20  | " + rs2 + "  | " + re2 + "  |];\n" +
                "query { L IJOIN " + relation + " (L.ls, L.le, R.rs, R.re) R };");
        return rows.stream().map(r -> r.get("rid").asDisplayString()).toList();
    }

    @Test
    @DisplayName("OVERLAPPED_BY: right crosses into left from below (converse of OVERLAPS)")
    void overlappedByTest() {
        // L=[3,7) OVERLAPPED_BY R=[1,5): rs<ls (1<3), re>ls (5>3), re<le (5<7) → true
        // R2=[8,9): rs<ls (8<3)? no → false
        assertThat(matchingRids("OVERLAPPED_BY", 3, 7, 1, 5, 8, 9))
                .containsExactly("10");
    }

    @Test
    @DisplayName("STARTS: same start, left ends first")
    void startsTest() {
        // L=[2,6) STARTS R=[2,9): ls=rs (2=2), le<re (6<9) → true
        // R2=[2,5): le<re (6<5)? no → false
        assertThat(matchingRids("STARTS", 2, 6, 2, 9, 2, 5))
                .containsExactly("10");
    }

    @Test
    @DisplayName("STARTED_BY: same start, right ends first (converse of STARTS)")
    void startedByTest() {
        // L=[2,9) STARTED_BY R=[2,6): ls=rs (2=2), re<le (6<9) → true
        // R2=[2,9): re<le (9<9)? no → false
        assertThat(matchingRids("STARTED_BY", 2, 9, 2, 6, 2, 9))
                .containsExactly("10");
    }

    @Test
    @DisplayName("FINISHES: same end, left starts later")
    void finishesTest() {
        // L=[5,9) FINISHES R=[2,9): le=re (9=9), rs<ls (2<5) → true
        // R2=[6,9): rs<ls (6<5)? no → false
        assertThat(matchingRids("FINISHES", 5, 9, 2, 9, 6, 9))
                .containsExactly("10");
    }

    @Test
    @DisplayName("FINISHED_BY: same end, right starts later (converse of FINISHES)")
    void finishedByTest() {
        // L=[2,9) FINISHED_BY R=[5,9): le=re (9=9), ls<rs (2<5) → true
        // R2=[1,9): ls<rs (2<1)? no → false
        assertThat(matchingRids("FINISHED_BY", 2, 9, 5, 9, 1, 9))
                .containsExactly("10");
    }

    @Test
    @DisplayName("EQUALS: identical start and end")
    void equalsTest() {
        // L=[2,6) EQUALS R=[2,6) → true
        // R2=[2,7): le=re (6=7)? no → false
        assertThat(matchingRids("EQUALS", 2, 6, 2, 6, 2, 7))
                .containsExactly("10");
    }

    @Test
    @DisplayName("MET_BY: left begins where right ends (converse of MEETS)")
    void metByTest() {
        // L=[6,9) MET_BY R=[2,6): ls=re (6=6) → true
        // R2=[2,7): ls=re (6=7)? no → false
        assertThat(matchingRids("MET_BY", 6, 9, 2, 6, 2, 7))
                .containsExactly("10");
    }

    @Test
    @DisplayName("PRECEDED_BY: left entirely after right (converse of PRECEDES)")
    void precededByTest() {
        // L=[8,10) PRECEDED_BY R=[2,5): ls>re (8>5) → true
        // R2=[2,8): ls>re (8>8)? no → false
        assertThat(matchingRids("PRECEDED_BY", 8, 10, 2, 5, 2, 8))
                .containsExactly("10");
    }

    @Test
    @DisplayName("rows with a NULL interval endpoint are dropped (no matches)")
    void nullEndpointsDropped() {
        // A left-outer join NULL-pads the span columns for the unmatched probe
        // (pk=9), so that row carries NULL endpoints and must produce no IJOIN
        // matches; the matched probe (pk=1) joins normally.
        var rows = collect(
                "Probes := [| pid | pk |\n" +
                "            | 1   | 1  |\n" +
                "            | 2   | 9  |];\n" +
                "Spans := [| sk | ss | se |\n" +
                "          | 1  | 0  | 10 |];\n" +
                "Windows := [| wid | ws | we |\n" +
                "             | 100 | 0  | 10 |];\n" +
                "P := { Probes |>< Probes.pk = Spans.sk Spans };\n" +
                "query { P IJOIN INTERSECTS (P.ss, P.se, Windows.ws, Windows.we) Windows };");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).hasValue("pid", "1")
                .hasValue("wid", "100");
    }

    // ── plane-sweep / band equivalence (ADR-0014 slice 4) ────────────────────
    //
    // The optimised executor (plane sweep for the overlap-or-touch relations,
    // sorted band for PRECEDES/PRECEDED_BY) must produce exactly the same
    // multiset of pairs as a brute-force O(n·m) scan over the trusted
    // JoinExecutor.allenTest predicate. A small coordinate universe forces many
    // ties, touching, nested, and degenerate intervals.

    private static final int UNIVERSE = 12;

    private record Ivl(int id, int start, int end) {}

    private static List<Ivl> randomIntervals(Random rnd, int n, int idBase) {
        List<Ivl> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int a = rnd.nextInt(UNIVERSE);
            int b = rnd.nextInt(UNIVERSE);
            out.add(new Ivl(idBase + i, Math.min(a, b), Math.max(a, b)));
        }
        return out;
    }

    private static String inlineTable(String name, String id, String s, String e, List<Ivl> ivls) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" := [| ").append(id).append(" | ").append(s).append(" | ").append(e).append(" |\n");
        for (Ivl iv : ivls) {
            sb.append("| ").append(iv.id()).append(" | ").append(iv.start()).append(" | ").append(iv.end()).append(" |\n");
        }
        return sb.append("];\n").toString();
    }

    private static Value v(int i) {
        return NumberValue.of(Integer.toString(i));
    }

    /** Brute-force oracle: every (l, r) pair for which allenTest holds, as "lid-rid". */
    private static List<String> oracle(AllenRelation rel, List<Ivl> lefts, List<Ivl> rights) {
        List<String> pairs = new ArrayList<>();
        for (Ivl l : lefts) {
            for (Ivl r : rights) {
                if (JoinExecutor.allenTest(rel, v(l.start()), v(l.end()), v(r.start()), v(r.end()))) {
                    pairs.add(l.id() + "-" + r.id());
                }
            }
        }
        return pairs;
    }

    private static List<String> executeIjoin(AllenRelation rel, List<Ivl> lefts, List<Ivl> rights) {
        var rows = collect(
                inlineTable("L", "lid", "ls", "le", lefts) +
                inlineTable("R", "rid", "rs", "re", rights) +
                "query { L IJOIN " + rel.name() + " (L.ls, L.le, R.rs, R.re) R };");
        return rows.stream()
                .map(r -> r.get("lid").asDisplayString() + "-" + r.get("rid").asDisplayString())
                .toList();
    }

    /**
     * Same as {@link #executeIjoin} but with a {@code τ} on each side's start column, so
     * both inputs arrive start-sorted and the planner selects the streaming sort-merge
     * variant (asserted in {@code PlannerTest}).  Reuses the brute-force oracle.
     */
    private static List<String> executeMergeIjoin(AllenRelation rel, List<Ivl> lefts, List<Ivl> rights) {
        var rows = collect(
                inlineTable("L", "lid", "ls", "le", lefts) +
                inlineTable("R", "rid", "rs", "re", rights) +
                "query { (τ ls (L)) IJOIN " + rel.name() + " (L.ls, L.le, R.rs, R.re) (τ rs (R)) };");
        return rows.stream()
                .map(r -> r.get("lid").asDisplayString() + "-" + r.get("rid").asDisplayString())
                .toList();
    }

    @ParameterizedTest
    @EnumSource(AllenRelation.class)
    @DisplayName("sort-merge variant (pre-sorted inputs) matches the brute-force oracle")
    void mergeVariantMatchesBruteForce(AllenRelation rel) {
        Random rnd = new Random(0xDEC0DE ^ rel.ordinal());
        for (int trial = 0; trial < 25; trial++) {
            List<Ivl> lefts  = randomIntervals(rnd, 6, 100);
            List<Ivl> rights = randomIntervals(rnd, 6, 200);
            assertThat(executeMergeIjoin(rel, lefts, rights))
                    .as("relation %s, trial %d", rel, trial)
                    .containsExactlyInAnyOrderElementsOf(oracle(rel, lefts, rights));
        }
    }

    @Test
    @DisplayName("sort-merge variant matches the oracle on a larger, overlap-heavy dataset")
    void mergeVariantLargerDatasetMatchesOracle() {
        Random rnd = new Random(424242);
        List<Ivl> lefts  = randomIntervals(rnd, 60, 1000);
        List<Ivl> rights = randomIntervals(rnd, 60, 5000);
        for (AllenRelation rel : AllenRelation.values()) {
            assertThat(executeMergeIjoin(rel, lefts, rights))
                    .as("relation %s", rel)
                    .containsExactlyInAnyOrderElementsOf(oracle(rel, lefts, rights));
        }
    }

    @ParameterizedTest
    @EnumSource(AllenRelation.class)
    @DisplayName("optimised executor matches the brute-force oracle for every relation")
    void sweepMatchesBruteForce(AllenRelation rel) {
        Random rnd = new Random(0xA11E2 ^ rel.ordinal());
        for (int trial = 0; trial < 25; trial++) {
            List<Ivl> lefts  = randomIntervals(rnd, 6, 100);
            List<Ivl> rights = randomIntervals(rnd, 6, 200);
            assertThat(executeIjoin(rel, lefts, rights))
                    .as("relation %s, trial %d", rel, trial)
                    .containsExactlyInAnyOrderElementsOf(oracle(rel, lefts, rights));
        }
    }

    @Test
    @DisplayName("optimised executor matches the oracle on a larger, overlap-heavy dataset")
    void largerDatasetMatchesOracle() {
        Random rnd = new Random(99);
        List<Ivl> lefts  = randomIntervals(rnd, 60, 1000);
        List<Ivl> rights = randomIntervals(rnd, 60, 5000);
        for (AllenRelation rel : AllenRelation.values()) {
            assertThat(executeIjoin(rel, lefts, rights))
                    .as("relation %s", rel)
                    .containsExactlyInAnyOrderElementsOf(oracle(rel, lefts, rights));
        }
    }
}
