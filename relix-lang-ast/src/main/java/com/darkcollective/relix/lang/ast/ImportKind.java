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
package com.darkcollective.relix.lang.ast;

/**
 * Classifies the kind of symbol being imported in an
 * {@link ImportStatement}.
 *
 * <p>The kind is declared explicitly in the import syntax:
 * <pre>
 *   import source  current_weather from './weather.relix';   // SOURCE
 *   import relation USCities       from './cities.relix';    // RELATION
 *   import function tax            from './calc.relix';      // FUNCTION
 *   import { Users, Orders }       from './db.relix';        // UNQUALIFIED
 *   import './common.relix';                                  // BULK
 * </pre>
 *
 * <p>{@link #UNQUALIFIED} is used when names are imported with braces but
 * without an explicit type keyword.  {@link #BULK} is used when the entire
 * file is imported with no name list.
 */
public enum ImportKind {

    /** The imported name is expected to resolve to a source declaration. */
    SOURCE,

    /** The imported name is expected to resolve to a relation (view or inline table). */
    RELATION,

    /** The imported name is expected to resolve to a function definition. */
    FUNCTION,

    /**
     * Names are imported without a type qualifier ({@code import { X, Y } from '...'}).
     * The semantic layer resolves what kind each name refers to.
     */
    UNQUALIFIED,

    /**
     * All exported names from the target file are imported into the current
     * namespace ({@code import './file.relix'}).
     */
    BULK
}
