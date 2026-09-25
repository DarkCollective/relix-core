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
package com.darkcollective.relix.embed;

import java.util.List;
import java.util.Objects;

/**
 * One page of the language reference, as {@link Relix#referencePages()} lists it.
 *
 * <p>The markdown itself is {@link Relix#referencePage(String)} of {@link #path()}. The
 * other components are what a tool needs to find and list a page without reading it:
 * the words a user might look it up by, and a one-line summary.
 *
 * @param path     where the page sits in the reference, such as
 *                 {@code operators/select.md}; the argument to {@code referencePage}
 * @param category the family it belongs to: {@code operator}, {@code join},
 *                 {@code set}, {@code aggregate}, {@code predicate}, {@code literal},
 *                 {@code language}, {@code advanced} or {@code guide}
 * @param title    a short name, such as {@code Selection}
 * @param symbol   how the construct is written, such as {@code σ} or {@code ROLLING};
 *                 for a page about no single construct, a key word for it
 * @param summary  one line saying what it is for
 * @param keys     every word the page can be looked up by, lower case except for
 *                 glyphs: its symbol, its keyword spellings, its common names
 * @since 1.0
 */
public record ReferencePage(String path, String category, String title, String symbol,
                            String summary, List<String> keys) {

    /**
     * Checks every component and copies the keys.
     *
     * @since 1.0
     */
    public ReferencePage {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(summary, "summary");
        keys = List.copyOf(keys);
    }
}
