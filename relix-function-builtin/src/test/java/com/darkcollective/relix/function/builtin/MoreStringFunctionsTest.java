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

import com.darkcollective.relix.function.PushdownTarget;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.function.builtin.BuiltinCalls.call;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.n;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.s;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The string functions of #137, each answering as PostgreSQL's does, NULL and empty arguments included. */
@DisplayName("LPAD, RPAD, REVERSE, REPEAT, SPLIT_PART, CONCAT_WS, INITCAP (#137)")
final class MoreStringFunctionsTest {

    private static final Value NULL = NullValue.INSTANCE;

    private static String t(String function, Value... args) {
        return text(call(function, args));
    }

    @Test
    @DisplayName("padding fills to the length, cuts a longer string, and an empty fill fills nothing")
    void padding() {
        assertThat(t("LPAD", s("hi"), n("5"))).isEqualTo("   hi");
        assertThat(t("LPAD", s("hi"), n("5"), s("xy"))).isEqualTo("xyxhi");
        assertThat(t("RPAD", s("hi"), n("6"), s("xy"))).isEqualTo("hixyxy");
        assertThat(t("LPAD", s("hello"), n("2"))).isEqualTo("he");
        assertThat(t("RPAD", s("hello"), n("2"))).isEqualTo("he");
        assertThat(t("LPAD", s("hi"), n("5"), s(""))).isEqualTo("hi");
        assertThat(t("LPAD", s("hi"), n("-1"))).isEmpty();
        assertThat(t("LPAD", s(""), n("2"), s("é"))).isEqualTo("éé");
        assertThat(call("LPAD", NULL, n("2")).isNull()).isTrue();
        assertThat(call("RPAD", s("a"), n("2"), NULL).isNull()).isTrue();
    }

    @Test
    @DisplayName("REVERSE keeps a character outside the basic plane whole; REPEAT of none is empty")
    void reverseAndRepeat() {
        assertThat(t("REVERSE", s("ab😀c"))).isEqualTo("c😀ba");
        assertThat(t("REVERSE", s(""))).isEmpty();
        assertThat(call("REVERSE", NULL).isNull()).isTrue();
        assertThat(t("REPEAT", s("ab"), n("0"))).isEmpty();
        assertThat(t("REPEAT", s("ab"), n("-3"))).isEmpty();
        assertThat(t("REPLICATE", s("-"), n("3"))).isEqualTo("---");
        assertThat(call("REPEAT", NULL, n("2")).isNull()).isTrue();
        assertThat(call("REPEAT", s("a"), NULL).isNull()).isTrue();
        assertThatThrownBy(() -> call("REPEAT", s("ab"), n("1000000000")))
                .hasMessageContaining("REPEAT: the result would be longer than");
    }

    @Test
    @DisplayName("SPLIT_PART counts fields from 1, or from the end, and refuses 0")
    void splitPart() {
        assertThat(t("SPLIT_PART", s("a,b,,d"), s(","), n("3"))).isEmpty();
        assertThat(t("SPLIT_PART", s("a,b,,d"), s(","), n("4"))).isEqualTo("d");
        assertThat(t("SPLIT_PART", s("a,b,,d"), s(","), n("5"))).isEmpty();
        assertThat(t("SPLIT_PART", s("a,b,,d"), s(","), n("-1"))).isEqualTo("d");
        assertThat(t("SPLIT_PART", s("a,b,,d"), s(","), n("-5"))).isEmpty();
        assertThat(t("SPLIT_PART", s("a::b"), s("::"), n("2"))).isEqualTo("b");
        assertThat(t("SPLIT_PART", s("abc"), s(""), n("1"))).isEqualTo("abc");
        assertThat(t("SPLIT_PART", s("abc"), s(""), n("2"))).isEmpty();
        assertThat(t("SPLIT_PART", s(""), s(","), n("1"))).isEmpty();
        assertThat(call("SPLIT_PART", NULL, s(","), n("1")).isNull()).isTrue();
        assertThatThrownBy(() -> call("SPLIT_PART", s("a"), s(","), n("0")))
                .hasMessage("SPLIT_PART: field position must not be zero");
    }

    @Test
    @DisplayName("CONCAT_WS skips a NULL value and its separator; a NULL separator is a NULL result")
    void concatWs() {
        assertThat(t("CONCAT_WS", s(", "), s("a"), NULL, s("b"))).isEqualTo("a, b");
        assertThat(t("CONCAT_WS", s(", "), NULL)).isEmpty();
        assertThat(t("CONCAT_WS", s(""), s("a"), n("2"))).isEqualTo("a2");
        assertThat(call("CONCAT_WS", NULL, s("a")).isNull()).isTrue();
    }

    @Test
    @DisplayName("INITCAP capitalises each run of letters and digits")
    void initcap() {
        assertThat(t("INITCAP", s("hi THOMAS"))).isEqualTo("Hi Thomas");
        assertThat(t("INITCAP", s("o'neil-SMITH 2nd"))).isEqualTo("O'Neil-Smith 2nd");
        assertThat(t("INITCAP", s(""))).isEmpty();
        assertThat(call("INITCAP", NULL).isNull()).isTrue();
    }

    @Test
    @DisplayName("each folds to PostgreSQL's function of its name; the case-mapping and text-writing ones nowhere")
    void spellings() {
        PushdownTarget postgres = new PushdownTarget("sql", "postgres");
        PushdownTarget mysql = new PushdownTarget("sql", "mysql");
        PushdownTarget sqlServer = new PushdownTarget("sql", "sqlserver");
        assertThat(spell("LPAD", postgres, "s", "5", "'x'")).contains("LPAD(s, 5, 'x')");
        assertThat(spell("RPAD", postgres, "s", "5")).contains("RPAD(s, 5)");
        assertThat(spell("LPAD", mysql, "s", "5")).isEmpty();
        assertThat(spell("REVERSE", mysql, "s")).contains("REVERSE(s)");
        assertThat(spell("REVERSE", sqlServer, "s")).isEmpty();
        assertThat(spell("REPEAT", new PushdownTarget("sql", "duckdb"), "s", "2")).contains("REPEAT(s, 2)");
        assertThat(spell("REPLICATE", sqlServer, "s", "2")).isEmpty();
        assertThat(spell("SPLIT_PART", postgres, "s", "','", "2")).contains("SPLIT_PART(s, ',', 2)");
        assertThat(spell("SPLIT_PART", mysql, "s", "','", "2")).isEmpty();
        assertThat(spell("CONCAT_WS", postgres, "','", "a")).isEmpty();
        assertThat(spell("INITCAP", postgres, "s")).isEmpty();
    }

    private static java.util.Optional<String> spell(String name, PushdownTarget target, String... args) {
        ScalarFunction fn = BuiltinCalls.all().stream().filter(f -> f.signature().name().equals(name))
                .findFirst().orElseThrow();
        return fn.pushdown().render(target, List.of(args));
    }
}
