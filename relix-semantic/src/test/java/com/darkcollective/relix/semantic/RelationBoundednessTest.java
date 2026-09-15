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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.cost.Boundedness;
import com.darkcollective.relix.cost.BoundednessSource;
import com.darkcollective.relix.cost.PropertyDeriver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.darkcollective.relix.ast.AstBuilders.limit;
import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Tests for {@link RelationBoundedness} — the by-<em>name</em> half of the seam.  What
 * each kind of relation answers is asserted through the catalog it feeds
 * ({@code CatalogBuilderTest}); this covers the resolution step in front of it, and the
 * fact that the result is a {@link BoundednessSource} an ordinary derivation can consume.
 */
@DisplayName("RelationBoundedness")
final class RelationBoundednessTest {

    private static final String SCRIPT = """
            Users  := [| id | name  |
                       | 1  | Alice |];
            Adults := { σ id > 0 (Users) };
            """;

    private static RelationBoundedness of(String src) {
        SemanticModel m = model(src);
        return new RelationBoundedness(m.symbolTable(), m.sources(), GeneratorCatalog.NONE);
    }

    @Test
    @DisplayName("resolves a declared relation by name, case-insensitively")
    void resolvesByName() {
        RelationBoundedness boundedness = of(SCRIPT);
        assertThat(boundedness.boundednessOf("Users")).isEqualTo(Boundedness.BOUNDED);
        assertThat(boundedness.boundednessOf("adults")).isEqualTo(Boundedness.BOUNDED);
    }

    /**
     * A name this analysis cannot see is {@code UNKNOWN}, where the planner's source reads
     * an unrecognised leaf as {@code BOUNDED}.  The two are answering different questions:
     * one is deciding whether to refuse a query, the other is stating a fact.
     */
    @Test
    @DisplayName("a name that resolves to nothing is unknown")
    void unresolvableNameIsUnknown() {
        assertThat(of(SCRIPT).boundednessOf("Nowhere")).isEqualTo(Boundedness.UNKNOWN);
    }

    @Test
    @DisplayName("a reserved-namespace relation resolves through its qualified name")
    void resolvesTheReservedNamespace() {
        assertThat(of(SCRIPT).boundednessOf("relix.relations"))
                .isEqualTo(Boundedness.BOUNDED);
    }

    /**
     * It is a {@link BoundednessSource}, so an expression nobody has named is derived with
     * the same walk a view's body is — which is what makes the catalog's answer and the
     * planner's the same derivation over different leaves.
     */
    @Test
    @DisplayName("derives an unnamed expression, as a BoundednessSource")
    void servesPropertyDeriverDirectly() {
        BoundednessSource source = of(SCRIPT);
        assertThat(PropertyDeriver.boundedness(rel("Nowhere"), source))
                .isEqualTo(Boundedness.UNKNOWN);
        assertThat(PropertyDeriver.boundedness(limit(3, rel("Nowhere")), source))
                .isEqualTo(Boundedness.BOUNDED);
    }

    @Test
    @DisplayName("rejects null collaborators and null names")
    void rejectsNulls() {
        SemanticModel m = model(SCRIPT);
        assertThatNullPointerException().isThrownBy(() ->
                new RelationBoundedness(null, m.sources(), GeneratorCatalog.NONE));
        assertThatNullPointerException().isThrownBy(() ->
                new RelationBoundedness(m.symbolTable(), null, GeneratorCatalog.NONE));
        assertThatNullPointerException().isThrownBy(() ->
                new RelationBoundedness(m.symbolTable(), Map.of(), null));
        assertThatNullPointerException().isThrownBy(() -> of(SCRIPT).boundednessOf(null));
        assertThatNullPointerException().isThrownBy(() -> of(SCRIPT).of(null));
    }
}
