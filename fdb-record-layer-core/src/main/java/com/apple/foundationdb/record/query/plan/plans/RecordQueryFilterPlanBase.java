/*
 * RecordQueryFilterPlanBase.java
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

package com.apple.foundationdb.record.query.plan.plans;

import com.apple.foundationdb.record.EvaluationContext;
import com.apple.foundationdb.record.ExecuteProperties;
import com.apple.foundationdb.record.PipelineOperation;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.provider.common.StoreTimer;
import com.apple.foundationdb.record.provider.foundationdb.FDBQueriedRecord;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecord;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStoreBase;
import com.apple.foundationdb.record.provider.foundationdb.FDBStoreTimer;
import com.apple.foundationdb.record.query.plan.temp.ExpressionRef;
import com.apple.foundationdb.record.query.plan.temp.RelationalExpression;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.protobuf.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A base class for all query plans that filter based on predicates.
 */
abstract class RecordQueryFilterPlanBase implements RecordQueryPlanWithChild {
    @Nonnull
    private final ExpressionRef<RecordQueryPlan> inner;

    @Nonnull
    private static final Set<StoreTimer.Count> inCounts = ImmutableSet.of(FDBStoreTimer.Counts.QUERY_FILTER_GIVEN, FDBStoreTimer.Counts.QUERY_FILTER_PLAN_GIVEN);
    @Nonnull
    private static final Set<StoreTimer.Event> duringEvents = Collections.singleton(FDBStoreTimer.Events.QUERY_FILTER);
    @Nonnull
    private static final Set<StoreTimer.Count> successCounts = ImmutableSet.of(FDBStoreTimer.Counts.QUERY_FILTER_PASSED, FDBStoreTimer.Counts.QUERY_FILTER_PLAN_PASSED);
    @Nonnull
    private static final Set<StoreTimer.Count> failureCounts = Collections.singleton(FDBStoreTimer.Counts.QUERY_DISCARDED);

    protected RecordQueryFilterPlanBase(@Nonnull ExpressionRef<RecordQueryPlan> inner) {
        this.inner = inner;
    }

    protected abstract boolean hasAsyncFilter();

    @Nullable
    protected abstract <M extends Message> Boolean evalFilter(@Nonnull FDBRecordStoreBase<M> store,
                                                              @Nonnull EvaluationContext context,
                                                              @Nullable FDBRecord<M> record);

    @Nullable
    protected abstract <M extends Message> CompletableFuture<Boolean> evalFilterAsync(@Nonnull FDBRecordStoreBase<M> store,
                                                                                      @Nonnull EvaluationContext context,
                                                                                      @Nullable FDBRecord<M> record);


    @Nonnull
    @Override
    public <M extends Message> RecordCursor<FDBQueriedRecord<M>> execute(@Nonnull FDBRecordStoreBase<M> store,
                                                                         @Nonnull EvaluationContext context,
                                                                         @Nullable byte[] continuation,
                                                                         @Nonnull ExecuteProperties executeProperties) {
        final RecordCursor<FDBQueriedRecord<M>> results = getInner().execute(store, context, continuation, executeProperties.clearSkipAndLimit());

        if (hasAsyncFilter()) {
            return results
                    .filterAsyncInstrumented(record -> evalFilterAsync(store, context, record),
                            store.getPipelineSize(PipelineOperation.RECORD_ASYNC_FILTER),
                            store.getTimer(), inCounts, duringEvents, successCounts, failureCounts)
                    .skipThenLimit(executeProperties.getSkip(), executeProperties.getReturnedRowLimit());
        } else {
            return results
                    .filterInstrumented(record -> evalFilter(store, context, record), store.getTimer(),
                            inCounts, duringEvents, successCounts, failureCounts)
                    .skipThenLimit(executeProperties.getSkip(), executeProperties.getReturnedRowLimit());
        }
    }

    @Nonnull
    public RecordQueryPlan getInner() {
        return inner.get();
    }

    @Nonnull
    @Override
    public Iterator<? extends ExpressionRef<? extends RelationalExpression>> getPlannerExpressionChildren() {
        return Iterators.singletonIterator(inner);
    }

    @Override
    public boolean isReverse() {
        return getInner().isReverse();
    }

    @Override
    public boolean hasRecordScan() {
        return getInner().hasRecordScan();
    }

    @Override
    public boolean hasFullRecordScan() {
        return getInner().hasFullRecordScan();
    }

    @Override
    public boolean hasIndexScan(@Nonnull String indexName) {
        return getInner().hasIndexScan(indexName);
    }

    @Nonnull
    @Override
    public Set<String> getUsedIndexes() {
        return getInner().getUsedIndexes();
    }

    @Override
    @Nonnull
    public RecordQueryPlan getChild() {
        return getInner();
    }

    @Override
    public void logPlanStructure(StoreTimer timer) {
        timer.increment(FDBStoreTimer.Counts.PLAN_FILTER);
        getInner().logPlanStructure(timer);
    }

    @Override
    public int getComplexity() {
        return 1 + getInner().getComplexity();
    }
}
