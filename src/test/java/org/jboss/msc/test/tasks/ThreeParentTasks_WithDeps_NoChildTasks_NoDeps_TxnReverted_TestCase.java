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
public final class ThreeParentTasks_WithDeps_NoChildTasks_NoDeps_TxnReverted_TestCase extends AbstractTransactionTest {

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 cancels at EXECUTE</LI>
     * <LI>task2 cancels at EXECUTE, depends on task1</LI>
     * <LI>no children</LI>
     * <LI>transaction rolled back</LI>
     * </UL>
     */
    @Test
    public void usecase1() throws Exception {
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
        final TaskController<Void> task1Controller = newTask(transaction, e1, v1, r1, c1);
        assertNotNull(task1Controller);
        // installing task2
        final TestExecutable<Void> e2 = new TestExecutable<Void>(true, signal);
        final TestValidatable v2 = new TestValidatable();
        final TestRevertible r2 = new TestRevertible();
        final TestCommittable c2 = new TestCommittable();
        final TaskController<Void> task2Controller = newTask(transaction, e2, v2, r2, c2, task1Controller);
        assertNotNull(task2Controller);
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
        // e2.wasCalled() can return either true or false, depends on threads scheduling
        assertNotCalled(v2);
        assertNotCalled(r2);
        assertNotCalled(c2);
        if (e2.wasCalled()) {
            assertCallOrder(e1, e2);
        }
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 cancels at EXECUTE</LI>
     * <LI>task2 completes at EXECUTE, depends on task1</LI>
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
        final TaskController<Void> task1Controller = newTask(transaction, e1, v1, r1, c1);
        assertNotNull(task1Controller);
        // installing task2
        final TestExecutable<Void> e2 = new TestExecutable<Void>(signal);
        final TestValidatable v2 = new TestValidatable();
        final TestRevertible r2 = new TestRevertible();
        final TestCommittable c2 = new TestCommittable();
        final TaskController<Void> task2Controller = newTask(transaction, e2, v2, r2, c2, task1Controller);
        assertNotNull(task2Controller);
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
        // e2.wasCalled() can return either true or false, depends on threads scheduling
        assertNotCalled(v2);
        if (e2.wasCalled()) {
            assertCalled(r2);
            assertCallOrder(e1, e2, r2);
            if (v2.wasCalled()) {
                assertCallOrder(e1, e2, v2, r2);
            }
        } else {
            assertNotCalled(r2);
        }
        assertNotCalled(c2);
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 completes at EXECUTE</LI>
     * <LI>task2 cancels at EXECUTE, depends on task1</LI>
     * <LI>no children</LI>
     * <LI>transaction rolled back</LI>
     * </UL>
     */
    @Test
    public void usecase3() throws Exception {
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
        final TestExecutable<Void> e1 = new TestExecutable<Void>(signal);
        final TestValidatable v1 = new TestValidatable();
        final TestRevertible r1 = new TestRevertible();
        final TestCommittable c1 = new TestCommittable();
        final TaskController<Void> task1Controller = newTask(transaction, e1, v1, r1, c1);
        assertNotNull(task1Controller);
        // installing task2
        final TestExecutable<Void> e2 = new TestExecutable<Void>(true, signal);
        final TestValidatable v2 = new TestValidatable();
        final TestRevertible r2 = new TestRevertible();
        final TestCommittable c2 = new TestCommittable();
        final TaskController<Void> task2Controller = newTask(transaction, e2, v2, r2, c2, task1Controller);
        assertNotNull(task2Controller);
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
        assertCalled(r1);
        assertNotCalled(c1);
        assertCalled(e2);
        assertNotCalled(v2);
        assertNotCalled(r2);
        assertNotCalled(c2);
        assertCallOrder(e1, e2);
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 completes at EXECUTE</LI>
     * <LI>task2 completes at EXECUTE, depends on task1</LI>
     * <LI>no children</LI>
     * <LI>transaction prepared</LI>
     * <LI>transaction aborted</LI>
     * </UL>
     */
    @Test
    public void usecase4() throws Exception {
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
        final TaskController<Void> task1Controller = newTask(transaction, e1, v1, r1, c1);
        assertNotNull(task1Controller);
        // installing task2
        final TestExecutable<Void> e2 = new TestExecutable<Void>();
        final TestValidatable v2 = new TestValidatable();
        final TestRevertible r2 = new TestRevertible();
        final TestCommittable c2 = new TestCommittable();
        final TaskController<Void> task2Controller = newTask(transaction, e2, v2, r2, c2, task1Controller);
        assertNotNull(task2Controller);
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
        assertCalled(e2);
        assertCalled(v2);
        assertNotCalled(r2);
        assertNotCalled(c2);
        assertCallOrder(e1, e2, v1);
        assertCallOrder(e1, e2, v2);
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
        assertCalled(e2);
        assertCalled(v2);
        assertCalled(r2);
        assertNotCalled(c2);
        assertCallOrder(e1, e2, v2, r2, r1);
        assertCallOrder(e1, e2, v1, r2, r1);
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 cancels at EXECUTE</LI>
     * <LI>task2 cancels at EXECUTE, depends on task1 and task0</LI>
     * <LI>no children</LI>
     * <LI>transaction rolled back</LI>
     * </UL>
     */
    @Test
    public void usecase5() throws Exception {
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
        final TaskController<Void> task1Controller = newTask(transaction, e1, v1, r1, c1);
        assertNotNull(task1Controller);
        // installing task2
        final TestExecutable<Void> e2 = new TestExecutable<Void>(true, signal);
        final TestValidatable v2 = new TestValidatable();
        final TestRevertible r2 = new TestRevertible();
        final TestCommittable c2 = new TestCommittable();
        final TaskController<Void> task2Controller = newTask(transaction, e2, v2, r2, c2, task0Controller, task1Controller);
        assertNotNull(task2Controller);
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
        // e2.wasCalled() can return either true or false, depends on threads scheduling
        assertNotCalled(v2);
        assertNotCalled(r2);
        assertNotCalled(c2);
        assertCallOrder(e0, r0);
        if (e2.wasCalled()) {
            assertCallOrder(e0, e2);
            assertCallOrder(e1, e2);
        }
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 cancels at EXECUTE</LI>
     * <LI>task2 completes at EXECUTE, depends on task1 and task0</LI>
     * <LI>no children</LI>
     * <LI>transaction rolled back</LI>
     * </UL>
     */
    @Test
    public void usecase6() throws Exception {
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
        final TaskController<Void> task1Controller = newTask(transaction, e1, v1, r1, c1);
        assertNotNull(task1Controller);
        // installing task2
        final TestExecutable<Void> e2 = new TestExecutable<Void>(signal);
        final TestValidatable v2 = new TestValidatable();
        final TestRevertible r2 = new TestRevertible();
        final TestCommittable c2 = new TestCommittable();
        final TaskController<Void> task2Controller = newTask(transaction, e2, v2, r2, c2, task0Controller, task1Controller);
        assertNotNull(task2Controller);
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
        // e2.wasCalled() can return either true or false, depends on threads scheduling
        assertNotCalled(v2);
        assertCallOrder(e0, r0);
        if (e2.wasCalled()) {
            assertCalled(r2);
            assertCallOrder(e0, e2, r0, r2);
            assertCallOrder(e1, e2, r2);
        } else {
            assertNotCalled(r2);
        }
        assertNotCalled(c2);
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 completes at EXECUTE</LI>
     * <LI>task2 cancels at EXECUTE, depends on task1 and task0</LI>
     * <LI>no children</LI>
     * <LI>transaction rolled back</LI>
     * </UL>
     */
    @Test
    public void usecase7() throws Exception {
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
        final TestExecutable<Void> e1 = new TestExecutable<Void>(signal);
        final TestValidatable v1 = new TestValidatable();
        final TestRevertible r1 = new TestRevertible();
        final TestCommittable c1 = new TestCommittable();
        final TaskController<Void> task1Controller = newTask(transaction, e1, v1, r1, c1);
        assertNotNull(task1Controller);
        // installing task2
        final TestExecutable<Void> e2 = new TestExecutable<Void>(true, signal);
        final TestValidatable v2 = new TestValidatable();
        final TestRevertible r2 = new TestRevertible();
        final TestCommittable c2 = new TestCommittable();
        final TaskController<Void> task2Controller = newTask(transaction, e2, v2, r2, c2, task0Controller, task1Controller);
        assertNotNull(task2Controller);
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
        assertCalled(r1);
        assertNotCalled(c1);
        assertCalled(e2);
        assertNotCalled(v2);
        assertNotCalled(r2);
        assertNotCalled(c2);
        assertCallOrder(e0, e2, r0);
        assertCallOrder(e1, e2, r1);
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 completes at EXECUTE</LI>
     * <LI>task2 completes at EXECUTE, depends on task1 and task0</LI>
     * <LI>no children</LI>
     * <LI>transaction prepared</LI>
     * <LI>transaction aborted</LI>
     * </UL>
     */
    @Test
    public void usecase8() throws Exception {
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
        final TaskController<Void> task1Controller = newTask(transaction, e1, v1, r1, c1);
        assertNotNull(task1Controller);
        // installing task2
        final TestExecutable<Void> e2 = new TestExecutable<Void>();
        final TestValidatable v2 = new TestValidatable();
        final TestRevertible r2 = new TestRevertible();
        final TestCommittable c2 = new TestCommittable();
        final TaskController<Void> task2Controller = newTask(transaction, e2, v2, r2, c2, task0Controller, task1Controller);
        assertNotNull(task2Controller);
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
        assertCalled(e2);
        assertCalled(v2);
        assertNotCalled(r2);
        assertNotCalled(c2);
        assertCallOrder(e0, e2, v0);
        assertCallOrder(e0, e2, v2);
        assertCallOrder(e1, e2, v1);
        assertCallOrder(e1, e2, v2);
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
        assertCalled(e2);
        assertCalled(v2);
        assertCalled(r2);
        assertNotCalled(c2);
        assertCallOrder(e0, e2, v0, r2, r0);
        assertCallOrder(e0, e2, v2, r2, r0);
        assertCallOrder(e1, e2, v1, r2, r1);
        assertCallOrder(e1, e2, v2, r2, r1);
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 cancels at EXECUTE, depends on task0</LI>
     * <LI>task2 cancels at EXECUTE, depends on task1</LI>
     * <LI>no children</LI>
     * <LI>transaction rolled back</LI>
     * </UL>
     */
    @Test
    public void usecase9() throws Exception {
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
        // installing task2
        final TestExecutable<Void> e2 = new TestExecutable<Void>(true, signal);
        final TestValidatable v2 = new TestValidatable();
        final TestRevertible r2 = new TestRevertible();
        final TestCommittable c2 = new TestCommittable();
        final TaskController<Void> task2Controller = newTask(transaction, e2, v2, r2, c2, task1Controller);
        assertNotNull(task2Controller);
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
        // e2.wasCalled() can return either true or false, depends on threads scheduling
        assertNotCalled(v2);
        assertNotCalled(r2);
        assertNotCalled(c2);
        assertCallOrder(e0, e1, r0);
        if (e2.wasCalled()) {
            assertCallOrder(e0, e1, e2, r0);
        }
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 cancels at EXECUTE, depends on task0</LI>
     * <LI>task2 completes at EXECUTE, depends on task1</LI>
     * <LI>no children</LI>
     * <LI>transaction rolled back</LI>
     * </UL>
     */
    @Test
    public void usecase10() throws Exception {
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
        // installing task2
        final TestExecutable<Void> e2 = new TestExecutable<Void>(signal);
        final TestValidatable v2 = new TestValidatable();
        final TestRevertible r2 = new TestRevertible();
        final TestCommittable c2 = new TestCommittable();
        final TaskController<Void> task2Controller = newTask(transaction, e2, v2, r2, c2, task1Controller);
        assertNotNull(task2Controller);
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
        // e2.wasCalled() can return either true or false, depends on threads scheduling
        assertNotCalled(v2);
        assertCallOrder(e0, e1, r0);
        if (e2.wasCalled()) {
            assertCalled(r2);
            assertCallOrder(e0, e1, e2, r2);
        } else {
            assertNotCalled(r2);
        }
        assertNotCalled(c2);
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 completes at EXECUTE, depends on task0</LI>
     * <LI>task2 cancels at EXECUTE, depends on task1</LI>
     * <LI>no children</LI>
     * <LI>transaction rolled back</LI>
     * </UL>
     */
    @Test
    public void usecase11() throws Exception {
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
        final TestExecutable<Void> e1 = new TestExecutable<Void>(signal);
        final TestValidatable v1 = new TestValidatable();
        final TestRevertible r1 = new TestRevertible();
        final TestCommittable c1 = new TestCommittable();
        final TaskController<Void> task1Controller = newTask(transaction, e1, v1, r1, c1, task0Controller);
        assertNotNull(task1Controller);
        // installing task2
        final TestExecutable<Void> e2 = new TestExecutable<Void>(true, signal);
        final TestValidatable v2 = new TestValidatable();
        final TestRevertible r2 = new TestRevertible();
        final TestCommittable c2 = new TestCommittable();
        final TaskController<Void> task2Controller = newTask(transaction, e2, v2, r2, c2, task1Controller);
        assertNotNull(task2Controller);
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
        assertCalled(r1);
        assertNotCalled(c1);
        assertCalled(e2);
        assertNotCalled(v2);
        assertNotCalled(r2);
        assertNotCalled(c2);
        assertCallOrder(e0, e1, e2, r1, r0);
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 completes at EXECUTE, depends on task0</LI>
     * <LI>task2 completes at EXECUTE, depends on task1</LI>
     * <LI>no children</LI>
     * <LI>transaction prepared</LI>
     * <LI>transaction aborted</LI>
     * </UL>
     */
    @Test
    public void usecase12() throws Exception {
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
        // installing task2
        final TestExecutable<Void> e2 = new TestExecutable<Void>();
        final TestValidatable v2 = new TestValidatable();
        final TestRevertible r2 = new TestRevertible();
        final TestCommittable c2 = new TestCommittable();
        final TaskController<Void> task2Controller = newTask(transaction, e2, v2, r2, c2, task1Controller);
        assertNotNull(task2Controller);
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
        assertCalled(e2);
        assertCalled(v2);
        assertNotCalled(r2);
        assertNotCalled(c2);
        assertCallOrder(e0, e1, e2, v0);
        assertCallOrder(e0, e1, e2, v1);
        assertCallOrder(e0, e1, e2, v2);
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
        assertCalled(e2);
        assertCalled(v2);
        assertCalled(r2);
        assertNotCalled(c2);
        assertCallOrder(e0, e1, e2, v0, r2, r1, r0);
        assertCallOrder(e0, e1, e2, v1, r2, r1, r0);
        assertCallOrder(e0, e1, e2, v2, r2, r1, r0);
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 cancels at EXECUTE, depends on task0</LI>
     * <LI>task2 cancels at EXECUTE, depends on task1 and task0</LI>
     * <LI>no children</LI>
     * <LI>transaction rolled back</LI>
     * </UL>
     */
    @Test
    public void usecase13() throws Exception {
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
        // installing task2
        final TestExecutable<Void> e2 = new TestExecutable<Void>(true, signal);
        final TestValidatable v2 = new TestValidatable();
        final TestRevertible r2 = new TestRevertible();
        final TestCommittable c2 = new TestCommittable();
        final TaskController<Void> task2Controller = newTask(transaction, e2, v2, r2, c2, task0Controller, task1Controller);
        assertNotNull(task2Controller);
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
        // e2.wasCalled() can return either true or false, depends on threads scheduling
        assertNotCalled(v2);
        assertNotCalled(r2);
        assertNotCalled(c2);
        assertCallOrder(e0, e1, r0);
        if (e2.wasCalled()) {
            assertCallOrder(e0, e1, e2, r0);
        }
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 cancels at EXECUTE, depends on task0</LI>
     * <LI>task2 completes at EXECUTE, depends on task1 and task0</LI>
     * <LI>no children</LI>
     * <LI>transaction rolled back</LI>
     * </UL>
     */
    @Test
    public void usecase14() throws Exception {
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
        // installing task2
        final TestExecutable<Void> e2 = new TestExecutable<Void>(signal);
        final TestValidatable v2 = new TestValidatable();
        final TestRevertible r2 = new TestRevertible();
        final TestCommittable c2 = new TestCommittable();
        final TaskController<Void> task2Controller = newTask(transaction, e2, v2, r2, c2, task0Controller, task1Controller);
        assertNotNull(task2Controller);
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
        // e2.wasCalled() can return either true or false, depends on threads scheduling
        assertNotCalled(v2);
        assertCallOrder(e0, e1, r0);
        if (e2.wasCalled()) {
            assertCalled(r2);
            assertCallOrder(e0, e1, e2, r2);
        } else {
            assertNotCalled(r2);
        }
        assertNotCalled(c2);
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 completes at EXECUTE, depends on task0</LI>
     * <LI>task2 cancels at EXECUTE, depends on task1 and task0</LI>
     * <LI>no children</LI>
     * <LI>transaction rolled back</LI>
     * </UL>
     */
    @Test
    public void usecase15() throws Exception {
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
        final TestExecutable<Void> e1 = new TestExecutable<Void>(signal);
        final TestValidatable v1 = new TestValidatable();
        final TestRevertible r1 = new TestRevertible();
        final TestCommittable c1 = new TestCommittable();
        final TaskController<Void> task1Controller = newTask(transaction, e1, v1, r1, c1, task0Controller);
        assertNotNull(task1Controller);
        // installing task2
        final TestExecutable<Void> e2 = new TestExecutable<Void>(true, signal);
        final TestValidatable v2 = new TestValidatable();
        final TestRevertible r2 = new TestRevertible();
        final TestCommittable c2 = new TestCommittable();
        final TaskController<Void> task2Controller = newTask(transaction, e2, v2, r2, c2, task0Controller, task1Controller);
        assertNotNull(task2Controller);
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
        assertCalled(r1);
        assertNotCalled(c1);
        assertCalled(e2);
        assertNotCalled(v2);
        assertNotCalled(r2);
        assertNotCalled(c2);
        assertCallOrder(e0, e1, e2, r1, r0);
    }

    /**
     * Scenario:
     * <UL>
     * <LI>task0 completes at EXECUTE</LI>
     * <LI>task1 completes at EXECUTE, depends on task0</LI>
     * <LI>task2 completes at EXECUTE, depends on task1 and task0</LI>
     * <LI>no children</LI>
     * <LI>transaction prepared</LI>
     * <LI>transaction aborted</LI>
     * </UL>
     */
    @Test
    public void usecase16() throws Exception {
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
        // installing task2
        final TestExecutable<Void> e2 = new TestExecutable<Void>();
        final TestValidatable v2 = new TestValidatable();
        final TestRevertible r2 = new TestRevertible();
        final TestCommittable c2 = new TestCommittable();
        final TaskController<Void> task2Controller = newTask(transaction, e2, v2, r2, c2, task0Controller, task1Controller);
        assertNotNull(task2Controller);
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
        assertCalled(e2);
        assertCalled(v2);
        assertNotCalled(r2);
        assertNotCalled(c2);
        assertCallOrder(e0, e1, e2, v0);
        assertCallOrder(e0, e1, e2, v1);
        assertCallOrder(e0, e1, e2, v2);
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
        assertCalled(e2);
        assertCalled(v2);
        assertCalled(r2);
        assertNotCalled(c2);
        assertCallOrder(e0, e1, e2, v0, r2, r1, r0);
        assertCallOrder(e0, e1, e2, v1, r2, r1, r0);
        assertCallOrder(e0, e1, e2, v2, r2, r1, r0);
    }
}
