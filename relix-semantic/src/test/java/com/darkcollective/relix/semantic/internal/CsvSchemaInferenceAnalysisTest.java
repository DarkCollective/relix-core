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

import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.ScriptBuilders;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.CsvFileSourceConfig;
import com.darkcollective.relix.semantic.CatalogProvider;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("A CSV source with no schema takes its heading from the catalog")
final class CsvSchemaInferenceAnalysisTest {

    private static final String SOURCE = "source Visits from csv(\"visits.csv\") { header: false,"
            + " columns: [day, hits] };\n";

    /** A catalog that describes every source it is asked about, recording what it was asked. */
    private static final class Describing implements CatalogProvider {
        final List<SourceDeclaration> asked = new ArrayList<>();

        @Override
        public Optional<Schema> tableSchema(ConnectionDeclaration connection, String table) {
            return Optional.empty();
        }

        @Override
        public Optional<Schema> sourceSchema(SourceDeclaration source) {
            asked.add(source);
            return Optional.of(new Schema(List.of(
                    new ColumnDefinition("day", ScalarType.DATE),
                    new ColumnDefinition("hits", ScalarType.NUMBER))));
        }
    }

    @Test
    @DisplayName("analyses against the heading the catalog reads, and type-checks with it")
    void readsTheCatalog() {
        Describing catalog = new Describing();
        assertThat(analyze(SOURCE + "query { σ hits > 3 (Visits) };", catalog)).hasNoErrors();
        assertThat(catalog.asked).singleElement().satisfies(source ->
                assertThat(((CsvFileSourceConfig) source.config()).names())
                        .containsExactly("day", "hits"));
        assertThat(analyze(SOURCE + "query { σ nosuch > 3 (Visits) };", new Describing()))
                .hasErrorContaining("'nosuch'");
    }

    @Test
    @DisplayName("a catalog that reads no files is reported for the source, not for its columns")
    void noReadingCatalog() {
        assertThat(analyze(SOURCE + "query { σ hits > 3 (Visits) };", CatalogProvider.NONE))
                .hasErrorContaining("Cannot infer the columns of CSV source 'Visits' (\"visits.csv\"):"
                        + " its schema is inferred from the file, and this analysis reads no files;"
                        + " declare its schema")
                .hasDiagnosticCount(1);
    }

    @Test
    @DisplayName("a catalog that cannot read the file says why, and that is what is reported")
    void unreadable() {
        CatalogProvider failing = new CatalogProvider() {
            @Override
            public Optional<Schema> tableSchema(ConnectionDeclaration connection, String table) {
                return Optional.empty();
            }

            @Override
            public Optional<Schema> sourceSchema(SourceDeclaration source) {
                throw new IllegalStateException("there is no file /data/visits.csv");
            }
        };
        assertThat(analyze(SOURCE + "query { σ hits > 3 (Visits) };", failing))
                .hasErrorContaining("Cannot infer the columns of CSV source 'Visits' (\"visits.csv\"):"
                        + " there is no file /data/visits.csv")
                .hasDiagnosticCount(1);
    }

    @Test
    @DisplayName("a declared schema asks the catalog nothing")
    void declared() {
        Describing catalog = new Describing();
        assertThat(analyze("source V from csv(\"v.csv\") { schema: { day: DATE } };\n"
                + "query { V };", catalog)).hasNoErrors();
        assertThat(catalog.asked).isEmpty();
        assertThat(CatalogProvider.NONE.sourceSchema(ScriptBuilders.source("V",
                ScriptBuilders.csvSourceInferred("v.csv", true, List.of())))).isEmpty();
    }
}
