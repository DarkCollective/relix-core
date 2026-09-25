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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same comparison, asked through different operators, must give the same answer.
 *
 * <p>It did not. Equality had grown two implementations — {@code PredicateEvaluator}
 * for σ and a theta join's condition, {@code ValueComparator} for ⋈, τ, AS-OF and the
 * aggregates — and each had acquired the coercion its own callers needed and not the
 * other's. Over one row of inline data, {@code σ Events.at = Typed.at} returned no
 * rows while {@code Events ⋈ Typed} returned one, the AS-OF form failed analysis, and
 * nothing reported a problem in any of the three cases.
 *
 * <p>These tests state the property rather than the fix: whatever the coercion rule
 * is, every operator has to read the same one.
 *
 * <p>That includes the operators which <em>deduplicate</em> rather than match. σ and the
 * joins reach {@code ValueComparator}; δ, γ and the set operations reach
 * {@code ArrayRow.equals}, which compares values as records. {@code NumberValue}
 * overrides {@code equals} precisely to keep the two readings together — see
 * {@code SetOperationsTest.NumericIdentity} — and the remaining coercions have no such
 * bridge, so the same question asked of a matching operator and a deduplicating one can
 * still get two answers.
 */
@DisplayName("Comparison semantics agree across operators")
final class ComparisonAgreementTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget    named -> rel(named.name());
            case ExpressionQueryTarget e   -> e.expression();
        };
    }

    /** Executes the {@code n}-th query statement of {@code src}. */
    private static List<Row> collect(String src, int n) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(model.rootQueries().get(n)), ctx)) {
            return stream.toList();
        }
    }

    /**
     * An inline {@code at} column (STRING, as every inline cell is) and a properly
     * typed TIMESTAMP naming the same instant, compared four ways.
     */
    private static final String TEMPORAL_SCRIPT =
            "Events := [| eid | at                  |\n" +
            "            | 1   | 2024-01-15T10:00:00 |];\n" +
            "Marks := [| mid | m |\n" +
            "           | 9   | x |];\n" +
            "Typed := { π mid, TIMESTAMP '2024-01-15T10:00:00Z' -> at (Marks) };\n" +
            "ViaSelection := { σ Events.at = Typed.at (Events × Typed) };\n" +
            "ViaNaturalJoin := { Events ⋈ Typed };\n" +
            "ViaThetaJoin := { Events >< Events.at = Typed.at Typed };\n" +
            "query ViaSelection;\n" +
            "query ViaNaturalJoin;\n" +
            "query ViaThetaJoin;\n";

    @Test
    @DisplayName("a STRING cell and the TIMESTAMP it denotes match through every operator")
    void temporalStringAgreesAcrossOperators() {
        assertThat(collect(TEMPORAL_SCRIPT, 0)).as("σ over ×").hasSize(1);
        assertThat(collect(TEMPORAL_SCRIPT, 1)).as("natural join").hasSize(1);
        assertThat(collect(TEMPORAL_SCRIPT, 2)).as("theta join").hasSize(1);
    }

    private static final String NON_MATCH_SCRIPT =
            TEMPORAL_SCRIPT.replace("2024-01-15T10:00:00 |]", "2024-01-15T11:00:00 |]");

    @Test
    @DisplayName("a genuinely different instant matches through none of them")
    void nonMatchingInstantAgreesAcrossOperators() {
        assertThat(collect(NON_MATCH_SCRIPT, 0)).as("σ over ×").isEmpty();
        assertThat(collect(NON_MATCH_SCRIPT, 1)).as("natural join").isEmpty();
        assertThat(collect(NON_MATCH_SCRIPT, 2)).as("theta join").isEmpty();
    }

    /** The other half of the split: a boolean-shaped cell against a BOOLEAN. */
    private static final String BOOLEAN_SCRIPT =
            "Flags := [| fid | flag |\n" +
            "           | 1   | true |];\n" +
            "Marks := [| mid | m |\n" +
            "           | 9   | x |];\n" +
            "Typed := { π mid, IIf(1 = 1, true, false) -> flag (Marks) };\n" +
            "ViaSelection := { σ Flags.flag = Typed.flag (Flags × Typed) };\n" +
            "ViaNaturalJoin := { Flags ⋈ Typed };\n" +
            "query ViaSelection;\n" +
            "query ViaNaturalJoin;\n";

    @Test
    @DisplayName("a STRING cell and the BOOLEAN it denotes match through every operator")
    void booleanStringAgreesAcrossOperators() {
        assertThat(collect(BOOLEAN_SCRIPT, 0)).as("σ over ×").hasSize(1);
        assertThat(collect(BOOLEAN_SCRIPT, 1)).as("natural join").hasSize(1);
    }

    @Test
    @DisplayName("a STRING column compares against a temporal literal directly")
    void stringColumnAgainstTemporalLiteral() {
        // The unqualified form, where the validator sees two known types rather than
        // the ANY a qualified reference through a × infers. This was refused at
        // analysis time — "cannot compare STRING with TIMESTAMP" — while the engine
        // had been coercing exactly this pairing since issue #277.
        String src =
                "Events := [| eid | at                  |\n" +
                "            | 1   | 2024-01-15T10:00:00 |\n" +
                "            | 2   | 2024-01-15T11:00:00 |];\n" +
                "Match := { σ at = TIMESTAMP '2024-01-15T10:00:00Z' (Events) };\n" +
                "After := { σ at > TIMESTAMP '2024-01-15T10:30:00Z' (Events) };\n" +
                "query Match;\n" +
                "query After;\n";

        assertThat(collect(src, 0)).as("equality against a TIMESTAMP literal").hasSize(1);
        assertThat(collect(src, 1)).as("ordering against a TIMESTAMP literal").hasSize(1);
    }

    @Test
    @DisplayName("a type mismatch is a non-match for both, not an error for one")
    void mismatchedTypesAgreeAcrossOperators() {
        // A NUMBER against a STRING that no coercion covers. The two share a hash
        // bucket — "7" renders identically either way — so the join really does
        // reach the comparator with an incompatible pair, which is the case that
        // separates equality from ordering: the join used to ask compareNonNull,
        // and an unorderable pair throws rather than reporting "not equal", so the
        // query aborted where the same test as a selection returned no rows.
        // CStr is what makes k a genuine STRING "7" — an inline cell written 7 infers
        // as NUMBER, and one written "7" keeps its quotes and so never shares a bucket.
        String src =
                "Nums := [| nid | k |\n" +
                "          | 1   | 7 |];\n" +
                "Raw := [| sid | n |\n" +
                "         | 2   | 7 |];\n" +
                "Strs := { π sid, CStr(n) -> k (Raw) };\n" +
                "ViaSelection := { σ Nums.k = Strs.k (Nums × Strs) };\n" +
                "ViaNaturalJoin := { Nums ⋈ Strs };\n" +
                "query ViaSelection;\n" +
                "query ViaNaturalJoin;\n";

        assertThat(collect(src, 0)).as("σ over ×").isEmpty();
        assertThat(collect(src, 1)).as("natural join").isEmpty();
    }

    /**
     * A column holding both spellings of one value, asked of σ and then of δ and γ.
     *
     * <p>The heading is ANY because that is the only one that can legally carry both: a
     * two-branch {@code IIf} whose branches disagree types as ANY, and being a single
     * projection it stamps every row with one schema. So what is under test here is the
     * <em>values</em>, and not the heading they arrived under — which is the separate
     * claim {@code SetOperationsTest.PositionalIdentity} makes.
     *
     * <p>The σ query is what makes the other two answerable. It establishes that the
     * engine considers the two values equal; δ and γ are then obliged to agree, whatever
     * the coercion rule turns out to be. If σ ever stops keeping both rows, this test
     * should be read as reporting that first.
     */
    private static final String MIXED_SPELLINGS_SCRIPT =
            "Ids := [| id |\n" +
            "         | 1  |\n" +
            "         | 2  |];\n" +
            "Mixed := { π IIf(id = 1, 'true', true) -> flag (Ids) };\n" +
            "KeptBySelection := { σ flag = true (Mixed) };\n" +
            "query KeptBySelection;\n" +
            "query { δ (Mixed) };\n" +
            "query { γ flag, COUNT(flag) -> n (Mixed) };\n";

    @Test
    @DisplayName("σ calls the two spellings equal — the premise the next two rest on")
    void selectionCallsTheSpellingsEqual() {
        assertThat(collect(MIXED_SPELLINGS_SCRIPT, 0))
                .as("σ flag = true, over one STRING 'true' and one BOOLEAN TRUE")
                .hasSize(2);
    }

    @Test
    @DisplayName("δ reads the same equality σ does")
    void distinctAgreesWithSelection() {
        assertThat(collect(MIXED_SPELLINGS_SCRIPT, 1))
                .as("δ over two values σ calls equal")
                .hasSize(1);
    }

    @Test
    @DisplayName("γ reads the same equality σ does")
    void groupingAgreesWithSelection() {
        assertThat(collect(MIXED_SPELLINGS_SCRIPT, 2))
                .as("γ over two values σ calls equal — one group")
                .hasSize(1);
    }

    /**
     * The other side of the line the identity key draws, and the reason it is drawn by
     * declared type rather than applied to every value.
     *
     * <p>These two strings denote one instant, so σ matches them against each other and
     * against the {@code TIMESTAMP} that names it. They are still two strings, and the
     * column says {@code STRING} — so δ must return both. Collapsing them would discard a
     * value the user asked for, and it would disagree with the {@code SELECT DISTINCT}
     * this operator folds into, which compares the text the database stores.
     *
     * <p>So σ and δ are answering different questions here, and it is the column's type
     * that says so: σ asks whether these denote one value, δ asks how many values the
     * column holds. Where the type is {@code ANY} there is no such answer on offer, which
     * is the case {@link #distinctAgreesWithSelection} covers.
     */
    private static final String STRING_SPELLINGS_SCRIPT =
            "Codes := [| c                         |\n" +
            "           | 2024-01-15T10:00:00Z      |\n" +
            "           | 2024-01-15T11:00:00+01:00 |];\n" +
            "query { σ c = TIMESTAMP '2024-01-15T10:00:00Z' (Codes) };\n" +
            "query { δ (Codes) };\n";

    @Test
    @DisplayName("σ matches both spellings of one instant")
    void selectionMatchesBothSpellings() {
        assertThat(collect(STRING_SPELLINGS_SCRIPT, 0))
                .as("both strings denote the instant the literal names")
                .hasSize(2);
    }

    @Test
    @DisplayName("δ over a declared STRING column keeps both spellings")
    void distinctKeepsBothSpellingsOfATypedString() {
        assertThat(collect(STRING_SPELLINGS_SCRIPT, 1))
                .as("two strings, in a column whose type says they are strings")
                .hasSize(2);
    }
}
