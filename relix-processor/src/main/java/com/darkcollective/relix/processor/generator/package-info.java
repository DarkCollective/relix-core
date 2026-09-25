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
 * Generator relations — leaf relations whose rows are produced by engine code
 * rather than read from a file or database.
 *
 * <p>A {@link com.darkcollective.relix.processor.generator.Generator} owns its
 * schema and lazily produces its rows;
 * {@link com.darkcollective.relix.processor.generator.GeneratorRegistry} indexes the
 * built-ins ({@link com.darkcollective.relix.processor.generator.RangeGenerator},
 * {@link com.darkcollective.relix.processor.generator.NaturalsGenerator},
 * {@link com.darkcollective.relix.processor.generator.PrimesGenerator})
 * and doubles as the {@link com.darkcollective.relix.semantic.internal.GeneratorCatalog} seam
 * so the same definitions supply schemas to semantic analysis and rows to execution
 * (served by {@code GeneratorDataSourceConnector}).
 *
 * <p>A generator also answers the analyses that need to reason about it as a leaf:
 * {@code GeneratorBoundednessSource} (is it infinite?),
 * {@code GeneratorDistinctnessSource} (is it duplicate-free?) and
 * {@code GeneratorMonotonicitySource} (does it ascend on a column, so an upper-bound
 * selection can stop production?).
 */
package com.darkcollective.relix.processor.generator;
