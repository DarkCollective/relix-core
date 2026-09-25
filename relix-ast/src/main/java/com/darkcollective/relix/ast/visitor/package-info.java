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
 * The visitor interfaces over the expression tree: {@link com.darkcollective.relix.ast.visitor.RelNodeVisitor},
 * {@link com.darkcollective.relix.ast.visitor.PredicateVisitor} and
 * {@link com.darkcollective.relix.ast.visitor.OperandVisitor}.
 *
 * <p>Each hierarchy is sealed, so a {@code switch} over a node is exhaustive and is
 * usually the simpler choice; a visitor suits a walk that dispatches on every kind.
 */
package com.darkcollective.relix.ast.visitor;
