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
package com.darkcollective.relix.parser;

import org.junit.jupiter.api.Test;
import com.darkcollective.relix.ast.*;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;


final class ParserBasicTest extends ParserTestSupport {

    @Test
    public void parsesSimpleRelation() {
        assertParsesTo("Users",
                rel("Users"));
    }

    @Test
    public void parsesRelationWithUnderscoreAndDigits() {
        assertParsesTo("Order_Items2",
                rel("Order_Items2"));
    }

    @Test
    public void parsesParenthesizedRelation() {
        assertParsesTo("(Users)",
                rel("Users"));
    }

    @Test
    public void parsesProjection() {
        assertParsesTo("π name (Users)",
                project(List.of(projected(attr("name"))), rel("Users")));
    }

    @Test
    public void parsesProjectionWithMultipleAttributes() {
        assertParsesTo("π id, name, age (Users)",
                project(List.of(projected(attr("id")), projected(attr("name")), projected(attr("age"))), rel("Users")));
    }

    @Test
    public void parsesRenameRelationOnly() {
        assertParsesTo("ρ U (Users)",
                rename("U", List.of(), rel("Users")));
    }

    @Test
    public void parsesRenameWithAttributes() {
        assertParsesTo("ρ U(id, fullName) (Users)",
                rename("U", List.of("id", "fullName"), rel("Users")));
    }

    @Test
    public void parsesRenameRelationOnlyWithParenthesizedExpressionInput() {
        assertParsesTo("ρ U (Users ∪ Orders)",
                rename("U", List.of(), union(rel("Users"), rel("Orders"))));
    }

    @Test
    public void parsesRenameWithAttributesAndComplexInput() {
        assertParsesTo("ρ U(id, name) (Users ⋈ Orders)",
                rename("U", List.of("id", "name"), naturalJoin(rel("Users"), rel("Orders"))));
    }

    @Test
    public void parsesRenamePairSingle() {
        assertParsesTo("ρ RoomsR (name → room_name) (rooms)",
                rename(java.util.Optional.of("RoomsR"), List.of(),
                        List.of(new RenameNode.RenamePair("name", "room_name")),
                        rel("rooms")));
    }

    @Test
    public void parsesRenamePairsMultiple() {
        assertParsesTo("ρ RoomsR (name → room_name, id → room_id) (rooms)",
                rename(java.util.Optional.of("RoomsR"), List.of(),
                        List.of(new RenameNode.RenamePair("name", "room_name"),
                        new RenameNode.RenamePair("id", "room_id")),
                        rel("rooms")));
    }

    @Test
    public void parsesRenamePairsWithoutRelationName() {
        assertParsesTo("ρ (name → room_name, id → room_id) (rooms)",
                rename(java.util.Optional.empty(), List.of(),
                        List.of(new RenameNode.RenamePair("name", "room_name"),
                        new RenameNode.RenamePair("id", "room_id")),
                        rel("rooms")));
    }

    @Test
    public void parsesRenamePairAsciiArrow() {
        assertParsesTo("RENAME RoomsR (name -> room_name) (rooms)",
                rename(java.util.Optional.of("RoomsR"), List.of(),
                        List.of(new RenameNode.RenamePair("name", "room_name")),
                        rel("rooms")));
    }

    @Test
    public void parsesRenamePairInlineInsideJoin() {
        // The #450 inline-rename idiom: a pair rename as a join's right input.
        assertParsesTo("devices ⨝ devices.room = RoomsR.room_id (ρ RoomsR (id → room_id) (rooms))",
                join(
                        rel("devices"),
                        rename(java.util.Optional.of("RoomsR"), List.of(),
                                List.of(new RenameNode.RenamePair("id", "room_id")),
                                rel("rooms")),
                        cmp(attr("devices.room"), ComparisonOperator.EQUAL, attr("RoomsR.room_id"))));
    }

    @Test
    public void parsesComplexExpression() {
        assertParsesTo("π name, total (σ total ≥ 100 (Users ⨝ Users.id = Orders.user_id Orders)) ∪ VIPs",
                union(project(List.of(projected(attr("name")), projected(attr("total"))), select(cmp(attr("total"), ComparisonOperator.GREATER_EQUAL, num("100")), join(rel("Users"), rel("Orders"), cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id"))))), rel("VIPs")));
    }
}
