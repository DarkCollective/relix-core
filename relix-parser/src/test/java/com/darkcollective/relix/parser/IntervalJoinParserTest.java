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
package com.darkcollective.relix.parser;

import com.darkcollective.relix.ast.*;
import org.junit.jupiter.api.Test;

final class IntervalJoinParserTest extends ParserTestSupport {

    @Test
    void parsesIntersects() {
        assertParsesTo(
                "Stays IJOIN INTERSECTS (Stays.checkin, Stays.checkout, Bookings.from, Bookings.to) Bookings",
                intervalJoin(
                        rel("Stays"),
                        rel("Bookings"),
                        AllenRelation.INTERSECTS,
                        "Stays.checkin",
                        "Stays.checkout",
                        "Bookings.from",
                        "Bookings.to"));
    }

    @Test
    void parsesOverlaps() {
        assertParsesTo(
                "L IJOIN OVERLAPS (L.a, L.b, R.c, R.d) R",
                intervalJoin(
                        rel("L"),
                        rel("R"),
                        AllenRelation.OVERLAPS,
                        "L.a",
                        "L.b",
                        "R.c",
                        "R.d"));
    }

    @Test
    void parsesDuring() {
        assertParsesTo(
                "L IJOIN DURING (L.a, L.b, R.c, R.d) R",
                intervalJoin(
                        rel("L"),
                        rel("R"),
                        AllenRelation.DURING,
                        "L.a",
                        "L.b",
                        "R.c",
                        "R.d"));
    }

    @Test
    void parsesContains() {
        assertParsesTo(
                "L IJOIN CONTAINS (L.a, L.b, R.c, R.d) R",
                intervalJoin(
                        rel("L"),
                        rel("R"),
                        AllenRelation.CONTAINS,
                        "L.a",
                        "L.b",
                        "R.c",
                        "R.d"));
    }

    @Test
    void parsesMeets() {
        assertParsesTo(
                "L IJOIN MEETS (L.a, L.b, R.c, R.d) R",
                intervalJoin(
                        rel("L"),
                        rel("R"),
                        AllenRelation.MEETS,
                        "L.a",
                        "L.b",
                        "R.c",
                        "R.d"));
    }

    @Test
    void parsesPrecedes() {
        assertParsesTo(
                "L IJOIN PRECEDES (L.a, L.b, R.c, R.d) R",
                intervalJoin(
                        rel("L"),
                        rel("R"),
                        AllenRelation.PRECEDES,
                        "L.a",
                        "L.b",
                        "R.c",
                        "R.d"));
    }

    @Test
    void parsesAllThirteenAllenRelationsPlusIntersects() {
        // Every AllenRelation constant must parse to the matching enum value.
        for (AllenRelation rel : AllenRelation.values()) {
            assertParsesTo(
                    "L IJOIN " + rel.name() + " (L.a, L.b, R.c, R.d) R",
                    intervalJoin(
                            rel("L"),
                            rel("R"),
                            rel,
                            "L.a",
                            "L.b",
                            "R.c",
                            "R.d"));
        }
    }

    @Test
    void parsesConverseRelations() {
        assertParsesTo(
                "L IJOIN OVERLAPPED_BY (L.a, L.b, R.c, R.d) R",
                intervalJoin(
                        rel("L"),
                        rel("R"),
                        AllenRelation.OVERLAPPED_BY,
                        "L.a",
                        "L.b",
                        "R.c",
                        "R.d"));
        assertParsesTo(
                "L IJOIN PRECEDED_BY (L.a, L.b, R.c, R.d) R",
                intervalJoin(
                        rel("L"),
                        rel("R"),
                        AllenRelation.PRECEDED_BY,
                        "L.a",
                        "L.b",
                        "R.c",
                        "R.d"));
    }

    @Test
    void parsesKeywordCaseInsensitively() {
        assertParsesTo(
                "L IJOIN intersects (L.a, L.b, R.c, R.d) R",
                intervalJoin(
                        rel("L"),
                        rel("R"),
                        AllenRelation.INTERSECTS,
                        "L.a",
                        "L.b",
                        "R.c",
                        "R.d"));
    }

    @Test
    void bindsTighterThanUnion() {
        assertParsesTo(
                "A IJOIN INTERSECTS (A.a, A.b, B.c, B.d) B ∪ C",
                union(
                        intervalJoin(
                                rel("A"),
                                rel("B"),
                                AllenRelation.INTERSECTS,
                                "A.a",
                                "A.b",
                                "B.c",
                                "B.d"),
                        rel("C")));
    }

    @Test
    void prettyPrints() {
        RelNode node = intervalJoin(
                rel("Stays"),
                rel("Bookings"),
                AllenRelation.INTERSECTS,
                "Stays.checkin",
                "Stays.checkout",
                "Bookings.from",
                "Bookings.to");
        // One comma-separated four-tuple — the form the grammar accepts. This assertion
        // used to pin a "; "-separated pair-of-pairs, which the printer emitted and the
        // parser rejected, so no IJOIN survived a prettyPrint-and-reparse.
        assertPrettyPrints(node,
                "(Stays) IJOIN INTERSECTS (Stays.checkin, Stays.checkout, Bookings.from, Bookings.to) (Bookings)");
    }

    @Test
    void prettyPrintRoundTrips() {
        RelNode original = intervalJoin(
                rel("Stays"),
                rel("Bookings"),
                AllenRelation.INTERSECTS,
                "Stays.checkin",
                "Stays.checkout",
                "Bookings.from",
                "Bookings.to");
        assertParsesTo(original.prettyPrint(), original);
    }

    @Test
    void failsOnUnknownRelation() {
        assertParseError("L IJOIN UNKNOWN (a, b, c, d) R")
                .hasMessageContaining("UNKNOWN");
    }

    @Test
    void failsOnMissingArgList() {
        assertParseError("L IJOIN INTERSECTS R")
                .hasMessageContaining("(");
    }
}
