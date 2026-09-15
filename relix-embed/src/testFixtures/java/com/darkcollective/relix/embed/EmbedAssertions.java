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

import com.darkcollective.relix.processor.ProcessorAssertions;

/**
 * The relix assertion entry point at the facade layer — {@link ProcessorAssertions}
 * plus {@link Relation}, the one type the embedding API exists to hand a caller.
 *
 * <p>It completes the chain, so a test written against the facade needs one import to
 * reach every assert beneath it: {@code AstAssertions} ← {@code SymbolAssertions} ←
 * {@code SemanticAssertions} ← {@code PlanAssertions} ← {@code ProcessorAssertions} ←
 * this.
 *
 * {@snippet lang = "java":
 * import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
 *
 * assertThat(relix.relation("σ status = 'OPEN' (Orders)"))
 *         .hasRowCount(2)
 *         .schema().hasColumnNames("order_id", "status", "amount");
 *
 * assertThat(orders.optimized()).rewrote(OptimizationCode.SEL_002);
 * assertThat(plan).isNode(PhysicalNode.Select.class);   // from PlanAssertions
 * assertThat(names).containsExactly("id");              // from AssertJ
 * }
 *
 * <p><strong>These fixtures are not published.</strong> Exactly one coordinate ships
 * ({@code com.darkcollective.relix:relix}), and a second one carrying test support
 * would be the coupling that decision exists to prevent. This is for the suites in
 * this repository — the facade's own, and the two front ends built on it.
 *
 * @see RelationAssert
 */
public class EmbedAssertions extends ProcessorAssertions {

    /** Not instantiable directly; extend it, or import its members statically. */
    protected EmbedAssertions() {
    }

    /**
     * Begins an assertion on a relation.
     *
     * @param actual the relation under test; may be null (the assert reports it)
     * @return the assert
     */
    public static RelationAssert assertThat(Relation actual) {
        return new RelationAssert(actual);
    }
}
