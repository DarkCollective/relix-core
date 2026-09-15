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

import com.darkcollective.relix.ast.*;
import com.darkcollective.relix.ast.AstBuilders;
import org.assertj.core.api.AbstractAssert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Base class for relix-parser tests.
 * Extends {@link AstBuilders} (relix-ast {@code main}) for AST factory methods,
 * and adds parser-specific helpers and the {@link ParseExceptionAssert} fluent asserter.
 *
 * <p>{@link #assertParsesTo} strips source locations from the parsed result before
 * comparing with the expected node, so expected nodes built with convenience constructors
 * (which default to {@link SourceLocation#UNKNOWN}) compare equal to parsed nodes.
 */
abstract class ParserTestSupport extends AstBuilders {

    /**
     * Asserts that {@code node} pretty-prints to exactly {@code expected}.
     *
     * <p>Inherited by every parser test.  {@code AstBuilders} is {@code main} source
     * now, so it carries no test dependency and this delegates to the AssertJ
     * fixture that owns it.
     */
    static void assertPrettyPrints(RelNode node, String expected) {
        AstAssertions.assertPrettyPrints(node, expected);
    }


    static RelNode parse(String input) {
        return RelAlgebraParser.parse(input);
    }

    /**
     * Parses {@code input} and asserts the resulting AST equals {@code expected},
     * ignoring source locations on both sides.
     *
     * <p>Goes through {@link RelNodeAssert#isStructurallyEqualTo}, so a failure prints
     * both trees as the parser round-trips them rather than two record {@code toString}s
     * differing somewhere in the middle.
     */
    static void assertParsesTo(String input, RelNode expected) {
        AstAssertions.assertThat(parse(input)).isStructurallyEqualTo(expected);
    }

    static ParseExceptionAssert assertParseError(String input) {
        try {
            parse(input);
        } catch (ParseException e) {
            return new ParseExceptionAssert(e);
        }

        fail("Expected ParseException to be thrown");
        throw new AssertionError("unreachable");
    }

    /**
     * Returns a copy of {@code node} with all {@link SourceLocation} fields
     * replaced by {@link SourceLocation#UNKNOWN}, recursively.
     *
     * <p>This allows {@link #assertParsesTo} to compare parsed nodes (which carry
     * real file/line/column positions) against expected nodes built in tests
     * (which default to {@link SourceLocation#UNKNOWN}).
     *
     * <p>The rebuild itself lives in {@link AstLocations}, published from relix-ast's
     * test fixtures — exact location-blind comparison is what any test comparing an
     * expected tree to a produced one wants, not something parser-specific.  This
     * remains as an inherited name so the module's assertions read unchanged; its
     * javadoc is where the difference from {@link AstEquivalence} is written down.
     *
     * @param node the tree to strip
     * @return an equal-but-location-free copy
     */
    static RelNode stripLocations(RelNode node) {
        return AstLocations.stripLocations(node);
    }

    // =========================================================================
    // ParseExceptionAssert
    // =========================================================================

    static final class ParseExceptionAssert extends AbstractAssert<ParseExceptionAssert, ParseException> {
        private ParseExceptionAssert(ParseException actual) {
            super(actual, ParseExceptionAssert.class);
        }

        ParseExceptionAssert hasMessageContaining(String expected) {
            isNotNull();
            assertThat(actual.getMessage()).contains(expected);
            return this;
        }

        ParseExceptionAssert at(int line, int column) {
            isNotNull();
            assertThat(actual.line()).isEqualTo(line);
            assertThat(actual.column()).isEqualTo(column);
            return this;
        }

        ParseExceptionAssert found(String expected) {
            isNotNull();
            assertThat(actual.found()).isEqualTo(expected);
            return this;
        }

        ParseExceptionAssert diagnostic(String expected) {
            isNotNull();
            assertThat(actual.diagnostic()).isEqualTo(expected);
            return this;
        }
    }
}
