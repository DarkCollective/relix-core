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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.lang.ast.Script;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InMemoryScriptLoader — lookup, error cases, defensive copy")
final class InMemoryScriptLoaderTest {

    private static Script emptyScript() {
        return script();
    }

    @Test
    @DisplayName("Returns the script registered for a known path")
    void returnsKnownScript() throws IOException {
        Script s = emptyScript();
        var loader = new InMemoryScriptLoader(Map.of("a.relix", s));
        assertThat(loader.load("a.relix")).isSameAs(s);
    }

    @Test
    @DisplayName("Throws IOException for an unregistered path")
    void throwsForUnknownPath() {
        var loader = new InMemoryScriptLoader(Map.of("a.relix", emptyScript()));
        assertThatThrownBy(() -> loader.load("missing.relix"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("missing.relix");
    }

    @Test
    @DisplayName("Lookup is case-sensitive")
    void lookupIsCaseSensitive() {
        var loader = new InMemoryScriptLoader(Map.of("Foo.relix", emptyScript()));
        assertThatThrownBy(() -> loader.load("foo.relix"))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("Empty map throws IOException for any path")
    void emptyMapThrowsForAny() {
        var loader = new InMemoryScriptLoader(Map.of());
        assertThatThrownBy(() -> loader.load("anything.relix"))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("Null path throws NullPointerException")
    void nullPathThrows() {
        var loader = new InMemoryScriptLoader(Map.of());
        assertThatThrownBy(() -> loader.load(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("path");
    }

    @Test
    @DisplayName("Null map throws NullPointerException at construction")
    void nullMapThrows() {
        assertThatThrownBy(() -> new InMemoryScriptLoader(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("scripts");
    }

    @Test
    @DisplayName("Defensive copy: mutating original map does not affect loader")
    void defensiveCopy() throws IOException {
        Script s = emptyScript();
        var mutable = new HashMap<String, Script>();
        mutable.put("a.relix", s);
        var loader = new InMemoryScriptLoader(mutable);

        mutable.clear(); // mutate after construction

        assertThat(loader.load("a.relix")).isSameAs(s); // still works
    }

    @Test
    @DisplayName("scripts() returns all registered entries")
    void scriptsAccessor() {
        Script s1 = emptyScript();
        Script s2 = emptyScript();
        var loader = new InMemoryScriptLoader(Map.of("a.relix", s1, "b.relix", s2));
        assertThat(loader.scripts()).containsOnlyKeys("a.relix", "b.relix");
    }

    @Test
    @DisplayName("Multiple scripts can be loaded independently")
    void multipleScripts() throws IOException {
        Script a = emptyScript();
        Script b = emptyScript();
        var loader = new InMemoryScriptLoader(Map.of("a.relix", a, "b.relix", b));
        assertThat(loader.load("a.relix")).isSameAs(a);
        assertThat(loader.load("b.relix")).isSameAs(b);
    }

    @Test
    @DisplayName("Implements ScriptLoader — usable as a lambda-compatible reference")
    void implementsScriptLoader() throws IOException {
        Script s = emptyScript();
        ScriptLoader loader = new InMemoryScriptLoader(Map.of("x.relix", s));
        assertThat(loader.load("x.relix")).isSameAs(s);
    }
}
