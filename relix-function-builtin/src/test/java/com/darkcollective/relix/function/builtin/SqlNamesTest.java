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
import java.util.Map;

import static com.darkcollective.relix.function.builtin.BuiltinCalls.call;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.n;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.number;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.s;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The SQL names of the built-ins answer as PostgreSQL's functions of those names do. */
@DisplayName("SQL names for the built-ins (#136)")
final class SqlNamesTest {

    private static final Value NULL = NullValue.INSTANCE;

    private static ScalarFunction fn(String name) {
        return BuiltinCalls.all().stream().filter(f -> f.signature().name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("an alias is its original under another name: page, parameters, result and spelling")
    void aliasesAreTheirOriginals() {
        for (Map.Entry<String, String> alias : SqlNames.aliases().entrySet()) {
            ScalarFunction a = fn(alias.getKey());
            ScalarFunction of = fn(alias.getValue());
            assertThat(a.signature().docKey()).as(alias.getKey()).isEqualTo(of.signature().docKey());
            assertThat(a.signature().returnType()).as(alias.getKey()).isEqualTo(of.signature().returnType());
            for (PushdownTarget target : List.of(new PushdownTarget("sql", "postgres"),
                    new PushdownTarget("sql", "mysql"), new PushdownTarget("mongo", ""))) {
                assertThat(a.pushdown().render(target, List.of("x")))
                        .as(alias.getKey() + " on " + target).isEqualTo(of.pushdown().render(target, List.of("x")));
            }
        }
        // LN is Log's one-argument form only.
        assertThat(fn("LN").signature().arity().max()).isEqualTo(1);
    }

    @Test
    @DisplayName("SUBSTRING counts a start before the first character, as PostgreSQL's does")
    void substring() {
        assertThat(text(call("SUBSTRING", s("hello"), n("2"), n("3")))).isEqualTo("ell");
        assertThat(text(call("SUBSTRING", s("hello"), n("0"), n("3")))).isEqualTo("he");
        assertThat(text(call("SUBSTRING", s("hello"), n("-5"), n("3")))).isEmpty();
        assertThat(text(call("SUBSTRING", s("hello"), n("9")))).isEmpty();
        assertThat(text(call("SUBSTRING", s("hello"), n("-1")))).isEqualTo("hello");
        assertThat(text(call("SUBSTR", s("héllo"), n("2"), n("2")))).isEqualTo("él");
        assertThat(call("SUBSTRING", NULL, n("1")).isNull()).isTrue();
        assertThat(call("SUBSTRING", s("x"), NULL).isNull()).isTrue();
        assertThat(call("SUBSTRING", s("x"), n("1"), NULL).isNull()).isTrue();
        assertThatThrownBy(() -> call("SUBSTRING", s("hello"), n("1"), n("-1")))
                .hasMessage("SUBSTRING: negative substring length not allowed");
    }

    @Test
    @DisplayName("STRPOS and CHARINDEX find the same position with their arguments either way round")
    void position() {
        assertThat(number(call("STRPOS", s("hello"), s("lo")))).isEqualByComparingTo("4");
        assertThat(number(call("STRPOS", s("hello"), s("z")))).isEqualByComparingTo("0");
        assertThat(number(call("STRPOS", s("hello"), s("")))).isEqualByComparingTo("1");
        assertThat(number(call("CHARINDEX", s("lo"), s("hello")))).isEqualByComparingTo("4");
        assertThat(call("STRPOS", NULL, s("a")).isNull()).isTrue();
        assertThat(call("CHARINDEX", s("a"), NULL).isNull()).isTrue();
        assertThat(call("STRPOS", s("a"), NULL).isNull()).isTrue();
    }

    @Test
    @DisplayName("ASCII is 0 for an empty string, and CONCAT skips a NULL")
    void asciiAndConcat() {
        assertThat(number(call("ASCII", s("A")))).isEqualByComparingTo("65");
        assertThat(number(call("ASCII", s("")))).isEqualByComparingTo("0");
        assertThat(call("ASCII", NULL).isNull()).isTrue();
        assertThat(text(call("CONCAT", s("a"), NULL, s("b")))).isEqualTo("ab");
        assertThat(text(call("CONCAT", NULL))).isEmpty();
        assertThat(text(call("CONCAT", s("#"), n("7")))).isEqualTo("#7");
    }

    @Test
    @DisplayName("TRUNC cuts towards zero, to places either side of the point")
    void trunc() {
        assertThat(number(call("TRUNC", n("-2.78")))).isEqualByComparingTo("-2");
        assertThat(number(call("TRUNC", n("2.78"), n("1")))).isEqualByComparingTo("2.7");
        assertThat(number(call("TRUNCATE", n("-1299"), n("-2")))).isEqualByComparingTo("-1200");
        assertThat(call("TRUNC", NULL).isNull()).isTrue();
        assertThat(call("TRUNC", n("1"), NULL).isNull()).isTrue();
    }

    @Test
    @DisplayName("each new function's spelling, per dialect")
    void spellings() {
        PushdownTarget postgres = new PushdownTarget("sql", "postgres");
        PushdownTarget mysql = new PushdownTarget("sql", "mysql");
        PushdownTarget duckdb = new PushdownTarget("sql", "duckdb");
        PushdownTarget sqlite = new PushdownTarget("sql", "sqlite");
        PushdownTarget mongo = new PushdownTarget("mongo", "");
        assertThat(fn("SUBSTRING").pushdown().render(postgres, List.of("s", "1", "2"))).contains("SUBSTRING(s, 1, 2)");
        assertThat(fn("SUBSTRING").pushdown().render(mysql, List.of("s", "1", "2"))).isEmpty();
        assertThat(fn("ASCII").pushdown().render(postgres, List.of("s"))).contains("ASCII(s)");
        assertThat(fn("TRUNC").pushdown().render(postgres, List.of("x", "1"))).contains("TRUNC(x, 1)");
        assertThat(fn("TRUNC").pushdown().render(duckdb, List.of("x"))).contains("TRUNC(x)");
        assertThat(fn("TRUNC").pushdown().render(mysql, List.of("x"))).contains("TRUNCATE(x, 0)");
        assertThat(fn("TRUNC").pushdown().render(mysql, List.of("x", "2"))).contains("TRUNCATE(x, 2)");
        assertThat(fn("TRUNC").pushdown().render(sqlite, List.of("x"))).isEmpty();
        assertThat(fn("TRUNC").pushdown().render(new PushdownTarget("sql", "db2"), List.of("x", "1")))
                .contains("TRUNC(x, 1)");
        assertThat(fn("TRUNC").pushdown().render(mongo, List.of("x"))).isEmpty();
        assertThat(fn("CONCAT").pushdown().render(postgres, List.of("a", "b"))).isEmpty();
    }
}
