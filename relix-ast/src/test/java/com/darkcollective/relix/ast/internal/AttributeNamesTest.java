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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

final class AttributeNamesTest {

    @Test
    void stripsLeadingRelationQualifier() {
        assertThat(AttributeNames.stripQualifier("Users.id")).isEqualTo("id");
    }

    @Test
    void leavesUnqualifiedNameUnchanged() {
        assertThat(AttributeNames.stripQualifier("id")).isEqualTo("id");
    }

    @Test
    void stripsOnlyUpToTheLastDot() {
        assertThat(AttributeNames.stripQualifier("schema.Users.id")).isEqualTo("id");
    }

    @Test
    void handlesTrailingDotAsEmptyColumn() {
        assertThat(AttributeNames.stripQualifier("Users.")).isEmpty();
    }

    @Test
    void leadingDotStripsToTail() {
        assertThat(AttributeNames.stripQualifier(".id")).isEqualTo("id");
    }

    @Test
    void rejectsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> AttributeNames.stripQualifier(null));
    }

    // ── pathHead — the other reading of a dotted name ───────────────────────────

    @Test
    void pathHeadTakesEverythingBeforeTheFirstDot() {
        assertThat(AttributeNames.pathHead("location.city")).isEqualTo("location");
    }

    @Test
    void pathHeadStopsAtTheFirstDotWhereStripQualifierStopsAtTheLast() {
        // The two readings of one name, side by side: this is the whole reason both
        // exist. `location.geo.lat` is the field `lat` of `geo` of the column
        // `location` under one reading, and the column `lat` from the relation
        // `location.geo` under the other.
        assertThat(AttributeNames.pathHead("location.geo.lat")).isEqualTo("location");
        assertThat(AttributeNames.stripQualifier("location.geo.lat")).isEqualTo("lat");
    }

    @Test
    void pathHeadLeavesUnqualifiedNameUnchanged() {
        assertThat(AttributeNames.pathHead("city")).isEqualTo("city");
    }

    @Test
    void pathHeadOfALeadingDotIsTheWholeName() {
        // A leading dot has no head to take, so there is no column to name.
        assertThat(AttributeNames.pathHead(".city")).isEqualTo(".city");
    }

    @Test
    void pathHeadRejectsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> AttributeNames.pathHead(null));
    }

    // ── isPathInto ──────────────────────────────────────────────────────────────

    @Test
    void isPathIntoMatchesAFieldOfTheColumn() {
        assertThat(AttributeNames.isPathInto("skills.years", "skills")).isTrue();
        assertThat(AttributeNames.isPathInto("skills.detail.years", "skills")).isTrue();
    }

    @Test
    void isPathIntoIgnoresCase() {
        assertThat(AttributeNames.isPathInto("SKILLS.years", "skills")).isTrue();
    }

    @Test
    void theColumnItselfIsNotAPathIntoIt() {
        assertThat(AttributeNames.isPathInto("skills", "skills")).isFalse();
    }

    @Test
    void isPathIntoRejectsAPrefixThatIsNotTheWholeHead() {
        // `skillset.years` starts with `skills` as text and names a different column.
        assertThat(AttributeNames.isPathInto("skillset.years", "skills")).isFalse();
        assertThat(AttributeNames.isPathInto("skill.years", "skills")).isFalse();
    }

    @Test
    void aQualifiedReferenceEndingInTheColumnIsNotAPathIntoIt() {
        assertThat(AttributeNames.isPathInto("Orders.skills", "skills")).isFalse();
    }

    @Test
    void isPathIntoRejectsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> AttributeNames.isPathInto(null, "skills"));
        assertThatNullPointerException()
                .isThrownBy(() -> AttributeNames.isPathInto("skills.years", null));
    }
}
