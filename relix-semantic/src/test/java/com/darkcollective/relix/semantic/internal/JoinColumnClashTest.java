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
package com.darkcollective.relix.semantic.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;

/** A join that renames a column both inputs have to name_r says so (#201). */
@DisplayName("Join columns both inputs have")
final class JoinColumnClashTest {

    private static final String TABLES = """
            rooms := [
            | room_id | name   | floor |
            |---------|--------|-------|
            | 1       | Garage | 2     |
            ];
            devices := [
            | device_id | room_id | name            | status |
            |-----------|---------|-----------------|--------|
            | 10        | 1       | Garage Device 1 | online |
            ];
            """;

    private static SemanticResult query(String expression) {
        return analyze(TABLES + "query { " + expression + " };");
    }

    @Test
    @DisplayName("the issue's script warns once at the join, naming name and name_r")
    void issueScript() {
        assertThat(analyze(TABLES + """
                GarageRooms := { SELECT name = 'Garage' AND floor = 2 (rooms) };
                DevicesInGarages := { GarageRooms >< GarageRooms.room_id = devices.room_id devices };
                query { PROJECT device_id, room_id, name, status (DevicesInGarages) };
                """))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("both inputs have 'name': the result keeps GarageRooms.name as 'name' "
                        + "and calls devices.name 'name_r'. A later name reads GarageRooms.name; write "
                        + "name_r to read devices.name. To keep both under names of your own, rename "
                        + "one first: RENAME (name -> devices_name) (devices)");
    }

    @Test
    @DisplayName("the warning says what a later bare name reads, and names the right column's new name (#249)")
    void laterReadNamed() {
        String view = "J := { rooms >< rooms.room_id = devices.room_id devices };\n";
        assertThat(analyze(TABLES + view + "query { GROUP room_id, COLLECT(name) -> device_names (J) };"))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("A later name reads rooms.name; write name_r to read devices.name.");
        // The name it gives reads the right column through the view.
        assertThat(analyze(TABLES + view + "query { GROUP room_id, COLLECT(name_r) -> device_names (J) };"))
                .hasNoErrors()
                .hasDiagnosticCount(1);
        // An input with no name is described by its position.
        assertThat(query("rooms >< rooms.room_id = devices.room_id (devices × UNIT)"))
                .hasErrorContaining("A later name reads rooms.name; write name_r to read the right input's name.");
        assertThat(query("rooms ⨝ 1 = 1 rooms"))
                .hasErrorContaining("A later name reads the left input's name; write name_r to read the right"
                        + " input's name.");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "rooms ⨝ rooms.room_id = devices.room_id devices",
            "rooms LJOIN rooms.room_id = devices.room_id devices",
    })
    @DisplayName("an equated key keeps quiet where its copies are equal on every row")
    void equatedKeyQuiet(String expression) {
        assertThat(query(expression))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("both inputs have 'name'")
                .hasNoErrorContaining("'room_id'");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "rooms RJOIN rooms.room_id = devices.room_id devices",
            "rooms FJOIN rooms.room_id = devices.room_id devices",
    })
    @DisplayName("an equated key still warns where an unmatched right row has no left copy")
    void equatedKeyUnderRightOrFull(String expression) {
        assertThat(query(expression))
                .hasNoErrors()
                .hasDiagnosticCount(2)
                .hasErrorContaining("both inputs have 'room_id'")
                .hasErrorContaining("calls devices.room_id 'room_id_r'")
                .hasErrorContaining("both inputs have 'name'");
    }

    @Test
    @DisplayName("a key compared by other than equality is not quiet")
    void inequalityKeyWarns() {
        assertThat(query("rooms ⨝ rooms.room_id < devices.room_id devices"))
                .hasNoErrors()
                .hasDiagnosticCount(2)
                .hasErrorContaining("both inputs have 'room_id'");
    }

    @Test
    @DisplayName("a rename first leaves nothing to warn about")
    void renamedFirst() {
        assertThat(query("rooms ⨝ rooms.room_id = devices.room_id (RENAME (name -> devices_name) (devices))"))
                .isFullyValid();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "rooms ⋉ rooms.room_id < devices.room_id devices",
            "rooms ANTI rooms.room_id < devices.room_id devices",
            "rooms ⋈ devices",
    })
    @DisplayName("joins that keep one side's columns, or merge shared ones, say nothing")
    void otherJoinsQuiet(String expression) {
        assertThat(query(expression)).hasNoErrorContaining("both inputs have");
    }

    @Test
    @DisplayName("a self-join's inputs are described by their position, not their one name")
    void selfJoin() {
        assertThat(query("rooms ⨝ 1 = 1 rooms"))
                .hasNoErrors()
                .hasErrorContaining("the result keeps the left input's name as 'name' and calls the "
                        + "right input's name 'name_r'");
    }

    @Test
    @DisplayName("an unnamed input is described by its position")
    void unnamedRight() {
        assertThat(query("rooms ⨝ rooms.room_id = devices.room_id (devices × (PROJECT floor (rooms)))"))
                .hasNoErrors()
                .hasErrorContaining("both inputs have 'floor': the result keeps rooms.floor as 'floor' and "
                        + "calls the right input's floor 'floor_r'")
                .hasErrorContaining("RENAME (floor -> new_name) (…) around the right input");
    }
}
