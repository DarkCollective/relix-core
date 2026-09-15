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
package com.darkcollective.relix.symbol.function;

import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RelationFunctionSymbol}.
 */
final class RelationFunctionSymbolTest {

    @Test
    void builderSetsDefaults() {
        RelationFunctionSymbol fn = RelationFunctionSymbol.builder("ordersFor")
                .parameter("cid", ScalarType.NUMBER)
                .body(rel("Orders"))
                .build();
        assertThat(fn.declaredName()).isEqualTo("ordersFor");
        assertThat(fn.namespace()).isEqualTo("default");
        assertThat(fn.parameters()).hasSize(1);
        assertThat(fn.parameterSignature()).containsExactly(ScalarType.NUMBER);
        assertThat(fn.returnSchema()).isEmpty();
    }

    @Test
    void returnTypeIsAnyPlaceholderAndNoProperties() {
        RelationFunctionSymbol fn = RelationFunctionSymbol.builder("f")
                .body(rel("R")).build();
        // returnType() is the neutral placeholder — a relation function has no scalar type.
        assertThat(fn.returnType()).isEqualTo(ScalarType.ANY);
        assertThat(fn.properties()).isEmpty();
    }

    @Test
    void withReturnSchemaResolvesTheSchema() {
        Schema schema = new Schema(List.of(new ColumnDefinition("id", ScalarType.NUMBER)));
        RelationFunctionSymbol fn = RelationFunctionSymbol.builder("f")
                .body(rel("R")).build()
                .withReturnSchema(schema);
        assertThat(fn.returnSchema()).contains(schema);
    }

    @Test
    void canonicalNameIsLowerCased() {
        RelationFunctionSymbol fn = RelationFunctionSymbol.builder("OrdersFor")
                .body(rel("R")).build();
        assertThat(fn.canonicalName()).isEqualTo("ordersfor");
    }
}
