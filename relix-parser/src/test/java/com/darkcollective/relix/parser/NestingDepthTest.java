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
package com.darkcollective.relix.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A deeply nested expression is refused, rather than taking the stack with it.
 *
 * <p>This is recursive descent, so nesting depth is stack depth, and the depth at which the
 * stack runs out depends on how much of one the host gave the thread — on 1 MB, the JVM
 * default on Linux and Windows, parsing overflows around 900 levels. The overflow is the
 * problem rather than the depth: a {@link StackOverflowError}
 * is an {@code Error} and not the {@code ParseException} this API documents, so it escaped
 * {@code Relix.validate} — the one method whose whole purpose is to report a bad query
 * instead of raising on one, and the one meant for text the program did not write.
 */
@DisplayName("Nesting depth is bounded, and the bound is a diagnostic")
final class NestingDepthTest extends ParserTestSupport {

    private static String nested(int depth) {
        return "(".repeat(depth) + "R" + ")".repeat(depth);
    }

    @Test
    @DisplayName("an ordinary depth still parses")
    void shallowNestingIsUnaffected() {
        assertThatCode(() -> parse(nested(50))).doesNotThrowAnyException();
        assertThatCode(() -> parse(nested(200))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("past the limit it is a parse error, naming the limit")
    void deepNestingIsRefused() {
        assertParseError(nested(400))
                .hasMessageContaining("nests more than 250 levels deep");
    }

    /**
     * The point of the whole thing: what comes back is a {@code ParseException}, which
     * every caller of this API already handles, and not an {@code Error} that unwinds past
     * them.
     */
    @Test
    @DisplayName("what comes back is a ParseException, not a StackOverflowError")
    void theDepthWhichUsedToOverflowNowReports() {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> parse(nested(5_000)));

        assertThat(thrown)
                .as("a depth that overflows the stack on any host this runs on")
                .isInstanceOf(ParseException.class);
    }
}
