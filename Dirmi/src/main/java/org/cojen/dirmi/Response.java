/*
 *  Copyright 2008 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi;

import java.util.Queue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Generates responses for {@link Asynchronous} or {@link Batched} methods
 * which return a {@link Completion} or {@link Future}.
 *
 * @author Brian S O'Neill
 */
public class Response {
    private Response() {
    }

    /**
     * Returns a {@code Completion} which always returns the given value.
     */
    public static <V> Completion<V> complete(final V value) {
        return new AbstractCompletion<V>() {
            public V get() {
                return value;
            }
        };
    }

    /**
     * Returns a {@code Completion} which always throws an {@link
     * ExecutionException} wrapping the given cause.
     */
    public static <V> Completion<V> exception(final Throwable cause) {
        return new AbstractCompletion<V>() {
            public V get() throws ExecutionException {
                throw new ExecutionException(cause);
            }
        };
    }

    private static abstract class AbstractCompletion<V> implements Completion<V> {
        public void register(Queue<? super Completion<V>> queue) {
            // FIXME
        }

        public boolean cancel(boolean mayInterrupt) {
            return false;
        }

        public boolean isCancelled() {
            return false;
        }

        public boolean isDone() {
            return true;
        }

        public V get(long timeout, TimeUnit init) throws ExecutionException, InterruptedException {
            return get();
        }
    }
}
