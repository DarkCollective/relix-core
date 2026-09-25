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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.ApiKeyAuth;
import com.darkcollective.relix.lang.ast.source.BasicAuth;
import com.darkcollective.relix.lang.ast.source.BearerAuth;
import com.darkcollective.relix.lang.ast.source.ColumnDirection;
import com.darkcollective.relix.lang.ast.source.ColumnSpec;
import com.darkcollective.relix.lang.ast.source.HeaderBinding;
import com.darkcollective.relix.lang.ast.source.HttpMethod;
import com.darkcollective.relix.lang.ast.source.HttpSourceConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The checks on an HTTP source's request that are fixed facts about its declaration,
 * so that a request which can never be sent as written is refused at analysis rather
 * than at the first query that reads it.
 *
 * <p>Three rules. A {@code QUERY} source must declare a {@code body}, since RFC 10008
 * makes the body the query and a server must refuse one without it. No header may be
 * set twice — by {@code headers}, by {@code auth}, or by an {@code IN} column bound
 * {@code as header(…)} — compared case-insensitively, because the connector would keep
 * one and drop the other with no sign of which (#1103). And no header may be one
 * {@code java.net.http.HttpClient} refuses to send: a restricted name, a name that is
 * not an HTTP token, or a value holding a line break.
 */
final class HttpSourceChecks {

    /** The names {@code HttpClient} will not let a caller set. */
    static final Set<String> RESTRICTED = Set.of(
            "connection", "content-length", "expect", "host", "upgrade");

    private HttpSourceChecks() {}

    /**
     * {@return the problems with {@code http}'s request, as messages naming {@code src}}
     *
     * @param src  the declaration, for its name
     * @param http its HTTP configuration
     */
    static List<String> check(SourceDeclaration src, HttpSourceConfig http) {
        List<String> problems = new ArrayList<>();
        String source = "HTTP source '" + src.name() + "'";

        if (http.method() == HttpMethod.QUERY && http.body().isEmpty()) {
            problems.add(source + " uses method QUERY but declares no 'body': "
                    + "a QUERY request carries its query in the body");
        }

        // Every header the request will carry, with where it came from.
        Map<String, String> seen = new LinkedHashMap<>();
        http.headers().forEach((name, value) -> {
            header(problems, seen, source, name, "headers");
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                problems.add(source + ": the value of header '" + name + "' contains a line break");
            }
        });
        http.auth().ifPresent(auth -> {
            switch (auth) {
                case BearerAuth ignored -> header(problems, seen, source, "Authorization", "auth: bearer(…)");
                case BasicAuth ignored -> header(problems, seen, source, "Authorization", "auth: basic(…)");
                case ApiKeyAuth key -> {
                    if (key.location() == ApiKeyAuth.ApiKeyLocation.HEADER) {
                        header(problems, seen, source, key.name(), "auth: apikey(…)");
                    }
                }
            }
        });
        for (ColumnSpec col : http.columns()) {
            if (col.direction() == ColumnDirection.IN
                    && col.binding().orElse(null) instanceof HeaderBinding h) {
                header(problems, seen, source, h.headerName(), "column '" + col.name() + "'");
            }
        }
        return problems;
    }

    private static void header(List<String> problems, Map<String, String> seen,
                               String source, String name, String origin) {
        String key = name.toLowerCase(Locale.ROOT);
        String earlier = seen.putIfAbsent(key, origin);
        if (earlier != null) {
            problems.add(source + " sets header '" + name + "' twice, in " + earlier
                    + " and in " + origin + "; remove one");
        }
        if (RESTRICTED.contains(key)) {
            problems.add(source + ": header '" + name + "' cannot be set; the HTTP client "
                    + "manages it (Connection, Content-Length, Expect, Host and Upgrade)");
        } else if (!isToken(name)) {
            problems.add(source + ": '" + name + "' is not a valid HTTP header name");
        }
    }

    /** {@return whether {@code name} is an RFC 9110 token, the grammar of a header name} */
    static boolean isToken(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
