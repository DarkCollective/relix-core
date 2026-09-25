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

import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.symbol.ScalarType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A web server's access-log format string, compiled into the pattern that splits a line
 * and the type each field has.
 *
 * <p>Two vocabularies, told apart by their sigil: Apache's {@code LogFormat}
 * ({@code %h %l %u %t "%r" %>s %b}) and nginx's {@code log_format}
 * ({@code $remote_addr - $remote_user [$time_local] "$request" …}). A string mixing the
 * two is refused, as is a directive neither covers: guessing what an unknown directive
 * holds would make its column a string when it was meant to be something else.
 *
 * <p>The named presets are Apache's {@code common} and {@code combined}.
 */
final class LogFormat {

    /** Apache's {@code common} format. */
    static final String COMMON = "%h %l %u %t \"%r\" %>s %b";
    /** Apache's {@code combined} format. */
    static final String COMBINED = COMMON + " \"%{Referer}i\" \"%{User-Agent}i\"";

    /** What a field holds, which decides both its pattern and its type. */
    enum Kind {
        /** A token with no spaces. */
        TEXT(ScalarType.STRING),
        /** An integer or decimal; {@code -} is NULL. */
        NUMBER(ScalarType.NUMBER),
        /** Apache's {@code [10/Oct/2026:13:55:36 +0000]}, brackets included. */
        CLF_TIME(ScalarType.TIMESTAMP),
        /** The same time without its brackets, as nginx writes {@code $time_local}. */
        LOCAL_TIME(ScalarType.TIMESTAMP),
        /** An ISO-8601 timestamp with an offset. */
        ISO_TIME(ScalarType.TIMESTAMP),
        /** A request line, split into method, path and protocol. */
        REQUEST(ScalarType.STRING);

        final ScalarType type;

        Kind(ScalarType type) {
            this.type = type;
        }
    }

    /** One field of the format: the column it fills and what it holds. */
    record Field(String column, Kind kind) {}

    private final Pattern pattern;
    private final List<Field> fields;

    private LogFormat(Pattern pattern, List<Field> fields) {
        this.pattern = pattern;
        this.fields = fields;
    }

    /** {@return the fields in the order the pattern captures them} */
    List<Field> fields() {
        return fields;
    }

    /**
     * {@return the columns this format produces, with their types} A request line
     * produces three.
     */
    Map<String, ScalarType> columns() {
        Map<String, ScalarType> out = new LinkedHashMap<>();
        for (Field field : fields) {
            if (field.kind() == Kind.REQUEST) {
                out.put("method", ScalarType.STRING);
                out.put("path", ScalarType.STRING);
                out.put("protocol", ScalarType.STRING);
            } else {
                out.put(field.column(), field.kind().type);
            }
        }
        return out;
    }

    /** {@return the captured text of each field, or null when the line does not match} */
    List<String> split(String line) {
        Matcher m = pattern.matcher(line);
        if (!m.matches()) {
            return null;
        }
        List<String> out = new ArrayList<>(fields.size());
        for (int i = 1; i <= fields.size(); i++) {
            out.add(m.group(i));
        }
        return out;
    }

    /**
     * Compiles a preset name or a literal format string.
     *
     * @throws EvaluationException for an unknown directive, a string mixing Apache and
     *                             nginx directives, or one naming a column twice
     */
    static LogFormat parse(String spec) {
        String format = switch (spec.strip().toLowerCase(Locale.ROOT)) {
            case "common", "clf" -> COMMON;
            case "combined" -> COMBINED;
            default -> spec;
        };
        boolean apache = format.contains("%");
        boolean nginx = Pattern.compile("\\$[a-z_]").matcher(format).find();
        if (apache && nginx) {
            throw new EvaluationException("log format mixes Apache (%) and nginx ($) directives: " + format);
        }
        if (!apache && !nginx) {
            throw new EvaluationException("log format names no fields: '" + spec
                    + "'; use 'common', 'combined', or a server's own format string");
        }
        return apache ? apache(format) : nginx(format);
    }

    // ── Apache ────────────────────────────────────────────────────────────────

    private static final Pattern APACHE = Pattern.compile("%(?:\\{([^}]*)})?[<>]?([a-zA-Z%])");

    private static LogFormat apache(String format) {
        Builder b = new Builder(format);
        Matcher m = APACHE.matcher(format);
        int at = 0;
        while (m.find()) {
            b.literal(format.substring(at, m.start()));
            at = m.end();
            String argument = m.group(1);
            char directive = m.group(2).charAt(0);
            if (directive == '%') {
                b.literal("%");
                continue;
            }
            b.field(apacheField(directive, argument, m.group()));
        }
        b.literal(format.substring(at));
        return b.build();
    }

    private static Field apacheField(char directive, String argument, String spelled) {
        if (argument != null) {
            if (directive == 'i') {
                return new Field(headerColumn(argument), Kind.TEXT);
            }
            throw unknown(spelled);
        }
        return switch (directive) {
            case 'h' -> new Field("host", Kind.TEXT);
            case 'a' -> new Field("address", Kind.TEXT);
            case 'l' -> new Field("ident", Kind.TEXT);
            case 'u' -> new Field("user", Kind.TEXT);
            case 't' -> new Field("at", Kind.CLF_TIME);
            case 'r' -> new Field("request", Kind.REQUEST);
            case 's' -> new Field("status", Kind.NUMBER);
            case 'b', 'B' -> new Field("bytes", Kind.NUMBER);
            case 'D' -> new Field("duration_us", Kind.NUMBER);
            case 'T' -> new Field("duration_s", Kind.NUMBER);
            case 'm' -> new Field("method", Kind.TEXT);
            case 'U' -> new Field("path", Kind.TEXT);
            case 'q' -> new Field("query", Kind.TEXT);
            case 'H' -> new Field("protocol", Kind.TEXT);
            case 'v' -> new Field("server", Kind.TEXT);
            default -> throw unknown(spelled);
        };
    }

    // ── nginx ─────────────────────────────────────────────────────────────────

    private static final Pattern NGINX = Pattern.compile("\\$\\{?([a-z_][a-z0-9_]*)}?");

    private static LogFormat nginx(String format) {
        Builder b = new Builder(format);
        Matcher m = NGINX.matcher(format);
        int at = 0;
        while (m.find()) {
            b.literal(format.substring(at, m.start()));
            at = m.end();
            b.field(nginxField(m.group(1), m.group()));
        }
        b.literal(format.substring(at));
        return b.build();
    }

    private static Field nginxField(String variable, String spelled) {
        if (variable.startsWith("http_") && variable.length() > "http_".length()) {
            return new Field(headerColumn(variable.substring("http_".length())), Kind.TEXT);
        }
        return switch (variable) {
            case "remote_addr" -> new Field("host", Kind.TEXT);
            case "remote_user" -> new Field("user", Kind.TEXT);
            case "time_local" -> new Field("at", Kind.LOCAL_TIME);
            case "time_iso8601" -> new Field("at", Kind.ISO_TIME);
            case "request" -> new Field("request", Kind.REQUEST);
            case "status" -> new Field("status", Kind.NUMBER);
            case "body_bytes_sent" -> new Field("bytes", Kind.NUMBER);
            case "bytes_sent" -> new Field("bytes_sent", Kind.NUMBER);
            case "request_time" -> new Field("duration_s", Kind.NUMBER);
            case "request_method" -> new Field("method", Kind.TEXT);
            case "request_uri" -> new Field("path", Kind.TEXT);
            case "server_protocol" -> new Field("protocol", Kind.TEXT);
            case "host" -> new Field("server", Kind.TEXT);
            default -> throw unknown(spelled);
        };
    }

    // ── shared ────────────────────────────────────────────────────────────────

    /** {@code User-Agent} and {@code user_agent} are {@code agent}; any other header its own name. */
    private static String headerColumn(String header) {
        String name = header.toLowerCase(Locale.ROOT).replace('-', '_');
        return name.equals("user_agent") ? "agent" : name;
    }

    private static EvaluationException unknown(String spelled) {
        return new EvaluationException("log format directive '" + spelled + "' is not one relix reads");
    }

    /** Accumulates the pattern, choosing each field's sub-pattern from what surrounds it. */
    private static final class Builder {
        private final String format;
        private final StringBuilder regex = new StringBuilder("^");
        private final List<Field> fields = new ArrayList<>();
        private final List<String> columns = new ArrayList<>();
        private String lastLiteral = "";

        Builder(String format) {
            this.format = format;
        }

        void literal(String text) {
            if (!text.isEmpty()) {
                regex.append(Pattern.quote(text));
                lastLiteral = text;
            }
        }

        void field(Field field) {
            for (String column : field.kind() == Kind.REQUEST
                    ? List.of("method", "path", "protocol") : List.of(field.column())) {
                if (columns.contains(column)) {
                    throw new EvaluationException("log format fills '" + column + "' twice: " + format);
                }
                columns.add(column);
            }
            if (field.kind() == Kind.CLF_TIME) {
                regex.append("\\[([^\\]]*)\\]");
            } else if (lastLiteral.endsWith("\"")) {
                regex.append("((?:[^\"\\\\]|\\\\.)*)");
            } else if (lastLiteral.endsWith("[")) {
                regex.append("([^\\]]*)");
            } else {
                regex.append("(\\S*)");
            }
            fields.add(field);
            lastLiteral = "";
        }

        LogFormat build() {
            return new LogFormat(Pattern.compile(regex.append("$").toString()), List.copyOf(fields));
        }
    }
}
