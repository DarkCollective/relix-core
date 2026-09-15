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

import com.darkcollective.relix.lang.ast.*;
import com.darkcollective.relix.lang.ast.source.HttpSourceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ScriptParser} covering namespace declarations, env statements,
 * import statements, and import edge cases.
 */
@DisplayName("ScriptParser — imports and namespace")
class ScriptParserImportTest {

    private static Script parse(String source) {
        return ScriptParser.parse(source);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Statement> T firstStatement(Script s) {
        return (T) s.statements().get(0);
    }

    // =========================================================================
    // Namespace declarations
    // =========================================================================

    @Nested
    @DisplayName("namespace declaration")
    class NamespaceTests {

        @Test
        @DisplayName("Stores namespace")
        void storesNamespace() {
            Script s = parse("namespace myorg;");
            assertThat(s.namespace()).isEqualTo(Optional.of("myorg"));
        }

        @Test
        @DisplayName("Namespace is optional")
        void namespaceIsOptional() {
            Script s = parse("query Users;");
            assertThat(s.namespace()).isEmpty();
        }
    }

    // =========================================================================
    // Env declarations
    // =========================================================================

    @Nested
    @DisplayName("env declaration")
    class EnvTests {

        @Test
        @DisplayName("env with both clauses")
        void envWithBothClauses() {
            Script s = parse("env from \".env.json\" using \"dev\";");
            EnvStatement e = firstStatement(s);
            assertThat(e.filePath()).isEqualTo(Optional.of(".env.json"));
            assertThat(e.activeEnv()).isEqualTo(Optional.of("dev"));
        }

        @Test
        @DisplayName("env with no clauses uses defaults")
        void envWithNoClauses() {
            Script s = parse("env;");
            EnvStatement e = firstStatement(s);
            assertThat(e.filePath()).isEmpty();
            assertThat(e.activeEnv()).isEmpty();
        }

        @Test
        @DisplayName("env with only from clause")
        void envWithOnlyFrom() {
            Script s = parse("env from \".env.json\";");
            EnvStatement e = firstStatement(s);
            assertThat(e.filePath()).isEqualTo(Optional.of(".env.json"));
            assertThat(e.activeEnv()).isEmpty();
        }

        @Test
        @DisplayName("env with only using clause")
        void envWithOnlyUsing() {
            Script s = parse("env using \"prod\";");
            EnvStatement e = firstStatement(s);
            assertThat(e.filePath()).isEmpty();
            assertThat(e.activeEnv()).isEqualTo(Optional.of("prod"));
        }
    }

    // =========================================================================
    // Import statements
    // =========================================================================

    @Nested
    @DisplayName("import statements")
    class ImportTests {

        @Test
        @DisplayName("Bulk import (file path only)")
        void bulkImport() {
            Script s = parse("import \"./lib.relix\";");
            ImportStatement imp = firstStatement(s);
            assertThat(imp.kind()).isEqualTo(ImportKind.BULK);
            assertThat(imp.names()).isEmpty();
            assertThat(imp.sourcePath()).isEqualTo("./lib.relix");
        }

        @Test
        @DisplayName("Source-qualified import")
        void sourceQualifiedImport() {
            Script s = parse("import source WeatherAPI from \"./weather.relix\";");
            ImportStatement imp = firstStatement(s);
            assertThat(imp.kind()).isEqualTo(ImportKind.SOURCE);
            assertThat(imp.names()).containsExactly("WeatherAPI");
        }

        @Test
        @DisplayName("Relation-qualified import")
        void relationQualifiedImport() {
            Script s = parse("import relation Users from \"./db.relix\";");
            ImportStatement imp = firstStatement(s);
            assertThat(imp.kind()).isEqualTo(ImportKind.RELATION);
        }

        @Test
        @DisplayName("Function-qualified import")
        void functionQualifiedImport() {
            Script s = parse("import function tax from \"./calc.relix\";");
            ImportStatement imp = firstStatement(s);
            assertThat(imp.kind()).isEqualTo(ImportKind.FUNCTION);
        }

        @Test
        @DisplayName("Unqualified single import")
        void unqualifiedSingleImport() {
            Script s = parse("import MyHelper from \"./shared.relix\";");
            ImportStatement imp = firstStatement(s);
            assertThat(imp.kind()).isEqualTo(ImportKind.UNQUALIFIED);
            assertThat(imp.names()).containsExactly("MyHelper");
        }

        @Test
        @DisplayName("Unqualified multi-name import")
        void unqualifiedMultiNameImport() {
            Script s = parse("import { Alpha, Beta, Gamma } from \"./shared.relix\";");
            ImportStatement imp = firstStatement(s);
            assertThat(imp.kind()).isEqualTo(ImportKind.UNQUALIFIED);
            assertThat(imp.names()).containsExactly("Alpha", "Beta", "Gamma");
        }

        @Test
        @DisplayName("Multi-name import with trailing comma")
        void multiNameImportTrailingComma() {
            Script s = parse("import { A, B, } from \"./x.relix\";");
            ImportStatement imp = firstStatement(s);
            assertThat(imp.names()).containsExactly("A", "B");
        }
    }

    // =========================================================================
    // Import edge cases
    // =========================================================================

    @Nested
    @DisplayName("import edge cases")
    class ImportEdgeCaseTests {

        @Test
        @DisplayName("Import with invalid token after 'import' throws")
        void importInvalidToken() {
            assertThatThrownBy(() -> parse("import ;"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("Import with keyword as name")
        void importWithKeywordAsName() {
            Script s = parse("import schema from \"./shared.relix\";");
            ImportStatement imp = firstStatement(s);
            assertThat(imp.kind()).isEqualTo(ImportKind.UNQUALIFIED);
            assertThat(imp.names()).containsExactly("schema");
        }

        @Test
        @DisplayName("Headers map without trailing comma")
        void headersMapWithoutTrailingComma() {
            Script s = parse("""
                    source S from http {
                        url: "https://x",
                        method: GET,
                        headers: { "Accept": "application/json" },
                        extract: json("$."),
                        schema: { id: out NUMBER }
                    };
                    """);
            HttpSourceConfig cfg = (HttpSourceConfig)
                    ((SourceDeclaration) firstStatement(s)).config();
            assertThat(cfg.headers()).containsKey("Accept");
        }
    }
}
