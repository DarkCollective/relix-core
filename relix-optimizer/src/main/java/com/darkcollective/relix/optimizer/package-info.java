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
 * What the optimizer reports about a rewrite.
 *
 * <p>A {@link com.darkcollective.relix.optimizer.TransformationRecord} says which rule
 * fired, on what, and why; its {@link com.darkcollective.relix.optimizer.OptimizationCode}
 * names the rule. {@code Relation.rewrites()} returns them.
 */
package com.darkcollective.relix.optimizer;
