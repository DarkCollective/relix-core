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
package com.darkcollective.relix.processor.eval;

import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.semantic.internal.SemanticResult;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.Expr.*;
import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Temporal arithmetic is written twice, and the two copies must agree.
 *
 * <p>{@code TemporalArithmetic} (relix-semantic) decides at analysis time which operand
 * pairs are legal and what type each produces; {@code TemporalValueArithmetic}
 * (relix-processor) computes the value at run time. They are separate tables in separate
 * modules, and each has its own test — but nothing held them to <em>each other</em>.
 *
 * <p>The two failure modes are asymmetric and both are bad. If inference accepts a pair
 * the evaluator rejects, a script analyses cleanly and then dies mid-query, after the
 * output has started. If inference names a type the evaluator does not produce, every
 * consumer downstream — pushdown rendering, a merge join's comparator, the column a
 * caller reads — was planned against a type the rows do not have, and nothing raises.
 *
 * <p>This is the same shape as {@code ComparisonAgreementTest}: state the property, not
 * the fix. Whatever the table says, both layers have to read the same one.
 *
 * <p><b>STRING is deliberately not in the matrix.</b> The evaluator coerces an ISO-8601
 * string to the temporal kind its partner suggests — the inline-table idiom — while
 * inference is strict about it. That divergence is intended and is covered by
 * {@code TemporalArithmeticExecutionTest.StringCoercion}.
 */
@DisplayName("Temporal arithmetic agrees between inference and evaluation")
final class TemporalAgreementTest extends ProcessorTestSupport {

    /** One operand kind: the declared column type, and a literal of that type. */
    private record Kind(ScalarType type, String declared, Operand literal) { }

    private static final List<Kind> KINDS = List.of(
            new Kind(ScalarType.TIMESTAMP, "TIMESTAMP",
                    timestamp(Instant.parse("2026-06-15T12:00:00Z"))),
            new Kind(ScalarType.DATE, "DATE",
                    date(LocalDate.parse("2026-06-15"))),
            new Kind(ScalarType.TIME, "TIME",
                    time(LocalTime.parse("09:00"))),
            new Kind(ScalarType.DURATION, "DURATION",
                    duration(Duration.ofHours(2))),
            new Kind(ScalarType.NUMBER, "NUMBER", AstBuilders.num("2")));

    private static final String SYMBOL = "+-*/";

    private static String source(Kind left, Kind right) {
        return "connection db from database { url: \"jdbc:h2:mem:x\" };\n"
                + "source T from db { table: \"t\", schema: { a: " + left.declared()
                + ", b: " + right.declared() + " } };\n";
    }

    /**
     * Whether analysis accepts {@code a <op> b}, asked through a {@code σ} — the operator
     * that routes the type rule's verdict through the validator. See
     * {@link #projectionDoesNotReportAnIllegalCombination()} for why not through a π.
     */
    private static boolean analysisAccepts(Kind left, Kind right, ArithmeticOperator op) {
        // `IS NULL` rather than a comparison: a comparison would impose its own type
        // constraint on the result and reject a perfectly legal pair for the wrong reason.
        SemanticResult result = analyze(source(left, right)
                + "query { σ a " + SYMBOL.charAt(op.ordinal()) + " b IS NULL (T) };");
        return !result.hasErrors();
    }

    /** The type inference gives {@code a <op> b}, read off the projected column. */
    private static Optional<ScalarType> inferredType(Kind left, Kind right, ArithmeticOperator op) {
        SemanticResult result = analyze(source(left, right)
                + "query { π a " + SYMBOL.charAt(op.ordinal()) + " b → r (T) };");
        return result.model()
                .flatMap(m -> m.nodeSchemas().get(
                        ((com.darkcollective.relix.lang.ast.ExpressionQueryTarget)
                                m.rootQueries().getFirst().target()).expression()))
                .flatMap(sc -> sc.column("r"))
                .map(c -> c.type() instanceof ScalarType st ? st : ScalarType.ANY);
    }

    /** The type of the value {@code a <op> b} actually evaluates to, or empty if it raises. */
    private static Optional<ScalarType> evaluated(Kind left, Kind right, ArithmeticOperator op) {
        Row row = row(schema(col("dummy", ScalarType.NUMBER)), num(0));
        try {
            Value v = new OperandEvaluator().evaluate(
                    arith(left.literal(), op, right.literal()), row);
            return Optional.of(v.type());
        } catch (RuntimeException raised) {
            return Optional.empty();
        }
    }

    @Test
    @DisplayName("a pair analysis accepts is a pair evaluation computes, and conversely")
    void acceptanceAgrees() {
        for (ArithmeticOperator op : ArithmeticOperator.values()) {
            for (Kind left : KINDS) {
                for (Kind right : KINDS) {
                    boolean accepted = analysisAccepts(left, right, op);
                    boolean computed = evaluated(left, right, op).isPresent();
                    assertThat(accepted)
                            .as("%s %s %s — analysis %s, evaluation %s",
                                    left.type(), op, right.type(),
                                    accepted ? "accepted" : "rejected",
                                    computed ? "succeeded" : "raised")
                            .isEqualTo(computed);
                }
            }
        }
    }

    @Test
    @DisplayName("and the type inference names is the type the value actually has")
    void typesAgree() {
        for (ArithmeticOperator op : ArithmeticOperator.values()) {
            for (Kind left : KINDS) {
                for (Kind right : KINDS) {
                    Optional<ScalarType> computed = evaluated(left, right, op);
                    if (computed.isEmpty()) {
                        continue;   // an illegal pair has no type to agree about
                    }
                    assertThat(inferredType(left, right, op))
                            .as("%s %s %s — a consumer planned against the inferred type "
                                    + "must receive that type", left.type(), op, right.type())
                            .contains(computed.get());
                }
            }
        }
    }

    @Test
    @DisplayName("a π reports an illegal combination, as σ, τ and γ do")
    void projectionReportsAnIllegalCombination() {
        // It did not, and that was the one way to reach the first failure mode above: the
        // projection path has its own attribute walk (so it can report with the position
        // of the offending reference) and simply never asked the temporal rule, so
        // inference's lenient ANY stood and the evaluator raised once rows were flowing.
        // Fixed in #700; this is the regression guard.
        Kind ts = KINDS.getFirst();
        assertThat(analyze(source(ts, ts) + "query { σ a + b > 0 (T) };").errors())
                .as("σ").isNotEmpty();
        assertThat(analyze(source(ts, ts) + "query { τ a + b (T) };").errors())
                .as("τ").isNotEmpty();
        assertThat(analyze(source(ts, ts) + "query { π a + b → r (T) };").errors())
                .as("π")
                .anyMatch(e -> e.message().contains("cannot add TIMESTAMP to TIMESTAMP"));
    }

    @Test
    @DisplayName("the projection error carries the position of the offending expression")
    void projectionErrorIsPositioned() {
        // The reason the projection path does not simply delegate to PredicateValidator:
        // that validator reports at 0:0, and an unknown projection attribute has always
        // carried a real line. The temporal check had to keep that property.
        Kind ts = KINDS.getFirst();
        assertThat(analyze(source(ts, ts) + "query { π a + b → r (T) };").errors())
                .filteredOn(e -> e.message().contains("cannot add"))
                .allSatisfy(e -> assertThat(e.line()).isPositive());
    }

    @Test
    @DisplayName("an illegal combination nested inside a projected expression is reported too")
    void nestedIllegalCombinationIsReported() {
        Kind ts = KINDS.getFirst();
        assertThat(analyze(source(ts, ts) + "query { π Abs(a + b) → r (T) };").errors())
                .as("inside a function call").isNotEmpty();
        assertThat(analyze(source(ts, ts) + "query { π [a + b] → r (T) };").errors())
                .as("inside an array construction").isNotEmpty();
        assertThat(analyze(source(ts, ts) + "query { π IIf(a + b > 0, 1, 0) → r (T) };").errors())
                .as("inside a condition operand").isNotEmpty();
    }

    @Test
    @DisplayName("a legal projection still analyses clean — the check does not over-report")
    void legalProjectionIsUnaffected() {
        Kind ts = KINDS.getFirst();
        Kind dur = KINDS.get(3);
        assertThat(analyze(source(ts, dur) + "query { π a + b → r (T) };").errors()).isEmpty();
        assertThat(analyze(source(ts, dur) + "query { π a - b → r (T) };").errors()).isEmpty();
        assertThat(analyze(source(ts, ts) + "query { π a - b → r (T) };").errors())
                .as("TIMESTAMP − TIMESTAMP is legal and yields a DURATION")
                .isEmpty();
    }
}
