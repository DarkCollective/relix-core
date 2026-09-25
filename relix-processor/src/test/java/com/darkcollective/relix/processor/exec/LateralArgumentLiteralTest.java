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

import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.processor.eval.OperandEvaluator;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LateralExecutor#valueToLiteral} lifts a runtime {@link Value} back to an AST
 * {@link Operand} literal, and it is the hinge of the whole {@code LATERAL} path: the memo
 * keys on the tuple it produces, and the TVF body is re-planned per outer row against
 * exactly these literals.  A value that lifts to the wrong literal does not fail — it
 * silently runs the function on a different argument than the row held.
 *
 * <p>Before this test only the {@code NumberValue} arm had ever executed.  Every
 * end-to-end lateral case in the suite passed a numeric id, so a TVF keyed on a name —
 * the most ordinary lateral there is — was never run, and neither was the diagnostic for
 * an argument that cannot be lifted at all.
 *
 * <h2>The assertion is a round trip, not a type check</h2>
 *
 * <p>Asserting that a {@code StringValue} lifts to a {@code StringOperand} checks the arm
 * exists.  What matters is that the literal <em>denotes the same value</em>, so each case
 * evaluates the operand back through {@link OperandEvaluator} — the same evaluator that
 * will read it when the body is planned — and compares against the value it started from.
 */
@DisplayName("LATERAL — lifting an argument value to an AST literal")
final class LateralArgumentLiteralTest {

    private static final OperandEvaluator EVAL = new OperandEvaluator();

    /** A literal needs no columns, so the round trip reads it against an empty row. */
    private static final Row NO_COLUMNS = ArrayRow.of(Schema.empty(), List.of());

    /** Whether a value kind can be a lateral argument at all. */
    private enum Liftable { LIFTS, REFUSED }

    /**
     * Every concrete {@link Value} kind, what it lifts to, and a sample to lift.
     *
     * <p>A kind absent from here fails {@link Exhaustiveness#tableNamesEveryValueKind()}.
     * That guard is the point of the table: {@code valueToLiteral} ends in a
     * {@code default} arm that throws, so a new scalar {@code Value} kind would compile
     * cleanly, be rejected as "not supported as a lateral argument", and stay that way
     * until a user found it.
     */
    private record Case(Value sample, Liftable liftable, Class<? extends Operand> literal) {
    }

    private static final Map<String, Case> CASES = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("NumberValue",
                    new Case(new NumberValue(new BigDecimal("42.5")), Liftable.LIFTS, NumberOperand.class)),
            Map.entry("StringValue",
                    new Case(new StringValue("Alice"), Liftable.LIFTS, StringOperand.class)),
            Map.entry("BooleanValue",
                    new Case(BooleanValue.of(true), Liftable.LIFTS, BooleanOperand.class)),
            Map.entry("DateValue",
                    new Case(new DateValue(LocalDate.parse("2026-06-15")), Liftable.LIFTS, DateOperand.class)),
            Map.entry("TimeValue",
                    new Case(new TimeValue(LocalTime.parse("13:40:00")), Liftable.LIFTS, TimeOperand.class)),
            Map.entry("TimestampValue",
                    new Case(new TimestampValue(Instant.parse("2026-06-15T13:40:00Z")),
                            Liftable.LIFTS, TimestampOperand.class)),
            Map.entry("DurationValue",
                    new Case(new DurationValue(Duration.parse("PT30M")), Liftable.LIFTS, DurationOperand.class)),

            // No literal denotes these, so they are refused rather than mis-lifted.
            Map.entry("NullValue",
                    new Case(NullValue.INSTANCE, Liftable.REFUSED, null)),
            Map.entry("StructValue",
                    new Case(new StructValue(Map.of("city", new StringValue("Berlin"))),
                            Liftable.REFUSED, null)),
            Map.entry("ArrayValue",
                    new Case(new ArrayValue(List.of(new StringValue("a"))), Liftable.REFUSED, null))));

    private static Stream<DynamicTest> forEachCase(Liftable which,
                                                   java.util.function.BiConsumer<String, Case> check) {
        return CASES.entrySet().stream()
                .filter(e -> e.getValue().liftable() == which)
                .map(e -> DynamicTest.dynamicTest(e.getKey(),
                        () -> check.accept(e.getKey(), e.getValue())));
    }

    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("a scalar argument lifts")
    final class Lifts {

        @TestFactory
        @DisplayName("to the matching literal kind")
        Stream<DynamicTest> liftsToTheMatchingLiteral() {
            return forEachCase(Liftable.LIFTS, (kind, c) ->
                    assertThat(LateralExecutor.valueToLiteral(c.sample()))
                            .as("%s must lift to %s", kind, c.literal().getSimpleName())
                            .isInstanceOf(c.literal()));
        }

        @TestFactory
        @DisplayName("that evaluates back to the value it came from")
        Stream<DynamicTest> roundTripsThroughTheEvaluator() {
            // The contract that matters. A literal of the right *kind* carrying the wrong
            // *value* runs the TVF on an argument the outer row never held, and nothing
            // downstream can tell.
            return forEachCase(Liftable.LIFTS, (kind, c) -> {
                Operand literal = LateralExecutor.valueToLiteral(c.sample());

                assertThat(EVAL.evaluate(literal, NO_COLUMNS))
                        .as("%s: the lifted literal must denote the value it came from", kind)
                        .isEqualTo(c.sample());
            });
        }

        @Test
        @DisplayName("a number keeps its magnitude, whatever its scale")
        void numberScaleDoesNotChangeTheValue() {
            // valueToLiteral spells a number through asDisplayString(), which strips
            // trailing zeros — so 42.50 lifts to the literal `42.5`. The spelling changes
            // and the value must not, which also makes the memo key correct: two left rows
            // holding 42.50 and 42.5 are asking the same question.
            Operand scaled = LateralExecutor.valueToLiteral(new NumberValue(new BigDecimal("42.50")));
            Operand plain  = LateralExecutor.valueToLiteral(new NumberValue(new BigDecimal("42.5")));

            assertThat(scaled).isEqualTo(plain);
            assertThat(EVAL.evaluate(scaled, NO_COLUMNS))
                    .isEqualTo(new NumberValue(new BigDecimal("42.5")));
        }

        @Test
        @DisplayName("a large number is spelled plainly, never in scientific notation")
        void largeNumbersAreNotExponential() {
            // A literal spelled 1E+9 would have to survive re-parsing by whatever reads
            // the planned body; asDisplayString uses toPlainString for exactly this reason.
            Operand literal = LateralExecutor.valueToLiteral(
                    new NumberValue(new BigDecimal("1000000000")));

            assertThat(((NumberOperand) literal).value()).isEqualTo("1000000000");
        }
    }

    @Nested
    @DisplayName("a value with no literal form is refused")
    final class Refused {

        @TestFactory
        @DisplayName("with a diagnostic naming the kind and the rule")
        Stream<DynamicTest> refusedWithADiagnostic() {
            return forEachCase(Liftable.REFUSED, (kind, c) ->
                    assertThatThrownBy(() -> LateralExecutor.valueToLiteral(c.sample()))
                            .as("%s has no literal form, so it must be refused", kind)
                            .isInstanceOf(EvaluationException.class)
                            .hasMessageContaining(kind)
                            .hasMessageContaining("only scalar non-null values"));
        }

        @Test
        @DisplayName("NULL is refused rather than lifted to something falsy")
        void nullIsRefused() {
            // Worth stating on its own: NULL is the plausible one to get wrong, since a
            // lateral argument column with a missing value is ordinary data rather than a
            // programming error, and lifting it to (say) an empty string would silently
            // invoke the TVF on a value the row did not hold.
            assertThatThrownBy(() -> LateralExecutor.valueToLiteral(NullValue.INSTANCE))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("NullValue");
        }
    }

    @Nested
    @DisplayName("exhaustiveness")
    final class Exhaustiveness {

        @Test
        @DisplayName("the table names every concrete Value kind, and only kinds that exist")
        void tableNamesEveryValueKind() {
            // valueToLiteral ends in `default -> throw`, so a new Value kind compiles
            // cleanly and is silently unusable as a lateral argument. This is the only
            // thing that would notice.
            List<String> kinds = new ArrayList<>();
            for (Class<?> permitted : Value.class.getPermittedSubclasses()) {
                kinds.add(permitted.getSimpleName());
            }

            assertThat(CASES.keySet())
                    .as("every Value kind must state whether it can be a lateral argument")
                    .containsExactlyInAnyOrderElementsOf(kinds);
        }
    }
}
