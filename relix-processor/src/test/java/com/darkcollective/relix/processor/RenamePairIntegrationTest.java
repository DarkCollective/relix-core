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
package com.darkcollective.relix.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;

/**
 * End-to-end proof for the partial-rename pair form {@code ρ (old → new, …)}
 * (issue #453): rename only the listed columns and keep the rest, so a colliding
 * column can be disambiguated without restating every column of a wide relation.
 */
@DisplayName("Partial rename pairs ρ (old → new) (#453)")
final class RenamePairIntegrationTest extends ProcessorTestSupport {

    private static final QueryExecutor EXECUTOR = new QueryExecutor();

    private static List<QueryResult> run(String src) {
        var result = analyze(src);
        assertThat(result.errors()).as("semantic errors").isEmpty();
        return EXECUTOR.execute(result);
    }

    /** devices and rooms both have a `name` column — the collision the issue is about. */
    private static final String SETUP =
            "devices := [| device_id | room_id | name        |\n" +
            "            | 1          | 10       | Thermostat  |\n" +
            "            | 2          | 20       | Lamp        |];\n" +
            "rooms := [| room_id | name        |\n" +
            "          | 10       | LivingRoom  |\n" +
            "          | 20       | Bedroom     |];\n";

    @Test
    @DisplayName("renames just the colliding column and keeps the rest")
    void renamesOneColumnKeepsRest() {
        var results = run(SETUP +
                "Renamed := { ρ RoomsR (name → room_name) (rooms) };\n" +
                "query Renamed;");

        var rows = results.get(0).rows();
        assertThat(rows).hasSize(2);
        // room_id survives unchanged; name is now room_name.
        assertThat(rows.get(0)).hasValue("room_id", "10")
                .hasValue("room_name", "LivingRoom");
        assertThat(rows.get(1)).hasValue("room_name", "Bedroom");
    }

    @Test
    @DisplayName("the #450 inline idiom — a pair rename as a join input resolves the collision")
    void inlinePairRenameInsideJoin() {
        var results = run(SETUP +
                "Joined := { π name → device, room_name → room " +
                "(devices ⨝ devices.room_id = RoomsR.room_id " +
                "(ρ RoomsR (name → room_name) (rooms))) };\n" +
                "query Joined;");

        var rows = results.get(0).rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).hasValue("device", "Thermostat")
                .hasValue("room", "LivingRoom");
        assertThat(rows.get(1)).hasValue("device", "Lamp")
                .hasValue("room", "Bedroom");
    }

    @Test
    @DisplayName("pair rename with no relation name renames columns in place")
    void pairRenameNoRelationName() {
        var results = run(SETUP +
                "Renamed := { ρ (name → room_name) (rooms) };\n" +
                "query Renamed;");

        var rows = results.get(0).rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).hasValue("room_name", "LivingRoom")
                .hasValue("room_id", "10");
    }
}
