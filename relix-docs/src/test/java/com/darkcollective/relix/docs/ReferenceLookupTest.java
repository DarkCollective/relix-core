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
package com.darkcollective.relix.docs;

import com.darkcollective.relix.function.AggregateFunction;
import com.darkcollective.relix.function.AggregateSignature;
import com.darkcollective.relix.function.Arity;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.function.FunctionLibrary;
import com.darkcollective.relix.function.FunctionSignature;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.function.StrictScalarFunction;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The reference and the installed functions' pages, as one index. */
@DisplayName("ReferenceLookup — language pages and function pages in one index")
final class ReferenceLookupTest {

    private static final ReferenceLookup INSTALLED = ReferenceLookup.of(FunctionCatalog.discover());

    /** A function the test library offers, documented or not. */
    private static ScalarFunction scalar(String name, String category, String docKey) {
        FunctionSignature signature = new FunctionSignature(name, List.of(), Arity.exactly(0),
                ScalarType.NUMBER, Set.of(), category, Optional.ofNullable(docKey));
        return proxy(StrictScalarFunction.class, signature);
    }

    private static AggregateFunction aggregate(String name, String docKey) {
        AggregateSignature signature = new AggregateSignature(name, List.of(), Arity.exactly(0),
                ScalarType.NUMBER, Set.of(), true, AggregateSignature.AGGREGATE,
                Optional.of(docKey));
        return proxy(AggregateFunction.class, signature);
    }

    /** Only {@code signature()} is asked of a function by the index. */
    private static <T> T proxy(Class<T> type, Object signature) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (self, method, args) -> switch (method.getName()) {
                    case "signature" -> signature;
                    case "toString" -> signature.toString();
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
    }

    /** A library of the test's own, serving the pages in {@code pages}. */
    private static FunctionLibrary library(List<ScalarFunction> scalars,
                                           List<AggregateFunction> aggregates,
                                           Map<String, String> pages) {
        return new FunctionLibrary() {
            @Override public String name() {
                return "test-docs";
            }

            @Override public List<ScalarFunction> scalarFunctions() {
                return scalars;
            }

            @Override public List<AggregateFunction> aggregateFunctions() {
                return aggregates;
            }

            @Override public Optional<String> documentation(String docKey) {
                return Optional.ofNullable(pages.get(docKey));
            }
        };
    }

    @Nested
    @DisplayName("a word finds a page")
    final class Lookup {

        @Test
        @DisplayName("by glyph, keyword or name, all to the same page")
        void glyphKeywordAndName() {
            String page = INSTALLED.lookup("σ").orElseThrow();

            assertThat(INSTALLED.lookup("select")).contains(page);
            assertThat(INSTALLED.lookup("selection")).contains(page);
            assertThat(INSTALLED.lookup("  SELECT ")).contains(page);
            assertThat(page).startsWith("# Name: Selection");
            assertThat(INSTALLED.entry("σ")).map(ReferencePage::path).contains("operators/select.md");
        }

        @Test
        @DisplayName("a keyword keeps a name it shares with a function, which stays a function")
        void keywordKeepsASharedName() {
            assertThat(INSTALLED.entry("fix")).map(ReferencePage::path).contains("advanced/fix.md");

            ReferencePage fix = INSTALLED.function("fix").orElseThrow();
            assertThat(fix.category()).isEqualTo(ReferenceLookup.FUNCTION_CATEGORY);
            assertThat(fix.title()).isEqualTo("Fix");
            assertThat(fix.summary()).isEqualTo("Truncate toward zero");
            assertThat(INSTALLED.page(fix).orElseThrow()).startsWith("# Name: Fix");
        }

        @Test
        @DisplayName("a function a keyword does not have is found by its name")
        void functionByName() {
            ReferencePage len = INSTALLED.entry("len").orElseThrow();

            assertThat(len.category()).isEqualTo(ReferenceLookup.FUNCTION_CATEGORY);
            assertThat(INSTALLED.lookup("LEN")).isEqualTo(INSTALLED.page(len));
            assertThat(INSTALLED.function("Len")).contains(len);
        }

        @Test
        @DisplayName("nothing is found by a word no page has, nor is a page not in the index read")
        void nothing() {
            assertThat(INSTALLED.lookup("no-such-thing")).isEmpty();
            assertThat(INSTALLED.entry("no-such-thing")).isEmpty();
            assertThat(INSTALLED.function("select")).isEmpty();
            assertThat(INSTALLED.page(new ReferencePage("x.md", "guide", "X", "x", "X", List.of())))
                    .isEmpty();
            assertThatThrownBy(() -> INSTALLED.lookup(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("an installed library's functions")
    final class Libraries {

        private final ReferenceLookup docs = ReferenceLookup.of(FunctionCatalog.of(library(
                List.of(scalar("Triple", "arithmetic", "triple.md"),
                        scalar("Plain", "arithmetic", "plain.md"),
                        scalar("Undocumented", "arithmetic", null),
                        scalar("Pageless", "arithmetic", "missing.md")),
                List.of(aggregate("Spread", "spread.md")),
                Map.of("triple.md", "# Name: Triple (three times a number)\n",
                        "plain.md", "A page with no heading.\n",
                        "spread.md", "# Name: Spread ()\n"))));

        @Test
        @DisplayName("appear by category, and are found by name, with no change to relix-docs")
        void indexed() {
            assertThat(docs.functionsByCategory()).containsOnlyKeys("arithmetic", "aggregate");
            assertThat(docs.functionsByCategory().get("arithmetic"))
                    .extracting(ReferencePage::title).containsExactly("Triple", "Plain");
            assertThat(docs.lookup("triple")).contains("# Name: Triple (three times a number)\n");
            assertThat(docs.inCategory(ReferenceLookup.FUNCTION_CATEGORY))
                    .extracting(ReferencePage::title).containsExactly("Triple", "Plain", "Spread");
        }

        @Test
        @DisplayName("take the summary from the page's heading, else the function's name")
        void summaries() {
            assertThat(docs.function("triple")).map(ReferencePage::summary)
                    .contains("Three times a number");
            assertThat(docs.function("plain")).map(ReferencePage::summary).contains("Plain");
            assertThat(docs.function("spread")).map(ReferencePage::summary).contains("Spread");
        }

        @Test
        @DisplayName("are not indexed without a page to show")
        void undocumented() {
            assertThat(docs.function("undocumented")).isEmpty();
            assertThat(docs.function("pageless")).isEmpty();
        }

        @Test
        @DisplayName("none, from an empty catalogue")
        void empty() {
            ReferenceLookup none = ReferenceLookup.of(FunctionCatalog.empty());

            assertThat(none.functionsByCategory()).isEmpty();
            assertThat(none.inCategory(ReferenceLookup.FUNCTION_CATEGORY)).isEmpty();
            assertThat(none.lookup("σ")).isPresent();
        }
    }

    @Nested
    @DisplayName("a caller's own pages")
    final class Extra {

        private final ReferencePage about = new ReferencePage("about.md", "guide", "About",
                "about", "What this tool is", List.of("about", "relix", "select"));

        private final ReferenceLookup docs = ReferenceLookup.of(FunctionCatalog.of(library(
                        List.of(scalar("About", "string", "fn-about.md")), List.of(),
                        Map.of("fn-about.md", "# Name: About (a function)\n"))),
                List.of(about),
                path -> path.equals("about.md") ? Optional.of("# About\n") : Optional.empty());

        @Test
        @DisplayName("are found by their keys, and read from the caller")
        void found() {
            assertThat(docs.entry("relix")).contains(about);
            assertThat(docs.lookup("about")).contains("# About\n");
            assertThat(docs.inCategory("guide")).endsWith(about);
        }

        @Test
        @DisplayName("cannot take a key from a language page, and a function cannot take one from them")
        void precedence() {
            assertThat(docs.entry("select")).map(ReferencePage::path).contains("operators/select.md");
            assertThat(docs.function("about")).map(ReferencePage::path).contains("fn-about.md");
        }

        @Test
        @DisplayName("are refused when null")
        void nulls() {
            assertThatThrownBy(() -> ReferenceLookup.of(FunctionCatalog.empty(), null, p -> Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ReferenceLookup.of(FunctionCatalog.empty(), List.of(), null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ReferenceLookup.of(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
