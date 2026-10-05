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

import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.SolveEquation;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.function.ScalarFunctionSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Expands the user-defined scalar functions in a {@code SOLVE} equation into the
 * arithmetic they stand for, so that {@code SOLVE length = predicted(L0, k, temp)}
 * is solved exactly as the equation the {@code def} body spells.
 *
 * <p>Expansion substitutes each call's arguments for the parameters of the function's
 * body, and expands the result again, so a {@code def} calling another is followed
 * through. It is the same choice of function evaluation makes: a function a library
 * supplies takes precedence over a {@code def} of the same name, and stays a call —
 * one the equation then cannot be solved through. A call is also left as written
 * when nothing can be substituted for it faithfully: no {@code def} of that arity, a
 * body that names something other than its parameters, or a {@code def} that reaches
 * itself again.
 *
 * <p>Validation and planning both expand, so what is checked is what is solved.
 */
public final class SolveEquations {

    private SolveEquations() {
    }

    /**
     * An equation with its functions expanded, and the {@code def}s expansion went
     * through — named in a diagnostic about the expanded form, which the user did not
     * write.
     *
     * @param equation the expanded equation; never null
     * @param defs     the names of the expanded functions, in first-use order; never null
     */
    public record Expansion(SolveEquation equation, List<String> defs) {
        public Expansion {
            defs = List.copyOf(defs);
        }
    }

    /**
     * Expands every user-defined scalar function call in {@code equation}.
     *
     * @param equation  the equation as written; must not be null
     * @param functions the installed function libraries; must not be null
     * @param symbols   the symbol table holding the script's {@code def}s; must not be null
     * @return the expanded equation and the functions it expanded
     */
    public static Expansion expand(SolveEquation equation, FunctionCatalog functions,
                                   SymbolTable symbols) {
        Expander expander = new Expander(functions, symbols);
        SolveEquation expanded = new SolveEquation(
                expander.expand(equation.left()), expander.expand(equation.right()));
        return new Expansion(expanded, new ArrayList<>(expander.used));
    }

    /**
     * Expands every equation of a list.
     *
     * @param equations the equations as written; must not be null
     * @param functions the installed function libraries; must not be null
     * @param symbols   the symbol table holding the script's {@code def}s; must not be null
     * @return the expanded equations, in the same order
     */
    public static List<SolveEquation> expandAll(List<SolveEquation> equations,
                                                FunctionCatalog functions, SymbolTable symbols) {
        return equations.stream()
                .map(e -> expand(e, functions, symbols).equation())
                .toList();
    }

    private static final class Expander {
        private final FunctionCatalog functions;
        private final SymbolTable symbols;
        private final Deque<String> active = new ArrayDeque<>();
        private final Set<String> used = new LinkedHashSet<>();

        Expander(FunctionCatalog functions, SymbolTable symbols) {
            this.functions = functions;
            this.symbols = symbols;
        }

        Operand expand(Operand operand) {
            return switch (operand) {
                case BinaryArithmeticExpression b -> {
                    Operand left = expand(b.left());
                    Operand right = expand(b.right());
                    yield left == b.left() && right == b.right() ? b
                            : new BinaryArithmeticExpression(left, b.operator(), right, b.location());
                }
                case UnaryOperand u -> {
                    Operand inner = expand(u.operand());
                    yield inner == u.operand() ? u : new UnaryOperand(inner, u.location());
                }
                case FunctionCall call -> expandCall(call);
                default -> operand;
            };
        }

        private Operand expandCall(FunctionCall call) {
            String key = call.functionName().toLowerCase(Locale.ROOT);
            Optional<ScalarFunctionSymbol> def = definition(call);
            if (def.isEmpty() || active.contains(key)) {
                return call;
            }
            ScalarFunctionSymbol fn = def.get();
            Map<String, Operand> bindings = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (int i = 0; i < fn.parameters().size(); i++) {
                bindings.put(fn.parameters().get(i).name(), expand(call.arguments().get(i)));
            }
            Operand body = fn.body().orElseThrow();
            if (!namesOnly(body, bindings.keySet())) {
                return call;
            }
            used.add(fn.declaredName());
            active.push(key);
            try {
                return expand(substitute(body, bindings));
            } finally {
                active.pop();
            }
        }

        private Optional<ScalarFunctionSymbol> definition(FunctionCall call) {
            if (functions.scalar(call.functionName()).isPresent()) {
                return Optional.empty();
            }
            for (FunctionSymbol symbol : symbols.resolveFunction(call.functionName())) {
                if (symbol instanceof ScalarFunctionSymbol sfs
                        && sfs.body().isPresent()
                        && sfs.parameters().size() == call.arguments().size()) {
                    return Optional.of(sfs);
                }
            }
            return Optional.empty();
        }
    }

    /** Whether every attribute {@code body} names is one of {@code names}. */
    private static boolean namesOnly(Operand body, Set<String> names) {
        Set<String> lower = new HashSet<>();
        names.forEach(n -> lower.add(n.toLowerCase(Locale.ROOT)));
        return namesOnlyLower(body, lower);
    }

    private static boolean namesOnlyLower(Operand body, Set<String> names) {
        return switch (body) {
            case AttributeOperand a -> names.contains(a.unqualifiedName().toLowerCase(Locale.ROOT));
            case BinaryArithmeticExpression b ->
                    namesOnlyLower(b.left(), names) && namesOnlyLower(b.right(), names);
            case UnaryOperand u -> namesOnlyLower(u.operand(), names);
            case FunctionCall f -> f.arguments().stream().allMatch(a -> namesOnlyLower(a, names));
            default -> true;
        };
    }

    /** Replaces each parameter reference in {@code body} with its argument. */
    private static Operand substitute(Operand body, Map<String, Operand> bindings) {
        return switch (body) {
            case AttributeOperand a -> bindings.getOrDefault(a.unqualifiedName(), a);
            case BinaryArithmeticExpression b -> new BinaryArithmeticExpression(
                    substitute(b.left(), bindings), b.operator(),
                    substitute(b.right(), bindings), b.location());
            case UnaryOperand u -> new UnaryOperand(substitute(u.operand(), bindings), u.location());
            case FunctionCall f -> new FunctionCall(f.functionName(),
                    f.arguments().stream().map(a -> substitute(a, bindings)).toList(), f.location());
            default -> body;
        };
    }
}
