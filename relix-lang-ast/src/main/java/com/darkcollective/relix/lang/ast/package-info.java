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
/**
 * Root scripting-language AST — the {@link com.darkcollective.relix.lang.ast.Script}
 * node and the {@link com.darkcollective.relix.lang.ast.Statement} hierarchy.
 *
 * <p>A parsed {@code .relix} file is represented as a {@code Script} record
 * containing an optional namespace string and an ordered list of
 * {@code Statement}s.  The six statement kinds are:
 *
 * <ul>
 *   <li>{@link com.darkcollective.relix.lang.ast.EnvStatement} —
 *       {@code env from '...' using '...';}  declares the environment file and
 *       active profile.</li>
 *   <li>{@link com.darkcollective.relix.lang.ast.ImportStatement} —
 *       {@code import source X from '...';}  pulls symbols from another file.</li>
 *   <li>{@link com.darkcollective.relix.lang.ast.SourceDeclaration} —
 *       {@code source X from http { ... };}  declares a virtual relation backed
 *       by an external data source.</li>
 *   <li>{@link com.darkcollective.relix.lang.ast.AssignmentStatement} —
 *       {@code X := { RA-expr };}  or {@code X := [...];}  gives a name to a
 *       query expression or an inline table.</li>
 *   <li>{@link com.darkcollective.relix.lang.ast.DefStatement} —
 *       {@code def f(x: NUMBER): NUMBER := { expr };}  defines a scalar UDF.</li>
 *   <li>{@link com.darkcollective.relix.lang.ast.QueryStatement} —
 *       {@code query X;}  or {@code query { expr };}  produces output.</li>
 * </ul>
 *
 * <p>Embedded RA expressions ({@link com.darkcollective.relix.ast.RelNode}) and
 * operand expressions ({@link com.darkcollective.relix.ast.Operand}) are stored
 * as fully parsed objects produced by the {@code relix-parser} module; they are
 * not raw strings.  This keeps the AST type-safe and lets RA parse errors surface
 * at script-parse time rather than later.
 */
package com.darkcollective.relix.lang.ast;
