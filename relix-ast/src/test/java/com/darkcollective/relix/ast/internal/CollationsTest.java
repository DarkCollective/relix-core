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
package com.darkcollective.relix.ast.internal;

import com.darkcollective.relix.ast.CollateOperand;
import com.darkcollective.relix.ast.internal.Collations.Collation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.ast.AstBuilders.attr;
import static com.darkcollective.relix.ast.AstBuilders.collate;
import static com.darkcollective.relix.ast.AstBuilders.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What each collation name means (#262): an equality key and an order consistent with it. */
@DisplayName("Collations")
final class CollationsTest {

    private static Collation of(String name) {
        return Collations.resolve(name).orElseThrow();
    }

    @Test
    @DisplayName("BINARY compares by code point, astral characters included")
    void binary() {
        Collation binary = of("binary");
        assertThat(binary.key("Ada")).isEqualTo("Ada");
        assertThat(binary.compare("B", "a")).isNegative();
        // U+FF5E is below the emoji by code point and above its leading surrogate.
        assertThat(binary.compare("～", "😀")).isNegative();
        assertThat(binary.compare("a", "a")).isZero();
    }

    @Test
    @DisplayName("NOCASE keys and orders by the root-locale lower case")
    void nocase() {
        Collation nocase = of("NoCase");
        assertThat(nocase.key("ADA")).isEqualTo(nocase.key("ada"));
        assertThat(nocase.key("STRASSE")).isNotEqualTo(nocase.key("straße"));
        assertThat(nocase.compare("a", "B")).isNegative();
        assertThat(nocase.compare("ADA", "ada")).isZero();
    }

    @Test
    @DisplayName("a locale orders as its dictionary, and its key orders the same way")
    void locale() {
        Collation english = of("en-US");
        assertThat(english.compare("Émile", "Zed")).isNegative();
        assertThat(english.compare("apple", "Banana")).isNegative();
        assertThat(english.key("Émile").compareTo(english.key("Zed"))).isNegative();
        assertThat(english.key("a")).isEqualTo(english.key("a"));
        assertThat(Collations.resolve("en_US")).isPresent();
        assertThat(Collations.resolve("de")).isPresent();
    }

    @Test
    @DisplayName("a name that is no collation resolves to none")
    void unknown() {
        assertThat(Collations.resolve("Latin1_General_CI_AS")).isEmpty();
        assertThat(Collations.resolve("xx-ZZ")).isEmpty();
        assertThat(Collations.resolve("123")).isEmpty();
    }

    @Test
    @DisplayName("a comparison's collation is the one either side states, and a plain operand states none")
    void ofOperands() {
        assertThat(Collations.of(attr("s"), str("a"))).isEmpty();
        assertThat(Collations.of(collate(attr("s"), "NOCASE"), str("a"))).isPresent();
        assertThat(Collations.of(str("a"), collate(attr("s"), "NOCASE"))).isPresent();
        assertThat(Collations.strip(collate(attr("s"), "NOCASE"))).isEqualTo(attr("s"));
        assertThat(Collations.strip(attr("s"))).isEqualTo(attr("s"));
    }

    @Test
    @DisplayName("a CollateOperand upper-cases the two built-in names and keeps a locale as written")
    void operandNames() {
        assertThat(new CollateOperand(attr("s"), " nocase ").collation()).isEqualTo("NOCASE");
        assertThat(new CollateOperand(attr("s"), "en-US").collation()).isEqualTo("en-US");
        assertThat(new CollateOperand(attr("s"), "en-US").isLocale()).isTrue();
        assertThat(new CollateOperand(attr("s"), "BINARY").isLocale()).isFalse();
        assertThatThrownBy(() -> new CollateOperand(attr("s"), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
