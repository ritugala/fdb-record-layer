/*
 * UnionCursor.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2018 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.provider.foundationdb.cursors;

import com.apple.foundationdb.record.RecordCoreArgumentException;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.logging.LogMessageKeys;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.provider.foundationdb.FDBEvaluationContext;
import com.apple.foundationdb.record.provider.foundationdb.FDBStoreTimer;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecord;
import com.google.protobuf.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * A cursor that implements a union of all the records from a set of cursors all of whom are ordered compatibly.
 * @param <T> the type of elements returned by the cursor
 */
public class UnionCursor<T> extends UnionCursorBase<T> {
    private final boolean reverse;

    private UnionCursor(@Nonnull Function<? super T, ? extends List<Object>> comparisonKeyFunction,
                        boolean reverse, @Nonnull List<CursorState<T>> cursorStates,
                        @Nullable FDBStoreTimer timer) {
        super(comparisonKeyFunction, cursorStates, timer);
        this.reverse = reverse;
    }

    @Nonnull
    @Override
    CompletableFuture<Boolean> getIfAnyHaveNext(@Nonnull List<CursorState<T>> cursorStates) {
        return whenAll(cursorStates).thenApply(vignore -> {
            cursorStates.forEach(cursorState -> {
                if (!cursorState.hasNext) { // continuation is valid immediately
                    cursorState.continuation = cursorState.cursor.getContinuation();
                }
            });
            boolean anyHasNext = false;
            for (CursorState<T> cursorState : cursorStates) {
                if (!cursorState.hasNext && cursorState.cursor.getNoNextReason().isLimitReached()) { // continuation is valid immediately
                    // If any side stopped due to limit reached, need to stop completely,
                    // since might otherwise duplicate ones after that, if other side still available.
                    return false;
                } else if (cursorState.hasNext) {
                    anyHasNext = true;
                }
            }
            return anyHasNext;
        });
    }

    @Override
    void chooseStates(@Nonnull List<CursorState<T>> allStates, @Nonnull List<CursorState<T>> chosenStates, @Nonnull List<CursorState<T>> otherStates) {
        List<Object> nextKey = null;
        for (CursorState<T> cursorState : allStates) {
            if (cursorState.element == null) {
                cursorState.continuation = null;
            } else {
                int compare;
                if (nextKey == null) {
                    // This is the first key we've seen, so always chose it.
                    compare = -1;
                } else {
                    // Choose the minimum of the previous minimum key and this next one
                    // If doing a reverse scan, choose the maximum.
                    compare = KeyComparisons.KEY_COMPARATOR.compare(cursorState.key, nextKey) * (reverse ? -1 : 1);
                }
                if (compare < 0) {
                    // We have a new next key. Reset the book-keeping information.
                    otherStates.addAll(chosenStates);
                    chosenStates.clear();
                    nextKey = cursorState.key;
                }
                if (compare <= 0) {
                    chosenStates.add(cursorState);
                } else {
                    otherStates.add(cursorState);
                }
            }
        }
    }

    /**
     * Create a union cursor from two compatibly-ordered cursors. This cursor
     * is identical to the cursor that would be produced by calling the overload of
     * {@link #create(FDBEvaluationContext, KeyExpression, boolean, List, byte[]) create()}
     * that takes a list of cursors.
     *
     * @param context the context to use when evaluating the comparison key against records
     * @param comparisonKey the key expression used to compare records from different cursors
     * @param reverse whether records are returned in descending or ascending order by the comparison key
     * @param left a function to produce the first {@link RecordCursor} from a continuation
     * @param right a function to produce the second {@link RecordCursor} from a continuation
     * @param continuation any continuation from a previous scan
     * @param <M> the type of the Protobuf record elements of the cursor
     * @param <S> the type of record wrapping a record of type <code>M</code>
     * @return a cursor containing any records in any child cursors
     */
    @Nonnull
    public static <M extends Message, S extends FDBRecord<M>> UnionCursor<S> create(
            @Nonnull FDBEvaluationContext<M> context,
            @Nonnull KeyExpression comparisonKey, boolean reverse,
            @Nonnull Function<byte[], RecordCursor<S>> left,
            @Nonnull Function<byte[], RecordCursor<S>> right,
            @Nullable byte[] continuation) {
        return create(
                (S record) -> comparisonKey.evaluateSingleton(context, record).toTupleAppropriateList(),
                reverse, left, right, continuation, context.getTimer());
    }

    /**
     * Create a union cursor from two compatibly-ordered cursors. This cursor
     * is identical to the cursor that would be produced by calling the overload of
     * {@link #create(Function, boolean, List, byte[], FDBStoreTimer) create()}
     * that takes a list of cursors.
     *
     * @param comparisonKeyFunction the function expression used to compare elements from different cursors
     * @param reverse whether records are returned in descending or ascending order by the comparison key
     * @param left a function to produce the first {@link RecordCursor} from a continuation
     * @param right a function to produce the second {@link RecordCursor} from a continuation
     * @param continuation any continuation from a previous scan
     * @param timer the timer used to instrument events
     * @param <T> the type of elements returned by the cursor
     * @return a cursor containing all elements in both child cursors
     * @see #create(Function, boolean, Function, Function, byte[], FDBStoreTimer)
     */
    @Nonnull
    public static <T> UnionCursor<T> create(
            @Nonnull Function<? super T, ? extends List<Object>> comparisonKeyFunction,
            boolean reverse,
            @Nonnull Function<byte[], RecordCursor<T>> left,
            @Nonnull Function<byte[], RecordCursor<T>> right,
            @Nullable byte[] continuation,
            @Nullable FDBStoreTimer timer) {
        final List<CursorState<T>> cursorStates = createCursorStates(left, right, continuation);
        return new UnionCursor<>(comparisonKeyFunction, reverse, cursorStates, timer);
    }

    /**
     * Create a union cursor from two or more compatibly-ordered cursors.
     * As its comparison key function, it will evaluate the provided comparison key
     * on each record from each cursor. This otherwise behaves exactly the same way
     * as the overload of this function that takes a function to extract a comparison
     * key.
     *
     * @param context the context to use when evaluating the comparison key against records
     * @param comparisonKey the key expression used to compare records from different cursors
     * @param reverse whether records are returned in descending or ascending order by the comparison key
     * @param cursorFunctions a list of functions to produce {@link RecordCursor}s from a continuation
     * @param continuation any continuation from a previous scan
     * @param <M> the type of the Protobuf record elements of the cursor
     * @param <S> the type of record wrapping a record of type <code>M</code>
     * @return a cursor containing any records in any child cursors
     * @see #create(Function, boolean, List, byte[], FDBStoreTimer)
     */
    @Nonnull
    public static <M extends Message, S extends FDBRecord<M>> UnionCursor<S> create(
            @Nonnull FDBEvaluationContext<M> context,
            @Nonnull KeyExpression comparisonKey, boolean reverse,
            @Nonnull List<Function<byte[], RecordCursor<S>>> cursorFunctions,
            @Nullable byte[] continuation) {
        return create(
                (S record) -> comparisonKey.evaluateSingleton(context, record).toTupleAppropriateList(),
                reverse, cursorFunctions, continuation, context.getTimer());
    }

    /**
     * Create a union cursor from two or more compatibly-ordered cursors.
     * Note that this will throw an error if the list of cursors does not have at least two elements.
     * The returned cursor will return any records that appear in any of the provided
     * cursors, preserving order. All cursors must return records in the same order,
     * and that order should be determined by the comparison key function, i.e., if <code>reverse</code>
     * is <code>false</code>, then the records should be returned in ascending order by that key,
     * and if <code>reverse</code> is <code>true</code>, they should be returned in descending
     * order. Additionally, if the comparison key function evaluates to the same value when applied
     * to two records (possibly from different cursors), then those two elements
     * should be equal. In other words, the value of the comparison key should be the <i>only</i>
     * necessary data that need to be extracted from each element returned by the child cursors to
     * perform the union. Additionally, the provided comparison key function should not have
     * any side-effects and should produce the same output every time it is applied to the same input.
     *
     * <p>
     * The cursors are provided as a list of functions rather than a list of {@link RecordCursor}s.
     * These functions should create a new <code>RecordCursor</code> instance with a given
     * continuation appropriate for that cursor type. The value of that continuation will be determined
     * by this function from the <code>continuation</code> parameter for the union
     * cursor as a whole.
     * </p>
     *
     * @param comparisonKeyFunction the function evaluated to compare elements from different cursors
     * @param reverse whether records are returned in descending or ascending order by the comparison key
     * @param cursorFunctions a list of functions to produce {@link RecordCursor}s from a continuation
     * @param continuation any continuation from a previous scan
     * @param timer the timer used to instrument events
     * @param <T> the type of elements returned by this cursor
     * @return a cursor containing any records in any child cursors
     */
    @Nonnull
    public static <T> UnionCursor<T> create(
            @Nonnull Function<? super T, ? extends List<Object>> comparisonKeyFunction,
            boolean reverse,
            @Nonnull List<Function<byte[], RecordCursor<T>>> cursorFunctions,
            @Nullable byte[] continuation,
            @Nullable FDBStoreTimer timer) {
        if (cursorFunctions.size() < 2) {
            throw new RecordCoreArgumentException("not enough child cursors provided to UnionCursor")
                    .addLogInfo(LogMessageKeys.CHILD_COUNT, cursorFunctions.size());
        }
        final List<CursorState<T>> cursorStates = createCursorStates(cursorFunctions, continuation);
        return new UnionCursor<>(comparisonKeyFunction, reverse, cursorStates, timer);
    }
}