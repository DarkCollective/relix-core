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
package com.darkcollective.relix.function.builtin;

import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Locale;

import static com.darkcollective.relix.function.builtin.Arguments.integer;
import static com.darkcollective.relix.function.builtin.Arguments.number;
import static com.darkcollective.relix.function.builtin.Arguments.reject;
import static com.darkcollective.relix.function.builtin.Arguments.string;
import static com.darkcollective.relix.function.builtin.Category.PURE_DETERMINISTIC;
import static com.darkcollective.relix.function.builtin.Category.p;
import static com.darkcollective.relix.symbol.ScalarType.ANY;
import static com.darkcollective.relix.symbol.ScalarType.NUMBER;
import static com.darkcollective.relix.symbol.ScalarType.STRING;

/**
 * Writing a value as text by a pattern: {@code TO_CHAR}, in PostgreSQL's pattern
 * language, and {@code FORMAT}, MySQL's grouped decimal.
 *
 * <p>Both run in the engine. A pattern is a small program, and a backend that read
 * one of its letters differently would answer with different text rather than fail, so
 * none is sent.
 */
final class FormatFunctions {

    private static final Category CONVERSION = Category.of("conversion");

    private FormatFunctions() {
    }

    static List<ScalarFunction> all() {
        return List.of(
                CONVERSION.fn("TO_CHAR", STRING, PURE_DETERMINISTIC,
                        List.of(p("value", ANY), p("pattern", STRING)), FormatFunctions::toChar),

                CONVERSION.fn("FORMAT", STRING, PURE_DETERMINISTIC,
                        List.of(p("x", NUMBER), p("places", NUMBER)), FormatFunctions::format));
    }

    private static Value toChar(List<Value> args) {
        if (args.get(0).isNull() || args.get(1).isNull()) {
            return NullValue.INSTANCE;
        }
        String pattern = string(args.get(1), "TO_CHAR");
        return new StringValue(switch (args.get(0)) {
            case TimestampValue ts -> dateTime(LocalDateTime.ofInstant(ts.value(), ZoneOffset.UTC), pattern);
            case DateValue d -> dateTime(d.value().atStartOfDay(), pattern);
            case NumberValue n -> numeric(n.value(), pattern);
            default -> throw reject("TO_CHAR: expected TIMESTAMP, DATE or NUMBER, got " + args.get(0).type());
        });
    }

    // ── Dates and times ───────────────────────────────────────────────────────

    /**
     * The date-time patterns, longest first so that {@code HH24} is not read as
     * {@code HH} and {@code 24}. Matched in any case, except that the case of a name's
     * pattern — {@code MONTH}, {@code Month}, {@code month} — is the case it is written in.
     */
    private static final List<String> DATE_PATTERNS = List.of(
            "HH24", "HH12", "IYYY", "YYYY", "MONTH", "A.M.", "P.M.",
            "YYY", "MON", "DAY", "DDD",
            "HH", "YY", "MM", "MI", "SS", "MS", "US", "DD", "DY", "IW", "WW", "ID", "AM", "PM",
            "Y", "D", "Q");

    /**
     * PostgreSQL's {@code to_char} for a date-time: each pattern replaced by its field,
     * {@code FM} before one suppressing its padding, text in double quotes copied, and
     * anything else copied as it is.
     */
    static String dateTime(LocalDateTime t, String pattern) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            if (pattern.charAt(i) == '"') {
                int close = pattern.indexOf('"', i + 1);
                int end = close < 0 ? pattern.length() : close;
                out.append(pattern, i + 1, end);
                i = end + 1;
                continue;
            }
            boolean fill = !pattern.regionMatches(true, i, "FM", 0, 2);
            int at = fill ? i : i + 2;
            String matched = null;
            for (String p : DATE_PATTERNS) {
                if (pattern.regionMatches(true, at, p, 0, p.length())) {
                    matched = pattern.substring(at, at + p.length());
                    break;
                }
            }
            if (matched == null) {
                out.append(pattern.charAt(i));
                i++;
                continue;
            }
            out.append(field(t, matched, fill));
            i = at + matched.length();
        }
        return out.toString();
    }

    private static String field(LocalDateTime t, String written, boolean fill) {
        String key = written.toUpperCase(Locale.ROOT);
        int hour12 = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        return switch (key) {
            case "HH24" -> digits(t.getHour(), 2, fill);
            case "HH12", "HH" -> digits(hour12, 2, fill);
            case "MI" -> digits(t.getMinute(), 2, fill);
            case "SS" -> digits(t.getSecond(), 2, fill);
            case "MS" -> digits(t.getNano() / 1_000_000, 3, fill);
            case "US" -> digits(t.getNano() / 1_000, 6, fill);
            case "AM", "PM" -> cased(t.getHour() < 12 ? "AM" : "PM", written, false);
            case "A.M.", "P.M." -> cased(t.getHour() < 12 ? "A.M." : "P.M.", written, false);
            case "YYYY" -> digits(t.getYear(), 4, fill);
            case "YYY" -> digits(t.getYear() % 1000, 3, fill);
            case "YY" -> digits(t.getYear() % 100, 2, fill);
            case "Y" -> digits(t.getYear() % 10, 1, fill);
            case "IYYY" -> digits(t.get(IsoFields.WEEK_BASED_YEAR), 4, fill);
            case "MONTH" -> name(t.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH), written, fill);
            case "MON" -> cased(t.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH), written, true);
            case "MM" -> digits(t.getMonthValue(), 2, fill);
            case "DAY" -> name(t.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH), written, fill);
            case "DY" -> cased(t.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH), written, true);
            case "DDD" -> digits(t.getDayOfYear(), 3, fill);
            case "DD" -> digits(t.getDayOfMonth(), 2, fill);
            case "D" -> digits(t.getDayOfWeek().getValue() % 7 + 1, 1, fill);
            case "ID" -> digits(t.getDayOfWeek().getValue(), 1, fill);
            case "IW" -> digits(t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), 2, fill);
            case "WW" -> digits((t.getDayOfYear() - 1) / 7 + 1, 2, fill);
            default -> digits(t.get(IsoFields.QUARTER_OF_YEAR), 1, fill);
        };
    }

    /** A number zero-padded to {@code width}, or as it is under {@code FM}. */
    private static String digits(int value, int width, boolean fill) {
        String text = Integer.toString(value);
        return fill && text.length() < width ? "0".repeat(width - text.length()) + text : text;
    }

    /** A name padded to nine characters, PostgreSQL's widest month or day, unless {@code FM}. */
    private static String name(String english, String written, boolean fill) {
        String text = cased(english, written, true);
        return fill ? String.format(Locale.ROOT, "%-9s", text) : text;
    }

    /**
     * {@code text} in the case its pattern is written in: all capitals, all lower case,
     * or (where {@code capitalised} is allowed) a capital and then lower case.
     */
    private static String cased(String text, String written, boolean capitalised) {
        if (written.equals(written.toUpperCase(Locale.ROOT))) {
            return text.toUpperCase(Locale.ROOT);
        }
        if (written.equals(written.toLowerCase(Locale.ROOT)) || !capitalised) {
            return text.toLowerCase(Locale.ROOT);
        }
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1).toLowerCase(Locale.ROOT);
    }

    // ── Numbers ───────────────────────────────────────────────────────────────

    /**
     * PostgreSQL's {@code to_char} for a number: {@code 9} a digit, {@code 0} a digit
     * printed even when it is a leading zero, {@code .} or {@code D} the decimal point,
     * {@code ,} or {@code G} a group separator, and {@code FM} to drop the padding. The
     * value is rounded half away from zero to the fraction's digits; one leading
     * position holds the sign, and a value too wide for the pattern is all {@code #}.
     */
    static String numeric(BigDecimal value, String pattern) {
        boolean fm = pattern.regionMatches(true, 0, "FM", 0, 2);
        String p = (fm ? pattern.substring(2) : pattern).replace('D', '.').replace('G', ',');
        int point = p.indexOf('.');
        String intPattern = point < 0 ? p : p.substring(0, point);
        String fracPattern = point < 0 ? "" : p.substring(point + 1);
        int fracDigits = (int) fracPattern.chars().filter(c -> c == '9' || c == '0').count();
        BigDecimal rounded = value.abs().setScale(fracDigits, RoundingMode.HALF_UP);
        String plain = rounded.toPlainString();
        String intDigits = plain.contains(".") ? plain.substring(0, plain.indexOf('.')) : plain;
        String fracText = plain.contains(".") ? plain.substring(plain.indexOf('.') + 1) : "";
        if (intDigits.equals("0")) {
            intDigits = point < 0 ? "0" : "";
        }
        int intPositions = (int) intPattern.chars().filter(c -> c == '9' || c == '0').count();
        boolean overflow = intDigits.length() > intPositions;
        int zeroFrom = intPattern.indexOf('0');

        // The integer part, right to left over its pattern.
        StringBuilder integer = new StringBuilder();
        int d = intDigits.length() - 1;
        for (int k = intPattern.length() - 1; k >= 0; k--) {
            char c = intPattern.charAt(k);
            if (c == '9' || c == '0') {
                if (overflow) {
                    integer.append('#');
                } else if (d >= 0) {
                    integer.append(intDigits.charAt(d--));
                } else {
                    integer.append(zeroFrom >= 0 && k >= zeroFrom ? '0' : ' ');
                }
            } else if (c == ',') {
                boolean digitLeft = overflow || d >= 0 || zeroFrom >= 0 && k > zeroFrom;
                integer.append(digitLeft ? ',' : ' ');
            } else {
                integer.append(c);
            }
        }
        integer.reverse();

        StringBuilder fraction = new StringBuilder();
        int f = 0;
        for (char c : fracPattern.toCharArray()) {
            if (c == '9' || c == '0') {
                fraction.append(overflow ? '#' : fracText.charAt(f++));
            } else {
                fraction.append(c);
            }
        }
        if (fm) {
            // FM drops the zeros a 9 printed at the fraction's end.
            int end = fraction.length();
            for (int k = fracPattern.length() - 1; k >= 0 && fracPattern.charAt(k) == '9'
                    && fraction.charAt(k) == '0'; k--) {
                end = k;
            }
            fraction.setLength(end);
        }

        String body = " " + integer + (point < 0 ? "" : "." + fraction);
        if (value.signum() < 0 && !overflow) {
            // The body always holds a digit, so the scan ends inside it.
            int first = 0;
            while (body.charAt(first) == ' ') {
                first++;
            }
            body = body.substring(0, first - 1) + "-" + body.substring(first);
        }
        return fm ? body.stripLeading() : body;
    }

    /**
     * MySQL's {@code FORMAT(x, places)}: {@code x} rounded half away from zero to
     * {@code places} decimals, no fewer than none, with a comma between each group of
     * three digits: {@code FORMAT(1234567.891, 2)} is {@code '1,234,567.89'}.
     */
    private static Value format(List<Value> args) {
        if (args.get(0).isNull() || args.get(1).isNull()) {
            return NullValue.INSTANCE;
        }
        BigDecimal x = number(args.get(0), "FORMAT");
        int places = Math.max(integer(args.get(1), "FORMAT"), 0);
        BigDecimal rounded = x.setScale(places, RoundingMode.HALF_UP);
        java.text.DecimalFormat grouped = new java.text.DecimalFormat(
                "#,##0" + (places > 0 ? "." + "0".repeat(places) : ""),
                java.text.DecimalFormatSymbols.getInstance(Locale.ROOT));
        grouped.setRoundingMode(RoundingMode.HALF_UP);
        return new StringValue(grouped.format(rounded));
    }
}
