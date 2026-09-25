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
package com.darkcollective.relix.lang.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ScriptParseException} — the core-level "a frontend could not
 * produce a {@link Script}" signal (ADR-0025).
 */
@DisplayName("relix-lang-ast — ScriptParseException")
final class ScriptParseExceptionTest {

    @Test
    @DisplayName("Message-only constructor reports an unknown position")
    void messageOnly() {
        var e = new ScriptParseException("cannot build a script");
        assertThat(e).hasMessage("cannot build a script");
        assertThat(e.line()).isZero();
        assertThat(e.column()).isZero();
    }

    @Test
    @DisplayName("Positioned constructor keeps the message verbatim")
    void positioned() {
        var e = new ScriptParseException("unexpected token", 3, 12);
        assertThat(e).hasMessage("unexpected token");
        assertThat(e.line()).isEqualTo(3);
        assertThat(e.column()).isEqualTo(12);
    }

    @Test
    @DisplayName("description() is the message verbatim, a frontend having decorated nothing")
    void descriptionIsMessage() {
        assertThat(new ScriptParseException("unexpected token", 3, 12).description())
                .isEqualTo("unexpected token");
    }

    @Test
    @DisplayName("Is unchecked, so producing an AST needs no checked handling")
    void isUnchecked() {
        assertThat(new ScriptParseException("x")).isInstanceOf(RuntimeException.class);
    }
}
