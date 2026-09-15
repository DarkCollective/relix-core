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

import com.darkcollective.relix.ast.SourceLocation;

/**
 * Sealed root interface for every top-level declaration in a {@code .relix} script.
 *
 * <p>The permitted statement kinds map directly to the syntactic forms
 * described in the {@link com.darkcollective.relix.lang.ast package overview}.
 *
 * <p>All implementations are records and are therefore immutable.  Pattern
 * matching on this interface is exhaustive at compile time.
 */
public sealed interface Statement
        permits EnvStatement,
                ImportStatement,
                ConnectionDeclaration,
                SourceDeclaration,
                RelateStatement,
                AssignmentStatement,
                DefStatement,
                DefRelationStatement,
                QueryStatement {

    /** Returns the source location of the first token of this statement. */
    SourceLocation location();
}
