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

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.function.Arity;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two claims the installed function library makes about <em>every</em> function it
 * declares, asserted about every function it declares.
 *
 * <p>{@code BuiltinFunctionEvaluatorTest} tested each of these once, for one function:
 * {@code Len} with no arguments for the arity check, {@code len} in lower case for the
 * lookup. Both are properties of the catalogue rather than of {@code Len} — all 52
 * signatures declare an {@link Arity}, and the name index is case-insensitive for all of
 * them — so the domain is the catalogue and the test input should be too.
 *
 * <p>The catalogue is what is <em>installed</em>, discovered exactly as a query would
 * discover it, so a third-party library on the test classpath is held to the same two
 * claims as the bundled one. That is the point of the SPI: what a shipped function must
 * do, an installed function must do.
 *
 * <p>{@code returnTypeFor(argTypes)} agreeing with the type evaluation actually produces
 * is deliberately not here. Coercion, nullable results and polymorphic returns make it a
 * real contract investigation rather than a table, and folding it in would turn a
 * completeness fix into a redesign of function testing.
 */
@DisplayName("FunctionCatalog — the contract every installed function meets")
final class FunctionCatalogContractTest extends ProcessorTestSupport {

    private static final FunctionCatalog CATALOG = ExecutionContext.installedFunctions();

    private OperandEvaluator eval;
    private Row row;

    @BeforeEach
    void setUp() {
        eval = new OperandEvaluator();
        row = row(schema(col("id", ScalarType.NUMBER)), num(1));
    }

    /**
     * Each installed function, keyed by its declared name. Note that is
     * {@code signature().name()} rather than {@code canonicalName()}: the canonical
     * form is the lower-cased index key, while a diagnostic quotes the name as
     * declared — {@code "Len: expected 1 argument(s), got 2"}.
     */
    static List<Arguments> installedFunctions() {
        return CATALOG.scalars().stream()
                .map(fn -> Arguments.of(fn.signature().name(), fn))
                .toList();
    }

    @Test
    @DisplayName("the discovered catalogue is not empty")
    void theCatalogueIsPopulated() {
        // Every case below is vacuous if discovery found nothing — which is exactly how
        // a missing ServiceLoader registration would present itself here.
        assertThat(CATALOG.scalars())
                .as("installed scalar functions — the default library should be on the "
                        + "test runtime classpath")
                .isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("installedFunctions")
    @DisplayName("resolves under any casing of its name")
    void resolvesCaseInsensitively(String name, ScalarFunction function) {
        for (String spelling : List.of(name,
                name.toLowerCase(Locale.ROOT),
                name.toUpperCase(Locale.ROOT),
                alternatingCase(name))) {
            assertThat(CATALOG.scalar(spelling))
                    .as("%s does not resolve spelled '%s'", name, spelling)
                    .containsSame(function);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("installedFunctions")
    @DisplayName("rejects a call one argument below its declared minimum")
    void rejectsTooFewArguments(String name, ScalarFunction function) {
        Arity arity = function.signature().arity();
        if (arity.min() == 0) {
            return;   // nothing is below zero arguments; NOW() and Rand() are the cases
        }
        assertThatThrownBy(() -> eval.evaluate(call(name, arity.min() - 1), row))
                .as("%s accepts %d arguments though it declares %s",
                        name, arity.min() - 1, arity.describe())
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining(name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("installedFunctions")
    @DisplayName("rejects a call one argument above its declared maximum")
    void rejectsTooManyArguments(String name, ScalarFunction function) {
        Arity arity = function.signature().arity();
        if (arity.isUnbounded()) {
            return;   // Coalesce declares atLeast(1); there is no maximum to exceed
        }
        assertThatThrownBy(() -> eval.evaluate(call(name, arity.max() + 1), row))
                .as("%s accepts %d arguments though it declares %s",
                        name, arity.max() + 1, arity.describe())
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining(name);
    }

    @Test
    @DisplayName("every function's arity is one the two probes above can exercise")
    void everyArityIsProbed() {
        // The two returns above are the only exemptions, and they are properties of the
        // declared arity rather than of a name — so nothing is skipped silently, and a
        // function that somehow declares neither a floor nor a ceiling is reported here
        // rather than passing both probes by doing nothing.
        List<String> unprobed = new ArrayList<>();
        for (ScalarFunction fn : CATALOG.scalars()) {
            Arity arity = fn.signature().arity();
            if (arity.min() == 0 && arity.isUnbounded()) {
                unprobed.add(fn.signature().canonicalName() + " (" + arity.describe() + ")");
            }
        }
        assertThat(unprobed)
                .as("functions accepting any number of arguments — neither probe applies, "
                        + "so their arity is asserted by nothing")
                .isEmpty();
    }

    /** A call of {@code count} arguments; the arity check runs before any is evaluated. */
    private static FunctionCall call(String name, int count) {
        List<Operand> args = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            args.add(AstBuilders.num("1"));
        }
        return new FunctionCall(name, args);
    }

    private static String alternatingCase(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            out.append(i % 2 == 0 ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        return out.toString();
    }
}
