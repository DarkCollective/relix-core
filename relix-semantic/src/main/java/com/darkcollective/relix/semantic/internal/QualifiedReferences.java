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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.ast.internal.AttributeNames;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.symbol.Schema;

import java.util.List;

/**
 * Shared resolution of <em>relation-qualified</em> attribute references
 * ({@code rooms.name}) against a schema that carries source-relation
 * {@link com.darkcollective.relix.symbol.ColumnProvenance provenance}.
 *
 * <p>Both {@link PredicateValidator} (σ, join conditions, aggregate arguments) and
 * {@link RelAlgebraValidator} (π) resolve qualified references identically: a
 * qualifier must match exactly one source-relation column, else it is a
 * validation error — closing the silent-drop hole where a qualified reference
 * above a join used to strip its qualifier and bind to an unrelated first-match
 * column, returning wrong rows with no diagnostic.
 *
 * <p>A dotted name that resolves as a <em>path</em> into a nested column
 * ({@code location.city}, {@code skills.name}) is not an error either. That reading is
 * tried after the relation-qualified one, the order {@code Schema#resolvePath}
 * documents.
 *
 * <p>The check is a no-op (returns {@code null}) for a bare reference, an
 * {@linkplain Schema#isOpen() open}/schema-on-read input, or a schema that
 * carries no provenance — those keep the legacy qualifier-stripping semantics.
 * It also returns {@code null} when neither the qualified reference nor the bare
 * name resolves, so the caller's existing "attribute not found" diagnostic fires
 * instead (one error, not two).
 */
final class QualifiedReferences {

    private QualifiedReferences() {
    }

    /**
     * Returns a validation-error message (without any operator-context prefix) for
     * a qualified attribute that does not resolve by provenance, or {@code null}
     * when the reference is fine or the qualified check does not apply.
     *
     * @param attr   the attribute operand under validation
     * @param schema the schema the reference resolves against
     * @return an error message, or {@code null}
     */
    static String resolutionError(AttributeOperand attr, Schema schema) {
        String qualifier = AttributeNames.qualifierOf(attr.name());
        if (qualifier == null || schema.isOpen() || !schema.hasProvenance()) {
            return null;
        }
        String colName = attr.unqualifiedName();
        List<Integer> matches = schema.qualifiedIndices(qualifier, colName);
        if (matches.size() == 1) {
            return null;   // resolves precisely to its source-relation column
        }
        if (matches.size() > 1) {
            return "qualified attribute '" + attr.name() + "' is ambiguous — "
                    + matches.size() + " columns originate from '" + qualifier
                    + "' under the name '" + colName
                    + "' (rename one side with ρ to disambiguate)";
        }
        // The second reading of a dotted name: a path into a nested column. It is
        // spelled exactly like a qualified reference, and Schema#resolvePath is where
        // the two are told apart — the relation reading is tried above, this one next.
        //
        // Without this, a legitimate path was refused whenever its last segment also
        // named a column: `skills.name` over a heading of (name, location, skills) read
        // as "the column `name` coming from `skills`", found `name` present, and
        // reported a stale qualifier — while `location.city`, whose last segment names
        // no column, resolved. The two spellings are the same kind of reference, so a
        // rule that accepts one and refuses the other is not a rule about anything.
        if (schema.resolvePath(attr.name()).isPresent()) {
            return null;
        }
        // No provenance match. If the bare column exists, the qualifier is stale —
        // the relation is not a source of this column here. Never silently fall
        // back to an unqualified first-match — that is a silent wrong answer.
        if (schema.column(colName).isPresent()) {
            return "qualified attribute '" + attr.name()
                    + "' does not resolve — no column named '" + colName
                    + "' originates from relation '" + qualifier + "' at this point"
                    + Suggestions.qualifierHint(qualifier, schema);
        }
        // Neither the qualified ref nor the bare name resolves — let the caller's
        // plain missing-attribute diagnostic report it.
        return null;
    }
}
