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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a GEDCOM 5.5.1 genealogy file into its two record kinds.
 *
 * <p>GEDCOM is line-oriented and carries its nesting in a leading level number:
 * <pre>{@code
 * 0 @I1@ INDI
 * 1 NAME John /Smith/
 * 1 BIRT
 * 2 DATE 1 JAN 1900
 * 2 PLAC London
 * }</pre>
 * A line is {@code level [@xref@] TAG [value]}; a child is any following line of a
 * greater level, until one of the same or lesser level closes it. This reader keeps
 * the tree only as long as it takes to pull out the fields the two relations name —
 * an unrecognised tag is skipped rather than carried, because a relation's heading is
 * what decides the answer and GEDCOM is extensible enough that carrying everything
 * would mean carrying an open one.
 *
 * <h2>Dates</h2>
 * GEDCOM permits approximations and ranges ({@code ABT 1900}, {@code BET 1900 AND 1910})
 * as well as exact dates, so each event exposes both: {@code date} is a real
 * {@code DATE} when the value is an exact day and NULL otherwise, while {@code text}
 * always holds what the file said. The pair is what distinguishes <em>no date recorded</em>
 * (both NULL) from <em>a date that is not exact</em> (text present, date NULL) — a
 * distinction a single column cannot make, and one a genealogist cares about.
 */
final class GedcomReader {

    /** Month abbreviations as GEDCOM spells them, in order. */
    private static final List<String> MONTHS = List.of(
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
            "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");

    /** One node of the record tree: its tag, its value, and the lines nested under it. */
    record Node(String tag, String value, List<Node> children) {

        Optional<Node> child(String childTag) {
            return children.stream().filter(c -> c.tag().equals(childTag)).findFirst();
        }

        /** {@return the values of every child carrying {@code childTag}} */
        List<String> childValues(String childTag) {
            return children.stream()
                    .filter(c -> c.tag().equals(childTag))
                    .map(Node::value)
                    .filter(v -> !v.isEmpty())
                    .toList();
        }
    }

    /** A level-0 record: its cross-reference id, its tag, and its tree. */
    record Record(String id, String tag, Node root) {
    }

    private GedcomReader() {
    }

    /**
     * Parses {@code path} into its level-0 records, in file order.
     *
     * @param path the {@code .ged} file
     * @return the records
     * @throws UncheckedIOException if the file cannot be read
     */
    static List<Record> read(Path path) {
        List<String> lines;
        try {
            lines = FileResolver.readLines(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read GEDCOM file '" + path + "'", e);
        }

        List<Record> records = new ArrayList<>();
        // parents[i] is the node currently open at level i, so a line of level L attaches
        // to parents[L - 1]. A malformed jump in level is treated as a level-0 record
        // rather than an error: a reader that refuses a file no genealogy program refuses
        // is worse than one that skips what it cannot place.
        List<Node> parents = new ArrayList<>();
        String pendingId = null;

        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            Parsed parsed = parse(line);
            if (parsed == null) {
                continue;
            }
            Node node = new Node(parsed.tag(), parsed.value(), new ArrayList<>());

            if (parsed.level() == 0) {
                parents.clear();
                parents.add(node);
                pendingId = parsed.xref();
                if (pendingId != null) {
                    records.add(new Record(pendingId, parsed.tag(), node));
                }
                continue;
            }
            if (parsed.level() > parents.size()) {
                continue;   // a level that skips one has no parent to attach to
            }
            parents.get(parsed.level() - 1).children().add(node);
            parents.subList(parsed.level(), parents.size()).clear();
            parents.add(node);
        }
        return records;
    }

    private record Parsed(int level, String xref, String tag, String value) {
    }

    /** Splits one GEDCOM line, or {@code null} when it is not one. */
    private static Parsed parse(String line) {
        int sp = line.indexOf(' ');
        if (sp < 0) {
            return null;
        }
        int level;
        try {
            level = Integer.parseInt(line.substring(0, sp));
        } catch (NumberFormatException e) {
            return null;
        }
        String rest = line.substring(sp + 1).stripLeading();
        String xref = null;
        if (rest.startsWith("@")) {
            int close = rest.indexOf('@', 1);
            if (close < 0) {
                return null;
            }
            xref = rest.substring(1, close);
            rest = rest.substring(close + 1).stripLeading();
        }
        int tagEnd = rest.indexOf(' ');
        String tag = tagEnd < 0 ? rest : rest.substring(0, tagEnd);
        String value = tagEnd < 0 ? "" : rest.substring(tagEnd + 1).strip();
        return new Parsed(level, xref, tag.toUpperCase(Locale.ROOT), value);
    }

    /**
     * {@return the fields of one {@code INDI} record, keyed by column name} An event is a
     * nested map, so the caller shapes it into whatever the declared schema asks for.
     */
    static Map<String, Object> individual(Record record) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", record.id());
        record.root().child("NAME").ifPresent(name -> {
            fields.put("name", displayName(name.value()));
            surname(name.value()).ifPresent(s -> fields.put("surname", s));
        });
        record.root().child("SEX").ifPresent(sex -> fields.put("sex", sex.value()));
        record.root().child("BIRT").ifPresent(b -> fields.put("birth", event(b)));
        record.root().child("DEAT").ifPresent(d -> fields.put("death", event(d)));
        return fields;
    }

    /** {@return the fields of one {@code FAM} record, keyed by column name} */
    static Map<String, Object> family(Record record) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", record.id());
        record.root().child("HUSB").ifPresent(h -> fields.put("husband", reference(h.value())));
        record.root().child("WIFE").ifPresent(w -> fields.put("wife", reference(w.value())));
        fields.put("children", record.root().childValues("CHIL").stream()
                .map(GedcomReader::reference)
                .toList());
        return fields;
    }

    /** An event's date, its raw text and its place. */
    private static Map<String, Object> event(Node node) {
        Map<String, Object> fields = new LinkedHashMap<>();
        String text = node.child("DATE").map(Node::value).orElse(null);
        fields.put("text", text);
        fields.put("date", text == null ? null : exactDate(text).orElse(null));
        fields.put("place", node.child("PLAC").map(Node::value).orElse(null));
        return fields;
    }

    /**
     * {@return {@code value} as a date, when it names an exact day} {@code 1 JAN 1900} is
     * one; {@code ABT 1900}, {@code BET 1900 AND 1910} and a bare year are not, and come
     * back empty so the caller can keep the text and leave the date NULL.
     */
    static Optional<LocalDate> exactDate(String value) {
        String[] parts = value.strip().split("\\s+");
        if (parts.length != 3) {
            return Optional.empty();
        }
        int month = MONTHS.indexOf(parts[1].toUpperCase(Locale.ROOT)) + 1;
        if (month == 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.of(
                    Integer.parseInt(parts[2]), month, Integer.parseInt(parts[0])));
        } catch (NumberFormatException | DateTimeException e) {
            return Optional.empty();
        }
    }

    /** {@code @I1@} as it appears in a pointer, reduced to the id {@code I1}. */
    private static String reference(String value) {
        String v = value.strip();
        return v.startsWith("@") && v.endsWith("@") && v.length() > 2
                ? v.substring(1, v.length() - 1)
                : v;
    }

    /** {@code John /Smith/} read as a person would say it. */
    private static String displayName(String value) {
        return value.replace("/", "").replaceAll("\\s+", " ").strip();
    }

    /** The surname GEDCOM delimits with slashes, when the name carries one. */
    private static Optional<String> surname(String value) {
        int open = value.indexOf('/');
        int close = value.indexOf('/', open + 1);
        return open >= 0 && close > open + 1
                ? Optional.of(value.substring(open + 1, close))
                : Optional.empty();
    }
}
