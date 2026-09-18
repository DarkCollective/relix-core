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
package com.darkcollective.relix.embed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;

/**
 * A GEDCOM file read by a script, end to end.
 *
 * <p>The connector's own suite calls {@code open(...)} directly, which proves it parses a
 * file and shapes rows to a heading. It does not prove that a <em>script</em> can reach
 * it — that needs the type token to dispatch through the registry, the declared schema to
 * survive analysis, and the rows to come back through the executor. Those are the parts
 * that would fail silently: a connector nobody can name is discovered, compiled and
 * unreachable.
 */
@DisplayName("A GEDCOM connection, read by a script")
final class GedcomSessionTest {

    private static final String TREE = """
            0 HEAD
            0 @I1@ INDI
            1 NAME John /Smith/
            1 SEX M
            0 @I2@ INDI
            1 NAME Mary /Jones/
            1 SEX F
            0 @I3@ INDI
            1 NAME Alice /Smith/
            1 SEX F
            0 @F1@ FAM
            1 HUSB @I1@
            1 WIFE @I2@
            1 CHIL @I3@
            0 TRLR
            """;

    private static Relix session(Path dir) throws IOException {
        Path ged = dir.resolve("family.ged");
        Files.writeString(ged, TREE, StandardCharsets.UTF_8);

        Relix relix = Relix.builder().baseDirectory(dir).build();
        relix.define("""
                connection ancestry from gedcom { path: "%s" };

                source Individuals from ancestry { table: "individuals",
                    schema: { id: STRING, name: STRING, surname: STRING, sex: STRING } };

                source Families from ancestry { table: "families",
                    schema: { id: STRING, husband: STRING, wife: STRING,
                              children: [STRING] } };
                """.formatted(ged.toString().replace("\\", "\\\\")));
        return relix;
    }

    @Test
    @DisplayName("a declared table comes back with the heading the script asked for")
    void individualsResolveAndRun(@TempDir Path dir) throws IOException {
        try (Relix relix = session(dir)) {
            Relation individuals = relix.relation("Individuals");

            assertThat(individuals).hasRowCount(3);
            assertThat(individuals).tuples()
                    .extracting(t -> t.string("name"))
                    .containsExactly("John Smith", "Mary Jones", "Alice Smith");
            assertThat(individuals).schema()
                    .hasColumnNames("id", "name", "surname", "sex");
        }
    }

    @Test
    @DisplayName("a family's children unnest into the parent→child edge the operators take")
    void theEdgeIsAProjection(@TempDir Path dir) throws IOException {
        // A family is a hyperedge — two parents and n children — so the binary edge the
        // graph operators want is built by the query rather than by the connector.
        try (Relix relix = session(dir)) {
            Relation edges = relix.relation(
                    "π husband → parent, children → child (μ children (Families))");

            assertThat(edges).hasRowCount(1);
            assertThat(edges).tuples()
                    .extracting(t -> t.string("parent"), t -> t.string("child"))
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("I1", "I3"));
        }
    }

    @Test
    @DisplayName("a dotted reference resolves its columns from the connector, undeclared")
    void dottedReferenceIntrospects(@TempDir Path dir) throws IOException {
        // Nothing called RelixConnector.tableSchema before #1015, so only a jdbc connection
        // could be introspected and every other type had to have its heading written out.
        // GEDCOM's shape is fixed by the format, so the connector can simply say what it is.
        Path ged = dir.resolve("family.ged");
        Files.writeString(ged, TREE, StandardCharsets.UTF_8);

        try (Relix relix = Relix.builder().baseDirectory(dir).build()) {
            relix.define("connection ancestry from gedcom { path: \"%s\" };"
                    .formatted(ged.toString().replace("\\", "\\\\")));

            Relation individuals = relix.relation("ancestry.individuals");

            assertThat(individuals).schema()
                    .hasColumnNames("id", "name", "surname", "sex", "birth", "death");
            assertThat(individuals).hasRowCount(3);
            assertThat(individuals).tuples()
                    .extracting(t -> t.string("name"))
                    .containsExactly("John Smith", "Mary Jones", "Alice Smith");
        }
    }

    @Test
    @DisplayName("the introspected heading is typed, nested where the format nests")
    void introspectedHeadingIsTyped(@TempDir Path dir) throws IOException {
        Path ged = dir.resolve("family.ged");
        Files.writeString(ged, TREE, StandardCharsets.UTF_8);

        try (Relix relix = Relix.builder().baseDirectory(dir).build()) {
            relix.define("connection ancestry from gedcom { path: \"%s\" };"
                    .formatted(ged.toString().replace("\\", "\\\\")));

            // A declared ANY column would resolve a path at run time and type as ANY; this
            // is the typed heading, so a field the struct does not carry is an analysis
            // error rather than a NULL nobody asked about.
            assertThat(relix.relation("ancestry.individuals")).schema()
                    .hasColumn("birth.place", com.darkcollective.relix.symbol.ScalarType.STRING)
                    .hasColumn("birth.date", com.darkcollective.relix.symbol.ScalarType.DATE);
            assertThat(relix.relation("ancestry.families")).schema()
                    .hasColumnNames("id", "husband", "wife", "children");
        }
    }
}
