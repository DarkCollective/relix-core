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

import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.lang.ast.EndpointSpec;
import com.darkcollective.relix.lang.ast.RelateStatement;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.ColumnReference;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.lang.ast.source.CsvFileSourceConfig;
import com.darkcollective.relix.lang.ast.source.DatabaseSourceConfig;
import com.darkcollective.relix.lang.ast.source.JsonFileSourceConfig;
import com.darkcollective.relix.symbol.graph.EdgeOrigin;
import com.darkcollective.relix.symbol.graph.Endpoint;
import com.darkcollective.relix.symbol.graph.Relationship;
import com.darkcollective.relix.symbol.graph.SchemaGraph;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Assembles the {@link SchemaGraph} from declared relationships and validates
 * every edge against the symbol table (ADR-0024) — Phase 4.7 of the analysis
 * pipeline, after schema inference (so view endpoints carry their inferred
 * schemas) and before validation.
 *
 * <p>Two declared forms feed the graph: standalone {@code relate} statements
 * and {@code references:} blocks — on database, connection-table, CSV, and JSON
 * sources, and as the trailing {@code references} clause on inline tables. Every
 * declared edge is checked — both relations resolve, every named column exists,
 * the column lists have equal length, bounds are consistent, and the
 * {@code symmetric} modifier is used legally. A violation is a <em>hard
 * error</em>: an unchecked edge is a confidently wrong structural claim that
 * path resolution would then build on.
 *
 * <p>A <em>supplemental</em> graph — edges learned during a session
 * (conversational acquisition, joins observed in user queries) and re-supplied
 * on re-analysis — is merged after the declared edges. Supplemental edges are
 * re-validated against the current symbol table, but failures <em>demote to
 * warnings</em> and the edge is dropped: a learned edge whose relation vanished
 * (a {@code :reset}, an edited buffer) must not poison analysis the way a false
 * declaration in the file would. Surviving supplemental endpoints are re-bound
 * to the current symbol instances so consumers see current schemas.
 *
 * <p>After every declared edge is collected, the bounds of edges incident on a
 * <em>filtering view</em> ({@code QueryRelationSymbol} endpoint, §1.5) are
 * derived from the base relationship they inherit — {@code max} preserved,
 * {@code min} relaxed to 0 across a σ — rather than trusted from the author (see
 * {@link #deriveDerivedEndpointBounds}). The complementary rule, that a derived
 * endpoint enters path search only when nominated (§1.5 (ii)), lives in
 * {@code SchemaGraphSearch}.
 *
 * <p>Follows the standard phase idiom: errors accumulate, nothing throws.
 */
final class SchemaGraphBuilder {

    private final SymbolTable symbolTable;
    private final List<SemanticError> errors = new ArrayList<>();
    private final List<Relationship> declared = new ArrayList<>();
    private final Set<String> identities = new HashSet<>();
    private final Map<String, SourceLocation> declaredLocations = new HashMap<>();

    SchemaGraphBuilder(SymbolTable symbolTable) {
        this.symbolTable = Objects.requireNonNull(symbolTable, "symbolTable");
    }

    /**
     * Builds the schema graph from all declared edges plus the supplemental
     * (session-learned) graph.
     *
     * @param sources          all collected source declarations (their configs
     *                         may carry {@code references:} blocks)
     * @param relates          all collected {@code relate} statements
     * @param inlineReferences inline-table {@code references} clauses, keyed by
     *                         the declaring table's name
     * @param supplemental     session-learned edges to merge; may be
     *                         {@link SchemaGraph#EMPTY}
     * @param rootPath         the root file path, used to position warnings for
     *                         supplemental edges (which have no source location)
     */
    SchemaGraph build(Collection<SourceDeclaration> sources, List<RelateStatement> relates,
                      Map<String, List<ColumnReference>> inlineReferences,
                      SchemaGraph supplemental, String rootPath) {
        for (SourceDeclaration src : sources) {
            List<ColumnReference> refs = switch (src.config()) {
                case DatabaseSourceConfig db        -> db.references();
                case ConnectionTableSourceConfig ct -> ct.references();
                case CsvFileSourceConfig csv        -> csv.references();
                case JsonFileSourceConfig json      -> json.references();
                default                             -> List.<ColumnReference>of();
            };
            for (ColumnReference ref : refs) {
                addReferenceEdge("source", src.name(), ref);
            }
        }
        inlineReferences.forEach((owner, refs) -> {
            for (ColumnReference ref : refs) {
                addReferenceEdge("inline table", owner, ref);
            }
        });
        for (RelateStatement rel : relates) {
            addRelateEdge(rel);
        }
        List<Relationship> resolved = deriveDerivedEndpointBounds(declared, rootPath);
        return SchemaGraph.of(resolved).merge(revalidateSupplemental(supplemental, rootPath));
    }

    /** Returns all diagnostics accumulated during assembly (defensive copy). */
    List<SemanticError> errors() {
        return List.copyOf(errors);
    }

    // =========================================================================
    // Declared edges — hard errors
    // =========================================================================

    /** One {@code references:} entry: an FK arrow implying {@code max = 1} on the target. */
    private void addReferenceEdge(String ownerKind, String ownerName, ColumnReference ref) {
        SourceLocation loc = ref.location();

        // The declaring relation is the edge's source. If its own registration
        // failed, that error is already reported — stay silent.
        Optional<RelationSymbol> source = symbolTable.resolveRelation(ownerName);
        if (source.isEmpty()) {
            return;
        }

        Optional<RelationSymbol> target = symbolTable.resolveRelation(ref.targetRelation());
        if (target.isEmpty()) {
            error(loc, "Unknown relation '" + ref.targetRelation()
                    + "' referenced by " + ownerKind + " '" + ownerName + "'");
            return;
        }

        boolean ok = checkColumnCount(loc, "reference", ref.sourceColumns(), ref.targetColumns());
        ok &= checkColumns(loc, source.get(), ref.sourceColumns());
        ok &= checkColumns(loc, target.get(), ref.targetColumns());
        if (!ok) {
            return;
        }

        record(loc, new Relationship(ref.defaultName(), Optional.empty(), false,
                Endpoint.unbounded(source.get(), ref.sourceColumns()),
                new Endpoint(target.get(), ref.targetColumns(), 0, OptionalLong.of(1)),
                EdgeOrigin.DECLARED));
    }

    /** One {@code relate} statement: a fully-specified named edge. */
    private void addRelateEdge(RelateStatement rel) {
        SourceLocation loc = rel.location();

        Optional<RelationSymbol> source = resolveEndpointRelation(rel, rel.source());
        Optional<RelationSymbol> target = resolveEndpointRelation(rel, rel.target());

        boolean ok = source.isPresent() && target.isPresent();
        ok &= checkBounds(rel.source());
        ok &= checkBounds(rel.target());
        ok &= checkColumnCount(loc, "relationship '" + rel.name() + "'",
                rel.source().columns(), rel.target().columns());
        if (source.isPresent()) {
            ok &= checkColumns(rel.source().location(), source.get(), rel.source().columns());
        }
        if (target.isPresent()) {
            ok &= checkColumns(rel.target().location(), target.get(), rel.target().columns());
        }

        if (rel.symmetric() && rel.inverseName().isPresent()) {
            error(loc, "Relationship '" + rel.name()
                    + "' is symmetric and cannot also name an inverse"
                    + " — a symmetric edge has no distinct roles");
            ok = false;
        }
        if (rel.symmetric() && source.isPresent() && target.isPresent()
                && !SchemaGraph.key(source.get()).equals(SchemaGraph.key(target.get()))) {
            error(loc, "Relationship '" + rel.name()
                    + "' is marked symmetric but connects two different relations"
                    + " — symmetric applies only to self-referential edges");
            ok = false;
        }
        if (!ok) {
            return;
        }

        record(loc, new Relationship(rel.name(), rel.inverseName(), rel.symmetric(),
                toEndpoint(rel.source(), source.get()),
                toEndpoint(rel.target(), target.get()),
                EdgeOrigin.DECLARED));
    }

    private Optional<RelationSymbol> resolveEndpointRelation(RelateStatement rel,
                                                             EndpointSpec spec) {
        Optional<RelationSymbol> resolved = symbolTable.resolveRelation(spec.relationRef());
        if (resolved.isEmpty()) {
            error(spec.location(), "Unknown relation '" + spec.relationRef()
                    + "' in relationship '" + rel.name() + "'");
        }
        return resolved;
    }

    private static Endpoint toEndpoint(EndpointSpec spec, RelationSymbol relation) {
        return new Endpoint(relation, spec.columns(), spec.min(), spec.max());
    }

    private boolean checkBounds(EndpointSpec spec) {
        if (spec.max().isPresent()) {
            long max = spec.max().getAsLong();
            if (max == 0 || max < spec.min()) {
                error(spec.location(), "Invalid multiplicity bounds [" + spec.min() + ".." + max
                        + "]: the upper bound must be at least 1 and not below the lower bound");
                return false;
            }
        }
        return true;
    }

    private boolean checkColumnCount(SourceLocation loc, String what,
                                     List<String> source, List<String> target) {
        if (source.size() != target.size()) {
            error(loc, "Column lists of " + what + " must have equal length: "
                    + source.size() + " vs " + target.size()
                    + " — columns pair positionally");
            return false;
        }
        return true;
    }

    private boolean checkColumns(SourceLocation loc, RelationSymbol relation,
                                 List<String> columns) {
        boolean ok = true;
        for (String column : columns) {
            if (relation.schema().column(column).isEmpty()) {
                error(loc, "Relation '" + relation.declaredName()
                        + "' has no column '" + column + "'");
                ok = false;
            }
        }
        return ok;
    }

    private void record(SourceLocation loc, Relationship relationship) {
        String id = identity(relationship);
        if (!identities.add(id)) {
            error(loc, "Duplicate relationship declaration '" + relationship.name()
                    + "' between '" + relationship.source().relation().declaredName()
                    + "' and '" + relationship.target().relation().declaredName() + "'");
            return;
        }
        declared.add(relationship);
        declaredLocations.put(id, loc);
    }

    // =========================================================================
    // Derived (view) endpoint bounds — inheritance through a filter (§1.5 (i))
    // =========================================================================

    /**
     * Derives the bounds of every declared edge incident on a <em>filtering
     * view</em> from the base relationship it inherits (ADR-0024 §1.5 (i)).
     *
     * <p>A σ can only remove rows, so across a filtering view {@code ActiveOrders
     * := σ … (Orders)} the multiplicity {@code max} is preserved and {@code min}
     * relaxes to 0: {@code Customer —[1..10]→ Orders} yields {@code Customer
     * —[0..10]→ ActiveOrders}, because a customer with at least one order may have
     * zero <em>active</em> ones. The bounds are derived by the engine, never
     * trusted from the author — an inherited {@code min} would be a false claim
     * about row preservation. Where the view aggregates or joins rather than
     * filters, or no base relationship exists to inherit from, no rule applies and
     * the edge keeps its declared (or 0/∞ default) bounds.
     */
    private List<Relationship> deriveDerivedEndpointBounds(List<Relationship> edges, String rootPath) {
        List<Relationship> out = new ArrayList<>(edges.size());
        for (Relationship edge : edges) {
            out.add(deriveEdge(edge, edges, rootPath));
        }
        return out;
    }

    private Relationship deriveEdge(Relationship edge, List<Relationship> allEdges, String rootPath) {
        Optional<RelationSymbol> sourceBase = filteredBase(edge.source().relation());
        Optional<RelationSymbol> targetBase = filteredBase(edge.target().relation());
        // Exactly one endpoint must be a filtering view. Neither (a plain base-to-base
        // edge) or both (nothing sound to inherit from) leaves the edge untouched.
        if (sourceBase.isPresent() == targetBase.isPresent()) {
            return edge;
        }

        boolean sourceIsView = sourceBase.isPresent();
        Endpoint viewSide = sourceIsView ? edge.source() : edge.target();
        Endpoint oppSide = sourceIsView ? edge.target() : edge.source();
        RelationSymbol base = (sourceIsView ? sourceBase : targetBase).get();

        Optional<Endpoint> baseSide =
                baseEndpoint(allEdges, edge, oppSide.relation(), base, viewSide.columns());
        if (baseSide.isEmpty()) {
            return edge;
        }

        // max preserved (a filter cannot add matches), min relaxed to 0 (it may remove all).
        Endpoint derivedViewSide =
                new Endpoint(viewSide.relation(), viewSide.columns(), 0, baseSide.get().max());

        if (viewSide.bounded() && !sameBounds(viewSide, derivedViewSide)) {
            SourceLocation loc = declaredLocations.getOrDefault(identity(edge), SourceLocation.UNKNOWN);
            errors.add(SemanticError.warning(loc.filePath(), loc.line(), loc.column(),
                    "Relationship '" + edge.name() + "' targets filtering view '"
                            + viewSide.relation().declaredName() + "'; its declared bounds "
                            + viewSide.boundsLabel() + " were ignored — the engine derived "
                            + derivedViewSide.boundsLabel()
                            + " from the base relationship over '" + base.declaredName()
                            + "' (ADR-0024 §1.5)"));
        }

        return sourceIsView
                ? new Relationship(edge.name(), edge.inverseName(), edge.symmetric(),
                        derivedViewSide, oppSide, edge.origin())
                : new Relationship(edge.name(), edge.inverseName(), edge.symmetric(),
                        oppSide, derivedViewSide, edge.origin());
    }

    /**
     * If {@code relation} is a view whose body is a σ-only filter over a single
     * relation, returns that underlying relation; otherwise empty. Any other
     * operator (γ, a join, π, δ, τ, …) blocks inheritance — only a selection
     * chain preserves the "each output row is one base row" property §1.5 relies
     * on.
     */
    private Optional<RelationSymbol> filteredBase(RelationSymbol relation) {
        if (!(relation instanceof QueryRelationSymbol view)) {
            return Optional.empty();
        }
        RelNode node = view.body();
        boolean filtered = false;
        while (true) {
            switch (node) {
                case SelectionNode s -> {
                    filtered = true;
                    node = s.input();
                }
                case RelationNode rn -> {
                    return filtered ? symbolTable.resolveRelation(rn.name()) : Optional.empty();
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
    }

    /**
     * The base-relation-side endpoint of the unique declared edge connecting
     * {@code opposite} and {@code base} whose base-side columns match the view's
     * join columns. Empty if there is no such edge or more than one (an ambiguous
     * base is not safe to inherit from). The edge under derivation is skipped.
     */
    private Optional<Endpoint> baseEndpoint(List<Relationship> edges, Relationship self,
                                            RelationSymbol opposite, RelationSymbol base,
                                            List<String> viewColumns) {
        String oppKey = SchemaGraph.key(opposite);
        String baseKey = SchemaGraph.key(base);
        String selfId = identity(self);
        Endpoint match = null;
        for (Relationship e : edges) {
            if (identity(e).equals(selfId)) {
                continue;
            }
            String sKey = SchemaGraph.key(e.source().relation());
            String tKey = SchemaGraph.key(e.target().relation());
            Endpoint baseSide;
            if (sKey.equals(baseKey) && tKey.equals(oppKey)) {
                baseSide = e.source();
            } else if (tKey.equals(baseKey) && sKey.equals(oppKey)) {
                baseSide = e.target();
            } else {
                continue;
            }
            if (!columnsEqualIgnoreCase(baseSide.columns(), viewColumns)) {
                continue;
            }
            if (match != null) {
                return Optional.empty();
            }
            match = baseSide;
        }
        return Optional.ofNullable(match);
    }

    private static boolean columnsEqualIgnoreCase(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equalsIgnoreCase(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameBounds(Endpoint a, Endpoint b) {
        return a.min() == b.min() && a.max().equals(b.max());
    }

    // =========================================================================
    // Supplemental edges — warnings + exclusion, never hard errors
    // =========================================================================

    /**
     * Re-validates each supplemental edge against the current symbol table,
     * dropping stale ones with a warning and re-binding survivors to the
     * current symbol instances.
     */
    private SchemaGraph revalidateSupplemental(SchemaGraph supplemental, String rootPath) {
        if (supplemental.isEmpty()) {
            return supplemental;
        }
        List<Relationship> kept = new ArrayList<>();
        for (Relationship r : supplemental.relationships()) {
            Optional<Endpoint> source = rebind(r, r.source(), rootPath);
            Optional<Endpoint> target = rebind(r, r.target(), rootPath);
            if (source.isPresent() && target.isPresent()) {
                kept.add(new Relationship(r.name(), r.inverseName(), r.symmetric(),
                        source.get(), target.get(), r.origin()));
            }
        }
        return SchemaGraph.of(kept);
    }

    private Optional<Endpoint> rebind(Relationship r, Endpoint endpoint, String rootPath) {
        Optional<RelationSymbol> resolved =
                symbolTable.resolveRelation(endpoint.relation().declaredName());
        if (resolved.isEmpty()) {
            warn(rootPath, "Dropped " + originLabel(r) + " relationship '" + r.name()
                    + "': relation '" + endpoint.relation().declaredName()
                    + "' no longer exists");
            return Optional.empty();
        }
        for (String column : endpoint.columns()) {
            if (resolved.get().schema().column(column).isEmpty()) {
                warn(rootPath, "Dropped " + originLabel(r) + " relationship '" + r.name()
                        + "': relation '" + resolved.get().declaredName()
                        + "' no longer has column '" + column + "'");
                return Optional.empty();
            }
        }
        return Optional.of(new Endpoint(resolved.get(), endpoint.columns(),
                endpoint.min(), endpoint.max()));
    }

    private static String originLabel(Relationship r) {
        return r.origin().name().toLowerCase(Locale.ROOT);
    }

    // =========================================================================
    // Diagnostics
    // =========================================================================

    private void error(SourceLocation loc, String message) {
        errors.add(SemanticError.error(loc.filePath(), loc.line(), loc.column(), message));
    }

    private void warn(String filePath, String message) {
        errors.add(SemanticError.warning(filePath, 0, 0, message));
    }

    /**
     * Edge identity for duplicate detection: case-insensitive name plus the two
     * endpoints, unordered — mirrors {@link SchemaGraph}'s merge identity.
     */
    private static String identity(Relationship r) {
        String a = endpointIdentity(r.source());
        String b = endpointIdentity(r.target());
        String pair = a.compareTo(b) <= 0 ? a + "<->" + b : b + "<->" + a;
        return r.name().toLowerCase(Locale.ROOT) + "|" + pair;
    }

    private static String endpointIdentity(Endpoint e) {
        StringBuilder sb = new StringBuilder(SchemaGraph.key(e.relation()));
        for (String c : e.columns()) {
            sb.append('#').append(c.toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
