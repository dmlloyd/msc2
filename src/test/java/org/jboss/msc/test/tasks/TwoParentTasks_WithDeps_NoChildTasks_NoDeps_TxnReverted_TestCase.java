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

package org.jboss.msc.test.tasks;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;

import org.jboss.msc.test.utils.AbstractTransactionTest;
import org.jboss.msc.test.utils.TestCommittable;
import org.jboss.msc.test.utils.TestExecutable;
import org.jboss.msc.test.utils.TestRevertible;
import org.jboss.msc.test.utils.TestValidatable;
import org.jboss.msc.txn.BasicTransaction;
import org.jboss.msc.txn.CompletionListener;
import org.jboss.msc.txn.RollbackResult;
import org.jboss.msc.txn.TaskController;
import org.junit.Test;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public final class TwoParentTasks_WithDeps_NoChildTasks_NoDeps_TxnReverted_TestCase extends AbstractTransactionTest {

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 completes at EXECUTE, depends on task0</LI>
     * <LI>no children</LI>
     * <LI>transaction prepared</LI>
     * <LI>transaction aborted</LI>
     * </UL>
     */
    @Test
    public void usecase1() throws Exception {
        final BasicTransaction transaction = newTransaction();
        // installing task0
        final TestExecutable<Void> e0 = new TestExecutable<Void>();
        final TestValidatable v0 = new TestValidatable();
        final TestRevertible r0 = new TestRevertible();
        final TestCommittable c0 = new TestCommittable();
        final TaskController<Void> task0Controller = newTask(transaction, e0, v0, r0, c0);
        assertNotNull(task0Controller);
        // installing task1
        final TestExecutable<Void> e1 = new TestExecutable<Void>();
        final TestValidatable v1 = new TestValidatable();
        final TestRevertible r1 = new TestRevertible();
        final TestCommittable c1 = new TestCommittable();
        final TaskController<Void> task1Controller = newTask(transaction, e1, v1, r1, c1, task0Controller);
        assertNotNull(task1Controller);
        // preparing transaction
        prepare(transaction);
        assertCalled(e0);
        assertCalled(v0);
        assertNotCalled(r0);
        assertNotCalled(c0);
        assertCalled(e1);
        assertCalled(v1);
        assertNotCalled(r1);
        assertNotCalled(c1);
        assertCallOrder(e0, e1, v0);
        assertCallOrder(e0, e1, v1);
        // reverting transaction
        assertTrue(canCommit(transaction));
        abort(transaction);
        assertCalled(e0);
        assertCalled(v0);
        assertCalled(r0);
        assertNotCalled(c0);
        assertCalled(e1);
        assertCalled(v1);
        assertCalled(r1);
        assertNotCalled(c1);
        assertCallOrder(e0, e1, v0, r1, r0);
        assertCallOrder(e0, e1, v1, r1, r0);
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 cancels at EXECUTE, depends on task0</LI>
     * <LI>no children</LI>
     * <LI>transaction rolled back</LI>
     * </UL>
     */
    @Test
    public void usecase2() throws Exception {
        final BasicTransaction transaction = newTransaction();
        final CountDownLatch signal = new CountDownLatch(1);
        // installing task0
        final TestExecutable<Void> e0 = new TestExecutable<Void>(signal);
        final TestValidatable v0 = new TestValidatable();
        final TestRevertible r0 = new TestRevertible();
        final TestCommittable c0 = new TestCommittable();
        final TaskController<Void> task0Controller = newTask(transaction, e0, v0, r0, c0);
        assertNotNull(task0Controller);
        // installing task1
        final TestExecutable<Void> e1 = new TestExecutable<Void>(true, signal);
        final TestValidatable v1 = new TestValidatable();
        final TestRevertible r1 = new TestRevertible();
        final TestCommittable c1 = new TestCommittable();
        final TaskController<Void> task1Controller = newTask(transaction, e1, v1, r1, c1, task0Controller);
        assertNotNull(task1Controller);
        // reverting transaction
        final CompletionListener<RollbackResult<BasicTransaction>> rollbackListener = new CompletionListener<>();
        rollback(transaction, rollbackListener);
        signal.countDown();
        rollbackListener.awaitCompletion();
        assertCalled(e0);
        assertNotCalled(v0);
        assertCalled(r0);
        assertNotCalled(c0);
        assertCalled(e1);
        assertNotCalled(v1);
        assertNotCalled(r1);
        assertNotCalled(c1);
        assertCallOrder(e0, e1, r0);
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 cancels at EXECUTE</LI>
     * <LI>task1 cancels at EXECUTE, depends on task0</LI>
     * <LI>no children</LI>
     * <LI>transaction rolled back</LI>
     * </UL>
     */
    @Test
    public void usecase3() throws Exception {
        final BasicTransaction transaction = newTransaction();
        final CountDownLatch signal = new CountDownLatch(1);
        // installing task0
        final TestExecutable<Void> e0 = new TestExecutable<Void>(true, signal);
        final TestValidatable v0 = new TestValidatable();
        final TestRevertible r0 = new TestRevertible();
        final TestCommittable c0 = new TestCommittable();
        final TaskController<Void> task0Controller = newTask(transaction, e0, v0, r0, c0);
        assertNotNull(task0Controller);
        // installing task1
        final TestExecutable<Void> e1 = new TestExecutable<Void>(true, signal);
        final TestValidatable v1 = new TestValidatable();
        final TestRevertible r1 = new TestRevertible();
        final TestCommittable c1 = new TestCommittable();
        final TaskController<Void> task1Controller = newTask(transaction, e1, v1, r1, c1, task0Controller);
        assertNotNull(task1Controller);
        // reverting transaction
        final CompletionListener<RollbackResult<BasicTransaction>> rollbackListener = new CompletionListener<>();
        rollback(transaction, rollbackListener);
        signal.countDown();
        rollbackListener.awaitCompletion();
        assertCalled(e0);
        assertNotCalled(v0);
        assertNotCalled(r0);
        assertNotCalled(c0);
        // e1.wasCalled() can return either true or false, depends on threads scheduling
        assertNotCalled(v1);
        assertNotCalled(r1);
        assertNotCalled(c1);
        if (e1.wasCalled()) {
            assertCallOrder(e0, e1);
        }
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 cancels at EXECUTE</LI>
     * <LI>task1 completes at EXECUTE, depends on task0</LI>
     * <LI>no children</LI>
     * <LI>transaction rolled back</LI>
     * </UL>
     */
    @Test
    public void usecase4() throws Exception {
        final BasicTransaction transaction = newTransaction();
        final CountDownLatch signal = new CountDownLatch(1);
        // installing task0
        final TestExecutable<Void> e0 = new TestExecutable<Void>(true, signal);
        final TestValidatable v0 = new TestValidatable();
        final TestRevertible r0 = new TestRevertible();
        final TestCommittable c0 = new TestCommittable();
        final TaskController<Void> task0Controller = newTask(transaction, e0, v0, r0, c0);
        assertNotNull(task0Controller);
        // installing task1
        final TestExecutable<Void> e1 = new TestExecutable<Void>(signal);
        final TestValidatable v1 = new TestValidatable();
        final TestRevertible r1 = new TestRevertible();
        final TestCommittable c1 = new TestCommittable();
        final TaskController<Void> task1Controller = newTask(transaction, e1, v1, r1, c1, task0Controller);
        assertNotNull(task1Controller);
        // reverting transaction
        final CompletionListener<RollbackResult<BasicTransaction>> rollbackListener = new CompletionListener<>();
        rollback(transaction, rollbackListener);
        signal.countDown();
        rollbackListener.awaitCompletion();
        assertCalled(e0);
        assertNotCalled(v0);
        assertNotCalled(r0);
        assertNotCalled(c0);
        // e1.wasCalled() can return either true or false, depends on threads scheduling
        assertNotCalled(v1);
        if (e1.wasCalled()) {
            assertCalled(r1);
            assertCallOrder(e0, e1, r1);
        } else {
            assertNotCalled(r1);
        }
        assertNotCalled(c1);
    }
}
