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
package com.darkcollective.relix.plan;

import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.symbol.FunctionProperty;

/**
 * Whether a scalar call may be handed to a backend at all, asked before either
 * renderer asks the function how it is spelled.
 *
 * <p>A fold is supposed to change where work happens and never what the answer is, so a
 * call whose value depends on <em>when and where</em> it runs cannot cross the boundary:
 * the backend would evaluate it against its own clock or its own random source, not the
 * ones this session was given.  {@code NOW()} is the case that matters — a session built
 * with a fixed {@code Clock} is what makes a query over "now" reproducible, and a folded
 * {@code NOW()} silently reads the database's clock instead.
 *
 * <p>The engine already carries the answer, in the properties the signature declares, and
 * the optimizer already reads it: a call that is not deterministic is never folded or
 * de-duplicated there either.  The renderers were the one place that did not ask.
 *
 * <p>This is a property of the <em>call</em>, not of a list of names, which is what makes
 * it hold for a function library the engine has never heard of: a third-party function
 * that reads a clock, a sequence or a random source declines here the day it is
 * installed, without knowing this class exists.  Declining costs the containing operator
 * its fold and nothing else.
 */
final class PushdownFolding {

    private PushdownFolding() {
    }

    /**
     * @param function the resolved function behind a call
     * @return {@code true} when the backend may be asked to evaluate the call
     */
    static boolean mayFold(ScalarFunction function) {
        // PURE is documented as implying DETERMINISTIC, so a library declaring only the
        // stronger property is not punished for it.
        return function.signature().has(FunctionProperty.DETERMINISTIC)
                || function.signature().has(FunctionProperty.PURE);
    }
}
