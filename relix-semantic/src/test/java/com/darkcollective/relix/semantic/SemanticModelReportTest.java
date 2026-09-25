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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/** {@link SemanticModel#ir()} and its focused form. */
@DisplayName("SemanticModel — the IR report")
final class SemanticModelReportTest {

    private static final String SCRIPT = """
            Orders := [| id | amount |
                       | 1  | 120    |];
            Users := [| id | name |
                      | 1  | Ada  |];
            Large := { σ amount > 100 (Orders) };
            query Large;
            """;

    @Test
    @DisplayName("reports every relation the script declares")
    void whole() {
        assertThat(model(SCRIPT).ir())
                .contains("RELIX IR")
                .contains("Orders")
                .contains("Users")
                .contains("Large");
    }

    @Test
    @DisplayName("a focused report leaves out the relations it was not asked for")
    void focused() {
        String report = model(SCRIPT).ir(List.of("large", "ORDERS"));
        assertThat(report).contains("Large").contains("Orders");
        assertThat(report.lines().filter(l -> l.startsWith(" Users"))).isEmpty();
    }
}
