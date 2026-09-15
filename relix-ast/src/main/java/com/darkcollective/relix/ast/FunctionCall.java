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

import com.darkcollective.relix.ast.visitor.OperandVisitor;

import java.util.List;
import java.util.Objects;

/**
 * Represents a function call in an expression.
 * A function call has a name and a list of arguments.
 *
 * @param functionName the name of the function
 * @param arguments    the function arguments
 * @param location     the source location of this operand; never null
 */
public record FunctionCall(String functionName, List<Operand> arguments, SourceLocation location) implements Operand {
    public FunctionCall {
        Objects.requireNonNull(location, "location");
        if (arguments != null) {
            arguments = List.copyOf(arguments);
        }
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public FunctionCall(String functionName, List<Operand> arguments) {
        this(functionName, arguments, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(OperandVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
