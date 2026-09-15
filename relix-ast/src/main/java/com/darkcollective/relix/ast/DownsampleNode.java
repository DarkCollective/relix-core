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

import com.darkcollective.relix.ast.visitor.RelNodeVisitor;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time-series downsampling (DOWNSAMPLE) — groups input rows into fixed-width time
 * buckets and consolidates numeric columns within each bucket using the chosen
 * {@link ConsolidationFunction}.
 *
 * <p>Syntax:
 * <pre>
 *   DOWNSAMPLE ts BY '5m'  USING AVG (Metrics)
 *   DOWNSAMPLE ts BY '1h'  USING MAX PER host (NetworkData)
 *   DOWNSAMPLE ts BY 'P1D' USING SUM PER region FOR 30 ROWS (Events)
 * </pre>
 *
 * <p>The {@code timestampColumn} must be of type {@code TIMESTAMP} in the input schema.
 * The {@code interval} accepts ISO-8601 duration strings ({@code PT5M}, {@code PT1H},
 * {@code P1D}) as well as shorthand notation ({@code 5m}, {@code 1h}, {@code 1d}).
 *
 * <p>Output schema: {@code groupingKeys} columns (same types) + {@code bucket: TIMESTAMP}
 * + consolidated columns.  For {@link ConsolidationFunction#COUNT} the consolidated column
 * is a single {@code count: NUMBER}; for all other functions each {@code NUMBER} column
 * that is not the timestamp column and not a grouping key becomes
 * {@code <fn>_<colname>: NUMBER} (e.g. {@code avg_value}, {@code sum_amount}).
 *
 * <p>When {@code maxRows} is present, only the N most-recent buckets are emitted
 * (sort by bucket DESC, take N).
 *
 * <p>Materialisation: {@link MaterializationMode#BAG} (groups all rows before emitting).
 *
 * @param timestampColumn the input column of type TIMESTAMP to bucket; never null
 * @param interval        the bucket width — ISO-8601 or shorthand; never null
 * @param function        the consolidation function; never null
 * @param groupingKeys    optional additional grouping columns (PER clause); never null
 * @param maxRows         optional limit on the number of output buckets (FOR n ROWS)
 * @param input           the source relation; never null
 * @param location        source position of this node; never null
 */
public record DownsampleNode(
        String timestampColumn,
        String interval,
        ConsolidationFunction function,
        List<String> groupingKeys,
        OptionalLong maxRows,
        RelNode input,
        SourceLocation location
) implements RelNode {

    private static final Pattern SHORTHAND = Pattern.compile("(\\d+)([smhdw])");

    public DownsampleNode {
        Objects.requireNonNull(timestampColumn, "timestampColumn");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(groupingKeys, "groupingKeys");
        Objects.requireNonNull(maxRows, "maxRows");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
        if (timestampColumn.isBlank()) throw new IllegalArgumentException("timestampColumn must not be blank");
        if (interval.isBlank())       throw new IllegalArgumentException("interval must not be blank");
        if (maxRows.isPresent() && maxRows.getAsLong() < 1)
            throw new IllegalArgumentException("maxRows must be ≥ 1");
        groupingKeys = List.copyOf(groupingKeys);
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public DownsampleNode(String timestampColumn, String interval, ConsolidationFunction function,
                          List<String> groupingKeys, OptionalLong maxRows, RelNode input) {
        this(timestampColumn, interval, function, groupingKeys, maxRows, input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }

    /**
     * Parses the interval string to a number of seconds.
     *
     * <p>Supports shorthand notation ({@code 5m}, {@code 1h}, {@code 30s}, {@code 2d},
     * {@code 1w}) and ISO-8601 duration strings ({@code PT5M}, {@code PT1H}, {@code P1D}).
     *
     * @param interval the interval string; never null
     * @return the interval width in seconds; always positive
     * @throws IllegalArgumentException if the string is not a recognised format or is non-positive
     */
    public static long parseIntervalSeconds(String interval) {
        String s = interval.trim();
        Matcher m = SHORTHAND.matcher(s);
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            long seconds = switch (m.group(2)) {
                case "s" -> n;
                case "m" -> n * 60L;
                case "h" -> n * 3600L;
                case "d" -> n * 86_400L;
                case "w" -> n * 604_800L;
                default  -> throw new IllegalArgumentException("Unknown unit in '" + s + "'");
            };
            if (seconds <= 0) throw new IllegalArgumentException("Interval must be positive, got: " + s);
            return seconds;
        }
        try {
            long seconds = Duration.parse(s).getSeconds();
            if (seconds <= 0) throw new IllegalArgumentException("Interval must be positive, got: " + s);
            return seconds;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Cannot parse interval '" + s + "' — use shorthand (5m, 1h, 1d) or ISO-8601 (PT5M, PT1H, P1D): "
                    + e.getMessage());
        }
    }
}
