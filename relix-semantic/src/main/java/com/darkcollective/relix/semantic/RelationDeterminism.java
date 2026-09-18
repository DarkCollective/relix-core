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

import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.OperandWalker;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelNodeOperands;
import com.darkcollective.relix.ast.RelationFunctionCall;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Answers one question about a relational expression: <em>would evaluating it twice
 * necessarily give the same relation?</em>
 *
 * <p>Rewrites that change <em>how many times</em> an expression is evaluated need this.
 * The motivating one is lateral decorrelation ({@code LATERAL-001}) and its runtime twin,
 * the lateral argument-tuple memo: both replace "evaluate the function body once per outer
 * row" with "evaluate it once and reuse the rows", which is answer-preserving exactly when
 * the body is reproducible.
 *
 * <h2>What makes an expression irreproducible</h2>
 * <ul>
 *   <li>A <b>function call</b> the supplied {@link FunctionCatalog} does not tag
 *       {@link FunctionProperty#DETERMINISTIC} — {@code Rand()}, {@code NOW()},
 *       {@code CURRENT_DATE}, {@code CURRENT_TIME}, and every user-defined scalar
 *       function, which is in no library at all. Treating an unrecognised name as
 *       irreproducible is the conservative direction and matches {@code DIST-002}.</li>
 *   <li>An <b>unseeded sampling operator</b>, via
 *       {@link RelNodeOperands#usesSystemState(RelNode)}.</li>
 *   <li>Anything reached through a <b>named view</b> or a <b>nested table-valued
 *       function</b>: a body that reads nothing volatile itself can still call one that
 *       does, so both are resolved through the symbol table and walked.</li>
 * </ul>
 *
 * <h2>What it deliberately does not treat as irreproducible</h2>
 * <p>A <b>base relation</b>. Re-reading a source table could in principle return different
 * rows — but the engine already assumes a source is stable for the duration of a query:
 * {@code ×} materialises its right input once and replays it for every left row, and every
 * hash join, {@code ÷} and set operation does the same. A rewrite that reuses one
 * evaluation of a scan is therefore taking a liberty the executor takes already, not a new
 * one.
 *
 * <h2>Completeness</h2>
 * <p>The walk is built on {@link RelNodeOperands}, whose {@code switch} over the sealed
 * {@link RelNode} hierarchy has no {@code default} arm. A new node kind — or a new
 * expression on an existing one — does not compile until it is registered there, so this
 * predicate cannot silently start returning {@code true} for something it has never seen.
 * That property is the whole reason the walker exists.
 */
public final class RelationDeterminism {

    private RelationDeterminism() {
    }

    /**
     * Returns whether {@code node} evaluates to the same relation every time it is run.
     *
     * <p>A {@code false} answer is always safe to act on; a {@code true} answer is only
     * given when nothing volatile was found anywhere in the expression, including through
     * the views and table-valued functions it references.
     *
     * @param node      the expression to classify; must not be null
     * @param symbols   the table used to resolve view and TVF references; must not be null
     * @param functions the functions the expression was analysed against, consulted for
     *                  the {@link FunctionProperty#DETERMINISTIC} tag; must not be null.
     *                  An {@linkplain FunctionCatalog#empty() empty} catalogue answers
     *                  {@code false} for every call, which is the safe direction
     * @return {@code true} when the expression is reproducible
     */
    public static boolean isDeterministic(RelNode node, SymbolTable symbols,
                                          FunctionCatalog functions) {
        return isDeterministic(node, symbols, functions, new HashSet<>());
    }

    /**
     * @param visited canonical lower-case names already being walked, so a recursive or
     *                mutually recursive definition terminates rather than spinning. A name
     *                already on the stack contributes nothing new — anything volatile in
     *                it is found by the walk that is still in progress.
     */
    private static boolean isDeterministic(RelNode node, SymbolTable symbols,
                                           FunctionCatalog functions, Set<String> visited) {
        if (RelNodeOperands.usesSystemState(node)) {
            return false;
        }
        boolean[] reproducible = {true};
        RelNodeOperands.forEach(node,
                operand -> OperandWalker.walk(operand, unused -> { },
                        call -> reproducible[0] &= deterministic(call, functions)),
                predicate -> OperandWalker.walk(predicate, unused -> { },
                        call -> reproducible[0] &= deterministic(call, functions)));
        if (!reproducible[0]) {
            return false;
        }
        if (!referencedBodiesDeterministic(node, symbols, functions, visited)) {
            return false;
        }
        for (RelNode child : node.children()) {
            if (!isDeterministic(child, symbols, functions, visited)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Walks the body behind a name: a {@link RelationNode} naming a view, or a
     * {@link RelationFunctionCall} naming a table-valued function. Neither body is a
     * structural child, so without this the walk would stop at the reference and report a
     * volatile definition as reproducible.
     *
     * <p>An unresolvable name is reported as <em>not</em> deterministic: an expression
     * this analysis cannot see through is one it cannot vouch for.
     */
    private static boolean referencedBodiesDeterministic(RelNode node, SymbolTable symbols,
                                                         FunctionCatalog functions,
                                                         Set<String> visited) {
        return switch (node) {
            case RelationNode n -> symbols.lookupRelation(n.name())
                    .map(symbol -> !(symbol instanceof QueryRelationSymbol view)
                            || bodyDeterministic(n.name(), view.body(), symbols, functions, visited))
                    .orElse(false);
            case RelationFunctionCall n -> symbols.resolveFunction(n.functionName()).stream()
                    .filter(RelationFunctionSymbol.class::isInstance)
                    .map(RelationFunctionSymbol.class::cast)
                    .findFirst()
                    .map(fn -> bodyDeterministic(n.functionName(), fn.body(), symbols, functions, visited))
                    .orElse(false);
            default -> true;
        };
    }

    /** Walks {@code body} once, guarding against a cycle through {@code name}. */
    private static boolean bodyDeterministic(String name, RelNode body, SymbolTable symbols,
                                             FunctionCatalog functions, Set<String> visited) {
        if (!visited.add(name.toLowerCase(Locale.ROOT))) {
            return true;    // already on the walk stack — that walk covers it
        }
        try {
            return isDeterministic(body, symbols, functions, visited);
        } finally {
            visited.remove(name.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Whether {@code call} names an installed function tagged
     * {@link FunctionProperty#DETERMINISTIC}.
     */
    private static boolean deterministic(FunctionCall call, FunctionCatalog functions) {
        return functions.scalar(call.functionName())
                .map(fn -> fn.signature().has(FunctionProperty.DETERMINISTIC))
                .orElse(false);
    }
}
