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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.Symbol;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The environment a composed relation resolves against: what both of its operands know.
 *
 * <p>Two relations built by one session usually carry the same names, but not always —
 * a dotted reference introspects its table when the relation is built, so that symbol
 * lands in <em>that</em> relation's model and nowhere else, and a relation pinned before
 * a later {@code define} does not carry what the {@code define} declared. Composing the
 * two is a request for both, so the composed relation resolves against the union.
 *
 * <p>A <em>conflict</em> — one name bound to different things on the two sides — is
 * refused rather than settled by precedence. Which side would win is not a question the
 * caller asked, and either answer silently discards half of what they wrote.
 *
 * <p>The exception is a symbol the engine computed rather than the user declared: the
 * {@code relix.*} catalogs describe the analysis they belong to, so the two sides
 * genuinely differ and neither is wrong. The receiver's are kept — a composed relation
 * that reads the catalog reads it as its left operand saw it.
 */
final class ComposedModel {

    private ComposedModel() {
    }

    /**
     * The union of two models, or {@code left} when they are the same model.
     *
     * @param left  the receiving relation's model
     * @param right the other operand's model
     * @return a model resolving every name either side resolves
     * @throws RelixException if a name is bound to different things on the two sides
     */
    static SemanticModel of(SemanticModel left, SemanticModel right) {
        if (left == right) {
            return left;
        }
        SymbolTable symbols = mergedSymbols(left.symbolTable(), right.symbolTable());
        return new SemanticModel(
                left.namespace(),
                symbols,
                merged(left.sources(), right.sources(), "source", symbols),
                merged(left.connections(), right.connections(), "connection", symbols),
                mergedStatistics(left.statistics(), right.statistics()),
                mergedSchemas(left.nodeSchemas(), right.nodeSchemas()),
                left.schemaGraph().merge(right.schemaGraph()),
                left.rootQueries(),
                left.functions());
    }

    /**
     * Both tables' symbols in one table, registered in order so that the receiver's win a
     * shadowing race — which only decides where an engine-computed symbol comes from,
     * since a differing user-declared one has already been refused.
     */
    private static SymbolTable mergedSymbols(SymbolTable left, SymbolTable right) {
        InMemorySymbolTable merged = new InMemorySymbolTable();
        Map<String, Symbol> claimed = new LinkedHashMap<>();
        for (Symbol symbol : left.allSymbols()) {
            claim(claimed, symbol);
            merged.register(symbol);
        }
        for (Symbol symbol : right.allSymbols()) {
            if (claim(claimed, symbol)) {
                merged.register(symbol);
            }
        }
        return merged;
    }

    /**
     * Records {@code symbol} under its lookup key, reporting whether it adds anything.
     *
     * @return {@code true} when the key was free, {@code false} when the same symbol is
     *         already claimed
     * @throws RelixException if a <em>different</em> user-declared symbol holds the key
     */
    private static boolean claim(Map<String, Symbol> claimed, Symbol symbol) {
        Symbol existing = claimed.putIfAbsent(key(symbol), symbol);
        if (existing == null) {
            return true;
        }
        if (existing.equals(symbol)) {
            return false;
        }
        if (existing.provenance() == Provenance.BUILTIN
                && symbol.provenance() == Provenance.BUILTIN) {
            return false;   // an engine-computed catalog; the receiver's is kept
        }
        throw new RelixException(conflict(symbol.declaredName(),
                symbol instanceof FunctionSymbol ? "function" : "relation"));
    }

    /** The key a lookup would find this symbol by: namespace, name, and — for a function — its overload. */
    private static String key(Symbol symbol) {
        String base = symbol.namespace().toLowerCase(Locale.ROOT)
                + "." + symbol.canonicalName();
        return symbol instanceof FunctionSymbol fn ? base + fn.parameterSignature() : base;
    }

    /**
     * Two per-name maps in one, refusing a key the two sides disagree about.
     *
     * <p>These maps are keyed by canonical name, so the conflict is reported through the
     * symbol table: what the caller wrote is {@code Orders}, and a message saying
     * {@code orders} sends them looking for a second declaration they never made.
     */
    private static <V> Map<String, V> merged(Map<String, V> left, Map<String, V> right,
                                             String what, SymbolTable symbols) {
        if (left.equals(right)) {
            return left;
        }
        Map<String, V> combined = new LinkedHashMap<>(left);
        right.forEach((name, value) -> {
            V existing = combined.putIfAbsent(name, value);
            if (existing != null && !existing.equals(value)) {
                throw new RelixException(conflict(declared(symbols, name), what));
            }
        });
        return Map.copyOf(combined);
    }

    /**
     * Both sides' statistics, the receiver's winning a clash.
     *
     * <p>Unlike a declaration, a statistic is an observation rather than a binding: two
     * measurements of one relation disagreeing is ordinary, and refusing a composition
     * over it would fail a query for a reason that has nothing to do with its meaning.
     */
    private static Map<String, RelationStatistics> mergedStatistics(
            Map<String, RelationStatistics> left, Map<String, RelationStatistics> right) {
        if (left.equals(right)) {
            return left;
        }
        Map<String, RelationStatistics> combined = new LinkedHashMap<>(right);
        combined.putAll(left);
        return Map.copyOf(combined);
    }

    /**
     * Both sides' annotations in one map.
     *
     * <p>The composed tree is re-annotated over this, so what the merge carries is what a
     * re-walk cannot produce: the schemas of the view bodies each side's analysis
     * inferred, which are keyed by node identity and are not in the composed tree.
     */
    private static SchemaAnnotations mergedSchemas(SchemaAnnotations left, SchemaAnnotations right) {
        Map<RelNode, Schema> combined = new IdentityHashMap<>(right.asMap());
        combined.putAll(left.asMap());
        return new SchemaAnnotations(combined);
    }

    /** The name as the script spells it, when a symbol carries the spelling. */
    private static String declared(SymbolTable symbols, String canonicalName) {
        return symbols.resolveRelation(canonicalName)
                .map(Symbol::declaredName)
                .orElse(canonicalName);
    }

    private static String conflict(String name, String what) {
        return "cannot compose these relations: '" + name + "' names a different " + what
                + " in each of them. Build both operands against the same declarations, "
                + "or rename one.";
    }
}
