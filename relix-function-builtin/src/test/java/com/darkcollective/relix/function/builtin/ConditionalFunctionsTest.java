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
package com.darkcollective.relix.function.builtin;

import com.darkcollective.relix.function.Argument;
import com.darkcollective.relix.function.LazyScalarFunction;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.function.builtin.BuiltinCalls.call;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.function;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.n;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.s;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("The conditional built-ins")
final class ConditionalFunctionsTest {

    private static final Value NULL = NullValue.INSTANCE;

    /** An argument whose evaluation fails — reached only if the function asks for it. */
    private static final Argument EXPLOSIVE = () -> {
        throw new AssertionError("an argument was evaluated that should not have been");
    };

    private static Value lazily(String name, Argument... arguments) {
        ScalarFunction fn = function(name);
        assertThat(fn).isInstanceOf(LazyScalarFunction.class);
        return ((LazyScalarFunction) fn).invoke(BuiltinCalls.CONTEXT, List.of(arguments));
    }

    @Nested
    @DisplayName("Choosing a value")
    final class Choosing {

        @Test
        @DisplayName("IIf selects a branch on a BOOLEAN condition")
        void iif() {
            assertThat(text(call("IIf", BooleanValue.TRUE, s("yes"), s("no")))).isEqualTo("yes");
            assertThat(text(call("IIf", BooleanValue.FALSE, s("yes"), s("no")))).isEqualTo("no");
        }

        @Test
        @DisplayName("a NULL condition selects neither branch, so the result is NULL")
        void iifNullCondition() {
            assertThat(call("IIf", NULL, s("yes"), s("no")).isNull()).isTrue();
        }

        @Test
        @DisplayName("a condition that is not BOOLEAN is reported as such")
        void iifWrongCondition() {
            assertThatThrownBy(() -> call("IIf", n("1"), s("yes"), s("no")))
                    .hasMessage("IIf: first argument must be BOOLEAN, got NUMBER");
        }

        @Test
        @DisplayName("Nz substitutes for a missing value, and passes a present one through")
        void nz() {
            assertThat(text(call("Nz", s("here"), s("fallback")))).isEqualTo("here");
            assertThat(text(call("Nz", NULL, s("fallback")))).isEqualTo("fallback");
        }

        @Test
        @DisplayName("Nz with no replacement substitutes the empty string")
        void nzAlone() {
            assertThat(text(call("Nz", NULL))).isEmpty();
            assertThat(text(call("Nz", s("here")))).isEqualTo("here");
        }

        @Test
        @DisplayName("an explicit NULL replacement is returned as given")
        void nzNullReplacement() {
            assertThat(call("Nz", NULL, NULL).isNull()).isTrue();
        }

        @Test
        @DisplayName("Coalesce takes the first value that is there, over any number of candidates")
        void coalesce() {
            assertThat(text(call("Coalesce", NULL, NULL, s("third"), s("fourth"))))
                    .isEqualTo("third");
            assertThat(text(call("Coalesce", s("first")))).isEqualTo("first");
            assertThat(call("Coalesce", NULL, NULL).isNull()).isTrue();
        }

        @Test
        @DisplayName("Coalesce declares the argument count it actually accepts")
        void coalesceArity() {
            assertThat(function("Coalesce").signature().arity().isUnbounded()).isTrue();
            assertThat(function("Coalesce").signature().arity().accepts(1)).isTrue();
            assertThat(function("Coalesce").signature().arity().accepts(7)).isTrue();
            assertThat(function("Coalesce").signature().arity().accepts(0)).isFalse();
        }
    }

    @Nested
    @DisplayName("Short-circuiting")
    final class ShortCircuiting {

        @Test
        @DisplayName("IIf evaluates only the branch it selects")
        void iif() {
            assertThat(text(lazily("IIf", Argument.of(BooleanValue.TRUE),
                    Argument.of(s("taken")), EXPLOSIVE))).isEqualTo("taken");
            assertThat(text(lazily("IIf", Argument.of(BooleanValue.FALSE),
                    EXPLOSIVE, Argument.of(s("taken"))))).isEqualTo("taken");
        }

        @Test
        @DisplayName("a NULL condition evaluates neither branch")
        void iifNull() {
            assertThat(lazily("IIf", Argument.of(NULL), EXPLOSIVE, EXPLOSIVE).isNull()).isTrue();
        }

        @Test
        @DisplayName("Nz leaves the replacement alone when the value is there")
        void nz() {
            assertThat(text(lazily("Nz", Argument.of(s("here")), EXPLOSIVE))).isEqualTo("here");
        }

        @Test
        @DisplayName("Coalesce stops at the first value it finds")
        void coalesce() {
            assertThat(text(lazily("Coalesce", Argument.of(NULL), Argument.of(s("second")),
                    EXPLOSIVE, EXPLOSIVE))).isEqualTo("second");
        }
    }

    @Nested
    @DisplayName("Result types")
    final class ResultTypes {

        @Test
        @DisplayName("IIf is the branches' type when they agree, and ANY when they do not")
        void iif() {
            assertThat(returnType("IIf", ScalarType.BOOLEAN, ScalarType.NUMBER, ScalarType.NUMBER))
                    .isEqualTo(ScalarType.NUMBER);
            assertThat(returnType("IIf", ScalarType.BOOLEAN, ScalarType.NUMBER, ScalarType.STRING))
                    .isEqualTo(ScalarType.ANY);
            assertThat(returnType("IIf", ScalarType.BOOLEAN, ScalarType.ANY, ScalarType.NUMBER))
                    .isEqualTo(ScalarType.ANY);
        }

        @Test
        @DisplayName("the condition's own type does not reach the result")
        void iifIgnoresTheCondition() {
            assertThat(returnType("IIf", ScalarType.ANY, ScalarType.STRING, ScalarType.STRING))
                    .isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("Coalesce is its candidates' type when they agree")
        void coalesce() {
            assertThat(returnType("Coalesce", ScalarType.NUMBER, ScalarType.NUMBER,
                    ScalarType.NUMBER)).isEqualTo(ScalarType.NUMBER);
            assertThat(returnType("Coalesce", ScalarType.NUMBER, ScalarType.STRING))
                    .isEqualTo(ScalarType.ANY);
            assertThat(returnType("Coalesce", ScalarType.DATE)).isEqualTo(ScalarType.DATE);
        }

        @Test
        @DisplayName("Nz alone is a STRING only when its value already is one")
        void nz() {
            assertThat(returnType("Nz", ScalarType.STRING)).isEqualTo(ScalarType.STRING);
            assertThat(returnType("Nz", ScalarType.NUMBER)).isEqualTo(ScalarType.ANY);
            assertThat(returnType("Nz", ScalarType.NUMBER, ScalarType.NUMBER))
                    .isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("a call the declaration does not describe falls back to ANY")
        void unexpectedShape() {
            assertThat(returnType("IIf", ScalarType.BOOLEAN)).isEqualTo(ScalarType.ANY);
            assertThat(returnType("Coalesce")).isEqualTo(ScalarType.ANY);
        }

        private ScalarType returnType(String name, ScalarType... argumentTypes) {
            return function(name).returnTypeFor(List.of(argumentTypes));
        }
    }
}
