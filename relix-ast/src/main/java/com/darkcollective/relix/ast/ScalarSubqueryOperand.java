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

import java.util.Objects;

/**
 * A relation used as a value: {@code (γ COUNT(*) → n (σ customer_id = Customers.customer_id (Orders)))}.
 *
 * <p>The relation has one column. Its value is that column's value in its one row, or
 * NULL when it has none; more than one row is an error when the query runs. Inside it, a
 * qualified name whose relation the subquery does not read, {@code Customers.customer_id}
 * above, is a column of the row the value is computed for: the subquery is
 * <em>correlated</em>, and yields a value per row.
 *
 * @param query    the relation whose one value this is; must not be null
 * @param location the source location of the opening parenthesis; never null
 */
public record ScalarSubqueryOperand(RelNode query, SourceLocation location) implements Operand {

    public ScalarSubqueryOperand {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public ScalarSubqueryOperand(RelNode query) {
        this(query, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(OperandVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
