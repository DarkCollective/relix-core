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

import com.darkcollective.relix.ast.ConditionOperand;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.SourceLocation;

/**
 * The predicate that keeps exactly the rows a selection <em>drops</em>.
 *
 * <p>Not {@code ¬p}. A selection keeps a row only when its predicate is TRUE, so the
 * rows it drops are those where the predicate is FALSE <em>or</em> UNKNOWN — and
 * {@code ¬UNKNOWN} is UNKNOWN, which a selection also drops. So {@code σ ¬p} keeps
 * strictly fewer rows than {@code R − σ p (R)}, and the gap is every row where
 * {@code p} could not be decided:
 *
 * <pre>{@code
 *   R = [(1, 10), (2, 500), (3, NULL)]
 *
 *   R − σ amount > 100 (R)        →  (1, 10), (3, NULL)
 *   σ ¬(amount > 100) (R)         →  (1, 10)                 ← the NULL row is lost
 *   σ complement(amount > 100)(R) →  (1, 10), (3, NULL)
 * }</pre>
 *
 * <p>The correction is {@code ¬p ∨ p IS UNKNOWN}, and the second disjunct is a null
 * test over the predicate's own truth value: a {@link ConditionOperand} evaluates to
 * NULL exactly when the predicate it wraps is UNKNOWN, which is the one place the
 * engine's third truth value becomes data.
 *
 * <p><strong>The node is built directly, never as a call to {@code IsNull}.</strong>
 * The function catalogue is discovered, so a library claiming that name at a higher
 * priority would silently change what a rewrite built on it means — which is the
 * hazard {@code FunctionAccessGuardTest} exists for. A predicate node needs no
 * catalogue and cannot be shadowed.
 *
 * <p>The result is deliberately <em>not</em> simplified here. {@code PRED-001} and
 * friends run in the {@code simplify} phase, which is over by the time the rules that
 * need this reach it, and a complement that folded itself would be a second
 * simplifier to keep in step with the first.
 */
final class SelectionComplement {

    private SelectionComplement() {}

    /**
     * Returns the predicate true for exactly the rows {@code kept} does not keep.
     *
     * @param kept the selection predicate to complement; must not be null
     * @param at   the location to carry onto the nodes built here; must not be null
     * @return {@code ¬kept ∨ kept IS UNKNOWN}; never null
     */
    static Predicate of(Predicate kept, SourceLocation at) {
        return new OrPredicate(
                new NotPredicate(kept, at),
                new NullPredicate(new ConditionOperand(kept, at), true, at),
                at);
    }
}
