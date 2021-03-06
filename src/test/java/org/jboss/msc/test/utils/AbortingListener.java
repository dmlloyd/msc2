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

import org.jboss.msc.txn.AbortResult;
import org.jboss.msc.txn.BasicTransaction;
import org.jboss.msc.txn.CompletionListener;
import org.jboss.msc.txn.Listener;
import org.jboss.msc.txn.PrepareResult;
import org.jboss.msc.txn.TransactionController;

/**
 * Listener that aborts the transaction. It provides utility method {@link #awaitAbort()} to wait until transaction have
 * been aborted.
 * 
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public final class AbortingListener implements Listener<PrepareResult<BasicTransaction>> {

    private final TransactionController transactionController;
    private final CompletionListener<AbortResult<BasicTransaction>> listener = new CompletionListener<>();

    public AbortingListener(TransactionController transactionController) {
        this.transactionController = transactionController;
    }

    @Override
    public void handleEvent(final PrepareResult<BasicTransaction> subject) {
        transactionController.abort(subject.getTransaction(), listener);
    }

    public void awaitAbort() throws InterruptedException {
        listener.awaitCompletion();
    }

}
