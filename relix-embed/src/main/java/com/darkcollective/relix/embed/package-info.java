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
 * The Relix embedding API — what a plain Java program depends on to build, optimise,
 * inspect and run a query.
 *
 * <p>Two types carry it. {@link com.darkcollective.relix.embed.Relix} is the
 * <strong>session</strong>: the declarations a script would hold, together with the
 * bindings that connect them outward — a live {@code DataSource}, a catalog, a clock.
 * {@link com.darkcollective.relix.embed.Relation} is the <strong>value</strong>: an
 * expression together with the analysis it resolves against. Operators are functions
 * from relations to relations, and nothing executes until an execution terminal is
 * called.
 *
 * <h2>What this artifact promises</h2>
 *
 * <p>{@code relix-embed} is <strong>backward compatible within a major version</strong>.
 * Code that compiles and runs against one release keeps compiling and running against
 * every later release of the same major.
 *
 * <ul>
 *   <li><strong>Every public member records when it arrived</strong>, with
 *       {@code @since}. That is what makes the rest of this readable: a promise about
 *       when something may be removed says nothing without the release it appeared in.</li>
 *   <li><strong>Nothing is removed without warning.</strong> A member on its way out is
 *       marked {@code @Deprecated} for at least one minor release, with the replacement
 *       named, and can only disappear at a major.</li>
 *   <li><strong>The types this API hands back are part of it.</strong> A relation's
 *       schema is the engine's {@code Schema}, a row is its {@code Row}, an event its
 *       {@code QueryEvent} — deliberately, because a second {@code Schema} type would be
 *       a permanent tax on every caller. The consequence is that those engine types are
 *       under this promise too, and the set of them is fixed rather than left to grow
 *       quietly.</li>
 * </ul>
 *
 * <p>Both claims are checked on every build rather than remembered.
 *
 * <h2>One artifact</h2>
 *
 * <p>Everything above ships in a single dependency, {@code com.darkcollective.relix:relix}:
 * the engine, the default function library, the standard connectors and a solver. A
 * program needs that and a JDBC driver.
 *
 * <p>The modules the engine is built from are not published separately. Their boundaries
 * are enforced where they mean something — in the build that defines them — and were
 * never a surface to write code against.
 *
 */
package com.darkcollective.relix.embed;
