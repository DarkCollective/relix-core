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
package com.darkcollective.relix.function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("Pushdown")
final class PushdownTest {

    @Nested
    @DisplayName("PushdownTarget")
    final class Targets {

        @Test
        @DisplayName("names a family and a dialect within it")
        void namesAFamilyAndDialect() {
            PushdownTarget postgres = PushdownTarget.sql("postgres");

            assertThat(postgres.family()).isEqualTo("sql");
            assertThat(postgres.variant()).isEqualTo("postgres");
            assertThat(postgres.isFamily("SQL")).isTrue();
            assertThat(postgres.isVariant("POSTGRES")).isTrue();
            assertThat(postgres.isVariant("mysql")).isFalse();
        }

        @Test
        @DisplayName("a family with one dialect has an empty variant, never a null one")
        void anAbsentVariantIsEmpty() {
            assertThat(PushdownTarget.mongo().variant()).isEmpty();
            assertThat(new PushdownTarget("mongo", null).variant()).isEmpty();
        }

        @Test
        @DisplayName("matches case-insensitively by normalising both parts")
        void normalisesCase() {
            assertThat(new PushdownTarget("SQL", "Postgres"))
                    .isEqualTo(PushdownTarget.sql("postgres"));
        }

        @Test
        @DisplayName("rejects a blank family — a target has to name something")
        void rejectsABlankFamily() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PushdownTarget(" ", "postgres"))
                    .withMessageContaining("family must not be blank");
        }
    }

    @Nested
    @DisplayName("PushdownSpelling")
    final class Spellings {

        @Test
        @DisplayName("NONE declines every backend")
        void noneDeclinesEverything() {
            assertThat(PushdownSpelling.NONE.render(PushdownTarget.sql("postgres"), List.of("x")))
                    .isEmpty();
            assertThat(PushdownSpelling.NONE.render(PushdownTarget.mongo(), List.of("$x")))
                    .isEmpty();
        }

        @Test
        @DisplayName("a spelling assembles already-rendered arguments")
        void assemblesRenderedArguments() {
            PushdownSpelling upper = (target, args) -> target.isFamily(PushdownTarget.SQL)
                    ? Optional.of("upper(" + args.get(0) + ")")
                    : Optional.empty();

            assertThat(upper.render(PushdownTarget.sql("postgres"), List.of("\"name\"")))
                    .contains("upper(\"name\")");
        }

        @Test
        @DisplayName("declines the backends it cannot write, and the engine keeps the call")
        void declinesWhatItCannotWrite() {
            PushdownSpelling sqlOnly = (target, args) -> target.isFamily(PushdownTarget.SQL)
                    ? Optional.of("upper(" + args.get(0) + ")")
                    : Optional.empty();

            assertThat(sqlOnly.render(PushdownTarget.mongo(), List.of("$name"))).isEmpty();
        }

        @Test
        @DisplayName("branches per dialect where the spelling differs")
        void branchesPerDialect() {
            PushdownSpelling truncate = (target, args) -> {
                if (!target.isFamily(PushdownTarget.SQL)) {
                    return Optional.empty();
                }
                return Optional.of(target.isVariant("postgres")
                        ? "date_trunc(" + args.get(0) + ", " + args.get(1) + ")"
                        : "date_format(" + args.get(1) + ", " + args.get(0) + ")");
            };

            List<String> args = List.of("'day'", "\"ts\"");
            assertThat(truncate.render(PushdownTarget.sql("postgres"), args))
                    .contains("date_trunc('day', \"ts\")");
            assertThat(truncate.render(PushdownTarget.sql("mysql"), args))
                    .contains("date_format(\"ts\", 'day')");
        }
    }
}
