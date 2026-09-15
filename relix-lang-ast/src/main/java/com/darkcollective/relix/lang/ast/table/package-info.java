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
 * Inline table AST nodes — the two surface formats for embedding relation data
 * directly in a {@code .relix} script.
 *
 * <p>Both formats store cell values as raw strings.  Type inference (STRING vs
 * NUMBER) and conversion to {@link com.darkcollective.relix.ast.Operand} values
 * are deferred to the semantic layer, which applies the rule: a column whose
 * every cell parses as a numeric literal is typed
 * {@link com.darkcollective.relix.symbol.ScalarType#NUMBER}, otherwise
 * {@link com.darkcollective.relix.symbol.ScalarType#STRING}.
 */
package com.darkcollective.relix.lang.ast.table;
