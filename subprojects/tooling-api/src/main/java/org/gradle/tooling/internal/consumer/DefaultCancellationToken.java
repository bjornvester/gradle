/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.tooling.internal.consumer;

import org.gradle.tooling.CancellationToken;
import org.gradle.tooling.internal.protocol.InternalCancellationToken;

import java.util.LinkedList;
import java.util.Queue;

public class DefaultCancellationToken implements CancellationToken, InternalCancellationToken {
    private boolean cancelled;
    private Queue<Runnable> callbacks = new LinkedList<Runnable>();

    public synchronized boolean isCancellationRequested() {
        return cancelled;
    }

    public synchronized boolean addCallback(Runnable cancellationHandler) {
        callbacks.add(cancellationHandler);
        if (cancelled) {
            cancellationHandler.run();
        }
        return cancelled;
    }

    synchronized void doCancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        Runnable runnable = callbacks.poll();
        while (runnable != null) {
            runnable.run();
            runnable = callbacks.poll();
        }
    }
}
