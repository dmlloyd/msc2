/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
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

package org.jboss.msc.txn;

import static java.lang.Thread.holdsLock;

import java.util.ArrayList;

import org.jboss.msc._private.MSCLogger;

/**
 * A controller for an installed subtask.
 *
 * The following defines the state machine for this class.
 * <pre>
 *  +---------------+
 *  |               |
 *  |      NEW      |
 *  |               |
 *  +-------+-------+
 *          |
 *          |
 *          v
 *  +---------------+
 *  |               |   cancel_req
 *  |  EXECUTE_WAIT +-------------------------------------+
 *  |               |                                     |
 *  +-------+-------+                                     |
 *          |                                             |
 *          |                                             |
 *          v                                             |
 *  +---------------+                                     |
 *  |               |   cancel_req                        |
 *  |    EXECUTE    +----------------------------+        |
 *  |               |                            |        |
 *  +-------+-------+                            |        |
 *          |                                    |        |
 *          |                                    |        |
 *          v                                    |        |
 *  +---------------+                            |        |
 *  |               |   rb_req                   |        |
 *  |  EXECUTE_DONE +---------------------+      |        |
 *  |               |                     |      |        |
 *  +-------+-------+                     |      |        |
 *          |                             |      |        |
 *          |                             |      |        |
 *          v                             |      |        |
 *  +---------------+                     |      |        |
 *  |               |   rb_req            |      |        |
 *  |    VALIDATE   +-----------------+   |      |        |
 *  |               |                 |   |      |        |
 *  +-------+-------+                 |   |      |        |
 *          |                         |   |      |        |
 *          |                         |   |      |        |
 *          v                         |   |      |        |
 *  +------------------------+        |   |      |        |
 *  |                        | rb_req |   |      |        |
 *  | VALIDATE_CHILDREN_WAIT +----+   |   |      |        |
 *  |                        |    |   |   |      |        |
 *  +-------+----------------+    |   |   |      |        |
 *          |                     |   |   |      |        |
 *          |                     |   |   |      |        |
 *          v                     |   |   |      |        |
 *  +---------------+             |   |   |      |        |
 *  |               |   rb_req    |   |   |      |        |
 *  | VALIDATE_DONE +---------+   |   |   |      |        |
 *  |               |         |   |   |   |      |        |
 *  +-------+-------+         |   |   |   |      |        |
 *          |                 |   |   |   |      |        |
 *          |                 |   |   |   |      |        |
 *          v                 v   v   v   v      |        |
 *  +---------------+     +---------------+      |        |
 *  |               |     |               |      |        |
 *  |  COMMIT_WAIT  |     | ROLLBACK_WAIT |      |        |
 *  |               |     |               |      |        |
 *  +-------+-------+     +-------+-------+      |        |
 *          |                     |              |        |
 *          |                     |              |        |
 *          v                     v              |        |
 *  +---------------+     +---------------+      |        |
 *  |               |     |               |      |        |
 *  |     COMMIT    |     |    ROLLBACK   |      |        |
 *  |               |     |               |      |        |
 *  +-------+-------+     +-------+-------+      |        |
 *          |                     |              |        |
 *          |                     |              |        |
 *          v                     v              v        v
 *  +------------------------------------------------------------+
 *  |                                                            |
 *  |                       TERMINATE_WAIT                       |
 *  |                                                            |
 *  +-----------------------------+------------------------------+
 *                                |
 *                                |
 *                                v
 *  +------------------------------------------------------------+
 *  |                                                            |
 *  |                         TERMINATED                         |
 *  |                                                            |
 *  +------------------------------------------------------------+
 * </pre>
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class TaskControllerImpl<T> implements TaskController<T>, TaskParent, TaskChild {

    private static final Object NO_RESULT = new Object();

    private static final ThreadLocal<ClassLoader> CL_HOLDER = new ThreadLocal<ClassLoader>();

    private final TaskParent parent;
    private final TaskControllerImpl<?>[] dependencies;
    private final Executable<T> executable;
    private final Revertible revertible;
    private final Validatable validatable;
    private final Committable committable;
    private final ClassLoader classLoader;
    private final ArrayList<TaskControllerImpl<?>> dependents = new ArrayList<TaskControllerImpl<?>>();
    private final ArrayList<TaskChild> children = new ArrayList<TaskChild>();

    private int state;
    private int unfinishedDependencies;
    private int unfinishedChildren;
    private int unvalidatedChildren;
    private int uncommittedDependencies;
    private int unterminatedChildren;
    private int unterminatedDependents;

    @SuppressWarnings("unchecked")
    private volatile T result = (T) NO_RESULT;

    private TaskControllerImpl<?>[] cachedDependents;

    private static final int STATE_MASK        = 0xF;

    private static final int STATE_NEW                    = 0;
    private static final int STATE_EXECUTE_WAIT           = 1;
    private static final int STATE_EXECUTE                = 2;
    private static final int STATE_EXECUTE_CHILDREN_WAIT  = 3;
    private static final int STATE_EXECUTE_DONE           = 4;
    private static final int STATE_VALIDATE               = 5;
    private static final int STATE_VALIDATE_CHILDREN_WAIT = 6;
    private static final int STATE_VALIDATE_DONE          = 7;
    private static final int STATE_COMMIT_WAIT            = 8;
    private static final int STATE_COMMIT                 = 9;
    private static final int STATE_ROLLBACK_WAIT          = 10;
    private static final int STATE_ROLLBACK               = 11;
    private static final int STATE_TERMINATE_WAIT         = 12;
    private static final int STATE_TERMINATED             = 13;
    private static final int STATE_LAST = STATE_TERMINATED;

    private static final int T_NONE = 0;

    private static final int T_NEW_to_EXECUTE_WAIT = 1;
    private static final int T_NEW_to_TERMINATED = 2;

    private static final int T_EXECUTE_WAIT_to_TERMINATE_WAIT = 3;
    private static final int T_EXECUTE_WAIT_to_EXECUTE = 4;

    private static final int T_EXECUTE_to_TERMINATE_WAIT = 5;
    private static final int T_EXECUTE_to_EXECUTE_CHILDREN_WAIT = 6;

    private static final int T_EXECUTE_CHILDREN_WAIT_to_EXECUTE_DONE = 7;

    private static final int T_EXECUTE_DONE_to_ROLLBACK_WAIT = 8;
    private static final int T_EXECUTE_DONE_to_VALIDATE = 9;

    private static final int T_VALIDATE_to_VALIDATE_CHILDREN_WAIT = 10;
    private static final int T_VALIDATE_to_ROLLBACK_WAIT = 11;

    private static final int T_VALIDATE_CHILDREN_WAIT_to_ROLLBACK_WAIT = 12;
    private static final int T_VALIDATE_CHILDREN_WAIT_to_VALIDATE_DONE = 13;

    private static final int T_VALIDATE_DONE_to_ROLLBACK_WAIT = 14;
    private static final int T_VALIDATE_DONE_to_COMMIT_WAIT = 15;

    private static final int T_COMMIT_WAIT_to_COMMIT = 16;

    private static final int T_COMMIT_to_TERMINATE_WAIT = 17;

    private static final int T_ROLLBACK_WAIT_to_ROLLBACK = 18;

    private static final int T_ROLLBACK_to_TERMINATE_WAIT = 19;

    private static final int T_TERMINATE_WAIT_to_TERMINATED = 20;

    /**
     * A cancel request, due to rollback or dependency failure.
     */
    private static final int FLAG_CANCEL_REQ    = 1 << 4;
    private static final int FLAG_ROLLBACK_REQ  = 1 << 5;
    private static final int FLAG_VALIDATE_REQ  = 1 << 6;
    private static final int FLAG_COMMIT_REQ    = 1 << 7;

    private static final int PERSISTENT_STATE = STATE_MASK | FLAG_CANCEL_REQ | FLAG_ROLLBACK_REQ | FLAG_VALIDATE_REQ | FLAG_COMMIT_REQ;

    // non-persistent status flags
    private static final int FLAG_EXECUTE_DONE          = 1 << 8;
    private static final int FLAG_VALIDATE_DONE         = 1 << 9;
    private static final int FLAG_COMMIT_DONE           = 1 << 10;
    private static final int FLAG_ROLLBACK_DONE         = 1 << 11;
    private static final int FLAG_INSTALL_FAILED        = 1 << 12;

    // non-persistent job flags
    private static final int FLAG_SEND_CHILD_DONE           = 1 << 13; // to parents
    private static final int FLAG_SEND_DEPENDENCY_DONE      = 1 << 14; // to dependents
    private static final int FLAG_SEND_VALIDATE_REQ         = 1 << 15; // to children
    private static final int FLAG_SEND_CHILD_VALIDATE_DONE  = 1 << 17; // to parents
    private static final int FLAG_SEND_COMMIT_DONE          = 1 << 18; // to dependents
    private static final int FLAG_SEND_CHILD_TERMINATED     = 1 << 19; // to parents
    private static final int FLAG_SEND_TERMINATED           = 1 << 20; // to dependencies
    private static final int FLAG_SEND_CANCEL_REQ           = 1 << 21; // to children
    private static final int FLAG_SEND_ROLLBACK_REQ         = 1 << 22; // to children
    private static final int FLAG_SEND_COMMIT_REQ           = 1 << 23; // to children
    private static final int FLAG_SEND_CANCEL_DEPENDENTS    = 1 << 24; // to dependents

    private static final int SEND_FLAGS = Bits.intBitMask(13, 24);

    private static final int FLAG_DO_EXECUTE        = 1 << 25;
    private static final int FLAG_DO_VALIDATE       = 1 << 26;
    private static final int FLAG_DO_COMMIT         = 1 << 27;
    private static final int FLAG_DO_ROLLBACK       = 1 << 28;

    private static final int DO_FLAGS = Bits.intBitMask(25, 28);

    @SuppressWarnings("unused")
    private static final int TASK_FLAGS = DO_FLAGS | SEND_FLAGS;

    private static final int FLAG_USER_THREAD       = 1 << 31; // called from user thread; do not block

    TaskControllerImpl(final TaskParent parent, final TaskControllerImpl<?>[] dependencies, final Executable<T> executable, final Revertible revertible, final Validatable validatable, final Committable committable, final ClassLoader classLoader) {
        this.parent = parent;
        this.dependencies = dependencies;
        this.executable = executable;
        this.revertible = revertible;
        this.validatable = validatable;
        this.committable = committable;
        this.classLoader = classLoader;
        state = STATE_NEW;
    }

    public Transaction getTransaction() {
        return parent.getTransaction();
    }

    public T getResult() throws IllegalStateException {
        final T result = this.result;
        if (result == NO_RESULT) {
            throw new IllegalStateException("No result is available");
        }
        return result;
    }

    // ===================================================
    //   Private impl
    // ===================================================

    /**
     * Calculate the transition to take from the current state.
     *
     * @param state the current state
     * @return the transition to take
     */
    private int getTransition(int state) {
        assert holdsLock(this);
        int sid = stateOf(state);
        switch (sid) {
            case STATE_NEW: {
                if (Bits.allAreSet(state, FLAG_INSTALL_FAILED)) {
                    return T_NEW_to_TERMINATED;
                } else {
                    return T_NEW_to_EXECUTE_WAIT;
                }
            }
            case STATE_EXECUTE_WAIT: {
                if (Bits.allAreSet(state, FLAG_CANCEL_REQ)) {
                    return T_EXECUTE_WAIT_to_TERMINATE_WAIT;
                } else if (unfinishedDependencies == 0) {
                    return T_EXECUTE_WAIT_to_EXECUTE;
                } else {
                    return T_NONE;
                }
            }
            case STATE_EXECUTE: {
                if (Bits.allAreSet(state, FLAG_EXECUTE_DONE)) {
                    return T_EXECUTE_to_EXECUTE_CHILDREN_WAIT;
                } else if (Bits.allAreSet(state, FLAG_CANCEL_REQ)) {
                    return T_EXECUTE_to_TERMINATE_WAIT;
                } else {
                    return T_NONE;
                }
            }
            case STATE_EXECUTE_CHILDREN_WAIT: {
                if (unfinishedChildren == 0) {
                    return T_EXECUTE_CHILDREN_WAIT_to_EXECUTE_DONE;
                } else {
                    return T_NONE;
                }
            }
            case STATE_EXECUTE_DONE: {
                if (Bits.allAreSet(state, FLAG_ROLLBACK_REQ)) {
                    return T_EXECUTE_DONE_to_ROLLBACK_WAIT;
                } else if (Bits.allAreSet(state, FLAG_VALIDATE_REQ)) {
                    return T_EXECUTE_DONE_to_VALIDATE;
                } else {
                    return T_NONE;
                }
            }
            case STATE_VALIDATE: {
                if (Bits.allAreSet(state, FLAG_ROLLBACK_REQ)) {
                    return T_VALIDATE_to_ROLLBACK_WAIT;
                } else if (Bits.allAreSet(state, FLAG_VALIDATE_DONE)) {
                    return T_VALIDATE_to_VALIDATE_CHILDREN_WAIT;
                } else {
                    return T_NONE;
                }
            }
            case STATE_VALIDATE_CHILDREN_WAIT: {
                if (Bits.allAreSet(state, FLAG_ROLLBACK_REQ)) {
                    return T_VALIDATE_CHILDREN_WAIT_to_ROLLBACK_WAIT;
                } else if (unvalidatedChildren == 0) {
                    return T_VALIDATE_CHILDREN_WAIT_to_VALIDATE_DONE;
                } else {
                    return T_NONE;
                }
            }
            case STATE_VALIDATE_DONE: {
                if (Bits.allAreSet(state, FLAG_COMMIT_REQ)) {
                    return T_VALIDATE_DONE_to_COMMIT_WAIT;
                } else if (Bits.allAreSet(state, FLAG_ROLLBACK_REQ)) {
                    return T_VALIDATE_DONE_to_ROLLBACK_WAIT;
                } else {
                    return T_NONE;
                }
            }
            case STATE_COMMIT_WAIT: {
                if (uncommittedDependencies == 0) {
                    return T_COMMIT_WAIT_to_COMMIT;
                } else {
                    return T_NONE;
                }
            }
            case STATE_COMMIT: {
                if (Bits.allAreSet(state, FLAG_COMMIT_DONE)) {
                    return T_COMMIT_to_TERMINATE_WAIT;
                } else {
                    return T_NONE;
                }
            }
            case STATE_ROLLBACK_WAIT: {
                if (unterminatedDependents == 0 && unterminatedChildren == 0) {
                    return T_ROLLBACK_WAIT_to_ROLLBACK;
                } else {
                    return T_NONE;
                }
            }
            case STATE_ROLLBACK: {
                if (Bits.allAreSet(state, FLAG_ROLLBACK_DONE)) {
                    return T_ROLLBACK_to_TERMINATE_WAIT;
                } else {
                    return T_NONE;
                }
            }
            case STATE_TERMINATE_WAIT: {
                if (unterminatedChildren == 0) {
                    return T_TERMINATE_WAIT_to_TERMINATED;
                } else {
                    return T_NONE;
                }
            }
            case STATE_TERMINATED: {
                return T_NONE;
            }
            default: throw new IllegalStateException();
        }
    }

    /**
     * Perform any necessary/possible transition.
     *
     * @param state the current state
     * @return the new state
     */
    private int transition(int state) {
        assert holdsLock(this);
        for (;;) {
            int t = getTransition(state);
            switch (t) {
                case T_NONE: return state;
                case T_NEW_to_EXECUTE_WAIT: {
                    state = newState(STATE_EXECUTE_WAIT, state);
                    continue;
                }
                case T_EXECUTE_WAIT_to_EXECUTE: {
                    if (executable == null) {
                        state = newState(STATE_EXECUTE, state | FLAG_EXECUTE_DONE);
                        continue;
                    }
                    // not possible to go any farther
                    return newState(STATE_EXECUTE, state | FLAG_DO_EXECUTE);
                }
                case T_EXECUTE_to_EXECUTE_CHILDREN_WAIT: {
                    if (! dependents.isEmpty()) {
                        cachedDependents = dependents.toArray(new TaskControllerImpl[dependents.size()]);
                        state = newState(STATE_EXECUTE_CHILDREN_WAIT, state | FLAG_SEND_DEPENDENCY_DONE);
                    } else {
                        state = newState(STATE_EXECUTE_CHILDREN_WAIT, state);
                    }
                    continue;
                }
                case T_EXECUTE_CHILDREN_WAIT_to_EXECUTE_DONE: {
                    state = newState(STATE_EXECUTE_DONE, state | FLAG_SEND_CHILD_DONE);
                    continue;
                }
                case T_VALIDATE_to_VALIDATE_CHILDREN_WAIT: {
                    state = newState(STATE_VALIDATE_CHILDREN_WAIT, state | FLAG_SEND_VALIDATE_REQ);
                    continue;
                }
                case T_EXECUTE_DONE_to_VALIDATE: {
                    if (validatable == null) {
                        state = newState(STATE_VALIDATE, state | FLAG_VALIDATE_DONE);
                        continue;
                    }
                    // not possible to go any farther
                    return newState(STATE_VALIDATE, state | FLAG_DO_VALIDATE);
                }
                case T_VALIDATE_CHILDREN_WAIT_to_VALIDATE_DONE: {
                    state = newState(STATE_VALIDATE_DONE, state | FLAG_SEND_CHILD_VALIDATE_DONE);
                    continue;
                }
                case T_VALIDATE_DONE_to_COMMIT_WAIT: {
                    state = newState(STATE_COMMIT_WAIT, state);
                    continue;
                }
                case T_COMMIT_WAIT_to_COMMIT: {
                    if (committable == null) {
                        state = newState(STATE_COMMIT, state | FLAG_COMMIT_DONE);
                        continue;
                    }
                    // not possible to go any farther
                    return newState(STATE_COMMIT, state | FLAG_DO_COMMIT);
                }
                case T_COMMIT_to_TERMINATE_WAIT: {
                    state = newState(STATE_TERMINATE_WAIT, state | FLAG_SEND_COMMIT_DONE | FLAG_SEND_COMMIT_REQ);
                    continue;
                }
                case T_TERMINATE_WAIT_to_TERMINATED: {
                    state = newState(STATE_TERMINATED, state | FLAG_SEND_CHILD_TERMINATED | FLAG_SEND_TERMINATED);
                    continue;
                }

                // exceptional cases

                case T_NEW_to_TERMINATED: {
                    // not possible to go any farther
                    return newState(STATE_TERMINATED, state);
                }
                case T_EXECUTE_WAIT_to_TERMINATE_WAIT: {
                    if (! dependents.isEmpty()) {
                        cachedDependents = dependents.toArray(new TaskControllerImpl[dependents.size()]);
                        state = newState(STATE_TERMINATE_WAIT, state | FLAG_SEND_CANCEL_DEPENDENTS);
                    } else {
                        state = newState(STATE_TERMINATE_WAIT, state);
                    }
                    continue;
                }
                case T_EXECUTE_to_TERMINATE_WAIT: {
                    if (! dependents.isEmpty()) {
                        cachedDependents = dependents.toArray(new TaskControllerImpl[dependents.size()]);
                        state = newState(STATE_TERMINATE_WAIT, state | FLAG_SEND_CANCEL_DEPENDENTS | FLAG_SEND_CANCEL_REQ);
                    } else {
                        state = newState(STATE_TERMINATE_WAIT, state | FLAG_SEND_CANCEL_REQ);
                    }
                    continue;
                }
                case T_EXECUTE_DONE_to_ROLLBACK_WAIT: {
                    state = newState(STATE_ROLLBACK_WAIT, state | FLAG_SEND_ROLLBACK_REQ);
                    continue;
                }
                case T_VALIDATE_CHILDREN_WAIT_to_ROLLBACK_WAIT: {
                    state = newState(STATE_ROLLBACK_WAIT, state | FLAG_SEND_ROLLBACK_REQ);
                    continue;
                }
                case T_VALIDATE_to_ROLLBACK_WAIT: {
                    state = newState(STATE_ROLLBACK_WAIT, state | FLAG_SEND_ROLLBACK_REQ);
                    continue;
                }
                case T_VALIDATE_DONE_to_ROLLBACK_WAIT: {
                    state = newState(STATE_ROLLBACK_WAIT, state | FLAG_SEND_ROLLBACK_REQ);
                    continue;
                }
                case T_ROLLBACK_WAIT_to_ROLLBACK: {
                    if (revertible == null) {
                        state = newState(STATE_ROLLBACK, state | FLAG_ROLLBACK_DONE);
                        continue;
                    }
                    // not possible to go any farther
                    return newState(STATE_ROLLBACK, state | FLAG_DO_ROLLBACK);
                }
                case T_ROLLBACK_to_TERMINATE_WAIT: {
                    state = newState(STATE_TERMINATE_WAIT, state);
                    continue;
                }
                default: throw new IllegalStateException();
            }
        }
    }

    private void executeTasks(final int state) {
        final boolean userThread = Bits.allAreSet(state, FLAG_USER_THREAD);
        if (Bits.allAreSet(state, FLAG_SEND_DEPENDENCY_DONE)) {
            for (TaskControllerImpl<?> dependent : cachedDependents) {
                dependent.dependencyExecutionComplete(userThread);
            }
            cachedDependents = null;
        }
        if (Bits.allAreSet(state, FLAG_SEND_CHILD_DONE)) {
            parent.childExecutionFinished(userThread);
        }
        if (Bits.allAreSet(state, FLAG_SEND_VALIDATE_REQ)) {
            for (TaskChild child : children) {
                child.childInitiateValidate(userThread);
            }
        }
        if (Bits.allAreSet(state, FLAG_SEND_CANCEL_REQ)) {
            for (TaskChild child : children) {
                child.forceCancel(userThread);
            }
        }
        if (Bits.allAreSet(state, FLAG_SEND_ROLLBACK_REQ)) {
            for (TaskChild child : children) {
                child.childInitiateRollback(userThread);
            }
        }
        if (Bits.allAreSet(state, FLAG_SEND_COMMIT_REQ)) {
            for (TaskChild child : children) {
                child.childInitiateCommit(userThread);
            }
        }
        if (Bits.allAreSet(state, FLAG_SEND_CHILD_VALIDATE_DONE)) {
            parent.childValidationFinished(userThread);
        }
        if (Bits.allAreSet(state, FLAG_SEND_COMMIT_DONE)) {
            for (TaskControllerImpl<?> dependent : dependents) {
                dependent.dependencyCommitComplete(userThread);
            }
        }
        if (Bits.allAreSet(state, FLAG_SEND_CHILD_TERMINATED)) {
            parent.childTerminated(userThread);
        }
        if (Bits.allAreSet(state, FLAG_SEND_TERMINATED)) {
            for (TaskControllerImpl<?> dependency : dependencies) {
                dependency.dependentTerminated(userThread);
            }
        }
        if (Bits.allAreSet(state, FLAG_SEND_CANCEL_DEPENDENTS)) {
            for (TaskControllerImpl<?> dependent : cachedDependents) {
                dependent.forceCancel(userThread);
            }
            cachedDependents = null;
        }

        assert Bits.allAreClear(state, DO_FLAGS) || Bits.oneIsSet(state, DO_FLAGS);

        if (userThread) {
            if (Bits.allAreSet(state, FLAG_DO_EXECUTE)) {
                safeExecute(new AsyncTask(FLAG_DO_EXECUTE));
            }
            if (Bits.allAreSet(state, FLAG_DO_VALIDATE)) {
                safeExecute(new AsyncTask(FLAG_DO_VALIDATE));
            }
            if (Bits.allAreSet(state, FLAG_DO_ROLLBACK)) {
                safeExecute(new AsyncTask(FLAG_DO_ROLLBACK));
            }
            if (Bits.allAreSet(state, FLAG_DO_COMMIT)) {
                safeExecute(new AsyncTask(FLAG_DO_COMMIT));
            }
        } else {
            if (Bits.allAreSet(state, FLAG_DO_EXECUTE)) {
                execute();
            }
            if (Bits.allAreSet(state, FLAG_DO_VALIDATE)) {
                validate();
            }
            if (Bits.allAreSet(state, FLAG_DO_ROLLBACK)) {
                rollback();
            }
            if (Bits.allAreSet(state, FLAG_DO_COMMIT)) {
                commit();
            }
        }
    }

    private void safeExecute(final Runnable command) {
        try {
            getTransaction().getExecutor().execute(command);
        } catch (Throwable t) {
            MSCLogger.ROOT.runnableExecuteFailed(t, command);
        }
    }

    public void forceCancel(final boolean userThread) {
        assert ! holdsLock(this);
        int state;
        synchronized (this) {
            state = this.state;
            if (userThread) state |= FLAG_USER_THREAD;
            if (stateIsIn(state, STATE_NEW, STATE_EXECUTE_WAIT)) {
                state |= FLAG_CANCEL_REQ;
            } else if (stateIsIn(state, STATE_EXECUTE, STATE_EXECUTE_DONE)) {
                state |= FLAG_ROLLBACK_REQ;
            }
            state = transition(state);
            this.state = state & PERSISTENT_STATE;
        }
        executeTasks(state);
    }

    private void dependentTerminated(final boolean userThread) {
        assert ! holdsLock(this);
        int state;
        synchronized (this) {
            unterminatedDependents--;
            state = this.state;
            if (userThread) state |= FLAG_USER_THREAD;
            state = transition(state);
            this.state = state & PERSISTENT_STATE;
        }
        executeTasks(state);
    }

    private static int newState(int sid, int state) {
        assert sid >= 0 && sid <= STATE_LAST;
        return sid & STATE_MASK | state & ~STATE_MASK;
    }

    private static int stateOf(int oldVal) {
        return oldVal & STATE_MASK;
    }

    private static boolean stateIsIn(int state, int sid1) {
        final int sid = stateOf(state);
        return sid == sid1;
    }

    private static boolean stateIsIn(int state, int sid1, int sid2) {
        final int sid = stateOf(state);
        return sid == sid1 || sid == sid2;
    }

    private static boolean stateIsIn(int state, int sid1, int sid2, int sid3, int sid4, int sid5) {
        final int sid = stateOf(state);
        return sid == sid1 || sid == sid2 || sid == sid3 || sid == sid4 || sid == sid5;
    }

    private void execComplete(final T result) {
        assert ! holdsLock(this);
        int state;
        synchronized (this) {
            state = this.state | FLAG_USER_THREAD | FLAG_EXECUTE_DONE;
            if (stateOf(state) != STATE_EXECUTE) {
                throw new IllegalStateException("Task may not be completed now");
            }
            this.result = result;
            state = transition(state);
            this.state = state & PERSISTENT_STATE;
        }
        executeTasks(state);
    }

    private void execCancelled() {
        assert ! holdsLock(this);
        int state;
        final boolean canCancel = getTransaction().isRollbackRequested();
        synchronized (this) {
            state = this.state | FLAG_USER_THREAD | FLAG_CANCEL_REQ;
            if (!canCancel || stateOf(state) != STATE_EXECUTE) {
                throw new IllegalStateException("Task may not be cancelled now");
            }
            state = transition(state);
            this.state = state & PERSISTENT_STATE;
        }
        executeTasks(state);
    }

    private void rollbackComplete() {
        assert ! holdsLock(this);
        int state;
        synchronized (this) {
            state = this.state | FLAG_USER_THREAD | FLAG_ROLLBACK_DONE;
            if (stateOf(state) != STATE_ROLLBACK) {
                throw new IllegalStateException("Task may not be reverted now");
            }
            state = transition(state);
            this.state = state & PERSISTENT_STATE;
        }
        executeTasks(state);
    }

    void validate() {
        final ProblemReport problemReport = getTransaction().getProblemReport();
        final Validatable validatable = this.validatable;
        if (validatable != null) try {
            setClassLoader();
            validatable.validate(new ValidateContext() {
                public void addProblem(final Problem reason) {
                    problemReport.addProblem(reason);
                }

                public void addProblem(final Problem.Severity severity, final String message) {
                    addProblem(new Problem(TaskControllerImpl.this, message, severity));
                }

                public void addProblem(final Problem.Severity severity, final String message, final Throwable cause) {
                    addProblem(new Problem(TaskControllerImpl.this, message, cause, severity));
                }

                public void addProblem(final String message, final Throwable cause) {
                    addProblem(new Problem(TaskControllerImpl.this, message, cause));
                }

                public void addProblem(final String message) {
                    addProblem(new Problem(TaskControllerImpl.this, message));
                }

                public void addProblem(final Throwable cause) {
                    addProblem(new Problem(TaskControllerImpl.this, cause));
                }

                public void complete() {
                    validateComplete();
                }
            });
        } catch (Throwable t) {
            MSCLogger.TASK.taskValidationFailed(t, validatable);
        } finally {
            unsetClassLoader();
        }
    }

    void validateComplete() {
        assert ! holdsLock(this);
        int state;
        synchronized (this) {
            state = this.state | FLAG_USER_THREAD | FLAG_VALIDATE_DONE;
            if (stateOf(state) != STATE_VALIDATE) {
                throw new IllegalStateException("Task may not be completed now");
            }
            state = transition(state);
            this.state = state & PERSISTENT_STATE;
        }
        executeTasks(state);
    }

    void setClassLoader() {
        if (classLoader != null) {
            final Thread thread = Thread.currentThread();
            CL_HOLDER.set(thread.getContextClassLoader());
            thread.setContextClassLoader(classLoader);
        }
    }

    void unsetClassLoader() {
        if (classLoader != null) {
            final Thread thread = Thread.currentThread();
            final ClassLoader classLoader = CL_HOLDER.get();
            thread.setContextClassLoader(classLoader);
            CL_HOLDER.remove();
        }
    }

    void rollback() {
        final Revertible rev = revertible;
        if (rev != null) try {
            setClassLoader();
            rev.rollback(new RollbackContext() {
                public void complete() {
                    rollbackComplete();
                }
            });
        } catch (Throwable t) {
            MSCLogger.TASK.taskRollbackFailed(t, rev);
        } finally {
            unsetClassLoader();
        }
    }

    void execute() {
        final ProblemReport problemReport = getTransaction().getProblemReport();
        final Executable<T> exec = executable;
        if (exec != null) try {
            setClassLoader();
            final class ExecuteContextImpl implements ExecuteContext<T>, TaskFactory {
                @Override
                public void complete(final T result) {
                    execComplete(result);
                }

                @Override
                public void complete() {
                    complete(null);
                }

                @Override
                public boolean isCancelRequested() {
                    return getTransaction().isRollbackRequested();
                }

                @Override
                public void cancelled() {
                    execCancelled();
                }

                @Override
                public void addProblem(final Problem reason) {
                    problemReport.addProblem(reason);
                }

                @Override
                public void addProblem(final Problem.Severity severity, final String message) {
                    addProblem(new Problem(TaskControllerImpl.this, message, severity));
                }

                @Override
                public void addProblem(final Problem.Severity severity, final String message, final Throwable cause) {
                    addProblem(new Problem(TaskControllerImpl.this, message, cause, severity));
                }

                @Override
                public void addProblem(final String message, final Throwable cause) {
                    addProblem(new Problem(TaskControllerImpl.this, message, cause));
                }

                @Override
                public void addProblem(final String message) {
                    addProblem(new Problem(TaskControllerImpl.this, message));
                }

                @Override
                public void addProblem(final Throwable cause) {
                    addProblem(new Problem(TaskControllerImpl.this, cause));
                }

                @Override
                public <N> TaskBuilder<N> newTask(final Executable<N> task) throws IllegalStateException {
                    return new TaskBuilderImpl<N>(getTransaction(), TaskControllerImpl.this, task);
                }

                @Override
                public TaskBuilder<Void> newTask() throws IllegalStateException {
                    return new TaskBuilderImpl<Void>(getTransaction(), TaskControllerImpl.this);
                }
            }
            exec.execute(new ExecuteContextImpl());
        } catch (Throwable t) {
            MSCLogger.TASK.taskExecutionFailed(t, exec);
            problemReport.addProblem(new Problem(this, t, Problem.Severity.CRITICAL));
        } finally {
            unsetClassLoader();
        }
    }

    void commit() {
        final Committable committable = this.committable;
        if (committable != null) try {
            setClassLoader();
            committable.commit(new CommitContext() {
                public void complete() {
                    commitComplete();
                }
            });
        } catch (Throwable t) {
            MSCLogger.TASK.taskCommitFailed(t, committable);
        } finally {
            unsetClassLoader();
        }
    }

    void commitComplete() {
        assert ! holdsLock(this);
        int state;
        synchronized (this) {
            state = this.state | FLAG_USER_THREAD | FLAG_COMMIT_DONE;
            if (stateOf(state) != STATE_COMMIT) {
                throw new IllegalStateException("Task may not be completed now");
            }
            state = transition(state);
            this.state = state & PERSISTENT_STATE;
        }
        executeTasks(state);
    }

    public void childExecutionFinished(final boolean userThread) {
        assert ! holdsLock(this);
        int state;
        synchronized (this) {
            state = this.state;
            if (userThread) state |= FLAG_USER_THREAD;
            unfinishedChildren--;
            state = transition(state);
            this.state = state & PERSISTENT_STATE;
        }
        executeTasks(state);
    }

    public void childValidationFinished(final boolean userThread) {
        assert ! holdsLock(this);
        int state;
        synchronized (this) {
            state = this.state;
            if (userThread) state |= FLAG_USER_THREAD;
            unvalidatedChildren--;
            state = transition(state);
            this.state = state & PERSISTENT_STATE;
        }
        executeTasks(state);
    }

    public void childTerminated(final boolean userThread) {
        assert ! holdsLock(this);
        int state;
        synchronized (this) {
            state = this.state;
            if (userThread) state |= FLAG_USER_THREAD;
            unfinishedChildren--;
            unvalidatedChildren--;
            unterminatedChildren--;
            state = transition(state);
            this.state = state & PERSISTENT_STATE;
        }
        executeTasks(state);
    }

    public void childAdded(final TaskChild child, final boolean userThread) throws InvalidTransactionStateException {
        assert ! holdsLock(this);
        int state;
        synchronized (this) {
            state = this.state;
            if (stateIsIn(state, STATE_EXECUTE)) {
                children.add(child);
                unfinishedChildren++;
                unvalidatedChildren++;
                unterminatedChildren++;
                if (userThread) state |= FLAG_USER_THREAD;
                state = transition(state);
                this.state = state & PERSISTENT_STATE;
            } else {
                if (userThread) {
                    throw new IllegalStateException("Dependent may not be added at this point");
                } else {
                    // todo log and ignore...
                    return;
                }
            }
        }
        executeTasks(state);
    }

    public void dependencyExecutionComplete(final boolean userThread) {
        assert ! holdsLock(this);
        int state;
        synchronized (this) {
            state = this.state;
            if (userThread) state |= FLAG_USER_THREAD;
            unfinishedDependencies--;
            state = transition(state);
            this.state = state & PERSISTENT_STATE;
        }
        executeTasks(state);
    }

    public void dependencyCommitComplete(final boolean userThread) {
        assert ! holdsLock(this);
        int state;
        synchronized (this) {
            state = this.state;
            if (userThread) state |= FLAG_USER_THREAD;
            uncommittedDependencies--;
            state = transition(state);
            this.state = state & PERSISTENT_STATE;
        }
        executeTasks(state);
    }

    public void childInitiateRollback(final boolean userThread) {
        assert ! holdsLock(this);
        int state;
        synchronized (this) {
            state = this.state;
            if (userThread) state |= FLAG_USER_THREAD;
            state |= FLAG_ROLLBACK_REQ;
            state = transition(state);
            this.state = state & PERSISTENT_STATE;
        }
        executeTasks(state);
    }

    public void childInitiateValidate(final boolean userThread) {
        assert ! holdsLock(this);
        int state;
        synchronized (this) {
            state = this.state | FLAG_VALIDATE_REQ;
            if (userThread) state |= FLAG_USER_THREAD;
            state = transition(state);
            this.state = state & PERSISTENT_STATE;
        }
        executeTasks(state);
    }

    public void childInitiateCommit(final boolean userThread) {
        assert ! holdsLock(this);
        int state;
        synchronized (this) {
            state = this.state | FLAG_COMMIT_REQ;
            if (userThread) state |= FLAG_USER_THREAD;
            state = transition(state);
            this.state = state & PERSISTENT_STATE;
        }
        executeTasks(state);
    }

    void dependentAdded(final TaskControllerImpl<?> dependent, final boolean userThread) {
        assert ! holdsLock(this);
        int state;
        boolean dependencyDone = false;
        boolean dependencyCancelled = false;

        synchronized (this) {
            state = this.state;
            if (userThread) state |= FLAG_USER_THREAD;
            if (stateIsIn(state, STATE_EXECUTE_WAIT, STATE_EXECUTE, STATE_EXECUTE_DONE, STATE_TERMINATE_WAIT, STATE_TERMINATED)) {
                dependents.add(dependent);
                unterminatedDependents++;
                state = transition(state);
                this.state = state & PERSISTENT_STATE;
            } else {
                if (userThread) {
                    throw new IllegalStateException("Dependent may not be added at this point");
                } else {
                    // todo log and ignore...
                    return;
                }
            }
            switch (stateOf(state)) {
                case STATE_TERMINATED:
                case STATE_TERMINATE_WAIT: {
                    if (Bits.anyAreSet(state, FLAG_CANCEL_REQ)) {
                        dependencyCancelled = true;
                        break;
                    }
                }
                case STATE_EXECUTE_DONE: dependencyDone = true;
            }
        }
        if (dependencyDone) {
            dependent.dependencyExecutionComplete(userThread);
        } else if (dependencyCancelled) {
            dependent.forceCancel(userThread);
        }
        executeTasks(state);
    }

    void install() {
        assert ! holdsLock(this);
        int state;
        synchronized (this) {
            uncommittedDependencies = unfinishedDependencies = dependencies.length;
        }
        try {
            parent.childAdded(this, true);
        } catch (IllegalStateException e) {
            synchronized (this) {
                state = this.state | FLAG_USER_THREAD | FLAG_INSTALL_FAILED;
                state = transition(state);
                this.state = state & PERSISTENT_STATE;
            }
            executeTasks(state);
            throw e;
        }
        TaskControllerImpl<?> dependency;
        for (int i = 0; i < dependencies.length; i++) {
            dependency = dependencies[i];
            try {
                dependency.dependentAdded(this, true);
            } catch (IllegalStateException e) {
                for (; i >= 0; i --) {
                    dependency = dependencies[i];
                    dependency.dependentTerminated(true);
                }
                parent.childTerminated(true);
                synchronized (this) {
                    state = this.state | FLAG_USER_THREAD | FLAG_INSTALL_FAILED;
                    state = transition(state);
                    this.state = state & PERSISTENT_STATE;
                }
                executeTasks(state);
                throw e;
            }
        }
        synchronized (this) {
            state = this.state | FLAG_USER_THREAD;
            state = transition(state);
            this.state = state & PERSISTENT_STATE;
        }
        executeTasks(state);
    }

    class AsyncTask implements Runnable {
        private final int state;

        AsyncTask(final int state) {
            this.state = state;
        }

        public void run() {
            executeTasks(state);
        }
    }
}
