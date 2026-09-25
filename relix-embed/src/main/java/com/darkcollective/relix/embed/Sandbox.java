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

import com.darkcollective.relix.json.JsonReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * What a session lets its users reach, and how much work one query may do.
 *
 * <p>A session is <strong>open</strong> by default: every declaration the language has is
 * accepted, and nothing is limited beyond what the {@link Relix.Builder} sets. A
 * <strong>closed</strong> sandbox is for a session whose text comes from someone the host
 * does not fully trust, such as a learner or a language model. In a closed sandbox:
 *
 * <ul>
 *   <li>internal declarations are always accepted: inline tables, views, functions,
 *       {@code relate}, and generator sources, none of which reads anything outside the
 *       session;</li>
 *   <li>an <em>external</em> declaration is accepted only if the sandbox's own
 *       declarations contain the same one. External means a {@code source} that reads a
 *       file, a database or an HTTP endpoint, a {@code connection}, an {@code import} and
 *       an {@code env} statement. The comparison is on the declaration as printed, so
 *       layout and comments do not matter but every value does;</li>
 *   <li>the limits below apply to every query.</li>
 * </ul>
 *
 * <p>The sandbox's declarations are installed when the session is built, so the sources
 * and connections they name are ready to query. They may use {@code ${NAME}} placeholders,
 * resolved through {@link Relix.Builder#placeholders} like any other declaration, which
 * keeps credentials out of the configuration file.
 *
 * <p>The rules govern text and statements a session is given through
 * {@link Relix#define(String)}, {@link Relix#define(com.darkcollective.relix.lang.ast.Statement...)},
 * {@link Relix#script(String)}, {@link Relix#relation(String)} and
 * {@link Relix#validate(String)}. The Java registration methods ({@link Relix#table},
 * {@link Relix#source}, {@link Relix#connector} and the builder's bindings) are the
 * host's own and are not restricted.
 *
 * <h2>The configuration file</h2>
 *
 * {@link #load(Path)} reads a JSON document. Every key is optional; an unknown key is
 * refused, so a misspelt limit fails rather than silently not applying.
 *
 * {@snippet lang = "json":
 * {
 *   "declarations": "sandbox.relix",
 *   "limits": {
 *     "maxInputChars": 20000,
 *     "maxOutputRows": 1000,
 *     "maxMaterializedRows": 100000,
 *     "maxFixpointRounds": 1000,
 *     "maxProcessedRows": 10000000,
 *     "timeoutMillis": 30000
 *   }
 * }
 * }
 *
 * <p>{@code declarations} names a {@code .relix} file, relative to the configuration
 * file, holding the external declarations users may reach. It may also hold internal
 * ones, such as a fixed inline table. Relative paths inside it resolve against the
 * configuration file's directory, unless the builder names another base directory.
 *
 * <h2>The limits</h2>
 *
 * <ul>
 *   <li>{@code maxInputChars}: the longest text one call may pass. Longer text is
 *       refused before it is parsed.</li>
 *   <li>{@code maxOutputRows}: the most rows a query returns. A longer result is cut
 *       at that many rows, and the cut is reported: {@link Rows#truncated()} is true,
 *       and the run's events include an {@code EXECUTE}/{@code TRUNCATED} event, which
 *       {@link Relation#stream(com.darkcollective.relix.events.QueryEventListener)} also
 *       delivers. {@link Relation#toList()} and {@link Relation#stream()} are cut the same
 *       way but carry no events, so a host that must tell its user uses {@code run()}.
 *       {@link Relation#count()} is one row and counts the whole relation.</li>
 *   <li>{@code maxMaterializedRows} and {@code maxFixpointRounds}: the same caps as
 *       {@link Relix.Builder#maxMaterializedRows(int)} and
 *       {@link Relix.Builder#maxFixpointRounds(int)}. Where both set one, the smaller
 *       applies.</li>
 *   <li>{@code maxProcessedRows} and {@code timeoutMillis}: how much work one execution
 *       may do, counted as rows passed between operators, and how long it may run. These
 *       stop a query that works for a long time while producing little, such as a
 *       selection over an endless generator that matches nothing, which an output limit
 *       never reaches. See {@link Relix.Builder#maxProcessedRows(long)} and
 *       {@link Relix.Builder#timeout(Duration)}; where both set one, the smaller
 *       applies.</li>
 * </ul>
 *
 * @since 1.0
 */
public final class Sandbox {

    private static final Set<String> TOP_LEVEL_KEYS = Set.of("declarations", "limits");
    private static final Set<String> LIMIT_KEYS = Set.of(
            "maxInputChars", "maxOutputRows", "maxMaterializedRows", "maxFixpointRounds",
            "maxProcessedRows", "timeoutMillis");

    private static final Sandbox OPEN = new Sandbox(true, "", Optional.empty(),
            OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(),
            OptionalLong.empty(), Optional.empty());

    private final boolean open;
    private final String declarations;
    private final Optional<Path> baseDirectory;
    private final OptionalInt maxInputChars;
    private final OptionalInt maxOutputRows;
    private final OptionalInt maxMaterializedRows;
    private final OptionalInt maxFixpointRounds;
    private final OptionalLong maxProcessedRows;
    private final Optional<Duration> timeout;

    private Sandbox(boolean open, String declarations, Optional<Path> baseDirectory,
                    OptionalInt maxInputChars, OptionalInt maxOutputRows,
                    OptionalInt maxMaterializedRows, OptionalInt maxFixpointRounds,
                    OptionalLong maxProcessedRows, Optional<Duration> timeout) {
        this.open = open;
        this.declarations = declarations;
        this.baseDirectory = baseDirectory;
        this.maxInputChars = maxInputChars;
        this.maxOutputRows = maxOutputRows;
        this.maxMaterializedRows = maxMaterializedRows;
        this.maxFixpointRounds = maxFixpointRounds;
        this.maxProcessedRows = maxProcessedRows;
        this.timeout = timeout;
    }

    /**
     * The open sandbox: no rules and no limits. A session built without a sandbox has
     * this one.
     *
     * @return the open sandbox
     * @since 1.0
     */
    public static Sandbox open() {
        return OPEN;
    }

    /**
     * Starts building a closed sandbox. With nothing added, it permits no external
     * declaration at all and sets no limit.
     *
     * @return a new builder
     * @since 1.0
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Reads a closed sandbox from a configuration file. See the class description for
     * the format.
     *
     * @param configFile the JSON configuration file; must not be null
     * @return the sandbox it describes
     * @throws RelixException if the file cannot be read, is not a JSON object, names a
     *                        key this format does not have, gives a limit that is not a
     *                        positive integer, or names a declarations file that cannot
     *                        be read
     * @since 1.0
     */
    public static Sandbox load(Path configFile) {
        Objects.requireNonNull(configFile, "configFile");
        Path absolute = configFile.toAbsolutePath();
        Object parsed;
        try {
            parsed = JsonReader.parse(read(absolute));
        } catch (IllegalArgumentException e) {
            throw new RelixException("sandbox configuration " + absolute + " is not valid JSON: "
                    + e.getMessage(), e);
        }
        Map<String, Object> config = object(parsed, "the configuration", absolute);
        requireKnown(config, TOP_LEVEL_KEYS, "", absolute);
        Path directory = absolute.getParent();
        Builder builder = builder().baseDirectory(directory);
        Object declarationsFile = config.get("declarations");
        if (declarationsFile != null) {
            if (!(declarationsFile instanceof String file)) {
                throw new RelixException("sandbox configuration " + absolute
                        + ": \"declarations\" must be a file name");
            }
            builder.declarations(read(directory.resolve(file)));
        }
        Object limits = config.get("limits");
        if (limits != null) {
            Map<String, Object> values = object(limits, "\"limits\"", absolute);
            requireKnown(values, LIMIT_KEYS, "limits.", absolute);
            limit(values, "maxInputChars", absolute).ifPresent(builder::maxInputChars);
            limit(values, "maxOutputRows", absolute).ifPresent(builder::maxOutputRows);
            limit(values, "maxMaterializedRows", absolute).ifPresent(builder::maxMaterializedRows);
            limit(values, "maxFixpointRounds", absolute).ifPresent(builder::maxFixpointRounds);
            longLimit(values, "maxProcessedRows", absolute).ifPresent(builder::maxProcessedRows);
            longLimit(values, "timeoutMillis", absolute)
                    .ifPresent(millis -> builder.timeout(Duration.ofMillis(millis)));
        }
        return builder.build();
    }

    /**
     * {@return whether this is the open sandbox, which enforces nothing}
     *
     * @since 1.0
     */
    public boolean isOpen() {
        return open;
    }

    /**
     * {@return the {@code .relix} text installed when a session is built: the external
     * declarations users may reach, and any fixed data; empty for none}
     *
     * @since 1.0
     */
    public String declarations() {
        return declarations;
    }

    /**
     * {@return the directory relative paths in the declarations resolve against, when the
     * sandbox names one}
     *
     * @since 1.0
     */
    public Optional<Path> baseDirectory() {
        return baseDirectory;
    }

    /**
     * {@return the longest text one call may pass, when limited}
     *
     * @since 1.0
     */
    public OptionalInt maxInputChars() {
        return maxInputChars;
    }

    /**
     * {@return the most rows one query returns, when limited}
     *
     * @since 1.0
     */
    public OptionalInt maxOutputRows() {
        return maxOutputRows;
    }

    /**
     * {@return the most rows one blocking operator may buffer, when limited}
     *
     * @since 1.0
     */
    public OptionalInt maxMaterializedRows() {
        return maxMaterializedRows;
    }

    /**
     * {@return the most rounds one recursion may run, when limited}
     *
     * @since 1.0
     */
    public OptionalInt maxFixpointRounds() {
        return maxFixpointRounds;
    }

    /**
     * {@return the most rows one execution's operators may pass to one another, when
     * limited}
     *
     * @since 1.0
     */
    public OptionalLong maxProcessedRows() {
        return maxProcessedRows;
    }

    /**
     * {@return how long one execution may run, when limited}
     *
     * @since 1.0
     */
    public Optional<Duration> timeout() {
        return timeout;
    }

    @Override
    public String toString() {
        if (open) {
            return "Sandbox[open]";
        }
        return "Sandbox[closed, maxInputChars=" + describe(maxInputChars)
                + ", maxOutputRows=" + describe(maxOutputRows)
                + ", maxMaterializedRows=" + describe(maxMaterializedRows)
                + ", maxFixpointRounds=" + describe(maxFixpointRounds)
                + ", maxProcessedRows=" + (maxProcessedRows.isPresent()
                        ? Long.toString(maxProcessedRows.getAsLong()) : "unlimited")
                + ", timeout=" + timeout.map(Duration::toString).orElse("unlimited") + "]";
    }

    private static String describe(OptionalInt limit) {
        return limit.isPresent() ? Integer.toString(limit.getAsInt()) : "unlimited";
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new RelixException("cannot read sandbox file " + file + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String what, Path file) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new RelixException("sandbox configuration " + file + ": " + what + " must be a JSON object");
    }

    private static void requireKnown(Map<String, Object> values, Set<String> known, String prefix,
                                     Path file) {
        for (String key : values.keySet()) {
            if (!known.contains(key)) {
                throw new RelixException("sandbox configuration " + file + ": unknown key \""
                        + prefix + key + "\"; expected one of "
                        + known.stream().sorted().map(k -> prefix + k).toList());
            }
        }
    }

    private static OptionalLong longLimit(Map<String, Object> values, String key, Path file) {
        Object value = values.get(key);
        if (value == null) {
            return OptionalLong.empty();
        }
        if (value instanceof String text) {
            try {
                long parsed = Long.parseLong(text);
                if (parsed >= 1) {
                    return OptionalLong.of(parsed);
                }
            } catch (NumberFormatException ignored) {
                // falls through to the refusal below
            }
        }
        throw new RelixException("sandbox configuration " + file + ": limits." + key
                + " must be a positive integer, not " + value);
    }

    private static OptionalInt limit(Map<String, Object> values, String key, Path file) {
        Object value = values.get(key);
        if (value == null) {
            return OptionalInt.empty();
        }
        // The reader keeps a number's text verbatim, so a fraction or an exponent arrives
        // here as the string it was and is refused by the same parse a typo would be.
        if (value instanceof String text) {
            try {
                int parsed = Integer.parseInt(text);
                if (parsed >= 1) {
                    return OptionalInt.of(parsed);
                }
            } catch (NumberFormatException ignored) {
                // falls through to the refusal below
            }
        }
        throw new RelixException("sandbox configuration " + file + ": limits." + key
                + " must be a positive integer, not " + value);
    }

    /**
     * Assembles a closed sandbox.
     *
     * @since 1.0
     */
    public static final class Builder {

        private String declarations = "";
        private Optional<Path> baseDirectory = Optional.empty();
        private OptionalInt maxInputChars = OptionalInt.empty();
        private OptionalInt maxOutputRows = OptionalInt.empty();
        private OptionalInt maxMaterializedRows = OptionalInt.empty();
        private OptionalInt maxFixpointRounds = OptionalInt.empty();
        private OptionalLong maxProcessedRows = OptionalLong.empty();
        private Optional<Duration> timeout = Optional.empty();

        private Builder() {
        }

        /**
         * Sets the {@code .relix} text installed when a session is built: the external
         * declarations users may reach, and any fixed data.
         *
         * @param relixText the declarations; must not be null
         * @return this builder, for chaining
         * @since 1.0
         */
        public Builder declarations(String relixText) {
            this.declarations = Objects.requireNonNull(relixText, "relixText");
            return this;
        }

        /**
         * Sets the directory relative paths in the declarations resolve against.
         *
         * @param directory the directory; must not be null
         * @return this builder, for chaining
         * @since 1.0
         */
        public Builder baseDirectory(Path directory) {
            this.baseDirectory = Optional.of(Objects.requireNonNull(directory, "directory"));
            return this;
        }

        /**
         * Limits the length of the text one call may pass.
         *
         * @param chars the most characters; must be at least 1
         * @return this builder, for chaining
         * @since 1.0
         */
        public Builder maxInputChars(int chars) {
            this.maxInputChars = positive(chars, "maxInputChars");
            return this;
        }

        /**
         * Limits the rows one query returns. A longer result is cut, and the cut is
         * reported.
         *
         * @param rows the most rows; must be at least 1
         * @return this builder, for chaining
         * @since 1.0
         */
        public Builder maxOutputRows(int rows) {
            this.maxOutputRows = positive(rows, "maxOutputRows");
            return this;
        }

        /**
         * Limits the rows one blocking operator may buffer; see
         * {@link Relix.Builder#maxMaterializedRows(int)}.
         *
         * @param rows the most rows; must be at least 1
         * @return this builder, for chaining
         * @since 1.0
         */
        public Builder maxMaterializedRows(int rows) {
            this.maxMaterializedRows = positive(rows, "maxMaterializedRows");
            return this;
        }

        /**
         * Limits the rounds one recursion may run; see
         * {@link Relix.Builder#maxFixpointRounds(int)}.
         *
         * @param rounds the most rounds; must be at least 1
         * @return this builder, for chaining
         * @since 1.0
         */
        public Builder maxFixpointRounds(int rounds) {
            this.maxFixpointRounds = positive(rounds, "maxFixpointRounds");
            return this;
        }

        /**
         * Limits the work one execution may do, counted as rows passed from one operator
         * to the next; see {@link Relix.Builder#maxProcessedRows(long)}.
         *
         * @param rows the most rows processed; must be at least 1
         * @return this builder, for chaining
         * @since 1.0
         */
        public Builder maxProcessedRows(long rows) {
            if (rows < 1) {
                throw new IllegalArgumentException("maxProcessedRows must be >= 1: " + rows);
            }
            this.maxProcessedRows = OptionalLong.of(rows);
            return this;
        }

        /**
         * Limits how long one execution may run; see {@link Relix.Builder#timeout(Duration)}.
         *
         * @param timeout the longest one execution may run; must be positive
         * @return this builder, for chaining
         * @since 1.0
         */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive: " + timeout);
            }
            this.timeout = Optional.of(timeout);
            return this;
        }

        /**
         * Builds the sandbox.
         *
         * @return a closed sandbox
         * @since 1.0
         */
        public Sandbox build() {
            return new Sandbox(false, declarations, baseDirectory,
                    maxInputChars, maxOutputRows, maxMaterializedRows, maxFixpointRounds,
                    maxProcessedRows, timeout);
        }

        private static OptionalInt positive(int value, String name) {
            if (value < 1) {
                throw new IllegalArgumentException(name + " must be >= 1: " + value);
            }
            return OptionalInt.of(value);
        }
    }
}
