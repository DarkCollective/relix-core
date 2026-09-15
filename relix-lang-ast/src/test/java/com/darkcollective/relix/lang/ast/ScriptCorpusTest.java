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

import com.darkcollective.relix.lang.ast.source.SourceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds {@link ScriptCorpus} to the sealed hierarchies it claims to cover, walking them
 * <strong>reflectively</strong> rather than maintaining a list here — so a new
 * {@code permits} entry fails the build until the corpus catches up, and with it the
 * print → parse gate that reads the corpus.
 *
 * <p>The same device {@code RelNodeCorpusTest} uses one level down, and for the same
 * reason: the compiler makes a {@code switch} over a sealed hierarchy exhaustive, but
 * nothing makes a <em>test</em> exhaustive, so a new statement kind would otherwise ship
 * with a printer arm no test ever asked to print.
 */
@DisplayName("ScriptCorpus covers every Statement and SourceConfig kind, once each")
final class ScriptCorpusTest {

    /** Every concrete leaf of a sealed hierarchy, flattening nested sealed interfaces. */
    private static List<Class<?>> concreteKinds(Class<?> root) {
        Class<?>[] permitted = root.getPermittedSubclasses();
        if (permitted == null) {
            return List.of(root);
        }
        List<Class<?>> leaves = new ArrayList<>();
        for (Class<?> child : permitted) {
            leaves.addAll(concreteKinds(child));
        }
        return leaves;
    }

    private static List<String> simpleNames(List<?> instances) {
        return instances.stream().map(i -> i.getClass().getSimpleName()).toList();
    }

    @Test
    @DisplayName("every Statement kind appears")
    void everyStatementKindAppears() {
        List<String> present = simpleNames(ScriptCorpus.everyStatement());
        List<String> missing = concreteKinds(Statement.class).stream()
                .map(Class::getSimpleName)
                .filter(kind -> !present.contains(kind))
                .sorted()
                .toList();

        assertThat(missing)
                .as("Statement kinds with no ScriptCorpus entry — add one, built through ScriptBuilders")
                .isEmpty();
    }

    @Test
    @DisplayName("every SourceConfig kind appears")
    void everySourceConfigKindAppears() {
        List<String> present = simpleNames(ScriptCorpus.everySourceConfig());
        List<String> missing = concreteKinds(SourceConfig.class).stream()
                .map(Class::getSimpleName)
                .filter(kind -> !present.contains(kind))
                .sorted()
                .toList();

        assertThat(missing)
                .as("SourceConfig kinds with no ScriptCorpus entry — add one")
                .isEmpty();
    }

    @Test
    @DisplayName("every AssignmentBody kind appears among the assignment forms")
    void everyAssignmentBodyAppears() {
        List<String> present = ScriptCorpus.everyAssignmentForm().stream()
                .map(s -> ((AssignmentStatement) s).body().getClass().getSimpleName())
                .toList();
        List<String> missing = concreteKinds(AssignmentBody.class).stream()
                .map(Class::getSimpleName)
                .filter(kind -> !present.contains(kind))
                .sorted()
                .toList();

        assertThat(missing).as("AssignmentBody kinds with no ScriptCorpus form").isEmpty();
    }

    @Test
    @DisplayName("every InlineTable kind appears among the assignment forms")
    void everyInlineTableAppears() {
        List<String> present = ScriptCorpus.everyAssignmentForm().stream()
                .map(s -> ((AssignmentStatement) s).body())
                .filter(InlineTableBody.class::isInstance)
                .map(b -> ((InlineTableBody) b).table().getClass().getSimpleName())
                .toList();
        List<String> missing = concreteKinds(
                com.darkcollective.relix.lang.ast.table.InlineTable.class).stream()
                .map(Class::getSimpleName)
                .filter(kind -> !present.contains(kind))
                .sorted()
                .toList();

        assertThat(missing).as("InlineTable kinds with no ScriptCorpus form").isEmpty();
    }

    @Test
    @DisplayName("every QueryTarget kind appears")
    void everyQueryTargetAppears() {
        // The corpus carries one QueryStatement, so the other target form needs naming
        // explicitly rather than being assumed covered.
        List<String> targets = List.of(
                new NamedQueryTarget("Open").getClass().getSimpleName(),
                new ExpressionQueryTarget(
                        ScriptBuilders.rel("Open")).getClass().getSimpleName());
        List<String> missing = concreteKinds(QueryTarget.class).stream()
                .map(Class::getSimpleName)
                .filter(kind -> !targets.contains(kind))
                .sorted()
                .toList();

        assertThat(missing).as("QueryTarget kinds not exercised").isEmpty();
    }

    @Test
    @DisplayName("no Statement kind appears twice")
    void noStatementKindAppearsTwice() {
        Map<String, Long> counts = simpleNames(ScriptCorpus.everyStatement()).stream()
                .collect(Collectors.groupingBy(k -> k, Collectors.counting()));

        assertThat(counts.entrySet().stream().filter(e -> e.getValue() > 1).toList())
                .as("a kind appearing twice makes the corpus a sample rather than a census")
                .isEmpty();
    }

    @Test
    @DisplayName("the corpora are exactly as large as their hierarchies")
    void corporaMatchHierarchySizes() {
        assertThat(ScriptCorpus.everyStatement())
                .hasSameSizeAs(concreteKinds(Statement.class));
        assertThat(ScriptCorpus.everySourceConfig())
                .hasSameSizeAs(concreteKinds(SourceConfig.class));
    }

    @Test
    @DisplayName("every entry is a record, so its components can be enumerated")
    void everyEntryIsARecord() {
        List<String> notRecords = ScriptCorpus.everyStatement().stream()
                .filter(s -> !s.getClass().isRecord())
                .map(s -> s.getClass().getSimpleName())
                .toList();

        assertThat(notRecords).isEmpty();
    }
}
