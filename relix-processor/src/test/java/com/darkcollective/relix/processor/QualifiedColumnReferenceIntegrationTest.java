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

import com.darkcollective.relix.processor.internal.QueryExecutor;
import com.darkcollective.relix.processor.internal.QueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;

/**
 * End-to-end proof for qualified column references above a join (issue #454).
 *
 * <p>Before the fix, {@code π rooms.name (devices ⨝ … rooms)} silently dropped
 * the {@code rooms} qualifier and returned <em>device</em> names — no error, no
 * warning, wrong rows.  These tests execute the exact scenario from the issue and
 * assert each qualified reference resolves to its own source relation.
 */
@DisplayName("Qualified column references above a join (#454)")
final class QualifiedColumnReferenceIntegrationTest extends ProcessorTestSupport {

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
    @DisplayName("π rooms.name resolves to the ROOM name, not the device name")
    void qualifiedRefResolvesToItsOwnSide() {
        var results = run(SETUP +
                "Joined := { π devices.name → device, rooms.name → room " +
                "(devices ⨝ devices.room_id = rooms.room_id rooms) };\n" +
                "query Joined;");

        var rows = results.get(0).rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).hasValue("device", "Thermostat")
                .hasValue("room", "LivingRoom");
        assertThat(rows.get(1)).hasValue("device", "Lamp")
                .hasValue("room", "Bedroom");
    }

    @Test
    @DisplayName("a qualified reference in σ above the join filters on the right side")
    void qualifiedRefInSelection() {
        var results = run(SETUP +
                "Living := { σ rooms.name = \"LivingRoom\" " +
                "(devices ⨝ devices.room_id = rooms.room_id rooms) };\n" +
                "query Living;");

        var rows = results.get(0).rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).hasValue("devices.name", "Thermostat");
    }

    /**
     * The same collision one level down: a nested column whose <em>field</em> shares a
     * name with a column of the other input. `nested.room_id` is a path into the left's
     * struct; read as a relation qualifier it becomes the bare `room_id`, which the
     * right also carries — so the condition compared the right's value with itself and
     * the join matched every pair.
     */
    private static final String NESTED_SETUP =
            "devices := [| device_id | room_id | name        |\n" +
            "            | 1          | 10       | Thermostat  |\n" +
            "            | 2          | 20       | Lamp        |];\n" +
            "rooms := [| room_id | name        |\n" +
            "          | 10       | LivingRoom  |\n" +
            "          | 20       | Bedroom     |];\n" +
            "tagged := { π name, {room_id: room_id} → nested (devices) };\n";

    @Test
    @DisplayName("a join condition on a nested field matches its pairs, not every pair")
    void joinOnANestedFieldStillJoins() {
        var results = run(NESTED_SETUP +
                "Joined := { π name → device, rooms.name → room " +
                "(tagged ⨝ nested.room_id = rooms.room_id rooms) };\n" +
                "query Joined;");

        var rows = results.get(0).rows();
        assertThat(rows).as("two devices, one room each — not the 2×2 product").hasSize(2);
        assertThat(rows.get(0)).hasValue("device", "Thermostat")
                .hasValue("room", "LivingRoom");
        assertThat(rows.get(1)).hasValue("device", "Lamp")
                .hasValue("room", "Bedroom");
    }

    @Test
    @DisplayName("the legacy _r physical name still resolves (backward compatibility)")
    void legacyUnderscoreRStillWorks() {
        var results = run(SETUP +
                "Joined := { π name → device, name_r → room " +
                "(devices ⨝ devices.room_id = rooms.room_id rooms) };\n" +
                "query Joined;");

        var rows = results.get(0).rows();
        assertThat(rows.get(0)).hasValue("device", "Thermostat")
                .hasValue("room", "LivingRoom");
    }
}
