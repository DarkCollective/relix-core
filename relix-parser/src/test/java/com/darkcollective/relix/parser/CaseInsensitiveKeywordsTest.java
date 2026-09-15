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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.darkcollective.relix.ast.*;

import java.util.List;
import java.util.Optional;

/**
 * Verifies that all RA expression keywords are accepted in any casing.
 *
 * <p>Keyword matching in {@link Lexer} uses {@code toLowerCase(Locale.ROOT)} before
 * the map lookup, so {@code SELECT}, {@code select}, and {@code Select} all produce
 * the same token type.  This mirrors the behaviour of {@code LangLexer} in
 * {@code relix-lang}.
 */
final class CaseInsensitiveKeywordsTest extends ParserTestSupport {

    @Nested
    class UnaryOperators {

        @Test
        void lowercaseSelect() {
            assertParsesTo("select age > 18 (Users)", parse("SELECT age > 18 (Users)"));
        }

        @Test
        void mixedCaseSelect() {
            assertParsesTo("Select age > 18 (Users)", parse("SELECT age > 18 (Users)"));
        }

        @Test
        void lowercaseProject() {
            assertParsesTo("project name (Users)", parse("PROJECT name (Users)"));
        }

        @Test
        void lowercaseRename() {
            assertParsesTo("rename U (Users)", parse("RENAME U (Users)"));
        }

        @Test
        void lowercaseGroup() {
            assertParsesTo("group dept, SUM(salary) -> total (Employees)",
                    parse("GROUP dept, SUM(salary) -> total (Employees)"));
        }

        @Test
        void lowercaseSort() {
            assertParsesTo("sort name asc (Users)", parse("SORT name ASC (Users)"));
        }

        @Test
        void lowercaseLimit() {
            assertParsesTo("limit 10 (Orders)", parse("LIMIT 10 (Orders)"));
        }

        @Test
        void lowercaseDistinct() {
            assertParsesTo("distinct (Users)", parse("DISTINCT (Users)"));
        }

        @Test
        void lowercaseForall() {
            assertParsesTo("forall cid : amount > 0 (Orders)",
                    parse("FORALL cid : amount > 0 (Orders)"));
        }
    }

    @Nested
    class Aggregates {

        @Test
        void lowercaseSum() {
            assertParsesTo("γ dept, sum(salary) -> total (Employees)",
                    parse("γ dept, SUM(salary) -> total (Employees)"));
        }

        @Test
        void lowercaseAvg() {
            assertParsesTo("γ dept, avg(salary) -> avg_sal (Employees)",
                    parse("γ dept, AVG(salary) -> avg_sal (Employees)"));
        }

        @Test
        void lowercaseCount() {
            assertParsesTo("γ dept, count(id) -> n (Employees)",
                    parse("γ dept, COUNT(id) -> n (Employees)"));
        }

        @Test
        void lowercaseMin() {
            assertParsesTo("γ dept, min(salary) -> lo (Employees)",
                    parse("γ dept, MIN(salary) -> lo (Employees)"));
        }

        @Test
        void lowercaseMax() {
            assertParsesTo("γ dept, max(salary) -> hi (Employees)",
                    parse("γ dept, MAX(salary) -> hi (Employees)"));
        }

        @Test
        void mixedCaseSumAscDesc() {
            assertParsesTo("γ dept, Sum(salary) -> total (τ name Asc (Employees))",
                    parse("γ dept, SUM(salary) -> total (τ name ASC (Employees))"));
        }
    }

    @Nested
    class JoinKeywords {

        @Test
        void lowercaseJoin() {
            assertParsesTo("Users join Orders", parse("Users JOIN Orders"));
        }

        @Test
        void lowercaseSemi() {
            assertParsesTo("Users semi Users.id = Orders.user_id Orders",
                    parse("Users SEMI Users.id = Orders.user_id Orders"));
        }

        @Test
        void lowercaseAnti() {
            assertParsesTo("Users anti Users.id = Orders.user_id Orders",
                    parse("Users ANTI Users.id = Orders.user_id Orders"));
        }
    }

    @Nested
    class SetOperations {

        @Test
        void lowercaseCross() {
            assertParsesTo("A cross B", parse("A CROSS B"));
        }

        @Test
        void lowercaseUnion() {
            assertParsesTo("A union B", parse("A UNION B"));
        }

        @Test
        void lowercaseDiff() {
            assertParsesTo("A diff B", parse("A DIFF B"));
        }

        @Test
        void lowercaseInter() {
            assertParsesTo("A inter B", parse("A INTER B"));
        }

        @Test
        void lowercaseDiv() {
            assertParsesTo("A div B", parse("A DIV B"));
        }

        @Test
        void lowercaseSymdiff() {
            assertParsesTo("A symdiff B", parse("A SYMDIFF B"));
        }

        @Test
        void lowercaseCompose() {
            assertParsesTo("A compose B", parse("A COMPOSE B"));
        }
    }

    @Nested
    class LogicalOperators {

        @Test
        void lowercaseAnd() {
            assertParsesTo("select a = 1 and b = 2 (R)", parse("SELECT a = 1 AND b = 2 (R)"));
        }

        @Test
        void lowercaseOr() {
            assertParsesTo("select a = 1 or b = 2 (R)", parse("SELECT a = 1 OR b = 2 (R)"));
        }

        @Test
        void lowercaseNot() {
            assertParsesTo("select not (a = 1) (R)", parse("SELECT NOT (a = 1) (R)"));
        }

        @Test
        void lowercaseNull() {
            assertParsesTo("select a = null (R)", parse("SELECT a = NULL (R)"));
        }

        @Test
        void lowercaseIn() {
            assertParsesTo("select dept in {\"hr\"} (Employees)",
                    parse("SELECT dept IN {\"hr\"} (Employees)"));
        }

        @Test
        void lowercaseNotIn() {
            assertParsesTo("select dept not in {\"hr\"} (Employees)",
                    parse("SELECT dept NOT IN {\"hr\"} (Employees)"));
        }
    }

    @Nested
    class BooleanLiterals {

        @Test
        void uppercaseTrue() {
            assertParsesTo("σ active = TRUE (Users)", parse("σ active = true (Users)"));
        }

        @Test
        void titleCaseTrue() {
            assertParsesTo("σ active = True (Users)", parse("σ active = true (Users)"));
        }

        @Test
        void uppercaseFalse() {
            assertParsesTo("σ active = FALSE (Users)", parse("σ active = false (Users)"));
        }

        @Test
        void titleCaseFalse() {
            assertParsesTo("σ active = False (Users)", parse("σ active = false (Users)"));
        }
    }

    @Nested
    class SpecialKeywords {

        @Test
        void lowercaseClosure() {
            assertParsesTo("closure from, to (Edges)", parse("CLOSURE from, to (Edges)"));
        }

        @Test
        void lowercaseRclosure() {
            assertParsesTo("rclosure from, to (Edges)", parse("RCLOSURE from, to (Edges)"));
        }

        @Test
        void lowercaseSample() {
            assertParsesTo("sample 0.1 (R)", parse("SAMPLE 0.1 (R)"));
        }

        @Test
        void lowercaseSampleRows() {
            assertParsesTo("sample 100 rows (R)", parse("SAMPLE 100 ROWS (R)"));
        }

        @Test
        void lowercaseFix() {
            assertParsesTo("fix T (A, T union B)", parse("FIX T (A, T UNION B)"));
        }
    }
}
