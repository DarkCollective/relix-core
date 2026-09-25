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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;

@DisplayName("HTTP source request checks")
final class HttpSourceChecksTest {

    private static SemanticResult source(String fields) {
        return analyze("source Api from http { url: \"https://x\", " + fields + " };\nquery Api;");
    }

    @Nested
    @DisplayName("QUERY")
    class Query {

        @Test
        @DisplayName("a QUERY source with a body is valid")
        void withBody() {
            assertThat(source("method: QUERY, body: \"{}\"")).isFullyValid();
        }

        @Test
        @DisplayName("a QUERY source with no body is refused, naming the source")
        void withoutBody() {
            assertThat(source("method: QUERY"))
                    .hasErrorContaining("HTTP source 'Api' uses method QUERY but declares no 'body'");
        }

        @Test
        @DisplayName("QUERY is spelled case-insensitively, like every keyword")
        void lowerCase() {
            assertThat(source("method: query, body: \"{}\"")).isFullyValid();
        }
    }

    @Nested
    @DisplayName("a header set twice")
    class Twice {

        @Test
        @DisplayName("bearer auth beside a declared Authorization header")
        void bearerAndHeader() {
            assertThat(source("headers: { \"Authorization\": \"Token abc\" }, auth: bearer(\"xyz\")"))
                    .hasErrorContaining("HTTP source 'Api' sets header 'Authorization' twice, "
                            + "in headers and in auth: bearer(…)");
        }

        @Test
        @DisplayName("the comparison ignores case")
        void caseInsensitive() {
            assertThat(source("headers: { \"authorization\": \"Token abc\" }, auth: basic(\"a\", \"b\")"))
                    .hasErrorContaining("sets header 'Authorization' twice");
        }

        @Test
        @DisplayName("an apikey header of a declared header's name")
        void apiKey() {
            assertThat(source("headers: { \"X-Key\": \"a\" }, auth: apikey(\"x-key\", \"b\")"))
                    .hasErrorContaining("sets header 'x-key' twice, in headers and in auth: apikey(…)");
        }

        @Test
        @DisplayName("an apikey sent as a query parameter sets no header")
        void apiKeyInQuery() {
            assertThat(source("headers: { \"X-Key\": \"a\" }, auth: apikey(query(\"X-Key\"), \"b\")"))
                    .isFullyValid();
        }

        @Test
        @DisplayName("two spellings of one name in headers")
        void twoSpellings() {
            assertThat(source("headers: { \"Accept\": \"a\", \"accept\": \"b\" }"))
                    .hasErrorContaining("twice, in headers and in headers");
        }

        @Test
        @DisplayName("an IN column bound to a declared header")
        void inColumnHeader() {
            assertThat(source("""
                    headers: { "X-Units": "metric" },
                    schema: { units: in STRING as header("x-units") [default: "si"], t: out NUMBER }"""))
                    .hasErrorContaining("sets header 'x-units' twice, in headers and in column 'units'");
        }

        @Test
        @DisplayName("distinct headers are valid")
        void distinct() {
            assertThat(source("headers: { \"Accept\": \"application/json\" }, auth: bearer(\"t\")"))
                    .isFullyValid();
        }
    }

    @Nested
    @DisplayName("a header the client refuses")
    class Refused {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"Connection", "Content-Length", "Expect", "Host", "upgrade"})
        @DisplayName("a restricted name")
        void restricted(String name) {
            assertThat(source("headers: { \"" + name + "\": \"v\" }"))
                    .hasErrorContaining("HTTP source 'Api': header '" + name + "' cannot be set");
        }

        @Test
        @DisplayName("a restricted name as an apikey header")
        void restrictedApiKey() {
            assertThat(source("auth: apikey(\"Host\", \"v\")"))
                    .hasErrorContaining("header 'Host' cannot be set");
        }

        @Test
        @DisplayName("a name that is not an HTTP token")
        void invalidName() {
            assertThat(source("headers: { \"X Key\": \"v\" }"))
                    .hasErrorContaining("'X Key' is not a valid HTTP header name");
        }

        @Test
        @DisplayName("an empty name")
        void emptyName() {
            assertThat(HttpSourceChecks.isToken("")).isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"a", "z", "A", "Z", "0", "9", "!", "~", "X-Api_Key.v2"})
        @DisplayName("every character class of a token, at its edges")
        void tokens(String name) {
            assertThat(HttpSourceChecks.isToken(name)).isTrue();
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"{", "`a{", "@", "[", "/", ":", " ", "(", "é"})
        @DisplayName("a character just outside each class is not a token")
        void notTokens(String name) {
            assertThat(HttpSourceChecks.isToken(name)).isFalse();
        }

        @Test
        @DisplayName("a value holding a line break")
        void lineBreak() {
            assertThat(source("headers: { \"X-Key\": \"a\nb\" }"))
                    .hasErrorContaining("the value of header 'X-Key' contains a line break");
        }

        @Test
        @DisplayName("a value holding a carriage return")
        void carriageReturn() {
            assertThat(source("headers: { \"X-Key\": \"a\rb\" }"))
                    .hasErrorContaining("contains a line break");
        }
    }
}
