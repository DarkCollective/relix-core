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
package com.darkcollective.relix.lang.ast;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.lang.ast.source.ApiKeyAuth;
import com.darkcollective.relix.lang.ast.source.BearerAuth;
import com.darkcollective.relix.lang.ast.source.ColumnReference;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.lang.ast.source.DatabaseConnectionConfig;
import com.darkcollective.relix.lang.ast.source.GeneratorSourceConfig;
import com.darkcollective.relix.lang.ast.source.JsonFileSourceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Argument validation on the statement and source-config records of the language AST.
 *
 * <p>These are the records a frontend assembles a {@code Script} from, so their guards are
 * what a frontend other than the {@code .relix} grammar meets first: a hand-built AST does
 * not go through a parser that would have rejected a blank name earlier.
 */
@DisplayName("Language AST argument validation")
final class LangAstValidationTest {

    private static final RelNode BODY = new RelationNode("Users");

    @Nested
    @DisplayName("ConnectionDeclaration")
    final class Connections {

        @Test
        void rejectsBlankName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ConnectionDeclaration(true, " ", "jdbc",
                            Map.of(), SourceLocation.UNKNOWN))
                    .withMessageContaining("connection name must not be blank");
        }

        @Test
        void rejectsBlankConnectorType() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ConnectionDeclaration(true, "warehouse", "",
                            Map.of(), SourceLocation.UNKNOWN))
                    .withMessageContaining("connection type must not be blank");
        }

        @Test
        @DisplayName("The connector type is normalised to lower case")
        void lowercasesConnectorType() {
            var c = new ConnectionDeclaration(true, "warehouse", "JDBC",
                    Map.of(), SourceLocation.UNKNOWN);
            assertThat(c.connectorType()).isEqualTo("jdbc");
        }

        @Test
        @DisplayName("Projecting a non-JDBC connection to a JDBC config names the connection")
        void configWithoutUrlThrows() {
            var c = new ConnectionDeclaration(true, "docs", "mongo",
                    Map.of("database", "app"), SourceLocation.UNKNOWN);
            assertThatIllegalStateException()
                    .isThrownBy(c::config)
                    .withMessageContaining("docs")
                    .withMessageContaining("mongo")
                    .withMessageContaining("no 'url' property");
        }

        @Test
        @DisplayName("A JDBC connection round-trips through its typed config")
        void configRoundTrips() {
            var config = new DatabaseConnectionConfig("jdbc:postgresql://db/app",
                    Optional.of("app"), Optional.of("secret"), Optional.of("postgres"));
            assertThat(new ConnectionDeclaration("warehouse", config).config()).isEqualTo(config);
        }
    }

    @Nested
    @DisplayName("DefRelationStatement")
    final class Defs {

        @Test
        void rejectsBlankName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DefRelationStatement.of(" ", List.of(), BODY))
                    .withMessageContaining("name must not be blank");
        }

        @Test
        void rejectsNullName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DefRelationStatement.of(null, List.of(), BODY));
        }

        @Test
        void rejectsNullBody() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DefRelationStatement.of("Recent", List.of(), null));
        }
    }

    @Nested
    @DisplayName("Convenience constructors fill their defaults")
    final class Defaults {

        @Test
        @DisplayName("the location-less def statement defaults to UNKNOWN, keeping `exported`")
        void defRelationLocationDefaults() {
            var stmt = new DefRelationStatement(false, "ordersFor", List.of(), BODY);
            assertThat(stmt.location()).isEqualTo(SourceLocation.UNKNOWN);
            assertThat(stmt.exported()).isFalse();
            assertThat(stmt.name()).isEqualTo("ordersFor");
        }

        @Test
        @DisplayName("`of` is the exported case — the common one, which is why it has a name")
        void defRelationOfIsExported() {
            var stmt = DefRelationStatement.of("ordersFor", List.of(), BODY);
            assertThat(stmt.exported()).as("of() means exported").isTrue();
            assertThat(stmt.location()).isEqualTo(SourceLocation.UNKNOWN);
        }

        @Test
        @DisplayName("a JSON source with no declared references defaults to an empty list")
        void jsonFileReferencesDefault() {
            var config = new JsonFileSourceConfig("data.json", Optional.of("$.rows"));
            assertThat(config.references()).isEmpty();
            assertThat(config.records()).hasValue("$.rows");
            assertThat(config.path()).isEqualTo("data.json");
        }
    }

    @Nested
    @DisplayName("EndpointSpec")
    final class Endpoints {

        @Test
        void rejectsBlankRelationRef() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EndpointSpec.unbounded("", List.of("id"),
                            SourceLocation.UNKNOWN))
                    .withMessageContaining("relationRef must not be blank");
        }

        @Test
        @DisplayName("Rejects an empty column list — an endpoint with no columns joins nothing")
        void rejectsEmptyColumns() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EndpointSpec.unbounded("Orders", List.of(),
                            SourceLocation.UNKNOWN))
                    .withMessageContaining("columns must not be empty");
        }

        @Test
        void rejectsNegativeMinimum() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new EndpointSpec("Orders", List.of("id"), -1,
                            OptionalLong.empty(), SourceLocation.UNKNOWN))
                    .withMessageContaining("min must not be negative");
        }

        @Test
        @DisplayName("A zero minimum is legal — it is the unbounded default")
        void acceptsZeroMinimum() {
            var spec = EndpointSpec.unbounded("Orders", List.of("id"), SourceLocation.UNKNOWN);
            assertThat(spec.min()).isZero();
            assertThat(spec.max()).isEmpty();
        }
    }

    @Nested
    @DisplayName("RelateStatement")
    final class Relates {

        private static final EndpointSpec END =
                EndpointSpec.unbounded("Orders", List.of("id"), SourceLocation.UNKNOWN);

        @Test
        void rejectsBlankName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RelateStatement(" ", Optional.empty(), false,
                            END, END, SourceLocation.UNKNOWN))
                    .withMessageContaining("relationship name must not be blank");
        }

        @Test
        void rejectsNullInverseName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new RelateStatement("places", null, false,
                            END, END, SourceLocation.UNKNOWN));
        }
    }

    @Nested
    @DisplayName("Source configs")
    final class SourceConfigs {

        @Test
        void connectionTableRejectsBlankConnection() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ConnectionTableSourceConfig(" ", "orders",
                            List.of(), List.of()))
                    .withMessageContaining("connection name must not be blank");
        }

        @Test
        void connectionTableRejectsBlankTable() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ConnectionTableSourceConfig("warehouse", "",
                            List.of(), List.of()))
                    .withMessageContaining("table name must not be blank");
        }

        @Test
        void databaseConnectionRejectsBlankUrl() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DatabaseConnectionConfig("  ", Optional.empty(),
                            Optional.empty(), Optional.empty()))
                    .withMessageContaining("connection url must not be blank");
        }

        @Test
        void generatorRejectsBlankName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new GeneratorSourceConfig(" ", Map.of()))
                    .withMessageContaining("generator name must not be blank");
        }

        @Test
        void jsonFileRejectsBlankPath() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new JsonFileSourceConfig("", Optional.empty()))
                    .withMessageContaining("path must not be blank");
        }
    }

    @Nested
    @DisplayName("ColumnReference")
    final class ColumnReferences {

        @Test
        void rejectsEmptySourceColumns() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ColumnReference(List.of(), "Customers",
                            List.of("id"), SourceLocation.UNKNOWN))
                    .withMessageContaining("sourceColumns must not be empty");
        }

        @Test
        void rejectsBlankTargetRelation() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ColumnReference(List.of("customer_id"), " ",
                            List.of("id"), SourceLocation.UNKNOWN))
                    .withMessageContaining("targetRelation must not be blank");
        }

        @Test
        void rejectsEmptyTargetColumns() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ColumnReference(List.of("customer_id"), "Customers",
                            List.of(), SourceLocation.UNKNOWN))
                    .withMessageContaining("targetColumns must not be empty");
        }
    }

    @Nested
    @DisplayName("Auth specs")
    final class Auth {

        @Test
        void apiKeyRejectsBlankName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ApiKeyAuth(" ", "s3cret", ApiKeyAuth.ApiKeyLocation.HEADER))
                    .withMessageContaining("apikey name must not be blank");
        }

        @Test
        @DisplayName("An API key value may be empty — only the key's name identifies it")
        void apiKeyAcceptsEmptyValue() {
            assertThat(new ApiKeyAuth("X-Api-Key", "", ApiKeyAuth.ApiKeyLocation.HEADER).value()).isEmpty();
        }

        @Test
        void bearerRejectsBlankToken() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BearerAuth("   "))
                    .withMessageContaining("bearer token must not be blank");
        }
    }
}
