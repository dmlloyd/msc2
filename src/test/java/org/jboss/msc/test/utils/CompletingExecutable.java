/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
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

package org.jboss.msc.test.utils;

import java.util.concurrent.CountDownLatch;

import org.jboss.msc.txn.Executable;
import org.jboss.msc.txn.ExecuteContext;

/**
 * Executable that always calls complete.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public final class CompletingExecutable<T> extends Latchable implements Executable<T> {
    
    private final T result;
    
    public CompletingExecutable() {
        this(null, null);
    }
    
    public CompletingExecutable(final T result) {
        this(result, null);
    }
    
    public CompletingExecutable(final CountDownLatch signal) {
        this(null, signal);
    }
    
    public CompletingExecutable(final T result, final CountDownLatch signal) {
        super(signal);
        this.result = result;
    }

    @Override
    public void execute(final ExecuteContext<T> ctx) {
        ctx.complete(result);
    }
}
