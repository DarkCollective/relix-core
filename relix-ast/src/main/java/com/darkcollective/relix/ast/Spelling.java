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
package com.darkcollective.relix.ast;

import java.util.Map;
import java.util.Objects;

/**
 * Which of an operator's two spellings printed text uses.
 *
 * <p>Every operator is written either as a glyph ({@code σ}, {@code ⋈}, {@code ≤}) or as
 * its ASCII form ({@code SELECT}, {@code JOIN}, {@code <=}), and the two parse to the same
 * tree. Printing chooses one: {@link #GLYPHS}, what the engine prints by default, reads
 * like the algebra; {@link #KEYWORDS} needs no special keyboard and is the form
 * {@code language/spellings.md} recommends for generated code. Text printed either way
 * parses back to the same tree.
 *
 * @since 1.0
 */
public enum Spelling {

    /** The glyphs: {@code σ amount > 100 (Orders)}. */
    GLYPHS,

    /** The ASCII forms: {@code SELECT amount > 100 (Orders)}. */
    KEYWORDS;

    /** Each glyph a printer writes, and its ASCII form — {@code language/spellings.md}. */
    private static final Map<String, String> ASCII = Map.ofEntries(
            // Unary operators
            Map.entry("π", "PROJECT"),
            Map.entry("σ", "SELECT"),
            Map.entry("ρ", "RENAME"),
            Map.entry("γ", "GROUP"),
            Map.entry("τ", "SORT"),
            Map.entry("λ", "LIMIT"),
            Map.entry("δ", "DISTINCT"),
            Map.entry("μ", "UNNEST"),
            Map.entry("ω", "WHY"),
            Map.entry("∀", "FORALL"),
            // Joins
            Map.entry("⋈", "JOIN"),
            Map.entry("⨝", "><"),
            Map.entry("⟕", "|><"),
            Map.entry("⟖", "><|"),
            Map.entry("⟗", "|><|"),
            Map.entry("⋉", "SEMI"),
            Map.entry("▷", "ANTI"),
            // Set operations
            Map.entry("×", "CROSS"),
            Map.entry("∪", "UNION"),
            Map.entry("⊎", "UALL"),
            Map.entry("⊔", "OUNION"),
            Map.entry("−", "DIFF"),
            Map.entry("∆", "SYMDIFF"),
            Map.entry("∩", "INTER"),
            Map.entry("÷", "DIV"),
            Map.entry("∘", "COMPOSE"),
            // Logical and comparison operators
            Map.entry("∧", "AND"),
            Map.entry("∨", "OR"),
            Map.entry("¬", "NOT"),
            Map.entry("≠", "!="),
            Map.entry("≤", "<="),
            Map.entry("≥", ">="),
            // Special forms
            Map.entry("→", "->"),
            Map.entry("↔", "<->"),
            Map.entry("⊥", "NULL"),
            Map.entry("∈", "IN"),
            Map.entry("∉", "NOT IN"));

    /**
     * How this spelling writes an operator.
     *
     * @param glyph an operator's glyph, such as {@code σ}; a symbol with no glyph of its
     *              own ({@code =}, {@code <}) is written the same either way
     * @return {@code glyph} for {@link #GLYPHS}; its ASCII form for {@link #KEYWORDS}
     * @since 1.0
     */
    public String of(String glyph) {
        Objects.requireNonNull(glyph, "glyph");
        return this == GLYPHS ? glyph : ASCII.getOrDefault(glyph, glyph);
    }
}
