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

import com.darkcollective.relix.ast.ComparisonOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;

/** SQL's paging clauses (#259) and its datetime keywords without parentheses (#258). */
final class SqlPagingAndDatetimeParserTest extends ParserTestSupport {

    private static final String PAGING_FORMS = "LIMIT n (R), LIMIT m, n (R), LIMIT n OFFSET m (R),"
            + " or OFFSET m ROWS FETCH NEXT n ROWS ONLY (R)";

    @Nested
    @DisplayName("paging")
    class Paging {

        @Test
        @DisplayName("LIMIT n OFFSET m is LIMIT m, n")
        void limitOffset() {
            assertParsesTo("LIMIT 1 OFFSET 2 (R)", limit(Optional.of(2L), 1L, rel("R")));
            assertParsesTo("λ 10 OFFSET 20 ROWS (R)", limit(Optional.of(20L), 10L, rel("R")));
            assertPrettyPrints(parse("LIMIT 1 OFFSET 2 (R)"), "λ 2, 1 (R)");
        }

        @Test
        @DisplayName("OFFSET m ROWS FETCH NEXT n ROWS ONLY is LIMIT m, n, ROW and FIRST read the same")
        void offsetFetch() {
            var expected = limit(Optional.of(2L), 3L, rel("R"));
            assertParsesTo("OFFSET 2 ROWS FETCH NEXT 3 ROWS ONLY (R)", expected);
            assertParsesTo("offset 2 row fetch first 3 row only (R)", expected);
            assertParsesTo("OFFSET 2 FETCH FIRST 3 ROWS ONLY (R)", expected);
        }

        @Test
        @DisplayName("FETCH alone is a limit, and with no count fetches one row")
        void fetchAlone() {
            assertParsesTo("FETCH FIRST 5 ROWS ONLY (R)", limit(Optional.empty(), 5L, rel("R")));
            assertParsesTo("FETCH NEXT ROW ONLY (R)", limit(Optional.empty(), 1L, rel("R")));
        }

        @Test
        @DisplayName("OFFSET with no FETCH keeps every row after the offset")
        void offsetAlone() {
            assertParsesTo("OFFSET 5 ROWS (R)", limit(Optional.of(5L), Long.MAX_VALUE, rel("R")));
        }

        @Test
        @DisplayName("FETCH … WITH TIES over a sort is the TOP that keeps ties, as LIMIT n WITH TIES is")
        void fetchWithTies() {
            assertParsesTo("OFFSET 1 ROWS FETCH FIRST 2 ROWS WITH TIES (SORT score DESC (R))",
                    parse("LIMIT 1, 2 WITH TIES (SORT score DESC (R))"));
            assertParseError("FETCH FIRST 2 ROWS WITH TIES (R)")
                    .hasMessageContaining("WITH TIES needs a sorted input");
        }

        @Test
        @DisplayName("a relation named offset or fetch is still a relation")
        void namesStayNames() {
            assertParsesTo("offset", rel("offset"));
            assertParsesTo("fetch", rel("fetch"));
            parse("PROJECT offset (R)");
        }

        @Test
        @DisplayName("a malformed paging clause names every paging form")
        void malformed() {
            assertParseError("FETCH FIRST 2 ROWS (R)")
                    .hasMessageContaining("Expected ONLY or WITH TIES to end the FETCH: write " + PAGING_FORMS);
            assertParseError("OFFSET 2 ROWS FETCH 3 ROWS ONLY (R)")
                    .hasMessageContaining("Expected FIRST or NEXT after FETCH: write " + PAGING_FORMS);
            assertParseError("FETCH NEXT 3 ONLY (R)")
                    .hasMessageContaining("Expected ROWS after the FETCH count: write " + PAGING_FORMS);
            assertParseError("LIMIT 3 OFFSET ROWS (R)")
                    .hasMessageContaining("Expected a row count after OFFSET: write " + PAGING_FORMS);
            assertParseError("OFFSET 2 ROWS FETCH NEXT 3 ROWS ONLY R")
                    .hasMessageContaining("Expected '(' after the paging clause: write " + PAGING_FORMS);
        }
    }

    @Nested
    @DisplayName("datetime keywords")
    class Datetime {

        @Test
        @DisplayName("CURRENT_TIMESTAMP and LOCALTIMESTAMP are NOW(), with or without parentheses")
        void timestamps() {
            for (String keyword : List.of("CURRENT_TIMESTAMP", "current_timestamp", "CURRENT_TIMESTAMP()",
                    "LOCALTIMESTAMP", "LocalTimestamp()")) {
                assertParsesTo("π " + keyword + " → t (R)", project(List.of(projected(func("NOW"), "t")), rel("R")));
            }
        }

        @Test
        @DisplayName("CURRENT_DATE and CURRENT_TIME are their functions, with or without parentheses")
        void dateAndTime() {
            assertParsesTo("σ d = CURRENT_DATE (R)",
                    select(cmp(attr("d"), ComparisonOperator.EQUAL, func("CURRENT_DATE")), rel("R")));
            assertParsesTo("σ d = CURRENT_DATE() (R)",
                    select(cmp(attr("d"), ComparisonOperator.EQUAL, func("CURRENT_DATE")), rel("R")));
            assertParsesTo("π CURRENT_TIME → t (R)", project(List.of(projected(func("CURRENT_TIME"), "t")), rel("R")));
        }

        @Test
        @DisplayName("a bare keyword before a projection's input is not a call")
        void keywordBeforeInput() {
            assertParsesTo("π CURRENT_DATE (R)", project(List.of(projected(func("CURRENT_DATE"))), rel("R")));
        }

        @Test
        @DisplayName("the keyword wins; a column of the name is reached qualified or delimited")
        void columnOfTheName() {
            assertParsesTo("π R.current_date (R)", project(List.of(projected(attr("R.current_date"))), rel("R")));
            assertParsesTo("π `current_date` (R)", project(List.of(projected(attr("current_date"))), rel("R")));
            assertParsesTo("π a,\n  `current_date` (R)",
                    project(List.of(projected(attr("a")), projected(attr("current_date"))), rel("R")));
        }

        @Test
        @DisplayName("a precision is refused, naming both spellings")
        void noPrecision() {
            assertParseError("π CURRENT_TIMESTAMP(3) → t (R)")
                    .hasMessageContaining("CURRENT_TIMESTAMP takes no argument: write CURRENT_TIMESTAMP or CURRENT_TIMESTAMP()");
        }
    }
}
