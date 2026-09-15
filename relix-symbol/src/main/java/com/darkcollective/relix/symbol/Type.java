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
package com.darkcollective.relix.symbol;

/**
 * The type of a value in relix — a scalar, a nested struct, or an array.
 *
 * <p>This is the root of the (small) type system that lets relix represent and
 * query nested data natively.  The three shapes are:
 * <ul>
 *   <li>{@link ScalarType} — {@code NUMBER}, {@code STRING}, {@code BOOLEAN}, and
 *       {@code ANY}.  {@code ANY} doubles as the <em>dynamic document</em> type for
 *       schema-on-read sources: a value of type {@code ANY} may hold a scalar, a
 *       struct, or an array, resolved at runtime.</li>
 *   <li>{@link StructType} — an ordered set of named, typed fields (a row is a
 *       struct; a {@link Schema} is, conceptually, the top-level struct).</li>
 *   <li>{@link ArrayType} — an ordered collection of elements of a single type.</li>
 * </ul>
 *
 * <p>The flat relational model is the special case where every field is a
 * {@link ScalarType}.  The hierarchy is sealed, so consumers can pattern-match it
 * exhaustively.
 */
public sealed interface Type permits ScalarType, StructType, ArrayType {

    /**
     * A short, human-readable, structurally-recursive description of this type —
     * e.g. {@code "number"}, {@code "struct{id: number, tags: array<string>}"},
     * {@code "array<number>"}.
     *
     * @return the display string; never null or blank
     */
    String display();

    /**
     * A compact, structurally-recursive type code used in the IR report and in
     * machine-readable (JSON) output.  Scalars are one letter
     * ({@code N}umber, {@code S}tring, {@code B}oolean, {@code ?}=any); an array
     * is {@code [elem]} and a struct is {@code {field:code,…}} — e.g.
     * {@code [N]}, {@code {id:N,tags:[S]}}.
     *
     * @return the compact type code; never null or blank
     */
    String code();
}
