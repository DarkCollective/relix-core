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

/**
 * Base class for relix-ast tests.
 * All factory methods live in {@link AstBuilders} ({@code main} source since the
 * authoring surface was promoted), so they are shared with relix-parser tests and
 * with embedders.
 */
abstract class AstTestSupport extends AstBuilders {

    /**
     * Asserts that {@code node} pretty-prints to exactly {@code expected}.
     *
     * <p>Inherited by every test extending this class.  {@code AstBuilders} is
     * {@code main} source now, so it carries no test dependency and this delegates
     * to the AssertJ fixture that owns the assertion.
     */
    static void assertPrettyPrints(RelNode node, String expected) {
        AstAssertions.assertPrettyPrints(node, expected);
    }

}
