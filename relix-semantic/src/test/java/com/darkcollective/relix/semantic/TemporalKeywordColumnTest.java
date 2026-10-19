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
package com.darkcollective.relix.semantic;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;

/**
 * A column named {@code time} or {@code date} is read back as a column wherever no
 * quoted string follows the word, so the name a rename gives it can be used.
 */
final class TemporalKeywordColumnTest {

    private static final String DATA = """
            R := [| id | seconds | day |
                  | 1  | 1200    | 3   |];
            T := { π id, seconds → time, day → date (R) };
            """;

    @ParameterizedTest
    @ValueSource(strings = {
            "query { SELECT time IS NOT NULL (T) };",
            "query { SELECT time > 1000 (T) };",
            "query { PROJECT id, time (T) };",
            "query { SORT time (T) };",
            "query { SELECT date > 1 (T) };",
            "query { SELECT `time` > 1000 (T) };",
    })
    void aColumnNamedTimeOrDateIsReadAsAColumn(String query) {
        assertThat(analyze(DATA + query)).hasNoErrors();
    }
}
