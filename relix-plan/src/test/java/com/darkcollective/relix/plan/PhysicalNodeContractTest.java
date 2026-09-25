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
package com.darkcollective.relix.plan;

import com.darkcollective.relix.plan.internal.PhysicalPlanJson;
import com.darkcollective.relix.plan.internal.PhysicalPlanPrinter;
import com.darkcollective.relix.json.JsonReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is true of every physical operator, asserted over every physical operator.
 *
 * <p>{@code PhysicalPlanJsonTest} and {@code PhysicalPlanPrinterTest} say what each
 * operator specifically renders, which is a different claim per operator and rightly
 * written per operator; they are untouched. This says only what does not vary — a node
 * renders, it names itself in JSON, the JSON parses, its schema and children answer —
 * and it says it over {@link PhysicalNodeCorpus}, which
 * {@code PhysicalNodeCorpusTest} holds to the {@code permits} clause. So a new operator
 * arrives with this much coverage on the day its corpus entry lands, rather than on the
 * day someone remembers to write it two suites over.
 *
 * <p>The JSON claims are the ones worth having here. {@code op} is what a consumer
 * switches on, so two operators sharing one discriminator is a rendering that cannot be
 * read back; and a detail arm that emits a name with no value — the failure a
 * hand-written {@code contains("\"op\"")} assertion sails straight past — makes the
 * document unparseable, which is why it is parsed rather than pattern-matched.
 */
@DisplayName("PhysicalNode — the contract every operator shares")
final class PhysicalNodeContractTest {

    /**
     * The corpus, each entry paired with its kind name — so a failure is reported
     * against "Aggregate" rather than against the record's whole {@code toString},
     * which for a join runs to several hundred characters of nested schema.
     */
    static List<Arguments> everyKind() {
        return PhysicalNodeCorpus.everyKind().stream()
                .map(node -> Arguments.of(node.getClass().getSimpleName(), node))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyKind")
    @DisplayName("renders through the explain printer")
    void rendersAnExplainLabel(String kind, PhysicalNode node) {
        assertThat(PhysicalPlanPrinter.explain(node))
                .as("%s has no --explain rendering", kind)
                .isNotBlank();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyKind")
    @DisplayName("writes JSON that parses, carrying its own kind as the discriminator")
    void writesParseableJsonNamingItsKind(String kind, PhysicalNode node) {
        String json = PhysicalPlanJson.toJson(node);

        Object parsed = JsonReader.parse(json);
        assertThat(parsed).as("%s renders a JSON object", kind)
                .isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) parsed).get("op"))
                .as("%s must name itself in the op discriminator", kind)
                .isEqualTo(kind);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyKind")
    @DisplayName("answers schema() and children(), and every child is a real node")
    void answersItsStructuralAccessors(String kind, PhysicalNode node) {
        assertThat(node.schema()).as("%s schema()", kind).isNotNull();
        assertThat(node.children()).as("%s children()", kind)
                .isNotNull().doesNotContainNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyKind")
    @DisplayName("advertises a delivered ordering, even if it is none")
    void advertisesADeliveredOrdering(String kind, PhysicalNode node) {
        assertThat(node.deliveredOrdering())
                .as("%s deliveredOrdering() — the merge planner reads this of every "
                        + "node, so returning null is a plan-time NPE rather than an "
                        + "unordered input", kind)
                .isNotNull();
    }
}
