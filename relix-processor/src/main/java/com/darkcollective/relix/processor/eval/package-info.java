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
 * Scalar expression and predicate evaluation for query execution.
 *
 * <p>Two evaluators drive all row-level computation:
 * <ul>
 *   <li>{@link com.darkcollective.relix.processor.eval.OperandEvaluator} — evaluates
 *       any {@link com.darkcollective.relix.ast.Operand} expression against a
 *       {@link com.darkcollective.relix.processor.Row}, returning a
 *       {@link com.darkcollective.relix.value.Value}.  Handles all eight
 *       operand subtypes including arithmetic, attribute references, and the full
 *       built-in function catalogue.</li>
 *   <li>{@link com.darkcollective.relix.processor.eval.PredicateEvaluator} — evaluates
 *       any {@link com.darkcollective.relix.ast.Predicate} against a row, returning
 *       a {@code boolean}.  NULL values follow SQL three-valued logic.</li>
 * </ul>
 *
 * <p>{@link com.darkcollective.relix.processor.EvaluationException} is the
 * unchecked exception thrown when evaluation cannot proceed (unknown column,
 * type mismatch, bad function arity, etc.).
 */
package com.darkcollective.relix.processor.eval;
