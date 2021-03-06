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
import static org.jboss.msc._private.MSCLogger.TXN;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jboss.msc._private.MSCLogger;
import org.jboss.msc.service.DuplicateServiceException;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceMode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.txn.Problem.Severity;

/**
 * A service controller implementation.
 *
 * @param <S> the service type
 * 
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:frainone@redhat.com">Flavia Rainone</a>
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class ServiceControllerImpl<T> extends TransactionalObject implements ServiceController {

    // controller modes
    static final byte MODE_ACTIVE      = (byte)ServiceMode.ACTIVE.ordinal();
    static final byte MODE_LAZY        = (byte)ServiceMode.LAZY.ordinal();
    static final byte MODE_ON_DEMAND   = (byte)ServiceMode.ON_DEMAND.ordinal();
    static final byte MODE_MASK        = (byte)0b00000011;
    // controller states
    static final byte STATE_NEW        = (byte)0b00000000;
    static final byte STATE_DOWN       = (byte)0b00000100;
    static final byte STATE_STARTING   = (byte)0b00001000;
    static final byte STATE_UP         = (byte)0b00001100;
    static final byte STATE_FAILED     = (byte)0b00010000;
    static final byte STATE_STOPPING   = (byte)0b00010100;
    static final byte STATE_REMOVING   = (byte)0b00011000;
    static final byte STATE_REMOVED    = (byte)0b00011100;
    static final byte STATE_MASK       = (byte)0b00011100;
    // controller disposal flags
    static final byte SERVICE_ENABLED  = (byte)0b00100000;
    static final byte REGISTRY_ENABLED = (byte)0b01000000;
    
    /**
     * The service itself.
     */
    private final Service<T> service;
    /**
     * The primary registration of this service.
     */
    private final Registration primaryRegistration;
    /**
     * The alias registrations of this service.
     */
    private final Registration[] aliasRegistrations;
    /**
     * The dependencies of this service.
     */
    private final DependencyImpl<?>[] dependencies;
    /**
     * The service value, resulting of service start.
     */
    private T value;
    /**
     * The controller state.
     */
    private byte state = (byte)(STATE_NEW | MODE_ACTIVE);
    /**
     * The number of dependencies that are not satisfied.
     */
    private int unsatisfiedDependencies;
    /**
     * Indicates if this service is demanded to start. Has precedence over {@link downDemanded}.
     */
    private int upDemandedByCount;
    /**
     * The number of dependents that are currently running. The deployment will
     * not execute the {@code stop()} method (and subsequently leave the
     * {@link State#STOPPING} state) until all running dependents (and listeners) are stopped.
     */
    private int runningDependents;

    /**
     * Info enabled only when this service is write locked during a transaction.
     */
    // will be non null iff write locked
    private volatile TransactionalInfo transactionalInfo = null;

    /**
     * Creates the service controller, thus beginning installation.
     * 
     * @param primaryRegistration the primary registration
     * @param aliasRegistrations  the alias registrations
     * @param service             the service itself
     * @param mode                the service mode
     * @param dependencies        the service dependencies
     * @param transaction         the active transaction
     */
    ServiceControllerImpl(final Registration primaryRegistration, final Registration[] aliasRegistrations, final Service<T> service,
            final org.jboss.msc.service.ServiceMode mode, final DependencyImpl<?>[] dependencies, final Transaction transaction) {
        this.service = service;
        setMode(mode);
        this.dependencies = dependencies;
        this.aliasRegistrations = aliasRegistrations;
        this.primaryRegistration = primaryRegistration;
        lockWrite(transaction, transaction.getTaskFactory());
        unsatisfiedDependencies = dependencies.length;
        for (DependencyImpl<?> dependency: dependencies) {
            dependency.setDependent(this, transaction);
        }
    }

    private void setMode(final ServiceMode mode) {
        if (mode != null) {
            setMode((byte)mode.ordinal());
        } else {
            // default mode (if not provided) is ACTIVE
        }
    }

    /**
     * Completes service installation, enabling the service and installing it into registrations.
     *
     * @param transaction the active transaction
     */
    void install(ServiceRegistryImpl registry, Transaction transaction) {
        assert isWriteLocked(transaction);
        // if registry is removed, get an exception right away
        registry.newServiceInstalled(this, transaction);
        if (!primaryRegistration.setController(transaction, this)) {
            throw new DuplicateServiceException("Service " + primaryRegistration.getServiceName() + " is already installed");
        }
        int installedAliases = 0; 
        for (Registration alias: aliasRegistrations) {
            // attempt to install controller at alias
            if (!alias.setController(transaction, this)) {
                // first of all, uninstall controller from installed aliases
                primaryRegistration.clearController(transaction, transaction.getTaskFactory());
                for (int j = 0; j < installedAliases; j++) {
                    aliasRegistrations[j].clearController(transaction, transaction.getTaskFactory());
                }
                throw new DuplicateServiceException("Service " + alias.getServiceName() + " is already installed");
            }
            installedAliases ++;
        }
        boolean demandDependencies;
        synchronized (this) {
            state |= SERVICE_ENABLED;
            transactionalInfo.setState(STATE_DOWN);
            demandDependencies = isMode(MODE_ACTIVE);
        }
        if (demandDependencies) {
            DemandDependenciesTask.create(this, transaction, transaction.getTaskFactory());
        }
        transactionalInfo.transition(transaction, transaction.getTaskFactory());
    }

    /**
     * Gets the primary registration.
     */
    Registration getPrimaryRegistration() {
        return primaryRegistration;
    }

    /**
     * Gets the alias registrations.
     */
    Registration[] getAliasRegistrations() {
        return aliasRegistrations;
    }

    /**
     * Gets the dependencies.
     */
    DependencyImpl<?>[] getDependencies() {
        return dependencies;
    }

    /**
     * Gets the service.
     */
    public Service<T> getService() {
        return service;
    }

    T getValue() {
        if (value == null) {
            throw MSCLogger.SERVICE.serviceNotStarted();
        }
        return value;
    }

    void setValue(T value) {
        this.value = value;
    }

    /**
     * Gets the current service controller state.
     */
    synchronized int getState() {
        return getState(state);
    }

    /**
     * Gets the current service controller state inside {@code transaction} context.
     * 
     * @param transaction the transaction
     */
    synchronized int getState(Transaction transaction) {
        if (super.isWriteLocked(transaction)) {
            return this.transactionalInfo.getState();
        }
        return getState(state);
    }

    private static int getState(byte state) {
        return (state & STATE_MASK);
    }

    /**
     * Management operation for disabling a service. As a result, this service will stop if it is {@code UP}.
     */
    public void disable(final Transaction transaction) {
        if (transaction == null) {
            throw TXN.methodParameterIsNull("transaction");
        }
        lockWrite(transaction, transaction.getTaskFactory());
        synchronized(this) {
            if (!isServiceEnabled()) return;
            state &= ~SERVICE_ENABLED;
            if (!isRegistryEnabled()) return;
        }
        transactionalInfo.transition(transaction, transaction.getTaskFactory());
    }

    /**
     * Management operation for enabling a service. The service may start as a result, according to its {@link
     * ServiceMode mode} rules.
     * <p> Services are enabled by default.
     */
    public void enable(final Transaction transaction) {
        if (transaction == null) {
            throw TXN.methodParameterIsNull("transaction");
        }
        lockWrite(transaction, transaction.getTaskFactory());
        synchronized(this) {
            if (isServiceEnabled()) return;
            state |= SERVICE_ENABLED;
            if (!isRegistryEnabled()) return;
        }
        transactionalInfo.transition(transaction, transaction.getTaskFactory());
    }

    private boolean isServiceEnabled() {
        assert holdsLock(this);
        return Bits.allAreSet(state, SERVICE_ENABLED);
    }

    void disableRegistry(Transaction transaction) {
        lockWrite(transaction, transaction.getTaskFactory());
        synchronized (this) {
            if (!isRegistryEnabled()) return;
            state &= ~REGISTRY_ENABLED;
            if (!isServiceEnabled()) return;
        }
        transactionalInfo.transition(transaction, transaction.getTaskFactory());
    }

    void enableRegistry(Transaction transaction) {
        lockWrite(transaction, transaction.getTaskFactory());
        synchronized (this) {
            if (isRegistryEnabled()) return;
            state |= REGISTRY_ENABLED;
            if (!isServiceEnabled()) return;
        }
        transactionalInfo.transition(transaction, transaction.getTaskFactory());
    }

    private boolean isRegistryEnabled() {
        assert holdsLock(this);
        return Bits.allAreSet(state, REGISTRY_ENABLED);
    }

    @Override
    public void retry(Transaction transaction) {
        lockWrite(transaction, transaction.getTaskFactory());
        transactionalInfo.retry(transaction);
    }

    @Override
    public void remove(Transaction transaction) {
        this.remove(transaction, transaction.getTaskFactory());
    }

    @Override
    public void restart(Transaction transaction) {
        // TODO
    }

    /**
     * Removes this service.<p>
     * All dependent services will be automatically stopped as the result of this operation.
     * 
     * @param  transaction the active transaction
     * @param  taskFactory the task factory
     * @return the task that completes removal. Once this task is executed, the service will be at the
     *         {@code REMOVED} state.
     */
    TaskController<Void> remove(Transaction transaction, TaskFactory taskFactory) {
        lockWrite(transaction, taskFactory);
        return transactionalInfo.scheduleRemoval(transaction, taskFactory);
    }

    /**
     * Notifies this service that it is up demanded (demanded to be UP) by one of its incoming dependencies.
     * 
     * @param transaction the active transaction
     * @param taskFactory the task factory
     */
    void upDemanded(Transaction transaction, TaskFactory taskFactory) {
        lockWrite(transaction, taskFactory);
        final boolean propagate;
        synchronized (this) {
            if (upDemandedByCount ++ > 0) {
                return;
            }
            propagate = !isMode(MODE_ACTIVE);
        }
        if (propagate) {
            DemandDependenciesTask.create(this, transaction, taskFactory);
        }
        transition(transaction, taskFactory);
    }

    /**
     * Notifies this service that it is no longer up demanded by one of its incoming dependencies (invoked when incoming
     * dependency is being disabled or removed).
     * 
     * @param transaction the active transaction
     * @param taskFactory the task factory
     */
    void upUndemanded(Transaction transaction, TaskFactory taskFactory) {
        lockWrite(transaction, taskFactory);
        final boolean propagate;
        synchronized (this) {
            if (-- upDemandedByCount > 0) {
                return;
            }
            propagate = !isMode(MODE_ACTIVE);
        }
        if (propagate) {
            UndemandDependenciesTask.create(this, transaction, taskFactory);
        }
        transition(transaction, taskFactory);
    }

    /**
     * Indicates if this service is demanded to start by one or more of its incoming dependencies.
     * @return
     */
    synchronized boolean isUpDemanded() {
        return upDemandedByCount > 0;
    }

    /**
     * Notifies that a incoming dependency has started.
     * 
     * @param transaction the active transaction
     * @param taskFactory the task factory
     */
    void dependentStarted(Transaction transaction, TaskFactory taskFactory) {
        lockWrite(transaction, taskFactory);
        synchronized (this) {
            runningDependents++;
        }
    }

    /**
     * Notifies that a incoming dependency has stopped.
     * 
     * @param transaction the active transaction
     * @param taskFactory the task factory
     */
    void dependentStopped(Transaction transaction, TaskFactory taskFactory) {
        lockWrite(transaction, taskFactory);
        synchronized (this) {
            if (--runningDependents > 0) {
                return;
            }
        }
        transition(transaction, taskFactory);
    }

    public ServiceName getServiceName() {
        return primaryRegistration.getServiceName();
    }

    public TaskController<?> dependencySatisfied(Transaction transaction, TaskFactory taskFactory) {
        lockWrite(transaction, taskFactory);
        synchronized (this) {
            if (-- unsatisfiedDependencies > 0) {
                return null;
            }
        }
        return transition(transaction, taskFactory);
    }

    public TaskController<?> dependencyUnsatisfied(Transaction transaction, TaskFactory taskFactory) {
        lockWrite(transaction, taskFactory);
        synchronized (this) {
           if (++ unsatisfiedDependencies > 1) {
               return null;
            }
        }
        return transition(transaction, taskFactory);
    }

    /**
     * Sets the new transactional state of this service.
     * 
     * @param transactionalState the transactional state
     * @param taskFactory        the task factory
     */
    void setTransition(byte transactionalState, Transaction transaction, TaskFactory taskFactory) {
        assert isWriteLocked();
        transactionalInfo.setTransition(transactionalState, transaction, taskFactory);
    }

    private TaskController<?> transition(Transaction transaction, TaskFactory taskFactory) {
        assert isWriteLocked();
        return transactionalInfo.transition(transaction, taskFactory);
    }

    @Override
    protected synchronized void writeLocked(Transaction transaction) {
        transactionalInfo = new TransactionalInfo();
    }

    @Override
    protected synchronized void writeUnlocked() {
        state = (byte) (transactionalInfo.getState() & STATE_MASK | state & ~STATE_MASK);
        transactionalInfo = null;
    }

    @Override
    Object takeSnapshot() {
        return new Snapshot();
    }

    @SuppressWarnings("unchecked")
    @Override
    void revert(final Object snapshot) {
        if (snapshot != null) {
            ((Snapshot) snapshot).apply();
        }
    }

    final class TransactionalInfo {
        // current transactional state
        private byte transactionalState = ServiceControllerImpl.this.currentState();
        // if this service is under transition, this field points to the task that completes the transition
        private TaskController<Void> completeTransitionTask = null;
        // the expected state when complete transition task finishes
        private byte completeTransitionState = 0;
        // the total number of setTransition calls expected until completeTransitionTask is finished
        private int transitionCount;

        synchronized void setTransition(byte transactionalState, Transaction transaction, TaskFactory taskFactory) {
            this.transactionalState = transactionalState;
            assert transitionCount > 0;
            // transition has finally come to an end, and calling task equals completeTransitionTask
            if (-- transitionCount == 0) {
                switch(transactionalState) {
                    case STATE_UP:
                        notifyServiceUp(transaction, taskFactory);
                        break;
                    case STATE_REMOVED:
                        for (DependencyImpl<?> dependency: dependencies) {
                            dependency.clearDependent(transaction, taskFactory);
                        }
                        break;
                    case STATE_DOWN:
                        // fall thru!
                    case STATE_FAILED:
                        break;
                    default:
                        throw new IllegalStateException("Illegal state for finishing transition: " + transactionalState);
                }
                completeTransitionTask = null;
                completeTransitionState = 0;
            }
        }

        private synchronized void retry(Transaction transaction) {
            if (transactionalState != STATE_FAILED) {
                return;
            }
            assert completeTransitionTask == null;
            completeTransitionTask = StartingServiceTasks.create(ServiceControllerImpl.this, transaction, transaction.getTaskFactory());
        }

        private synchronized TaskController<?> transition(Transaction transaction, TaskFactory taskFactory) {
            assert !holdsLock(ServiceControllerImpl.this);
            switch (transactionalState) {
                case STATE_DOWN:
                    if (unsatisfiedDependencies == 0 && shouldStart() && !isStarting()) {
                        transactionalState = STATE_STARTING;
                        completeTransitionTask = StartingServiceTasks.create(ServiceControllerImpl.this, transaction, taskFactory);
                        completeTransitionState = STATE_UP;
                        transitionCount ++;
                    }
                    break;
                case STATE_STOPPING:
                    if (unsatisfiedDependencies == 0 && !shouldStop() && !isStarting()) {
                        // ongoing transition from UP to DOWN, transition to UP just once service is DOWN
                        TaskController<?> setStartingState = transaction.getTaskFactory().newTask(new SetTransactionalStateTask(ServiceControllerImpl.this, STATE_STARTING, transaction))
                            .addDependency(completeTransitionTask).release();
                        completeTransitionTask = StartingServiceTasks.create(ServiceControllerImpl.this, setStartingState, transaction, taskFactory);
                        completeTransitionState = STATE_UP;
                        transitionCount += 2;
                    }
                    break;
                case STATE_FAILED:
                    if ((unsatisfiedDependencies > 0 || shouldStop()) && !isStopping()) {
                        transactionalState = STATE_STOPPING;
                        completeTransitionTask = StoppingServiceTasks.createForFailedService(ServiceControllerImpl.this, transaction, taskFactory);
                        completeTransitionState = STATE_DOWN;
                        transitionCount ++;
                    }
                    break;
                case STATE_UP:
                    if ((unsatisfiedDependencies > 0 || shouldStop()) && !isStopping()) {
                        final Collection<TaskController<?>> dependentTasks = notifyServiceDown(transaction, taskFactory);
                        transactionalState = STATE_STOPPING;
                        completeTransitionTask = StoppingServiceTasks.create(ServiceControllerImpl.this, dependentTasks, transaction, taskFactory);
                        completeTransitionState = STATE_DOWN;
                        transitionCount ++;
                    }
                    break;
                case STATE_STARTING:
                    if ((unsatisfiedDependencies > 0 || !shouldStart()) && !isStopping()) {
                        // ongoing transition from DOWN to UP, transition to DOWN just once service is UP
                        TaskController<?> setStoppingState = transaction.getTaskFactory().newTask(new SetTransactionalStateTask(ServiceControllerImpl.this, STATE_STOPPING, transaction))
                                .addDependency(completeTransitionTask).release();
                        completeTransitionTask = StoppingServiceTasks.create(ServiceControllerImpl.this, setStoppingState, transaction, taskFactory);
                        completeTransitionState = STATE_DOWN;
                        transitionCount +=2;
                    }
                    break;
                default:
                    break;

            }
            return completeTransitionTask;
        }

        private void notifyServiceUp(Transaction transaction, TaskFactory taskFactory) {
            primaryRegistration.serviceUp(transaction, taskFactory);
            for (Registration registration: aliasRegistrations) {
                registration.serviceUp(transaction, taskFactory);
            }
        }

        private Collection<TaskController<?>> notifyServiceDown(Transaction transaction, TaskFactory taskFactory) {
            final List<TaskController<?>> tasks = new ArrayList<TaskController<?>>();
            primaryRegistration.serviceDown(transaction, taskFactory, tasks);
            for (Registration registration: aliasRegistrations) {
                registration.serviceDown(transaction, taskFactory, tasks);
            }
            return tasks;
        }

        private synchronized TaskController<Void> scheduleRemoval(Transaction transaction, TaskFactory taskFactory) {
            // idempotent
            if (getState() == STATE_REMOVED) {
                return null;
            }
            // disable service
            synchronized (ServiceControllerImpl.this) {
                state &= ~SERVICE_ENABLED;
            }
            // transition disabled service, guaranteeing that it is either at DOWN state or it will get to this state
            // after complete transition task completes
            transition(transaction, taskFactory);
            assert getState() == STATE_DOWN || completeTransitionTask!= null;// prevent hard to find bugs
            // create remove task
            final TaskBuilder<Void> removeTaskBuilder = taskFactory.newTask(new ServiceRemoveTask(ServiceControllerImpl.this, transaction));
            if (completeTransitionTask != null) {
                removeTaskBuilder.addDependency(completeTransitionTask);
            }
            removeTaskBuilder.addDependency(getUnlockTask());
            completeTransitionTask = removeTaskBuilder.release();
            completeTransitionState = STATE_REMOVED;
            transitionCount ++;
            return completeTransitionTask;
        }

        private void setState(final byte sid) {
            transactionalState = sid;
        }

        private byte getState() {
            return transactionalState;
        }

        private boolean isStarting() {
            return completeTransitionState == STATE_UP;
        }

        private boolean isStopping() {
            return completeTransitionState == STATE_DOWN || completeTransitionState == STATE_REMOVED;
        }
    }

    private final class Snapshot {
        private final byte state;
        private final int upDemandedByCount;
        private final int unsatisfiedDependencies;
        private final int runningDependents;

        // take snapshot
        public Snapshot() {
            assert holdsLock(ServiceControllerImpl.this);
            state = ServiceControllerImpl.this.state;
            upDemandedByCount = ServiceControllerImpl.this.upDemandedByCount;
            unsatisfiedDependencies = ServiceControllerImpl.this.unsatisfiedDependencies;
            runningDependents = ServiceControllerImpl.this.runningDependents;
        }

        // revert ServiceController state to what it was when snapshot was taken; invoked on rollback
        public void apply() {
            assert holdsLock(ServiceControllerImpl.this);
            // TODO temporary fix to an issue that needs to be evaluated:
            // as a result of a rollback, service must not think it is up when it is down, and vice-versa
            if (getState() == STATE_UP && (getState(state) == STATE_DOWN || getState(state) == STATE_NEW)) {
                service.stop(new StopContext() {

                    @Override
                    public void complete(Void result) {
                        // ignore
                    }

                    @Override
                    public void complete() {
                        // ignore
                    }

                    @Override
                    public void addProblem(Problem reason) {
                        throw new UnsupportedOperationException("Cannot add a problem to transaction during rollback");
                    }

                    @Override
                    public void addProblem(Severity severity, String message) {
                        throw new UnsupportedOperationException("Cannot add a problem to transaction during rollback");
                    }

                    @Override
                    public void addProblem(Severity severity, String message, Throwable cause) {
                        throw new UnsupportedOperationException("Cannot add a problem to transaction during rollback");
                    }

                    @Override
                    public void addProblem(String message, Throwable cause) {
                        throw new UnsupportedOperationException("Cannot add a problem to transaction during rollback");
                    }

                    @Override
                    public void addProblem(String message) {
                        throw new UnsupportedOperationException("Cannot add a problem to transaction during rollback");
                    }

                    @Override
                    public void addProblem(Throwable cause) {
                        throw new UnsupportedOperationException("Cannot add a problem to transaction during rollback");
                    }

                    @Override
                    public boolean isCancelRequested() {
                        return false;
                    }

                    @Override
                    public void cancelled() {
                        throw new IllegalStateException("Task cannot be cancelled now");
                    }

                    @Override
                    public <T> TaskBuilder<T> newTask(Executable<T> task) throws IllegalStateException {
                        throw new UnsupportedOperationException("not implemented");
                    }

                    @Override
                    public TaskBuilder<Void> newTask() throws IllegalStateException {
                        throw new UnsupportedOperationException("not implemented");
                    }});
            } else if ((getState() == STATE_DOWN || getState() == STATE_REMOVED) && getState(state) == STATE_UP) {
                service.start(new StartContext<T>() {

                    @Override
                    public void complete(Object result) {
                        // ignore
                    }

                    @Override
                    public void complete() {
                        // ignore
                    }

                    @Override
                    public void addProblem(Problem reason) {
                        throw new UnsupportedOperationException("Cannot add a problem to transaction during rollback");
                    }

                    @Override
                    public void addProblem(Severity severity, String message) {
                        throw new UnsupportedOperationException("Cannot add a problem to transaction during rollback");
                    }

                    @Override
                    public void addProblem(Severity severity, String message, Throwable cause) {
                        throw new UnsupportedOperationException("Cannot add a problem to transaction during rollback");
                    }

                    @Override
                    public void addProblem(String message, Throwable cause) {
                        throw new UnsupportedOperationException("Cannot add a problem to transaction during rollback");
                    }

                    @Override
                    public void addProblem(String message) {
                        throw new UnsupportedOperationException("Cannot add a problem to transaction during rollback");
                    }

                    @Override
                    public void addProblem(Throwable cause) {
                        throw new UnsupportedOperationException("Cannot add a problem to transaction during rollback");
                    }

                    @Override
                    public boolean isCancelRequested() {
                        return false;
                    }

                    @Override
                    public void cancelled() {
                        throw new IllegalStateException("Task cannot be cancelled now");
                    }

                    @Override
                    public <T> TaskBuilder<T> newTask(Executable<T> task) throws IllegalStateException {
                        throw new UnsupportedOperationException("not implemented");
                    }

                    @Override
                    public TaskBuilder<Void> newTask() throws IllegalStateException {
                        throw new UnsupportedOperationException("not implemented");
                    }

                    @Override
                    public void fail() {
                        throw new UnsupportedOperationException("not implemented");
                    }});
            }
            ServiceControllerImpl.this.state = state;
            ServiceControllerImpl.this.upDemandedByCount = upDemandedByCount;
            ServiceControllerImpl.this.unsatisfiedDependencies = unsatisfiedDependencies;
            ServiceControllerImpl.this.runningDependents = runningDependents;
        }
    }

    private synchronized boolean shouldStart() {
        return (isMode(MODE_ACTIVE) || upDemandedByCount > 0) && Bits.allAreSet(state, SERVICE_ENABLED | REGISTRY_ENABLED);
    }

    private synchronized boolean shouldStop() {
        return (isMode(MODE_ON_DEMAND) && upDemandedByCount == 0) || !Bits.allAreSet(state, SERVICE_ENABLED | REGISTRY_ENABLED);
    }

    private void setMode(final byte mid) {
        state = (byte) (mid & MODE_MASK | state & ~MODE_MASK);
    }

    private boolean isMode(final byte mode) {
        assert holdsLock(this);
        return (state & MODE_MASK) == mode;
    }

    private byte currentState() {
        assert holdsLock(this);
        return (byte)(state & STATE_MASK);
    }
}