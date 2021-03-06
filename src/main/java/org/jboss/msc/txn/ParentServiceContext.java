/*
 * JBoss, Home of Professional Open Source
 *
 * Copyright 2013 Red Hat, Inc. and/or its affiliates.
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

import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistry;

/**
 * Parent service context: behaves just like service context super class except that newly created services are
 * automatically children services of parent.
 * 
 * @author <a href="mailto:frainone@redhat.com">Flavia Rainone</a>
 *
 */
class ParentServiceContext extends ServiceContextImpl {
    private final Registration parentRegistration;

    public ParentServiceContext(Registration parentRegistration, TransactionController transactionController) {
        super (transactionController);
        this.parentRegistration = parentRegistration;
    }

    public void validateParentUp(final Transaction transaction) {
        if (parentRegistration.getController() == null) {
            throw new IllegalStateException("Service context error: " + parentRegistration.getServiceName() + " is not installed");
        }
        validateTransaction(transaction);
        if (!Bits.allAreSet(parentRegistration.getController().getState(transaction), ServiceControllerImpl.STATE_UP)) {
            throw new IllegalStateException("Service context error: " + parentRegistration.getServiceName() + " is not UP");
        }
    }

    @Override
    public <T> ServiceBuilder<T> addService(final Class<T> valueType, final ServiceRegistry registry, final ServiceName name, final Transaction transaction) {
        validateParentUp(transaction);
        final ServiceBuilder<T> serviceBuilder = super.addService(valueType, registry, name, transaction);
        ((ServiceBuilderImpl<T>) serviceBuilder).setParentDependency(parentRegistration);
        return serviceBuilder;
    }

    @Override
    public ServiceBuilder<Void> addService(final ServiceRegistry registry, final ServiceName name, final Transaction transaction) {
        validateParentUp(transaction);
        final ServiceBuilder<Void> serviceBuilder = super.addService(registry, name, transaction);
        ((ServiceBuilderImpl<Void>) serviceBuilder).setParentDependency(parentRegistration);
        return serviceBuilder;
    }
}
