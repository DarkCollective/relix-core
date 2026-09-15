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
package com.darkcollective.relix.optimizer;

import com.darkcollective.relix.ast.SourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TransformationRecord")
final class TransformationRecordTest {

    private static final OptimizationCode CODE     = OptimizationCode.EXPR_001;
    private static final String           RELATION = "MyQuery";
    private static final String           DETAIL   = "2 * 3 folded to 6";
    private static final SourceLocation   LOCATION = SourceLocation.UNKNOWN;

    @Test
    @DisplayName("constructs successfully with valid arguments")
    void validConstruction() {
        var rec = new TransformationRecord(CODE, RELATION, DETAIL, LOCATION);

        assertThat(rec.code()).isEqualTo(CODE);
        assertThat(rec.relationName()).isEqualTo(RELATION);
        assertThat(rec.detail()).isEqualTo(DETAIL);
        assertThat(rec.location()).isEqualTo(LOCATION);
    }

    @Test
    @DisplayName("null code throws NullPointerException")
    void nullCodeThrows() {
        assertThatThrownBy(() -> new TransformationRecord(null, RELATION, DETAIL, LOCATION))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("code");
    }

    @Test
    @DisplayName("null relationName throws NullPointerException")
    void nullRelationNameThrows() {
        assertThatThrownBy(() -> new TransformationRecord(CODE, null, DETAIL, LOCATION))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("relationName");
    }

    @Test
    @DisplayName("blank relationName throws IllegalArgumentException")
    void blankRelationNameThrows() {
        assertThatThrownBy(() -> new TransformationRecord(CODE, "  ", DETAIL, LOCATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relationName");
    }

    @Test
    @DisplayName("null detail throws NullPointerException")
    void nullDetailThrows() {
        assertThatThrownBy(() -> new TransformationRecord(CODE, RELATION, null, LOCATION))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("detail");
    }

    @Test
    @DisplayName("blank detail throws IllegalArgumentException")
    void blankDetailThrows() {
        assertThatThrownBy(() -> new TransformationRecord(CODE, RELATION, "", LOCATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("detail");
    }

    @Test
    @DisplayName("null location throws NullPointerException")
    void nullLocationThrows() {
        assertThatThrownBy(() -> new TransformationRecord(CODE, RELATION, DETAIL, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("location");
    }

    @Test
    @DisplayName("toString contains code string, relation name, and detail")
    void toStringContainsKeyParts() {
        var rec = new TransformationRecord(CODE, RELATION, DETAIL, LOCATION);
        String s = rec.toString();

        assertThat(s).contains(CODE.code());
        assertThat(s).contains(RELATION);
        assertThat(s).contains(DETAIL);
    }

    @Test
    @DisplayName("two records with same data are equal")
    void equalityAndHash() {
        var r1 = new TransformationRecord(CODE, RELATION, DETAIL, LOCATION);
        var r2 = new TransformationRecord(CODE, RELATION, DETAIL, LOCATION);

        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
    }

    @Test
    @DisplayName("records with different codes are not equal")
    void differentCodesNotEqual() {
        var r1 = new TransformationRecord(OptimizationCode.EXPR_001, RELATION, DETAIL, LOCATION);
        var r2 = new TransformationRecord(OptimizationCode.EXPR_002, RELATION, DETAIL, LOCATION);

        assertThat(r1).isNotEqualTo(r2);
    }
}
