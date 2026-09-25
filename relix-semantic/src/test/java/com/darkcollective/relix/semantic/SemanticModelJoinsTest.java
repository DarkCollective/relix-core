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

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.symbol.graph.EdgeOrigin;
import com.darkcollective.relix.symbol.graph.JoinResolution;
import com.darkcollective.relix.symbol.graph.Relationship;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/** The two join questions a {@link SemanticModel} answers about itself. */
@DisplayName("SemanticModel — demonstrated and resolved joins")
final class SemanticModelJoinsTest {

    private static final String DATA = """
            Users := [| id | name |
                      | 1  | Ada  |];
            Issues := [| id | assignee | reporter |
                       | 10 | 1        | 1        |];
            """;

    @Test
    @DisplayName("a join written in a view demonstrates its edge")
    void demonstrated() {
        SemanticModel m = model(DATA + "Assigned := { Issues ⨝ Issues.assignee = Users.id Users };");
        List<Relationship> edges = m.demonstratedRelationships();
        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.origin()).isEqualTo(EdgeOrigin.LEARNED);
            assertThat(e.source().columns()).containsExactly("assignee");
        });
    }

    @Test
    @DisplayName("an edge the graph already declares is not demonstrated again")
    void alreadyDeclared() {
        SemanticModel m = model(DATA + """
                relate "Assignee" Issues.assignee -> Users.id;
                Assigned := { Issues ⨝ Issues.assignee = Users.id Users };
                """);
        assertThat(m.demonstratedRelationships()).isEmpty();
    }

    @Test
    @DisplayName("a declared edge resolves a join written without its condition")
    void resolved() {
        SemanticModel m = model(DATA + "relate \"Assignee\" Issues.assignee -> Users.id;");
        JoinResolution r = m.resolveJoins(AstBuilders.naturalJoin(AstBuilders.rel("Issues"), AstBuilders.rel("Users")));
        assertThat(r).isInstanceOfSatisfying(JoinResolution.Resolved.class,
                resolved -> assertThat(resolved.program()).contains("Issues.assignee = Users.id"));
    }

    @Test
    @DisplayName("with no graph the expression stands")
    void passthrough() {
        SemanticModel m = model(DATA);
        assertThat(m.resolveJoins(AstBuilders.naturalJoin(AstBuilders.rel("Issues"), AstBuilders.rel("Users"))))
                .isInstanceOf(JoinResolution.Passthrough.class);
    }
}
