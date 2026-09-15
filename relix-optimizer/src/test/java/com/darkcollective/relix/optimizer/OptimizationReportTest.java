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

import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OptimizationReport")
final class OptimizationReportTest {

    private static final RelNode NODE = rel("R");

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static TransformationRecord rec(OptimizationCode code, String detail) {
        return new TransformationRecord(code, "Q", detail, SourceLocation.UNKNOWN);
    }

    private static OptimizationResult result(String name, RelNode original,
                                             RelNode optimized,
                                             List<TransformationRecord> applied) {
        return new OptimizationResult(name, original, optimized, applied);
    }

    private static OptimizationResult result(String name,
                                             List<TransformationRecord> applied) {
        return new OptimizationResult(name, NODE, NODE, applied);
    }

    private static ProjectedAttribute simple(String col) {
        return ProjectedAttribute.simple(attr(col));
    }

    private static Schema schema(String col) {
        return new Schema(List.of(new ColumnDefinition(col, ScalarType.NUMBER)));
    }

    // =========================================================================
    // Constants
    // =========================================================================

    @Test
    @DisplayName("WIDTH constant is 80")
    void widthIs80() {
        assertThat(OptimizationReport.WIDTH).isEqualTo(80);
    }

    // =========================================================================
    // Null guards
    // =========================================================================

    @Nested
    @DisplayName("Null guards")
    class NullGuards {

        @Test
        @DisplayName("null namespace throws NullPointerException")
        void nullNamespaceThrows() {
            assertThatThrownBy(() -> OptimizationReport.generate((String) null, List.of()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null results throws NullPointerException")
        void nullResultsThrows() {
            assertThatThrownBy(() -> OptimizationReport.generate("demo", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null SemanticModel throws NullPointerException")
        void nullModelThrows() {
            assertThatThrownBy(() -> OptimizationReport.generate((SemanticModel) null, List.of()))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // SemanticModel convenience overload
    // =========================================================================

    @Nested
    @DisplayName("generate(SemanticModel, results) — convenience overload")
    class ModelOverload {

        @Test
        @DisplayName("extracts namespace from SemanticModel")
        void extractsNamespaceFromModel() {
            var model = new SemanticModel("analytics", new InMemorySymbolTable(),
                    Map.of(), SchemaAnnotations.empty(), List.of());
            String report = OptimizationReport.generate(model, List.of());
            assertThat(report).contains("analytics");
        }

        @Test
        @DisplayName("produces the same output as generate(namespace, results)")
        void sameOutputAsNamespaceVariant() {
            var model = new SemanticModel("myns", new InMemorySymbolTable(),
                    Map.of(), SchemaAnnotations.empty(), List.of());
            var results = List.of(result("Q",
                    List.of(rec(OptimizationCode.EXPR_001, "folded constant"))));

            assertThat(OptimizationReport.generate(model, results))
                    .isEqualTo(OptimizationReport.generate("myns", results));
        }

        @Test
        @DisplayName("end-to-end: SemanticModel with QR body and LIM-001")
        void endToEndWithSemanticModel() {
            var base  = rel("R");
            var proj  = project(List.of(simple("a")), base);
            var body  = limit(10, proj);
            var qr    = QueryRelationSymbol.of("V", schema("a"), body);
            var table = new InMemorySymbolTable();
            table.register(qr);
            var model = new SemanticModel("default", table, Map.of(),
                    SchemaAnnotations.empty(),
                    List.of(query("V")));

            var results = new QueryOptimizer().optimize(model);
            String report = OptimizationReport.generate(model, results);

            assertThat(report)
                    .contains("LIM-001")
                    .contains("before:")
                    .contains("after:");
        }
    }

    // =========================================================================
    // Empty / no-op report
    // =========================================================================

    @Nested
    @DisplayName("Empty / no-op report")
    class EmptyReport {

        @Test
        @DisplayName("produces a non-empty string when no results supplied")
        void noResultsProducesOutput() {
            String report = OptimizationReport.generate("demo", List.of());
            assertThat(report).isNotBlank();
        }

        @Test
        @DisplayName("contains namespace in header")
        void containsNamespace() {
            String report = OptimizationReport.generate("analytics", List.of());
            assertThat(report).contains("analytics");
        }

        @Test
        @DisplayName("indicates no optimizations when all results have empty applied lists")
        void noOptimizationsMessage() {
            var results = List.of(result("Q1", List.of()), result("Q2", List.of()));
            String report = OptimizationReport.generate("demo", results);
            assertThat(report).contains("no optimizations applied");
        }

        @Test
        @DisplayName("no TREES section when no optimizations were applied")
        void noTreesSectionWhenNoOptimizations() {
            var results = List.of(result("Q1", List.of()));
            String report = OptimizationReport.generate("demo", results);
            assertThat(report).doesNotContain("TREES");
        }

        @Test
        @DisplayName("transformations=0 in header when nothing was applied")
        void headerShowsZeroTransformations() {
            String report = OptimizationReport.generate("demo", List.of());
            assertThat(report).contains("transformations=0");
        }
    }

    // =========================================================================
    // Non-empty report — SUMMARY section
    // =========================================================================

    @Nested
    @DisplayName("SUMMARY section")
    class SummarySection {

        @Test
        @DisplayName("contains SUMMARY section header")
        void containsSummaryHeader() {
            var results = List.of(result("Q1",
                    List.of(rec(OptimizationCode.EXPR_001, "folded 2*3 to 6"))));
            String report = OptimizationReport.generate("demo", results);
            assertThat(report).contains("SUMMARY");
        }

        @Test
        @DisplayName("summary shows code and count for each fired rule")
        void summaryShowsCodeAndCount() {
            var results = List.of(result("Q1", List.of(
                    rec(OptimizationCode.EXPR_001, "a"),
                    rec(OptimizationCode.EXPR_001, "b"),
                    rec(OptimizationCode.SEL_001,  "c"))));
            String report = OptimizationReport.generate("demo", results);

            assertThat(report).contains("EXPR-001");
            assertThat(report).contains("×2");
            assertThat(report).contains("SEL-001");
            assertThat(report).contains("×1");
        }

        @Test
        @DisplayName("total count in header matches sum of all transformations")
        void headerShowsTotalCount() {
            var results = List.of(
                    result("Q1", List.of(
                            rec(OptimizationCode.EXPR_001, "a"),
                            rec(OptimizationCode.EXPR_002, "b"))),
                    result("Q2", List.of(
                            rec(OptimizationCode.SEL_001, "c"))));
            String report = OptimizationReport.generate("demo", results);
            assertThat(report).contains("transformations=3");
        }

        @Test
        @DisplayName("codes sorted alphabetically in summary")
        void codesSortedAlphabetically() {
            var results = List.of(result("Q1", List.of(
                    rec(OptimizationCode.SEL_001, "x"),
                    rec(OptimizationCode.EXPR_001, "y"),
                    rec(OptimizationCode.LIM_001,  "z"))));
            String report = OptimizationReport.generate("demo", results);

            int expr = report.indexOf("EXPR-001");
            int lim  = report.indexOf("LIM-001");
            int sel  = report.indexOf("SEL-001");
            assertThat(expr).isLessThan(lim).isLessThan(sel);
        }
    }

    // =========================================================================
    // Non-empty report — TREES section
    // =========================================================================

    @Nested
    @DisplayName("TREES section")
    class TreesSection {

        @Test
        @DisplayName("TREES section present when a query was optimized")
        void treesSectionPresent() {
            var base  = rel("R");
            var proj  = project(List.of(simple("a")), base);
            var original  = limit(10, proj);
            var optimized = project(List.of(simple("a")),
                    limit(10, base));  // LIM-001 applied manually

            var results = List.of(result("V", original, optimized,
                    List.of(rec(OptimizationCode.LIM_001, "pushed"))));
            String report = OptimizationReport.generate("demo", results);

            assertThat(report).contains("TREES");
        }

        @Test
        @DisplayName("TREES section absent when no queries were optimized")
        void treesSectionAbsentWhenNoOptimizations() {
            // Mix a result with transformations applied with a force-same result
            var results = List.of(result("V", List.of()));
            String report = OptimizationReport.generate("demo", results);
            assertThat(report).doesNotContain("TREES");
        }

        @Test
        @DisplayName("before expression shows original.prettyPrint()")
        void beforeShowsOriginal() {
            var base     = rel("R");
            var proj     = project(List.of(simple("a")), base);
            var original = limit(10, proj);     // λ 10 (π a (R))
            var optimized = project(List.of(simple("a")), limit(10, base));

            var results = List.of(result("V", original, optimized,
                    List.of(rec(OptimizationCode.LIM_001, "pushed"))));
            String report = OptimizationReport.generate("demo", results);

            // The before line must contain the original's prettyPrint
            assertThat(report).contains("before:  " + original.prettyPrint());
        }

        @Test
        @DisplayName("after expression shows optimized.prettyPrint()")
        void afterShowsOptimized() {
            var base      = rel("R");
            var proj      = project(List.of(simple("a")), base);
            var original  = limit(10, proj);
            var optimized = project(List.of(simple("a")), limit(10, base));

            var results = List.of(result("V", original, optimized,
                    List.of(rec(OptimizationCode.LIM_001, "pushed"))));
            String report = OptimizationReport.generate("demo", results);

            assertThat(report).contains("after:   " + optimized.prettyPrint());
        }

        @Test
        @DisplayName("query name appears in TREES section")
        void queryNameAppearsInTrees() {
            var base      = rel("R");
            var proj      = project(List.of(simple("a")), base);
            var original  = limit(10, proj);
            var optimized = project(List.of(simple("a")), limit(10, base));

            var results = List.of(result("ActiveUsers", original, optimized,
                    List.of(rec(OptimizationCode.LIM_001, "pushed"))));
            String report = OptimizationReport.generate("demo", results);

            // TREES section comes before DETAIL; check query name presence
            int treesIdx  = report.indexOf("TREES");
            int detailIdx = report.indexOf("DETAIL");
            int nameIdx   = report.indexOf("ActiveUsers", treesIdx);
            assertThat(nameIdx).isGreaterThan(treesIdx).isLessThan(detailIdx);
        }

        @Test
        @DisplayName("unoptimized query is skipped in TREES")
        void unoptimizedQuerySkippedInTrees() {
            // One optimized, one not
            var base      = rel("R");
            var proj      = project(List.of(simple("a")), base);
            var original  = limit(10, proj);
            var optimized = project(List.of(simple("a")), limit(10, base));

            var results = List.of(
                    result("Optimized", original, optimized,
                            List.of(rec(OptimizationCode.LIM_001, "pushed"))),
                    result("Unoptimized", List.of())
            );
            String report = OptimizationReport.generate("demo", results);

            // "Unoptimized" must not appear in the TREES section
            int treesIdx  = report.indexOf("TREES");
            int detailIdx = report.indexOf("DETAIL");
            // Extract TREES body
            String treesPart = report.substring(treesIdx, detailIdx);
            assertThat(treesPart).doesNotContain("Unoptimized");
        }

        @Test
        @DisplayName("TREES section comes before DETAIL section")
        void treesSectionBeforeDetailSection() {
            var base      = rel("R");
            var proj      = project(List.of(simple("a")), base);
            var original  = limit(10, proj);
            var optimized = project(List.of(simple("a")), limit(10, base));

            var results = List.of(result("V", original, optimized,
                    List.of(rec(OptimizationCode.LIM_001, "pushed"))));
            String report = OptimizationReport.generate("demo", results);

            assertThat(report.indexOf("TREES"))
                    .isLessThan(report.indexOf("DETAIL"));
        }

        @Test
        @DisplayName("via QueryOptimizer.optimize(RelNode): LIM-001 before/after are correct")
        void integrationWithOptimizer() {
            var base  = rel("R");
            var proj  = project(List.of(simple("a")), base);
            var body  = limit(10, proj);

            var ctx       = new OptimizationContext();
            var optimized = new QueryOptimizer().optimize(
                    body, "V", SchemaAnnotations.empty(), ctx);

            var results = List.of(result("V", body, optimized, ctx.records()));
            String report = OptimizationReport.generate("default", results);

            assertThat(report)
                    .contains("before:  " + body.prettyPrint())
                    .contains("after:   " + optimized.prettyPrint())
                    .contains("TREES");
        }
    }

    // =========================================================================
    // Non-empty report — DETAIL section
    // =========================================================================

    @Nested
    @DisplayName("DETAIL section")
    class DetailSection {

        @Test
        @DisplayName("contains DETAIL section header")
        void containsDetailHeader() {
            var results = List.of(result("Q1",
                    List.of(rec(OptimizationCode.EXPR_001, "folded 2*3 to 6"))));
            String report = OptimizationReport.generate("demo", results);
            assertThat(report).contains("DETAIL");
        }

        @Test
        @DisplayName("detail shows query name and transformation detail")
        void detailShowsQueryNameAndDetail() {
            var results = List.of(result("MyQuery", List.of(
                    rec(OptimizationCode.JOIN_001, "product converted to theta join"))));
            String report = OptimizationReport.generate("demo", results);

            assertThat(report).contains("MyQuery");
            assertThat(report).contains("JOIN-001");
            assertThat(report).contains("product converted to theta join");
        }

        @Test
        @DisplayName("singular 'transformation' label for count == 1")
        void singularTransformationLabel() {
            var results = List.of(result("Q1",
                    List.of(rec(OptimizationCode.EXPR_001, "x"))));
            String report = OptimizationReport.generate("demo", results);
            assertThat(report).contains("1 transformation)");
        }

        @Test
        @DisplayName("plural 'transformations' label for count > 1")
        void pluralTransformationsLabel() {
            var results = List.of(result("Q1", List.of(
                    rec(OptimizationCode.EXPR_001, "x"),
                    rec(OptimizationCode.EXPR_002, "y"))));
            String report = OptimizationReport.generate("demo", results);
            assertThat(report).contains("2 transformations)");
        }

        @Test
        @DisplayName("unoptimized queries are not listed in DETAIL")
        void unoptimizedQueriesSkipped() {
            var results = List.of(
                    result("HasTransforms",
                            List.of(rec(OptimizationCode.LIM_001, "pushed"))),
                    result("NoTransforms", List.of())
            );
            String report = OptimizationReport.generate("demo", results);
            int detailIdx = report.indexOf("DETAIL");
            String detailPart = report.substring(detailIdx);
            // "NoTransforms" block should not appear in DETAIL
            assertThat(detailPart).doesNotContain("NoTransforms");
        }
    }

    // =========================================================================
    // Line width
    // =========================================================================

    @Nested
    @DisplayName("Line width")
    class LineWidth {

        @Test
        @DisplayName("no line exceeds WIDTH characters — basic")
        void noLineExceedsWidth() {
            var results = List.of(result("Q1", List.of(
                    rec(OptimizationCode.EXPR_001,
                            "this is a very long detail string that might exceed the width"
                            + " limit if not truncated properly by the formatter"))));
            String report = OptimizationReport.generate("demo", results);

            for (String line : report.split("\n")) {
                assertThat(line.length())
                        .as("line: %s", line)
                        .isLessThanOrEqualTo(OptimizationReport.WIDTH);
            }
        }

        @Test
        @DisplayName("no line exceeds WIDTH with a very long before/after expression")
        void noLineExceedsWidthWithLongExpression() {
            // Build a wide expression: deeply nested projection
            RelNode inner = rel("VeryLongRelationNameThatWillForceATruncation");
            for (int i = 0; i < 5; i++) {
                inner = project(List.of(simple("column" + i)), inner);
            }
            var original  = limit(100, inner);
            var optimized = inner; // pretend it was simplified somehow

            var results = List.of(result("QueryWithLongTree", original, optimized,
                    List.of(rec(OptimizationCode.LIM_001, "pushed deep limit"))));
            String report = OptimizationReport.generate("demo", results);

            for (String line : report.split("\n")) {
                assertThat(line.length())
                        .as("line too long: %s", line)
                        .isLessThanOrEqualTo(OptimizationReport.WIDTH);
            }
        }
    }
}
