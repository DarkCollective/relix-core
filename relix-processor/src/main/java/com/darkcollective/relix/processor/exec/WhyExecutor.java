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
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.ast.WhyNode;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.processor.internal.DocumentRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.provenance.AnnotatedRelation;
import com.darkcollective.relix.processor.provenance.internal.BaseAnnotator;
import com.darkcollective.relix.processor.provenance.internal.ProvenanceEvaluator;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.provenance.Monomial;
import com.darkcollective.relix.provenance.Polynomial;
import com.darkcollective.relix.provenance.PolynomialSemiring;
import com.darkcollective.relix.provenance.ProvenanceVariable;
import com.darkcollective.relix.provenance.SourceRef;
import com.darkcollective.relix.symbol.Schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Executes the lineage-reification operator (WHY, ω — ADR-0018): evaluates its
 * input subtree as a why-provenance K-relation and emits every result tuple
 * unchanged plus the reserved {@code provenance} column holding that tuple's
 * lineage polynomial reified as a nested {@code ANY} document.
 *
 * <p>WHY is a <em>self-terminating reification boundary</em>: the
 * {@link PhysicalNode.Why#logicalInput() logical} input subtree is evaluated by the
 * {@link ProvenanceEvaluator} over the polynomial-lineage semiring {@code ℕ[X]}
 * (threading the semiring through the positive operators, inlining views, reading
 * non-positive operators — including a nested {@code WHY} — as opaque lifted bases),
 * exactly as the {@code --provenance --semiring lineage} side-channel does. Each
 * base-tuple occurrence becomes a distinct variable carrying its {@link SourceRef}
 * (producing relation, occurrence ordinal, captured columns).
 *
 * <h2>Reified document shape</h2>
 * <p>For each result tuple the appended {@code provenance} value is an array with one
 * element per {@link Monomial monomial} (distinct derivation):
 * <pre>{@code
 *   [ { coefficient: <N>,
 *       variables:   [ { relation: <S>, ordinal: <N>, columns: { <captured cols…> } }, … ],
 *       truncated:   <B> }, … ]
 * }</pre>
 * preserving <em>joint</em> (one monomial, several variables) vs <em>alternative</em>
 * (several monomials) derivations. The polynomial-level {@code truncated} flag — set
 * when the polynomial hit {@link PolynomialSemiring#MAX_MONOMIALS} and dropped terms —
 * rides every derivation element so it survives a {@code μ provenance} explode. From
 * there it is ordinary data: {@code μ} explodes the arrays and path navigation reads
 * the fields.
 *
 * <p>Blocking (the canonical K-relation is materialised before output) and never
 * pushed down.
 */
final class WhyExecutor {

    WhyExecutor(ChildDispatch dispatch) {
        // WHY does not run its input through the streaming child executor — it
        // evaluates the logical subtree via the ProvenanceEvaluator path — so the
        // child-dispatch seam is unused. Accepted for constructor uniformity.
    }

    Stream<Row> executeWhy(PhysicalNode.Why node, EvalCtx ctx) {
        Schema outputSchema = node.schema();

        // The evaluator is created lazily here (not as a field): it transitively
        // constructs a PhysicalExecutor — which constructs a WhyExecutor — so an eager
        // field would be an infinite construction cycle. ProvenanceEvaluator is
        // stateless and WHY is a rare, heavy mode, so per-call construction is fine.
        ProvenanceEvaluator evaluator = new ProvenanceEvaluator();

        // Evaluate the logical input as a why-provenance K-relation. Each base-tuple
        // occurrence is minted as a distinct variable carrying its captured columns,
        // so the reified lineage is machine-actionable back to specific source rows.
        BaseAnnotator<Polynomial> annotator =
                (source, ordinal, row) -> PolynomialSemiring.variable(
                        new SourceRef(source, ordinal, capturedColumns(row)));
        AnnotatedRelation<Polynomial> relation = evaluator.evaluate(
                node.logicalInput(), PolynomialSemiring.INSTANCE, ctx.executionContext(), annotator);

        List<Row> output = new ArrayList<>();
        relation.stream().forEach(annotated ->
                output.add(augment(annotated.row(), annotated.annotation(), outputSchema)));
        return BagRelation.of(outputSchema, output).stream();
    }

    /** Appends the reified {@code provenance} value to {@code row} under {@code outputSchema}. */
    private static Row augment(Row row, Polynomial polynomial, Schema outputSchema) {
        Value provenance = reify(polynomial);
        if (outputSchema.isOpen()) {
            // Carry the input document forward and add the provenance field.
            DocumentRow doc = row instanceof DocumentRow d ? d : asDocument(row);
            return doc.with(WhyNode.PROVENANCE_COLUMN, provenance);
        }
        List<Value> values = new ArrayList<>(outputSchema.width());
        for (int i = 0; i < row.width(); i++) {
            values.add(row.get(i));
        }
        values.add(provenance);
        return ArrayRow.of(outputSchema, values);
    }

    /** Converts a (closed) row into an open document, preserving its column values. */
    private static DocumentRow asDocument(Row row) {
        var fields = new java.util.LinkedHashMap<String, Value>();
        for (String name : row.columnNames()) {
            fields.put(name, row.get(name));
        }
        return new DocumentRow(new StructValue(fields));
    }

    /**
     * Reifies a lineage {@link Polynomial} as the nested {@code provenance} array —
     * one element per monomial, carrying its coefficient, contributing variables, and
     * the polynomial-level {@code truncated} flag.
     */
    private static Value reify(Polynomial polynomial) {
        boolean truncated = polynomial.truncated();
        List<Value> derivations = new ArrayList<>(polynomial.size());
        for (Map.Entry<Monomial, BigInteger> term : polynomial.terms().entrySet()) {
            derivations.add(derivation(term.getKey(), term.getValue(), truncated));
        }
        return new ArrayValue(derivations);
    }

    /** One {@code {coefficient, variables, truncated}} derivation document for a monomial. */
    private static StructValue derivation(Monomial monomial, BigInteger coefficient, boolean truncated) {
        List<Value> variables = new ArrayList<>(monomial.exponents().size());
        // One element per distinct contributing variable (base-tuple occurrence).
        for (ProvenanceVariable variable : monomial.exponents().keySet()) {
            variables.add(variableDocument(variable));
        }
        var fields = new java.util.LinkedHashMap<String, Value>();
        fields.put("coefficient", new NumberValue(new BigDecimal(coefficient)));
        fields.put("variables", new ArrayValue(variables));
        fields.put("truncated", BooleanValue.of(truncated));
        return new StructValue(fields);
    }

    /** A {@code {relation, ordinal, columns}} document for one provenance variable. */
    private static StructValue variableDocument(ProvenanceVariable variable) {
        var fields = new java.util.LinkedHashMap<String, Value>();
        if (variable.source().isPresent()) {
            SourceRef ref = variable.source().get();
            fields.put("relation", new StringValue(ref.source()));
            fields.put("ordinal", new NumberValue(BigDecimal.valueOf(ref.ordinal())));
            fields.put("columns", columnsDocument(ref));
        } else {
            // A bare variable (no structured source) — surface its label only.
            fields.put("relation", new StringValue(variable.name()));
            fields.put("ordinal", NullValue.INSTANCE);
            fields.put("columns", new StructValue(Map.of()));
        }
        return new StructValue(fields);
    }

    /** The captured base-tuple columns of {@code ref} as a nested document. */
    private static StructValue columnsDocument(SourceRef ref) {
        var fields = new java.util.LinkedHashMap<String, Value>();
        ref.columns().forEach((name, display) ->
                fields.put(name, display == null ? NullValue.INSTANCE : new StringValue(display)));
        return new StructValue(fields);
    }

    /**
     * {@return a base tuple's column values as a sorted {@code name → display-string}
     * map for {@link SourceRef}} A SQL-null column is captured as a {@code null} value
     * so nulls survive into the reified document.
     */
    private static java.util.SortedMap<String, String> capturedColumns(Row row) {
        var columns = new java.util.TreeMap<String, String>();
        for (String name : row.columnNames()) {
            Value v = row.get(name);
            columns.put(name, v.isNull() ? null : v.asDisplayString());
        }
        return columns;
    }
}
