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
package com.darkcollective.relix.lang;

import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.lang.ast.source.DatabaseConnectionConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ScriptParser} covering {@code connection} declarations.
 */
@DisplayName("ScriptParser — connection declarations")
class ScriptParserConnectionTest {

    private static ConnectionDeclaration parseConnection(String src) {
        Script s = ScriptParser.parse(src);
        return (ConnectionDeclaration) s.statements().get(0);
    }

    @Test
    @DisplayName("parses a full connection declaration")
    void parsesFullConnection() {
        ConnectionDeclaration c = parseConnection("""
                connection sales from database {
                    url:      "jdbc:postgresql://host/db",
                    user:     "sales_user",
                    password: "secret",
                    dialect:  postgres
                };
                """);

        assertThat(c.name()).isEqualTo("sales");
        assertThat(c.exported()).isTrue();
        DatabaseConnectionConfig cfg = c.config();
        assertThat(cfg.url()).isEqualTo("jdbc:postgresql://host/db");
        assertThat(cfg.user()).contains("sales_user");
        assertThat(cfg.password()).contains("secret");
        assertThat(cfg.dialect()).contains("postgres");
    }

    @Test
    @DisplayName("url is the only required field; user/password/dialect are optional")
    void parsesMinimalConnection() {
        ConnectionDeclaration c = parseConnection("""
                connection h2 from database { url: "jdbc:h2:mem:test" };
                """);

        assertThat(c.config().url()).isEqualTo("jdbc:h2:mem:test");
        assertThat(c.config().user()).isEmpty();
        assertThat(c.config().password()).isEmpty();
        assertThat(c.config().dialect()).isEmpty();
    }

    @Test
    @DisplayName("private connection is not exported")
    void privateConnection() {
        ConnectionDeclaration c = parseConnection("""
                private connection internal from database { url: "jdbc:h2:mem:x" };
                """);
        assertThat(c.exported()).isFalse();
    }

    @Test
    @DisplayName("an unknown config field is rejected")
    void rejectsUnknownField() {
        assertThatThrownBy(() -> parseConnection("""
                connection c from database { url: "u", bogus: "x" };
                """))
                .isInstanceOf(LangParseException.class)
                .hasMessageContaining("bogus");
    }

    @Test
    @DisplayName("a missing url is rejected")
    void rejectsMissingUrl() {
        assertThatThrownBy(() -> parseConnection("""
                connection c from database { user: "u" };
                """))
                .isInstanceOf(LangParseException.class)
                .hasMessageContaining("url");
    }

    @Test
    @DisplayName("a source can bind a table within a connection")
    void parsesConnectionTableBinding() {
        Script s = ScriptParser.parse("""
                connection sales from database { url: "jdbc:h2:mem:x" };
                source Orders from sales {
                    table:  "orders",
                    schema: { id: NUMBER, amount: NUMBER }
                };
                """);

        SourceDeclaration sd = (SourceDeclaration) s.statements().get(1);
        assertThat(sd.name()).isEqualTo("Orders");
        assertThat(sd.config()).isInstanceOf(ConnectionTableSourceConfig.class);
        ConnectionTableSourceConfig cfg = (ConnectionTableSourceConfig) sd.config();
        assertThat(cfg.connection()).isEqualTo("sales");
        assertThat(cfg.table()).isEqualTo("orders");
        assertThat(cfg.columns()).extracting(c -> c.name()).containsExactly("id", "amount");
    }

    @Test
    @DisplayName("'from database' yields the jdbc connector type")
    void databaseIsAliasForJdbc() {
        ConnectionDeclaration c = parseConnection("""
                connection h2 from database { url: "jdbc:h2:mem:x" };
                """);
        assertThat(c.connectorType()).isEqualTo("jdbc");
    }

    @Test
    @DisplayName("'from jdbc' is accepted explicitly")
    void parsesExplicitJdbcType() {
        ConnectionDeclaration c = parseConnection("""
                connection pg from jdbc { url: "jdbc:postgresql://host/db", dialect: postgres };
                """);
        assertThat(c.connectorType()).isEqualTo("jdbc");
        assertThat(c.config().url()).isEqualTo("jdbc:postgresql://host/db");
        assertThat(c.config().dialect()).contains("postgres");
    }

    @Test
    @DisplayName("a non-JDBC connector type carries its own properties")
    void parsesMongodbConnection() {
        ConnectionDeclaration c = parseConnection("""
                connection mg from mongodb {
                    uri:      "mongodb://localhost:27017",
                    database: "myapp"
                };
                """);
        assertThat(c.connectorType()).isEqualTo("mongodb");
        assertThat(c.properties()).containsEntry("uri", "mongodb://localhost:27017");
        assertThat(c.properties()).containsEntry("database", "myapp");
    }

    @Test
    @DisplayName("a non-JDBC connection accepts arbitrary config keys (no url required)")
    void nonJdbcConnectionAcceptsArbitraryKeys() {
        ConnectionDeclaration c = parseConnection("""
                connection api from http { file: "myapi.http", cache: "300" };
                """);
        assertThat(c.connectorType()).isEqualTo("http");
        assertThat(c.properties()).containsKeys("file", "cache");
    }

    @Test
    @DisplayName("a connection-table binding requires a table")
    void connectionTableRequiresTable() {
        assertThatThrownBy(() -> ScriptParser.parse("""
                source Orders from sales { schema: { id: NUMBER } };
                """))
                .isInstanceOf(LangParseException.class)
                .hasMessageContaining("table");
    }
}
