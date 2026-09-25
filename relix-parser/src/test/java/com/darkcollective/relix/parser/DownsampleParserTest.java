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
import com.darkcollective.relix.ast.internal.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;

/** Tests for the DOWNSAMPLE operator parser. */
final class DownsampleParserTest extends ParserTestSupport {

    @Nested
    class HappyPath {

        @Test
        void parsesMinimalForm() {
            assertParsesTo("DOWNSAMPLE ts BY '5m' USING AVG (Metrics)",
                    downsample("ts", "5m", ConsolidationFunction.AVG, rel("Metrics")));
        }

        @Test
        void parsesWithIso8601Interval() {
            assertParsesTo("DOWNSAMPLE ts BY 'PT5M' USING AVG (Metrics)",
                    downsample("ts", "PT5M", ConsolidationFunction.AVG, rel("Metrics")));
        }

        @Test
        void parsesAllFunctions() {
            for (ConsolidationFunction fn : ConsolidationFunction.values()) {
                assertParsesTo("DOWNSAMPLE ts BY '1h' USING " + fn.name() + " (M)",
                        downsample("ts", "1h", fn, rel("M")));
            }
        }

        @Test
        void parsesWithGroupingKey() {
            assertParsesTo("DOWNSAMPLE ts BY '1h' USING MAX PER host (Sensors)",
                    downsample("ts", "1h", ConsolidationFunction.MAX, List.of("host"), rel("Sensors")));
        }

        @Test
        void parsesWithMultipleGroupingKeys() {
            assertParsesTo("DOWNSAMPLE ts BY '1h' USING SUM PER region, host (Sensors)",
                    downsample("ts", "1h", ConsolidationFunction.SUM, List.of("region", "host"), rel("Sensors")));
        }

        @Test
        void parsesWithMaxRows() {
            assertParsesTo("DOWNSAMPLE ts BY '1d' USING SUM FOR 7 ROWS (Events)",
                    downsample("ts", "1d", ConsolidationFunction.SUM, List.of(), 7L, rel("Events")));
        }

        @Test
        void parsesWithGroupingAndMaxRows() {
            assertParsesTo("DOWNSAMPLE ts BY '1h' USING COUNT PER host FOR 24 ROWS (Logs)",
                    downsample("ts", "1h", ConsolidationFunction.COUNT, List.of("host"), 24L, rel("Logs")));
        }

        @Test
        void parsesHourInterval() {
            assertParsesTo("DOWNSAMPLE at BY '1h' USING MIN (Readings)",
                    downsample("at", "1h", ConsolidationFunction.MIN, rel("Readings")));
        }

        @Test
        void parsesDayInterval() {
            assertParsesTo("DOWNSAMPLE at BY '1d' USING AVG (Readings)",
                    downsample("at", "1d", ConsolidationFunction.AVG, rel("Readings")));
        }

        @Test
        void prettyprintsWithoutGroupingOrMaxRows() {
            assertPrettyPrints(
                    downsample("ts", "5m", ConsolidationFunction.AVG, rel("M")),
                    "DOWNSAMPLE ts BY '5m' USING AVG (M)");
        }

        @Test
        void prettyprintsWithGroupingAndMaxRows() {
            assertPrettyPrints(
                    downsample("ts", "1h", ConsolidationFunction.SUM, List.of("host"), 24L, rel("Logs")),
                    "DOWNSAMPLE ts BY '1h' USING SUM PER host FOR 24 ROWS (Logs)");
        }
    }

    @Nested
    class ErrorCases {

        @Test
        void missingByKeyword() {
            assertParseError("DOWNSAMPLE ts '5m' USING AVG (M)")
                    .hasMessageContaining("BY");
        }

        @Test
        void missingUsingKeyword() {
            assertParseError("DOWNSAMPLE ts BY '5m' AVG (M)")
                    .hasMessageContaining("USING");
        }

        @Test
        void missingInterval() {
            assertParseError("DOWNSAMPLE ts BY USING AVG (M)")
                    .hasMessageContaining("Expected quoted interval string");
        }

        @Test
        void unknownFunction() {
            assertParseError("DOWNSAMPLE ts BY '5m' USING MEDIAN (M)")
                    .hasMessageContaining("consolidation function");
        }

        @Test
        void missingRowsKeyword() {
            assertParseError("DOWNSAMPLE ts BY '5m' USING AVG FOR 10 (M)")
                    .hasMessageContaining("ROWS");
        }
    }
}
