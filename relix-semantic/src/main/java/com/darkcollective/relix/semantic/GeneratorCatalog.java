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
import com.darkcollective.relix.symbol.Schema;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Supplies the schema of a <em>generator</em> source during semantic analysis
 * — the seam through which the analyzer learns a generator's
 * heading without the script declaring it (generator-owned schema).
 *
 * <p>It mirrors {@link CatalogProvider}: a generator's rows are produced in
 * {@code relix-processor} (they need {@code Row}, which sits above this module), so
 * the schema is resolved through this thin seam, implemented by the processor's
 * generator registry and injected into {@link SemanticAnalyzer}. The default
 * {@link #NONE} resolves nothing — a generator source then produces a clear
 * "unknown generator" error in contexts where no registry is wired.
 */
@FunctionalInterface
public interface GeneratorCatalog {

    /**
     * Returns the schema of the named generator for the given arguments, or
     * {@link Optional#empty()} if the generator is unknown.
     *
     * @param name the generator name (e.g. {@code "Range"}); case-insensitive
     * @param args the raw generator arguments
     * @return the generator's output schema, or empty if unknown
     */
    Optional<Schema> generatorSchema(String name, Map<String, String> args);

    /**
     * The exact cardinality of the named generator for the given arguments, when
     * known — fed to the cost model so a finite generator (e.g. {@code Range}) has an
     * exact row count. Returns {@link OptionalLong#empty()} for an unknown generator
     * or an unbounded/unknown cardinality; the default is empty.
     *
     * @param name the generator name; case-insensitive
     * @param args the raw generator arguments
     * @return the exact row count, or empty
     */
    default OptionalLong generatorCardinality(String name, Map<String, String> args) {
        return OptionalLong.empty();
    }

    /**
     * Whether the named generator is finite, for the given arguments — the leaf half of
     * {@code relix.relations.boundedness}, and the one half no analysis can derive.
     *
     * <p>It rides this seam for the reason {@link #generatorCardinality} does: a
     * generator's finiteness is declared by the generator, which lives above this module,
     * and the registry that produces its rows is the same object that answers here — so
     * the two halves of the pipeline cannot disagree about which relations are infinite.
     *
     * <p>The default is {@link Boundedness#UNKNOWN}, which is what a catalog that knows no
     * generators honestly has to say. That is <em>not</em> the conservatism the planner
     * wants: a check that refuses an unbounded input reads an unrecognised leaf as
     * {@code BOUNDED} rather than risk rejecting a legal query, while a catalog reporting
     * a fact about a generator it has never heard of would simply be making it up.
     *
     * @param name the generator name; case-insensitive
     * @param args the raw generator arguments
     * @return its boundedness, or {@link Boundedness#UNKNOWN} for a generator this catalog
     *         does not know
     */
    default Boundedness generatorBoundedness(String name, Map<String, String> args) {
        return Boundedness.UNKNOWN;
    }

    /** A catalog that knows no generators — the default for analysis without a registry. */
    GeneratorCatalog NONE = (name, args) -> Optional.empty();
}
