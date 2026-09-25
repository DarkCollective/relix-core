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
import com.darkcollective.relix.ast.internal.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests struct ({@code { name: expr, … }}) and array ({@code [ expr, … ]})
 * construction operands in projections (ADR-0001, Slice 5b).
 */
final class ConstructionParserTest extends ParserTestSupport {

    @Nested
    class StructConstructionTests {

        @Test
        void parsesExplicitFields() {
            assertParsesTo("π { full: name, years: age } → person (Users)",
                    project(
                            List.of(projected(
                                    structOf(field("full", attr("name")), field("years", attr("age"))),
                                    "person")),
                            rel("Users")));
        }

        @Test
        void parsesShorthandFields() {
            // { name, age } desugars to { name: name, age: age }
            assertParsesTo("π { name, age } → person (Users)",
                    project(
                            List.of(projected(
                                    structOf(field("name", attr("name")), field("age", attr("age"))),
                                    "person")),
                            rel("Users")));
        }

        @Test
        void parsesMixedShorthandAndExplicit() {
            assertParsesTo("π { name, total: amount + tax } → r (Orders)",
                    project(
                            List.of(projected(
                                    structOf(
                                            field("name", attr("name")),
                                            field("total", arith(attr("amount"),
                                                    ArithmeticOperator.PLUS, attr("tax")))),
                                    "r")),
                            rel("Orders")));
        }

        @Test
        void parsesEmptyStruct() {
            assertParsesTo("π {} → empty (Users)",
                    project(
                            List.of(projected(structOf(), "empty")),
                            rel("Users")));
        }

        @Test
        void parsesNestedStruct() {
            assertParsesTo("π { outer: { inner: x } } → s (T)",
                    project(
                            List.of(projected(
                                    structOf(field("outer", structOf(field("inner", attr("x"))))),
                                    "s")),
                            rel("T")));
        }

        @Test
        void prettyPrintsStruct() {
            RelNode node = project(
                    List.of(projected(
                            structOf(field("name", attr("name")), field("age", attr("age"))), "person")),
                    rel("Users"));
            assertPrettyPrints(node, "π {name: name, age: age} → person (Users)");
        }
    }

    @Nested
    class ArrayConstructionTests {

        @Test
        void parsesArrayOfAttributes() {
            assertParsesTo("π [x, y] → coords (Points)",
                    project(
                            List.of(projected(arrayOf(attr("x"), attr("y")), "coords")),
                            rel("Points")));
        }

        @Test
        void parsesArrayOfLiterals() {
            assertParsesTo("π [1, 2, 3] → nums (T)",
                    project(
                            List.of(projected(arrayOf(num("1"), num("2"), num("3")), "nums")),
                            rel("T")));
        }

        @Test
        void parsesEmptyArray() {
            assertParsesTo("π [] → empty (T)",
                    project(
                            List.of(projected(arrayOf(), "empty")),
                            rel("T")));
        }

        @Test
        void parsesArrayOfStructs() {
            assertParsesTo("π [ { a: x }, { a: y } ] → rows (T)",
                    project(
                            List.of(projected(arrayOf(
                                    structOf(field("a", attr("x"))),
                                    structOf(field("a", attr("y")))), "rows")),
                            rel("T")));
        }

        @Test
        void prettyPrintsArray() {
            RelNode node = project(
                    List.of(projected(arrayOf(attr("x"), attr("y")), "coords")),
                    rel("Points"));
            assertPrettyPrints(node, "π [x, y] → coords (Points)");
        }
    }
}
