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
package com.darkcollective.relix.ast;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Identifiers} — the delimited-identifier rendering that
 * lets a pretty-printed name round-trip when it collides with a reserved word.
 */
final class IdentifiersTest {

    @Nested
    class Render {

        @Test
        void ordinaryNameIsUnchanged() {
            assertThat(Identifiers.render("Users")).isEqualTo("Users");
            assertThat(Identifiers.render("customer_id")).isEqualTo("customer_id");
            assertThat(Identifiers.render("_private")).isEqualTo("_private");
        }

        @Test
        void reservedWordIsDelimited() {
            assertThat(Identifiers.render("order")).isEqualTo("`order`");
            assertThat(Identifiers.render("select")).isEqualTo("`select`");
        }

        @Test
        void reservedWordIsCaseInsensitive() {
            assertThat(Identifiers.render("Order")).isEqualTo("`Order`");
            assertThat(Identifiers.render("SELECT")).isEqualTo("`SELECT`");
        }

        @Test
        void nonIdentifierShapedIsDelimited() {
            assertThat(Identifiers.render("my order")).isEqualTo("`my order`");
            assertThat(Identifiers.render("1st")).isEqualTo("`1st`");
            assertThat(Identifiers.render("a-b")).isEqualTo("`a-b`");
        }

        @Test
        void literalBacktickIsDoubled() {
            assertThat(Identifiers.render("a`b")).isEqualTo("`a``b`");
        }

        @Test
        void emptyAndNullRenderAsEmptyDelimited() {
            assertThat(Identifiers.render("")).isEqualTo("``");
            assertThat(Identifiers.render(null)).isEqualTo("``");
        }

        @Test
        void qualifiedNameIsDelimitedPerSegment() {
            assertThat(Identifiers.render("Users.id")).isEqualTo("Users.id");
            assertThat(Identifiers.render("order.id")).isEqualTo("`order`.id");
            assertThat(Identifiers.render("Users.select")).isEqualTo("Users.`select`");
            assertThat(Identifiers.render("order.select")).isEqualTo("`order`.`select`");
        }
    }

    @Nested
    class Predicates {

        @Test
        void isReserved() {
            assertThat(Identifiers.isReserved("sort")).isTrue();
            assertThat(Identifiers.isReserved("SORT")).isTrue();
            assertThat(Identifiers.isReserved("Users")).isFalse();
            assertThat(Identifiers.isReserved(null)).isFalse();
        }

        @Test
        void needsDelimiting() {
            assertThat(Identifiers.needsDelimiting("group")).isTrue();
            assertThat(Identifiers.needsDelimiting("with space")).isTrue();
            assertThat(Identifiers.needsDelimiting("")).isTrue();
            assertThat(Identifiers.needsDelimiting(null)).isTrue();
            assertThat(Identifiers.needsDelimiting("Users")).isFalse();
        }
    }
}
