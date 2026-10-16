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
package com.darkcollective.relix.ast.internal;

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.DurationOperand;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Durations with calendar months, as ISO-8601 and as SQL's interval text (#142). */
final class TemporalLiteralsTest {

    private static TemporalLiterals.Span span(long months, Duration exact) {
        return new TemporalLiterals.Span(months, exact);
    }

    @Test
    void isoTakesYearsMonthsWeeksAndDays() {
        assertThat(TemporalLiterals.parseSpan("P1Y2M3DT4H")).isEqualTo(span(14, Duration.ofHours(76)));
        assertThat(TemporalLiterals.parseSpan("P2W")).isEqualTo(span(0, Duration.ofDays(14)));
        assertThat(TemporalLiterals.parseSpan("-P1M")).isEqualTo(span(-1, Duration.ZERO));
        assertThat(TemporalLiterals.parseSpan("+PT30M")).isEqualTo(span(0, Duration.ofMinutes(30)));
        assertThat(TemporalLiterals.parseSpan("pt1h")).isEqualTo(span(0, Duration.ofHours(1)));
        assertThatThrownBy(() -> TemporalLiterals.parseSpan("P")).isInstanceOf(DateTimeParseException.class);
        assertThatThrownBy(() -> TemporalLiterals.parseSpan("1M")).isInstanceOf(DateTimeParseException.class);
        assertThat(TemporalLiterals.parseDuration("P1D")).isEqualTo(Duration.ofDays(1));
        assertThatThrownBy(() -> TemporalLiterals.parseDuration("P1M")).hasMessageContaining("not exact");
    }

    @Test
    void intervalTextAsPostgresWritesIt() {
        assertThat(TemporalLiterals.parseInterval("2 years 3 mons")).isEqualTo(span(27, Duration.ZERO));
        assertThat(TemporalLiterals.parseInterval("1 Quarter 1 week")).isEqualTo(span(3, Duration.ofDays(7)));
        assertThat(TemporalLiterals.parseInterval("1 day 02:30:00")).isEqualTo(span(0, Duration.ofMinutes(26 * 60 + 30)));
        assertThat(TemporalLiterals.parseInterval("-01:00")).isEqualTo(span(0, Duration.ofHours(-1)));
        assertThat(TemporalLiterals.parseInterval("1:00:00.5")).isEqualTo(span(0, Duration.ofMillis(3_600_500)));
        assertThat(TemporalLiterals.parseInterval("3 hours ago")).isEqualTo(span(0, Duration.ofHours(-3)));
        assertThat(TemporalLiterals.parseInterval("1 hr 2 min 3 sec")).isEqualTo(span(0, Duration.ofSeconds(3723)));
        assertThat(TemporalLiterals.parseInterval("P1M")).isEqualTo(span(1, Duration.ZERO));
        assertThat(TemporalLiterals.parseInterval("p1m")).isEqualTo(span(1, Duration.ZERO));
        assertThat(TemporalLiterals.parseInterval("-P1M")).isEqualTo(span(-1, Duration.ZERO));
        assertThat(TemporalLiterals.parseInterval("+P1M")).isEqualTo(span(1, Duration.ZERO));
        assertThatThrownBy(() -> TemporalLiterals.parseInterval("x 1 day")).isInstanceOf(DateTimeParseException.class);
        // PostgreSQL's fractions: a year's are whole months, a month's are days at 30 a month.
        assertThat(TemporalLiterals.parseInterval("1.51 years")).isEqualTo(span(18, Duration.ZERO));
        assertThat(TemporalLiterals.parseInterval("1.5 months")).isEqualTo(span(1, Duration.ofDays(15)));
        assertThatThrownBy(() -> TemporalLiterals.parseInterval("3 fortnights")).hasMessageContaining("unknown interval unit");
        assertThatThrownBy(() -> TemporalLiterals.parseInterval("soon")).isInstanceOf(DateTimeParseException.class);
        assertThatThrownBy(() -> TemporalLiterals.parseInterval(" ")).isInstanceOf(DateTimeParseException.class);
    }

    @Test
    void formatsAsDurationPrints() {
        assertThat(TemporalLiterals.formatSpan(0, Duration.ofMinutes(30))).isEqualTo("PT30M");
        assertThat(TemporalLiterals.formatSpan(14, Duration.ZERO)).isEqualTo("P1Y2M");
        assertThat(TemporalLiterals.formatSpan(1, Duration.ofHours(360))).isEqualTo("P1MT360H");
        DurationOperand month = AstBuilders.duration("P1M");
        assertThat(month.isExact()).isFalse();
        assertThat(month.iso()).isEqualTo("P1M");
        assertThat(AstBuilders.duration("PT1H").isExact()).isTrue();
        assertThat(AstEquivalence.equivalent(month, AstBuilders.duration("P1M"))).isTrue();
        assertThat(AstEquivalence.equivalent(month, AstBuilders.duration("P30D"))).isFalse();
    }
}
