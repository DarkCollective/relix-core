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

import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.RelNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.ast.ComparisonOperator.EQUAL;
import static org.assertj.core.api.Assertions.assertThat;

/** SQL's {@code COLLATE} (#262), read as a {@code CollateOperand} on the value it follows. */
final class CollateParserTest extends ParserTestSupport {

    @Test
    @DisplayName("COLLATE takes BINARY or NOCASE as a word, in any case, or a locale as a string")
    void names() {
        assertParsesTo("σ s COLLATE nocase = 'ada' (R)",
                select(cmp(collate(attr("s"), "NOCASE"), EQUAL, str("ada")), rel("R")));
        assertParsesTo("σ s COLLATE 'binary' = 'ada' (R)",
                select(cmp(collate(attr("s"), "BINARY"), EQUAL, str("ada")), rel("R")));
        assertParsesTo("σ s COLLATE 'en-US' = 'ada' (R)",
                select(cmp(collate(attr("s"), "en-US"), EQUAL, str("ada")), rel("R")));
        assertParsesTo("σ 'ada' = s COLLATE NOCASE (R)",
                select(cmp(str("ada"), EQUAL, collate(attr("s"), "NOCASE")), rel("R")));
    }

    @Test
    @DisplayName("COLLATE binds tightest, as in SQL: it collates the value just before it")
    void bindsTightest() {
        assertParsesTo("σ a + b COLLATE NOCASE = 'x' (R)",
                select(cmp(arith(attr("a"), ArithmeticOperator.PLUS, collate(attr("b"), "NOCASE")), EQUAL,
                        str("x")), rel("R")));
        assertParsesTo("σ (a + b) COLLATE NOCASE = 'x' (R)",
                select(cmp(collate(arith(attr("a"), ArithmeticOperator.PLUS, attr("b")), "NOCASE"), EQUAL,
                        str("x")), rel("R")));
    }

    @Test
    @DisplayName("a sort key and a grouping key may be collated")
    void keys() {
        assertThat(parse("τ s COLLATE NOCASE DESC (R)").prettyPrint()).isEqualTo("τ s COLLATE NOCASE DESC (R)");
        RelNode grouped = parse("γ s COLLATE NOCASE, COUNT(1) → n (R)");
        assertThat(grouped.prettyPrint()).isEqualTo("γ s COLLATE NOCASE, COUNT(1) → n (R)");
    }

    @Test
    @DisplayName("printed back, a collation round-trips, a locale quoted and an expression parenthesised")
    void roundTrips() {
        for (String text : new String[] {"σ s COLLATE NOCASE = \"ada\" (R)", "σ s COLLATE 'en-US' < \"b\" (R)",
                "σ (a + b) COLLATE BINARY = \"x\" (R)", "σ (-a) COLLATE BINARY = \"x\" (R)",
                "τ s COLLATE 'it''s' (R)"}) {
            assertThat(parse(text).prettyPrint()).isEqualTo(text);
            assertParsesTo(parse(text).prettyPrint(), parse(text));
        }
    }

    @Test
    @DisplayName("a collated key with no aggregate is told to count, a projection carrying no collation")
    void keysOnlyGroup() {
        assertParseError("γ name COLLATE NOCASE (R)").hasMessageContaining(
                "γ needs an aggregate after its keys; for one row per collated key write"
                + " γ name COLLATE NOCASE, COUNT(*) → n (R)");
    }

    @Test
    @DisplayName("a column called collate is still a column, and COLLATE needs a name after it")
    void notReserved() {
        assertParsesTo("π collate (R)", parse("π `collate` (R)"));
        assertParseError("σ s = t collate (R)").hasMessageNotContaining("COLLATE names");
        assertParseError("σ s COLLATE '' = 'a' (R)")
                .hasMessageContaining("COLLATE names a collation: BINARY, NOCASE, or a locale such as 'en-US'");
    }
}
