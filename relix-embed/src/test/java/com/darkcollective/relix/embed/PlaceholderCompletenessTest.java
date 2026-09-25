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

import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.lang.ast.source.ApiKeyAuth;
import com.darkcollective.relix.lang.ast.source.AuthSpec;
import com.darkcollective.relix.lang.ast.source.BasicAuth;
import com.darkcollective.relix.lang.ast.source.BearerAuth;
import com.darkcollective.relix.lang.ast.source.ColumnDirection;
import com.darkcollective.relix.lang.ast.source.ColumnReference;
import com.darkcollective.relix.lang.ast.source.ColumnSpec;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.lang.ast.source.CsvFileSourceConfig;
import com.darkcollective.relix.lang.ast.source.DatabaseSourceConfig;
import com.darkcollective.relix.lang.ast.source.ExtractPathBinding;
import com.darkcollective.relix.lang.ast.source.GeneratorSourceConfig;
import com.darkcollective.relix.lang.ast.source.HeaderBinding;
import com.darkcollective.relix.lang.ast.source.HttpMethod;
import com.darkcollective.relix.lang.ast.source.HttpSourceConfig;
import com.darkcollective.relix.lang.ast.source.JsonExtractSpec;
import com.darkcollective.relix.lang.ast.source.JsonFileSourceConfig;
import com.darkcollective.relix.lang.ast.source.PaginateEntry;
import com.darkcollective.relix.lang.ast.source.PaginateSpec;
import com.darkcollective.relix.lang.ast.source.PathParamBinding;
import com.darkcollective.relix.lang.ast.source.QueryParamBinding;
import com.darkcollective.relix.lang.ast.source.SourceConfig;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.connection;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every string in a declaration is either resolved or on a list of names, with a reason.
 *
 * <p>The guard the other completeness tests are: a new {@code SourceConfig} kind, or a
 * new string component on an existing one, fails here until it is either resolved or
 * listed. It works on one instance of each kind with <em>every</em> string component set
 * to a placeholder, which the first test checks, so a component the instance forgets to
 * populate is caught too.
 */
@DisplayName("placeholder resolution reaches every string value")
final class PlaceholderCompletenessTest {

    private static final String P = "${X}";

    /**
     * The string components that are deliberately <em>not</em> resolved. Each one is a
     * name the analyser has already read, or a path into the response, rather than a
     * value sent anywhere, and a placeholder there would change what analysis checked.
     */
    private static final Set<String> NAMES = Set.of(
            "HttpSourceConfig.headers<key>",           // a header's name, checked by analysis
            "JsonExtractSpec.jsonPath",                // where records sit in the response
            "PaginateEntry.logicalName",
            "PaginateEntry.paramName",
            "ColumnSpec.name",
            "QueryParamBinding.paramName",
            "HeaderBinding.headerName",                // checked by analysis, like headers<key>
            "PathParamBinding.paramName",
            "ExtractPathBinding.path",                 // a path into the response
            "ApiKeyAuth.name",                         // a header or parameter name
            "ColumnReference.sourceColumns",
            "ColumnReference.targetRelation",
            "ColumnReference.targetColumns",
            "JsonFileSourceConfig.records",            // a path into the document
            "ConnectionTableSourceConfig.connection",  // the name of a declaration
            "GeneratorSourceConfig.generatorName",
            "GeneratorSourceConfig.args<key>",
            "ConnectionDeclaration.properties<key>");

    private static final List<ColumnSpec> COLUMNS = List.of(
            new ColumnSpec(ColumnDirection.IN, P, ScalarType.STRING,
                    Optional.of(new QueryParamBinding(P)), false, Optional.of(P)),
            new ColumnSpec(ColumnDirection.IN, P, ScalarType.STRING,
                    Optional.of(new HeaderBinding(P)), false, Optional.of(P)),
            new ColumnSpec(ColumnDirection.IN, P, ScalarType.STRING,
                    Optional.of(new PathParamBinding(P)), false, Optional.of(P)),
            new ColumnSpec(ColumnDirection.OUT, P, ScalarType.STRING,
                    Optional.of(new ExtractPathBinding(P)), false, Optional.of(P)));

    private static final List<ColumnReference> REFERENCES = List.of(
            new ColumnReference(List.of(P), P, List.of(P), SourceLocation.UNKNOWN));

    private static List<SourceConfig> everyKind() {
        return List.of(
                http(new BearerAuth(P)),
                new DatabaseSourceConfig(P, P, COLUMNS, REFERENCES),
                new CsvFileSourceConfig(P, true, COLUMNS, REFERENCES),
                new JsonFileSourceConfig(P, Optional.of(P), REFERENCES),
                new ConnectionTableSourceConfig(P, P, COLUMNS, REFERENCES),
                new GeneratorSourceConfig(P, Map.of(P, P)));
    }

    private static HttpSourceConfig http(AuthSpec auth) {
        return new HttpSourceConfig(P, HttpMethod.POST, Map.of(P, P),
                Optional.of(new JsonExtractSpec(P)),
                Optional.of(new PaginateSpec(List.of(new PaginateEntry(P, P, Optional.of(1L))))),
                COLUMNS, Optional.of(P), Optional.of(auth));
    }

    private static final Placeholders RESOLVER = Placeholders.of(name -> Optional.of("v"));

    @Test
    @DisplayName("the instances cover every kind, and set every string to a placeholder")
    void instancesAreComplete() {
        Set<Class<?>> kinds = everyKind().stream().map(Object::getClass).collect(Collectors.toSet());
        assertThat(kinds).containsExactlyInAnyOrder(SourceConfig.class.getPermittedSubclasses());
        for (SourceConfig config : everyKind()) {
            assertThat(strings(config, "")).as(config.getClass().getSimpleName())
                    .allSatisfy((path, value) -> assertThat(value).as(path).isEqualTo(P));
        }
    }

    @Test
    @DisplayName("after resolution, only the listed names still hold a placeholder")
    void everyValueIsResolved() {
        Set<String> unresolved = new TreeSet<>();
        for (SourceConfig config : everyKind()) {
            SourceConfig resolved = RESOLVER.resolve(config, text -> text.replace(P, "v"));
            strings(resolved, "").forEach((path, value) -> {
                if (value.contains(P)) {
                    unresolved.add(path);
                }
            });
        }
        assertThat(unresolved).isSubsetOf(NAMES);
    }

    @Test
    @DisplayName("every kind of credential is resolved")
    void everyAuth() {
        for (AuthSpec auth : List.of(new BearerAuth(P), new BasicAuth(P, P),
                new ApiKeyAuth(P, P, ApiKeyAuth.ApiKeyLocation.HEADER))) {
            SourceConfig resolved = RESOLVER.resolve(http(auth), text -> text.replace(P, "v"));
            strings(resolved, "").forEach((path, value) -> {
                if (value.contains(P)) {
                    assertThat(NAMES).as(path).contains(path);
                }
            });
        }
        assertThat(AuthSpec.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(BearerAuth.class, BasicAuth.class, ApiKeyAuth.class);
    }

    @Test
    @DisplayName("a connection's property values are resolved and its keys are not")
    void connectionProperties() {
        var connection = connection("c", "jdbc", Map.of(P, P));
        var model = com.darkcollective.relix.semantic.SemanticFixtures.model(
                "connection c from jdbc { url: \"x\" };\nsource S from c { table: \"t\", schema: { a: NUMBER } };");
        var withConnection = new com.darkcollective.relix.semantic.SemanticModel(
                model.namespace(), model.symbolTable(), model.sources(), Map.of("c", connection),
                model.statistics(), model.nodeSchemas(), model.schemaGraph(), model.rootQueries(),
                model.functions());
        var resolved = RESOLVER.resolve(withConnection,
                com.darkcollective.relix.ast.AstBuilders.rel("S")).connections().get("c");
        assertThat(resolved.properties()).containsExactly(Map.entry(P, "v"));
    }

    /** Every string reachable from {@code value}, keyed by {@code Record.component} path. */
    private static Map<String, String> strings(Object value, String path) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        collect(value, path, out);
        return out;
    }

    private static void collect(Object value, String path, Map<String, String> out) {
        switch (value) {
            case null -> { }
            case String s -> out.merge(path, s, (a, b) -> a.equals(P) && b.equals(P) ? P : a + "|" + b);
            case Optional<?> o -> o.ifPresent(v -> collect(v, path, out));
            case Collection<?> c -> c.forEach(v -> collect(v, path, out));
            case Map<?, ?> m -> m.forEach((k, v) -> {
                collect(k, path + "<key>", out);
                collect(v, path, out);
            });
            case SourceLocation ignored -> { }   // where it was written, not what it says
            case Record r -> Arrays.stream(r.getClass().getRecordComponents()).forEach(c -> {
                try {
                    collect(c.getAccessor().invoke(r), r.getClass().getSimpleName() + "." + c.getName(), out);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            default -> { }   // enums, booleans, numbers, types, locations: not text
        }
    }
}
