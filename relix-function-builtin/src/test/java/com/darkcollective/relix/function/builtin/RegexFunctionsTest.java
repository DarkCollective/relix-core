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
import java.util.Optional;

import static com.darkcollective.relix.function.builtin.BuiltinCalls.call;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.n;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.number;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.s;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.text;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.truth;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** REGEXP_LIKE, REGEXP_REPLACE, REGEXP_SUBSTR and REGEXP_COUNT, and where each is pushed (#138). */
@DisplayName("Regular expressions (#138)")
final class RegexFunctionsTest {

    private static final Value NULL = NullValue.INSTANCE;

    @Test
    @DisplayName("REGEXP_LIKE finds a match anywhere, case-sensitively unless told i, and . matches a newline")
    void like() {
        assertThat(truth(call("REGEXP_LIKE", s("order 42"), s("[0-9]+")))).isTrue();
        assertThat(truth(call("REGEXP_LIKE", s("Abc"), s("^abc")))).isFalse();
        assertThat(truth(call("REGEXP_LIKE", s("Abc"), s("^abc"), s("i")))).isTrue();
        assertThat(truth(call("REGEXP_LIKE", s("Abc"), s("^abc"), s("ic")))).isFalse();
        assertThat(truth(call("REGEXP_LIKE", s("a\nb"), s("a.b")))).isTrue();
        assertThat(call("REGEXP_LIKE", NULL, s("a")).isNull()).isTrue();
        assertThat(call("REGEXP_LIKE", s("a"), NULL).isNull()).isTrue();
    }

    @Test
    @DisplayName("REGEXP_REPLACE replaces the first match, or every one with g, reading \\1 and \\& as PostgreSQL does")
    void replace() {
        assertThat(text(call("REGEXP_REPLACE", s("a1b2"), s("[0-9]"), s("#")))).isEqualTo("a#b2");
        assertThat(text(call("REGEXP_REPLACE", s("a1b2"), s("[0-9]"), s("#"), s("g")))).isEqualTo("a#b#");
        assertThat(text(call("REGEXP_REPLACE", s("John Smith"), s("(\\w+) (\\w+)"), s("\\2, \\1"))))
                .isEqualTo("Smith, John");
        assertThat(text(call("REGEXP_REPLACE", s("ab"), s("b"), s("[\\&]\\\\$1"))))
                .isEqualTo("a[b]\\$1");
        assertThat(text(call("REGEXP_REPLACE", s("ab"), s("(x)?b"), s("<\\1\\9>")))).isEqualTo("a<>");
        assertThat(text(call("REGEXP_REPLACE", s("ab"), s("b"), s("tail\\")))).isEqualTo("atail\\");
        assertThat(text(call("REGEXP_REPLACE", s("ABA"), s("a"), s("-"), s("gi")))).isEqualTo("-B-");
        assertThat(text(call("REGEXP_REPLACE", s("abc"), s("z"), s("-")))).isEqualTo("abc");
        assertThat(call("REGEXP_REPLACE", s("a"), s("a"), NULL).isNull()).isTrue();
    }

    @Test
    @DisplayName("REGEXP_SUBSTR gives the first match or NULL; REGEXP_COUNT counts them")
    void substrAndCount() {
        assertThat(text(call("REGEXP_SUBSTR", s("tel: 555-1234"), s("[0-9]+-[0-9]+")))).isEqualTo("555-1234");
        assertThat(call("REGEXP_SUBSTR", s("none"), s("[0-9]")).isNull()).isTrue();
        assertThat(call("REGEXP_SUBSTR", NULL, s("x")).isNull()).isTrue();
        assertThat(number(call("REGEXP_COUNT", s("banana"), s("an")))).isEqualByComparingTo("2");
        assertThat(number(call("REGEXP_COUNT", s("Banana"), s("b"), s("i")))).isEqualByComparingTo("1");
        assertThat(call("REGEXP_COUNT", NULL, s("x")).isNull()).isTrue();
    }

    @Test
    @DisplayName("REGEXP_REPLACE's positional form: a position from 1 and an occurrence, 0 for every match")
    void replacePositional() {
        assertThat(text(call("REGEXP_REPLACE", s("a-b-c"), s("-"), s("+")))).isEqualTo("a+b-c");
        assertThat(text(call("REGEXP_REPLACE", s("a-b-c"), s("-"), s("+"), n("1"), n("0")))).isEqualTo("a+b+c");
        assertThat(text(call("REGEXP_REPLACE", s("a-b-c"), s("-"), s("+"), n("1"), n("2")))).isEqualTo("a-b+c");
        assertThat(text(call("REGEXP_REPLACE", s("a-b-c"), s("-"), s("+"), n("1"), n("3")))).isEqualTo("a-b-c");
        // Omitted, the occurrence is MySQL's and Oracle's 0.
        assertThat(text(call("REGEXP_REPLACE", s("a-b-c"), s("-"), s("+"), n("1")))).isEqualTo("a+b+c");
        // The search starts at the position; the text before it is kept.
        assertThat(text(call("REGEXP_REPLACE", s("a-b-c"), s("-"), s("+"), n("3"), n("0")))).isEqualTo("a-b+c");
        assertThat(text(call("REGEXP_REPLACE", s("a-b-c"), s("-"), s("+"), n("3"), n("1")))).isEqualTo("a-b+c");
        assertThat(text(call("REGEXP_REPLACE", s("a-b-c"), s("-"), s("+"), n("9"), n("0")))).isEqualTo("a-b-c");
        // ^ is still the string's start, and a group still reads.
        assertThat(text(call("REGEXP_REPLACE", s("aaa"), s("^a"), s("X"), n("2"), n("0")))).isEqualTo("aaa");
        assertThat(text(call("REGEXP_REPLACE", s("ab ab"), s("(a)b"), s("<\\1>"), n("1"), n("2")))).isEqualTo("ab <a>");
        assertThat(text(call("REGEXP_REPLACE", s("A-a"), s("a"), s("x"), n("1"), n("0"), s("i")))).isEqualTo("x-x");
        assertThat(call("REGEXP_REPLACE", s("a"), s("a"), s("b"), NULL, n("0")).isNull()).isTrue();
    }

    @Test
    @DisplayName("REGEXP_SUBSTR's positional form: the nth match at or after a position")
    void substrPositional() {
        assertThat(text(call("REGEXP_SUBSTR", s("a1 b22 c333"), s("[0-9]+"), n("1"), n("2")))).isEqualTo("22");
        assertThat(text(call("REGEXP_SUBSTR", s("a1 b22 c333"), s("[0-9]+"), n("4")))).isEqualTo("22");
        assertThat(text(call("REGEXP_SUBSTR", s("a1 B22"), s("b[0-9]+"), n("1"), n("1"), s("i")))).isEqualTo("B22");
        assertThat(call("REGEXP_SUBSTR", s("a1 b22"), s("[0-9]+"), n("1"), n("3")).isNull()).isTrue();
    }

    @Test
    @DisplayName("the positional form refuses a position below 1, a negative occurrence, g, and flags in its place")
    void positionalRefusals() {
        assertThatThrownBy(() -> call("REGEXP_REPLACE", s("a"), s("a"), s("b"), n("0"), n("0")))
                .hasMessage("REGEXP_REPLACE: the position counts from 1, got 0");
        assertThatThrownBy(() -> call("REGEXP_REPLACE", s("a"), s("a"), s("b"), n("1"), n("-1")))
                .hasMessage("REGEXP_REPLACE: the occurrence is 0 for every match, or a match counted from 1, got -1");
        assertThatThrownBy(() -> call("REGEXP_SUBSTR", s("a"), s("a"), n("1"), n("0")))
                .hasMessage("REGEXP_SUBSTR: the occurrence is a match counted from 1, got 0");
        assertThatThrownBy(() -> call("REGEXP_REPLACE", s("a"), s("a"), s("b"), n("1"), n("0"), s("g")))
                .hasMessage("REGEXP_REPLACE: a call with a position takes no flag 'g'; an occurrence of 0"
                        + " replaces every match");
        assertThatThrownBy(() -> call("REGEXP_REPLACE", s("a"), s("a"), s("b"), s("i"), n("0")))
                .hasMessage("REGEXP_REPLACE: a call of 5 arguments takes a position as its fourth, a NUMBER,"
                        + " not STRING; its flags go last");
        assertThatThrownBy(() -> call("REGEXP_SUBSTR", s("a"), s("a"), s("i"), n("1")))
                .hasMessageContaining("takes a position as its third");
    }

    @Test
    @DisplayName("a bad pattern or flag is refused, naming it")
    void refusals() {
        assertThatThrownBy(() -> call("REGEXP_LIKE", s("a"), s("(")))
                .hasMessageStartingWith("REGEXP_LIKE: invalid regular expression '(':");
        assertThatThrownBy(() -> call("REGEXP_LIKE", s("a"), s("a"), s("x")))
                .hasMessage("REGEXP_LIKE: unknown flag 'x'");
        assertThatThrownBy(() -> call("REGEXP_SUBSTR", s("a"), s("a"), s("g")))
                .hasMessage("REGEXP_SUBSTR: unknown flag 'g'");
    }

    private static Optional<String> spell(String name, PushdownTarget target, String... args) {
        ScalarFunction fn = BuiltinCalls.all().stream().filter(f -> f.signature().name().equals(name))
                .findFirst().orElseThrow();
        return fn.pushdown().render(target, List.of(args));
    }

    private static final PushdownTarget POSTGRES = new PushdownTarget("sql", "postgres");
    private static final PushdownTarget MYSQL = new PushdownTarget("sql", "mysql");

    @Test
    @DisplayName("a portable pattern is PostgreSQL's ~ and MySQL's case-sensitive REGEXP_LIKE")
    void likeSpelling() {
        assertThat(spell("REGEXP_LIKE", POSTGRES, "s", "'^a[0-9]+'")).contains("(s ~ '^a[0-9]+')");
        assertThat(spell("REGEXP_LIKE", POSTGRES, "s", "'a'", "'i'")).contains("(s ~* 'a')");
        assertThat(spell("REGEXP_LIKE", MYSQL, "s", "'a.b'")).contains("REGEXP_LIKE(s, 'a.b', 'cn')");
        assertThat(spell("REGEXP_LIKE", MYSQL, "s", "'a'", "'ci'")).contains("REGEXP_LIKE(s, 'a', 'in')");
        assertThat(spell("REGEXP_LIKE", new PushdownTarget("sql", "sqlserver"), "s", "'a'")).isEmpty();
        assertThat(spell("REGEXP_LIKE", new PushdownTarget("sql", "duckdb"), "s", "'a'")).isEmpty();
        assertThat(spell("REGEXP_LIKE", new PushdownTarget("mongo", ""), "s", "'a'")).isEmpty();
        assertThat(spell("REGEXP_LIKE", POSTGRES, "s")).isEmpty();
        assertThat(spell("REGEXP_LIKE", POSTGRES, "s", "'a'", "'x'")).isEmpty();
        assertThat(spell("REGEXP_LIKE", POSTGRES, "s", "'a'", "flags")).isEmpty();
    }

    @Test
    @DisplayName("a pattern only Java reads one way stays in the engine")
    void unportablePatterns() {
        for (String p : List.of("'\\d+'", "'a$'", "'(?i)a'", "'[[:alpha:]]'", "'[a[b]]'", "'[a&&b]'",
                "'[ab'", "'a\\'", "'a['", "col", "'a'||'b'")) {
            assertThat(RegexFunctions.portable(p)).as(p).isFalse();
        }
        for (String p : List.of("'a\\.b'", "'[^]a]'", "'[]a]+'", "'[a&b]'", "'(ab|cd){2,3}'", "'^x?'")) {
            assertThat(RegexFunctions.portable(p)).as(p).isTrue();
        }
    }

    @Test
    @DisplayName("REGEXP_REPLACE folds on PostgreSQL alone, for a literal replacement and portable flags")
    void replaceSpelling() {
        assertThat(spell("REGEXP_REPLACE", POSTGRES, "s", "'b'", "'x'")).contains("regexp_replace(s, 'b', 'x')");
        assertThat(spell("REGEXP_REPLACE", POSTGRES, "s", "'b'", "'x'", "'g'"))
                .contains("regexp_replace(s, 'b', 'x', 'g')");
        assertThat(spell("REGEXP_REPLACE", POSTGRES, "s", "'b'", "r")).isEmpty();
        // The positional form runs in the engine: PostgreSQL has it only from 15.
        assertThat(spell("REGEXP_REPLACE", POSTGRES, "s", "'b'", "'x'", "1", "0")).isEmpty();
        assertThat(spell("REGEXP_REPLACE", POSTGRES, "s", "'b'", "'x'", "1")).isEmpty();
        assertThat(spell("REGEXP_REPLACE", POSTGRES, "s", "'b'", "'x'", "'q'")).isEmpty();
        assertThat(spell("REGEXP_REPLACE", POSTGRES, "s", "'\\d'", "'x'")).isEmpty();
        assertThat(spell("REGEXP_REPLACE", MYSQL, "s", "'b'", "'x'")).isEmpty();
        assertThat(spell("REGEXP_REPLACE", new PushdownTarget("mongo", ""), "s", "'b'", "'x'")).isEmpty();
        assertThat(spell("REGEXP_REPLACE", POSTGRES, "s", "'b'")).isEmpty();
        assertThat(spell("REGEXP_SUBSTR", POSTGRES, "s", "'b'")).isEmpty();
        assertThat(spell("REGEXP_COUNT", POSTGRES, "s", "'b'")).isEmpty();
    }
}
