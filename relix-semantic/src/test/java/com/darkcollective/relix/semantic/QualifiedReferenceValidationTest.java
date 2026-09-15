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

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier-1 validation for relation-qualified column references above a join
 * (issue #454): a qualified reference must resolve to exactly one source-relation
 * column, or be a validation error — never a silent unqualified first-match.
 */
@DisplayName("Qualified column reference validation (#454)")
final class QualifiedReferenceValidationTest {

    /** devices and rooms both expose a `name` column — the collision the issue is about. */
    private static final String SOURCES = """
            source devices from csv("devices.csv") {
                header: true,
                schema: { device_id: NUMBER, room_id: NUMBER, name: STRING }
            };
            source rooms from csv("rooms.csv") {
                header: true,
                schema: { room_id: NUMBER, name: STRING }
            };
            """;

    private static List<SemanticError> validate(String query) {
        return analyze(SOURCES + query).errors();
    }

    @Test
    @DisplayName("π rooms.name over a collision resolves precisely — no error")
    void projectionQualifiedResolves() {
        assertThat(validate(
                "Joined := { π devices.name → d, rooms.name → r "
                + "(devices ⨝ devices.room_id = rooms.room_id rooms) };\n"
                + "query Joined;"))
                .isEmpty();
    }

    @Test
    @DisplayName("σ on a qualified collided column resolves precisely — no error")
    void selectionQualifiedResolves() {
        assertThat(validate(
                "Living := { σ rooms.name = \"LivingRoom\" "
                + "(devices ⨝ devices.room_id = rooms.room_id rooms) };\n"
                + "query Living;"))
                .isEmpty();
    }

    @Test
    @DisplayName("a stale qualifier above a join is a validation error, not a silent first-match")
    void staleQualifierInProjectionErrors() {
        List<SemanticError> errors = validate(
                "Bad := { π kitchen.name "
                + "(devices ⨝ devices.room_id = rooms.room_id rooms) };\n"
                + "query Bad;");

        assertThat(errors).anySatisfy(e ->
                assertThat(e.message())
                        .contains("kitchen.name")
                        .contains("does not resolve"));
    }

    @Test
    @DisplayName("a stale qualifier in a selection is a validation error")
    void staleQualifierInSelectionErrors() {
        List<SemanticError> errors = validate(
                "Bad := { σ kitchen.name = \"x\" "
                + "(devices ⨝ devices.room_id = rooms.room_id rooms) };\n"
                + "query Bad;");

        assertThat(errors).anySatisfy(e ->
                assertThat(e.message()).contains("kitchen.name").contains("does not resolve"));
    }

    @Test
    @DisplayName("the stale-qualifier diagnostic lists the source relations in scope")
    void staleQualifierHintListsRelations() {
        List<SemanticError> errors = validate(
                "Bad := { π kitchen.name "
                + "(devices ⨝ devices.room_id = rooms.room_id rooms) };\n"
                + "query Bad;");

        assertThat(errors).anySatisfy(e ->
                assertThat(e.message()).contains("devices").contains("rooms"));
    }

    @Test
    @DisplayName("a qualified reference against a single base relation still resolves")
    void qualifiedRefOverBaseRelation() {
        assertThat(validate(
                "Adults := { σ devices.room_id > 0 (devices) };\n"
                + "query Adults;"))
                .isEmpty();
    }
}
