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

import com.darkcollective.relix.function.PushdownTarget;
import com.darkcollective.relix.value.NullValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.function.builtin.BuiltinCalls.call;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.function;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.n;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.s;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hash functions (#264). The digests are PostgreSQL's: {@code md5('a')},
 * {@code encode(sha256(convert_to('a', 'UTF8')), 'hex')}, and the same over a string
 * with characters outside ASCII, whose UTF-8 bytes are what is hashed.
 */
@DisplayName("Hash functions")
final class HashFunctionsTest {

    private static final String WIDE = "héllo wörld ✓";

    @Test
    @DisplayName("MD5, SHA1 and SHA256 are the lowercase hex digest of the UTF-8 bytes")
    void digests() {
        assertThat(text(call("MD5", s("a")))).isEqualTo("0cc175b9c0f1b6a831c399e269772661");
        assertThat(text(call("MD5", s(WIDE)))).isEqualTo("aa0c8a307a4488bfe0cb56530da19bc3");
        assertThat(text(call("SHA1", s("a")))).isEqualTo("86f7e437faa5a7fce15d1ddcb9eaeaea377667b8");
        assertThat(text(call("SHA1", s(WIDE)))).isEqualTo("a5e7f35caea50aa6f3bc37d2f24a540fc0b3cb32");
        assertThat(text(call("SHA256", s("a"))))
                .isEqualTo("ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb");
        assertThat(text(call("SHA256", s(WIDE))))
                .isEqualTo("c2a59c71097b678dc5af2eb1f98ddc575b63948b0fa6740071a945673aaada4d");
    }

    @Test
    @DisplayName("SHA2 takes MySQL's bit lengths, 0 meaning 256, and refuses any other")
    void sha2() {
        assertThat(text(call("SHA2", s("a"), n("256")))).isEqualTo(text(call("SHA256", s("a"))));
        assertThat(text(call("SHA2", s("a"), n("0")))).isEqualTo(text(call("SHA256", s("a"))));
        assertThat(text(call("SHA2", s("a"), n("224")))).isEqualTo("abd37534c7d9a2efb9465de931cd7055ffdb8879563ae98078d6d6d5");
        assertThat(text(call("SHA2", s("a"), n("384")))).startsWith("54a59b9f22b0b80880d8427e548b7c23");
        assertThat(text(call("SHA2", s("a"), n("512")))).startsWith("1f40fc92da241694750979ee6cf582f2");
        assertThatThrownBy(() -> call("SHA2", s("a"), n("128")))
                .hasMessage("SHA2: bits must be 224, 256, 384 or 512, got 128");
    }

    @Test
    @DisplayName("HASHBYTES and STANDARD_HASH name an algorithm, in any case, and refuse one they do not have")
    void namedAlgorithms() {
        assertThat(text(call("HASHBYTES", s("md5"), s("a")))).isEqualTo(text(call("MD5", s("a"))));
        assertThat(text(call("HASHBYTES", s("SHA2_256"), s("a")))).isEqualTo(text(call("SHA256", s("a"))));
        assertThat(text(call("HASHBYTES", s("SHA1"), s("a")))).isEqualTo(text(call("SHA1", s("a"))));
        assertThat(text(call("STANDARD_HASH", s("a")))).isEqualTo(text(call("SHA1", s("a"))));
        assertThat(text(call("STANDARD_HASH", s("a"), s("SHA256")))).isEqualTo(text(call("SHA256", s("a"))));
        assertThat(text(call("STANDARD_HASH", s("a"), s("SHA512")))).isEqualTo(text(call("SHA2", s("a"), n("512"))));
        assertThatThrownBy(() -> call("HASHBYTES", s("CRC32"), s("a")))
                .hasMessage("HASHBYTES: algorithm 'CRC32' is not one of MD5, SHA, SHA1, SHA2_256, SHA2_512");
        assertThatThrownBy(() -> call("STANDARD_HASH", s("a"), s("SHA2_256")))
                .hasMessage("STANDARD_HASH: algorithm 'SHA2_256' is not one of MD5, SHA1, SHA256, SHA384, SHA512");
    }

    @Test
    @DisplayName("a NULL text or algorithm gives NULL")
    void nulls() {
        for (String name : List.of("MD5", "SHA1", "SHA256", "STANDARD_HASH")) {
            assertThat(call(name, NullValue.INSTANCE)).as(name).isEqualTo(NullValue.INSTANCE);
        }
        assertThat(call("SHA2", NullValue.INSTANCE, n("256"))).isEqualTo(NullValue.INSTANCE);
        assertThat(call("SHA2", s("a"), NullValue.INSTANCE)).isEqualTo(NullValue.INSTANCE);
        assertThat(call("HASHBYTES", NullValue.INSTANCE, s("a"))).isEqualTo(NullValue.INSTANCE);
        assertThat(call("HASHBYTES", s("MD5"), NullValue.INSTANCE)).isEqualTo(NullValue.INSTANCE);
        assertThat(call("STANDARD_HASH", NullValue.INSTANCE, s("MD5"))).isEqualTo(NullValue.INSTANCE);
    }

    @Test
    @DisplayName("each is pushed where the backend hashes the same bytes into the same text")
    void pushdown() {
        PushdownTarget postgres = PushdownTarget.sql("postgres");
        PushdownTarget mysql = PushdownTarget.sql("mysql");
        PushdownTarget duckdb = PushdownTarget.sql("duckdb");
        PushdownTarget sqlserver = PushdownTarget.sql("sqlserver");
        assertThat(function("MD5").pushdown().render(postgres, List.of("s")))
                .contains("md5(convert_to(s, 'UTF8'))");
        assertThat(function("MD5").pushdown().render(mysql, List.of("s"))).contains("MD5(s)");
        assertThat(function("MD5").pushdown().render(duckdb, List.of("s"))).contains("MD5(s)");
        assertThat(function("MD5").pushdown().render(sqlserver, List.of("s"))).isEmpty();
        assertThat(function("SHA1").pushdown().render(mysql, List.of("s"))).contains("SHA1(s)");
        assertThat(function("SHA1").pushdown().render(postgres, List.of("s"))).isEmpty();
        assertThat(function("SHA256").pushdown().render(postgres, List.of("s")))
                .contains("encode(sha256(convert_to(s, 'UTF8')), 'hex')");
        assertThat(function("SHA256").pushdown().render(mysql, List.of("s"))).contains("SHA2(s, 256)");
        assertThat(function("SHA256").pushdown().render(duckdb, List.of("s"))).contains("SHA256(s)");
        assertThat(function("SHA2").pushdown().render(mysql, List.of("s", "512"))).contains("SHA2(s, 512)");
        assertThat(function("SHA2").pushdown().render(mysql, List.of("s", "0"))).isEmpty();
        assertThat(function("SHA2").pushdown().render(mysql, List.of("s", "bits"))).isEmpty();
        assertThat(function("SHA2").pushdown().render(postgres, List.of("s", "256"))).isEmpty();
        assertThat(function("SHA2").pushdown().render(PushdownTarget.mongo(), List.of("s", "256"))).isEmpty();
        assertThat(function("HASHBYTES").pushdown().render(sqlserver, List.of("'MD5'", "s"))).isEmpty();
    }
}
