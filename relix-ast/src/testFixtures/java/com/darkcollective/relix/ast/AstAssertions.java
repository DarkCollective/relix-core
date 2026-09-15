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
package com.darkcollective.relix.ast;

import org.assertj.core.api.Assertions;

/**
 * The entry point to relix's custom AssertJ assertions, at the AST layer.
 *
 * <p>It extends {@link Assertions}, so a test statically importing this class gets
 * AssertJ's own {@code assertThat} <em>and</em> the relix overloads from one import
 * and never has to reason about which one a call resolves to:
 *
 * {@snippet lang = "java":
 * import static com.darkcollective.relix.ast.AstAssertions.assertThat;
 *
 * assertThat(rewritten).isNode(SelectionNode.class).child(0).isRelation("Users");
 * assertThat(rewritten).isEquivalentTo(expected);
 * assertThat(names).containsExactly("id", "name");   // AssertJ's own, inherited
 * }
 *
 * <p>Each module further up the graph publishes its own entry point extending this
 * one — {@code SymbolAssertions}, {@code SemanticAssertions}, {@code PlanAssertions},
 * {@code ProcessorAssertions} — so a test imports the one for the layer it works at
 * and reaches every assert below it.  That chain is the reason these live in
 * published {@code testFixtures} source sets rather than in one test class: two
 * modules asserting on a {@link RelNode} must fail the same way.
 *
 * @see RelNodeAssert
 */
public class AstAssertions extends Assertions {

    /** Not instantiable directly; extend it, or import its members statically. */
    protected AstAssertions() {
    }

    /**
     * Begins an assertion on a logical expression tree.
     *
     * @param actual the tree under test; may be null (the assert reports it)
     * @return the assert
     */
    public static RelNodeAssert assertThat(RelNode actual) {
        return new RelNodeAssert(actual);
    }

    /**
     * Asserts that {@code node} pretty-prints to exactly {@code expected}.
     *
     * <p>Lives here rather than on {@code AstBuilders} because that class is now
     * {@code main} source, and a main-source authoring API must not carry a test
     * dependency.
     *
     * @param node     the tree to print
     * @param expected the exact expected rendering
     */
    public static void assertPrettyPrints(RelNode node, String expected) {
        Assertions.assertThat(node.prettyPrint()).isEqualTo(expected);
    }
}
