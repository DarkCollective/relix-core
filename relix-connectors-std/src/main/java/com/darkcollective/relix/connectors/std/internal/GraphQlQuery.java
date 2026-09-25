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
package com.darkcollective.relix.connectors.std.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Builds a GraphQL query document asking for exactly the fields a scan was asked for.
 *
 * <p>GraphQL's projection pushdown is not a query-language fold like SQL's: the selection
 * set <em>is</em> the field list, and a connector is already handed one as the
 * {@link com.darkcollective.relix.symbol.Schema} it must produce. So there is no query to
 * render and no dialect to render it in — there is a set of paths, and this turns them into
 * the document that asks for them and nothing else.
 *
 * <p>The nesting comes from two places, and they meet in the middle. The <em>records</em>
 * path — the source's {@code extract}, minus GraphQL's {@code data} envelope — says how
 * deep the rows sit, and each column's own dotted path says what to select once there:
 *
 * <pre>{@code
 *   records: data.users        columns: id, name, author.name
 *   ⇒  { users { id name author { name } } }
 * }</pre>
 *
 * <h2>It declines rather than guesses</h2>
 * A path this cannot turn into a valid GraphQL selection — no field left after the envelope
 * is stripped, or a segment that is not a GraphQL name — yields {@link Optional#empty()},
 * and the caller sends what it would have sent anyway. Emitting a document that is nearly
 * right is the one outcome worth avoiding: a malformed selection is an error from the
 * server, and a <em>wrong</em> one is rows that look plausible.
 */
final class GraphQlQuery {

    /** A GraphQL name: what the spec allows for a field. */
    private static final Pattern NAME = Pattern.compile("[_A-Za-z][_0-9A-Za-z]*");

    /** The envelope every GraphQL response wraps its payload in; not a selectable field. */
    private static final String ENVELOPE = "data";

    private GraphQlQuery() {
    }

    /**
     * Builds the document selecting {@code fieldPaths} beneath {@code recordsPath}.
     *
     * @param recordsPath the dotted path the records sit at, envelope included
     *                    (e.g. {@code data.users}); must not be null
     * @param fieldPaths  the dotted paths of the fields to select, in the order the scan
     *                    wants its columns; must not be null
     * @return the document, or empty when it cannot be built without guessing
     */
    static Optional<String> build(String recordsPath, List<String> fieldPaths) {
        List<String> wrappers = selectable(recordsPath);
        if (wrappers.isEmpty()) {
            return Optional.empty();   // nothing to select from — the envelope was all there was
        }
        Node root = new Node();
        boolean any = false;
        for (String path : fieldPaths) {
            List<String> segments = segments(path);
            if (segments.isEmpty()) {
                return Optional.empty();   // a field this cannot name
            }
            root.add(segments);
            any = true;
        }
        if (!any) {
            return Optional.empty();   // no columns to ask for; a bare selection set is invalid
        }

        StringBuilder out = new StringBuilder("{ ");
        wrappers.forEach(w -> out.append(w).append(" { "));
        root.render(out);
        wrappers.forEach(ignored -> out.append("} "));
        return Optional.of(out.append('}').toString());
    }

    /**
     * {@return the selectable segments of {@code recordsPath}} The leading {@code data} is
     * GraphQL's response envelope rather than a field, so it is dropped; anything that is
     * not a GraphQL name makes the whole path unusable.
     */
    private static List<String> selectable(String recordsPath) {
        List<String> segments = segments(recordsPath);
        if (!segments.isEmpty() && segments.get(0).equals(ENVELOPE)) {
            segments = segments.subList(1, segments.size());
        }
        return segments;
    }

    /** {@return {@code path} split into GraphQL names, or empty if any segment is not one} */
    private static List<String> segments(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String segment : path.split("\\.")) {
            if (!NAME.matcher(segment).matches()) {
                return List.of();
            }
            out.add(segment);
        }
        return out;
    }

    /** One level of the selection set being assembled; a leaf has no children. */
    private static final class Node {
        private final Map<String, Node> children = new LinkedHashMap<>();

        void add(List<String> segments) {
            Node cursor = this;
            for (String segment : segments) {
                cursor = cursor.children.computeIfAbsent(segment, ignored -> new Node());
            }
        }

        void render(StringBuilder out) {
            children.forEach((name, child) -> {
                out.append(name).append(' ');
                if (!child.children.isEmpty()) {
                    out.append("{ ");
                    child.render(out);
                    out.append("} ");
                }
            });
        }
    }
}
