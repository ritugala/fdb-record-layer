/*
 * NotPredicate.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2019 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.query.predicates;

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.record.EvaluationContext;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStoreBase;
import com.apple.foundationdb.record.query.plan.temp.Bindable;
import com.apple.foundationdb.record.query.plan.temp.matchers.ExpressionMatcher;
import com.apple.foundationdb.record.query.plan.temp.matchers.PlannerBindings;
import com.apple.foundationdb.record.query.plan.temp.view.SourceEntry;
import com.google.common.collect.Iterators;
import com.google.protobuf.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A {@link QueryPredicate} that is satisfied when its child component is not satisfied.
 *
 * For tri-valued logic, if the child evaluates to unknown / {@code null}, {@code NOT} is still unknown.
 */
@API(API.Status.EXPERIMENTAL)
public class NotPredicate implements QueryPredicate {
    @Nonnull
    public final QueryPredicate child;

    public NotPredicate(@Nonnull QueryPredicate child) {
        this.child = child;
    }

    @Nullable
    @Override
    public <M extends Message> Boolean eval(@Nonnull FDBRecordStoreBase<M> store, @Nonnull EvaluationContext context, @Nonnull SourceEntry sourceEntry) {
        return invert(child.eval(store, context, sourceEntry));
    }

    @Nullable
    private Boolean invert(@Nullable Boolean v) {
        if (v == null) {
            return null;
        } else {
            return !v;
        }
    }

    @Nonnull
    public QueryPredicate getChild() {
        return child;
    }

    @Override
    @Nonnull
    public Stream<PlannerBindings> bindTo(@Nonnull ExpressionMatcher<? extends Bindable> binding) {
        Stream<PlannerBindings> bindings = binding.matchWith(this);
        return bindings.flatMap(outerBindings -> binding.getChildrenMatcher().matches(Iterators.singletonIterator(getChild()))
                .map(outerBindings::mergedWith));
    }

    @Override
    public String toString() {
        return "Not(" + getChild() + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        NotPredicate that = (NotPredicate)o;
        return Objects.equals(getChild(), that.getChild());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getChild());
    }

    @Override
    public int planHash() {
        return getChild().planHash() + 1;
    }
}
