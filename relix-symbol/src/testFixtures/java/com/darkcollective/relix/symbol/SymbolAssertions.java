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
package com.darkcollective.relix.symbol;

import com.darkcollective.relix.ast.AstAssertions;

/**
 * The relix assertion entry point at the symbol layer — {@link AstAssertions} plus
 * {@link Schema}.
 *
 * <p>Import it statically instead of AssertJ's own {@code Assertions} and every
 * assert below this layer is reachable from the one import:
 *
 * {@snippet lang = "java":
 * import static com.darkcollective.relix.symbol.SymbolAssertions.assertThat;
 *
 * assertThat(schema).hasColumnNames("id", "name").hasColumn("addr.city", ScalarType.STRING);
 * assertThat(body).isRelation("Users");           // from AstAssertions
 * assertThat(width).isEqualTo(3);                 // from AssertJ
 * }
 *
 * @see SchemaAssert
 */
public class SymbolAssertions extends AstAssertions {

    /** Not instantiable directly; extend it, or import its members statically. */
    protected SymbolAssertions() {
    }

    /**
     * Begins an assertion on a relation heading.
     *
     * @param actual the schema under test; may be null (the assert reports it)
     * @return the assert
     */
    public static SchemaAssert assertThat(Schema actual) {
        return new SchemaAssert(actual);
    }
}
