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

import com.darkcollective.relix.function.Arity;
import com.darkcollective.relix.function.PushdownSpelling;
import com.darkcollective.relix.function.PushdownTarget;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.darkcollective.relix.function.builtin.Arguments.integer;
import static com.darkcollective.relix.function.builtin.Arguments.reject;
import static com.darkcollective.relix.function.builtin.Arguments.string;
import static com.darkcollective.relix.function.builtin.Category.PURE_DETERMINISTIC;
import static com.darkcollective.relix.function.builtin.Category.p;
import static com.darkcollective.relix.symbol.ScalarType.NUMBER;
import static com.darkcollective.relix.symbol.ScalarType.STRING;

/**
 * The hash built-ins: a string's digest, as lowercase hexadecimal, for fingerprinting a
 * row to detect a change or to compare data held in two places.
 *
 * <p>Each hashes the text as UTF-8 and answers as PostgreSQL's {@code md5} does: the
 * digest's bytes in lowercase hex, so {@code MD5('a')} is
 * {@code '0cc175b9c0f1b6a831c399e269772661'} wherever it is computed. A NULL is NULL.
 *
 * <p>SQL Server's {@code HASHBYTES} and Oracle's {@code STANDARD_HASH} return bytes
 * rather than text; here they return the same digest as the others do, in hex, since
 * the engine has no binary type. Each names its algorithm, and one that is not among
 * these is refused.
 *
 * <h2>Which of these a backend is offered</h2>
 * A backend computes a digest only where it hashes the same bytes and writes the same
 * text: PostgreSQL over {@code convert_to(s, 'UTF8')}, which is the UTF-8 encoding
 * whatever the database's own; MySQL, whose {@code MD5}, {@code SHA1} and {@code SHA2}
 * answer in lowercase hex; and DuckDB's {@code md5} and {@code sha256}. SQL Server hashes
 * an {@code NVARCHAR} as UTF-16, so it is not offered one.
 */
final class HashFunctions {

    private static final Category STRINGS = Category.of("string");

    /** SHA2's bit lengths, and the digest each is; MySQL's 0 is 256. */
    private static final Map<Integer, String> SHA2_BITS = Map.of(
            0, "SHA-256", 224, "SHA-224", 256, "SHA-256", 384, "SHA-384", 512, "SHA-512");

    /** HASHBYTES' algorithm names, as SQL Server writes them, and the digest each is. */
    private static final Map<String, String> HASHBYTES = Map.of(
            "MD5", "MD5", "SHA1", "SHA-1", "SHA", "SHA-1", "SHA2_256", "SHA-256", "SHA2_512", "SHA-512");

    /** STANDARD_HASH's algorithm names, as Oracle writes them, and the digest each is. */
    private static final Map<String, String> STANDARD_HASH = Map.of(
            "MD5", "MD5", "SHA1", "SHA-1", "SHA256", "SHA-256", "SHA384", "SHA-384", "SHA512", "SHA-512");

    private HashFunctions() {
    }

    static List<ScalarFunction> all() {
        return List.of(
                STRINGS.fn("MD5", STRING, PURE_DETERMINISTIC, List.of(p("s", STRING)),
                        Spellings.firstOf(
                                Spellings.on(Spellings.POSTGRES, 1, a -> "md5(convert_to(" + a.get(0) + ", 'UTF8'))"),
                                Spellings.sqlOn("MD5", 1, Spellings.MYSQL, Spellings.DUCKDB)),
                        args -> digest(args.get(0), "MD5", "MD5")),

                STRINGS.fn("SHA1", STRING, PURE_DETERMINISTIC, List.of(p("s", STRING)),
                        Spellings.sqlOn("SHA1", 1, Spellings.MYSQL),
                        args -> digest(args.get(0), "SHA-1", "SHA1")),

                STRINGS.fn("SHA256", STRING, PURE_DETERMINISTIC, List.of(p("s", STRING)),
                        Spellings.firstOf(
                                Spellings.on(Spellings.POSTGRES, 1, HashFunctions::postgresSha256),
                                Spellings.on(Spellings.MYSQL, 1, a -> "SHA2(" + a.get(0) + ", 256)"),
                                Spellings.sqlOn("SHA256", 1, Spellings.DUCKDB)),
                        args -> digest(args.get(0), "SHA-256", "SHA256")),

                STRINGS.fn("SHA2", STRING, PURE_DETERMINISTIC, List.of(p("s", STRING), p("bits", NUMBER)),
                        HashFunctions::sha2Spelling, HashFunctions::sha2),

                STRINGS.fn("HASHBYTES", STRING, PURE_DETERMINISTIC,
                        List.of(p("algorithm", STRING), p("s", STRING)),
                        args -> named(args.get(0), args.get(1), HASHBYTES, "HASHBYTES")),

                STRINGS.fn("STANDARD_HASH", STRING, PURE_DETERMINISTIC,
                        List.of(p("s", STRING), p("algorithm", STRING)), Arity.between(1, 2),
                        args -> args.size() == 1
                                ? digest(args.get(0), "SHA-1", "STANDARD_HASH")
                                : named(args.get(1), args.get(0), STANDARD_HASH, "STANDARD_HASH")));
    }

    /** PostgreSQL's SHA-256 of the UTF-8 bytes, as hex text; {@code sha256} takes bytes and returns bytes. */
    private static String postgresSha256(List<String> arguments) {
        return "encode(sha256(convert_to(" + arguments.get(0) + ", 'UTF8')), 'hex')";
    }

    /**
     * {@code SHA2(s, bits)} on MySQL, the function it is, for a bit length written as a
     * literal the engine also accepts; any other is left to the engine, which refuses it
     * where MySQL would answer NULL.
     */
    private static Optional<String> sha2Spelling(PushdownTarget target, List<String> arguments) {
        return target.isFamily(PushdownTarget.SQL) && target.isVariant(Spellings.MYSQL)
                && arguments.size() == 2 && Set.of("224", "256", "384", "512").contains(arguments.get(1).strip())
                ? Optional.of("SHA2(" + arguments.get(0) + ", " + arguments.get(1).strip() + ")")
                : Optional.empty();
    }

    private static Value sha2(List<Value> args) {
        if (args.get(0).isNull() || args.get(1).isNull()) {
            return NullValue.INSTANCE;
        }
        int bits = integer(args.get(1), "SHA2");
        String algorithm = SHA2_BITS.get(bits);
        if (algorithm == null) {
            throw reject("SHA2: bits must be 224, 256, 384 or 512, got " + bits);
        }
        return digest(args.get(0), algorithm, "SHA2");
    }

    /** The digest named by {@code algorithm} in {@code names}, of {@code text}. */
    private static Value named(Value algorithm, Value text, Map<String, String> names, String function) {
        if (algorithm.isNull()) {
            return NullValue.INSTANCE;
        }
        String name = string(algorithm, function).strip().toUpperCase(Locale.ROOT);
        String digest = names.get(name);
        if (digest == null) {
            throw reject(function + ": algorithm '" + string(algorithm, function) + "' is not one of "
                    + String.join(", ", names.keySet().stream().sorted().toList()));
        }
        return digest(text, digest, function);
    }

    /** The {@code algorithm} digest of the UTF-8 bytes of {@code text}, in lowercase hex. */
    private static Value digest(Value text, String algorithm, String function) {
        if (text.isNull()) {
            return NullValue.INSTANCE;
        }
        byte[] bytes = string(text, function).getBytes(StandardCharsets.UTF_8);
        try {
            return new StringValue(HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide each algorithm used here.
            throw new IllegalStateException(algorithm + " is not available", e);
        }
    }
}
