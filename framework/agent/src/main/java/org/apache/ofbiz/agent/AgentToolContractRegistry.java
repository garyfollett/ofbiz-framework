/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.ofbiz.agent;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.ofbiz.service.GenericServiceException;

/**
 * Runtime registry for governed agent tool contracts.
 *
 * <p>The registry separates configuration loading from the normal agent
 * execution path:</p>
 *
 * <pre>
 * agent-tool-contracts.xml
 *          |
 *          v
 * AgentToolContractLoader
 *          |
 *          v
 * immutable Map&lt;String, AgentToolContract&gt;
 *          |
 *          v
 * AgentToolContractRegistry
 *          |
 *          +---- getRequired(serviceName)
 *          +---- getRequired(serviceName)
 *          +---- getRequired(serviceName)
 * </pre>
 *
 * <p>XML parsing and XSD validation therefore do not occur for every agent
 * invocation. They occur only during initial loading or an explicit reload.</p>
 *
 * <p>Reload is atomic. The loader constructs and validates a complete new
 * snapshot before the registry replaces the currently active snapshot. If
 * loading fails, the previous snapshot remains active.</p>
 *
 * <p>An execution that has already obtained an {@link AgentToolContract}
 * continues using that immutable contract even if another thread reloads the
 * registry concurrently. This gives each execution a consistent contract
 * view.</p>
 */
public final class AgentToolContractRegistry {

    /**
     * Singleton registry used by the normal agent runtime.
     *
     * <p>Configuration is loaded lazily on first access. This avoids throwing
     * checked configuration exceptions during Java static initialization while
     * still ensuring that the first real use validates the complete
     * configuration before returning a contract.</p>
     */
    private static final AgentToolContractRegistry DEFAULT_REGISTRY =
            new AgentToolContractRegistry(
                    new AgentToolContractLoader());

    /**
     * Loader used to construct complete candidate snapshots.
     */
    private final AgentToolContractLoader loader;

    /**
     * Currently active immutable configuration snapshot.
     *
     * <p>A null value means that initial loading has not yet completed
     * successfully.</p>
     */
    private final AtomicReference<Map<String, AgentToolContract>> contracts =
            new AtomicReference<>();

    /**
     * Monotonic in-process registry generation.
     *
     * <p>Generation zero means that no configuration has yet been activated.
     * Each successful initial load or reload increments the generation.</p>
     *
     * <p>This is not yet a persistent configuration version. It is an
     * in-process generation counter that will later be useful for tracing and
     * diagnostics.</p>
     */
    private final AtomicLong generation =
            new AtomicLong(0L);

    /**
     * Serializes initial loading.
     *
     * <p>Normal reads do not synchronize.</p>
     */
    private final Object initializationLock =
            new Object();

    /**
     * Creates a registry backed by the supplied loader.
     *
     * <p>The loader is not invoked by the constructor. Loading occurs lazily
     * on the first lookup or explicitly through {@link #reload()}.</p>
     *
     * @param loader contract loader
     */
    public AgentToolContractRegistry(
            AgentToolContractLoader loader) {

        if (loader == null) {
            throw new IllegalArgumentException(
                    "Agent tool contract loader must not be null");
        }

        this.loader =
                loader;
    }

    /**
     * Returns the process-wide default registry.
     *
     * @return default registry
     */
    public static AgentToolContractRegistry getDefault() {

        return DEFAULT_REGISTRY;
    }

    /**
     * Returns the required contract for an OFBiz service.
     *
     * <p>The first call causes the complete XML configuration to be loaded and
     * validated. Subsequent calls are in-memory map lookups unless an explicit
     * reload occurs.</p>
     *
     * @param serviceName OFBiz service name
     * @return configured immutable tool contract
     * @throws GenericServiceException if configuration cannot be loaded or the
     *         service has no configured agent tool contract
     */
    public AgentToolContract getRequired(
            String serviceName)
            throws GenericServiceException {

        String normalizedServiceName =
                requireServiceName(
                        serviceName);

        Map<String, AgentToolContract> snapshot =
                ensureLoaded();

        AgentToolContract contract =
                snapshot.get(
                        normalizedServiceName);

        if (contract == null) {
            throw new GenericServiceException(
                    "No agent tool contract is configured for OFBiz service ["
                    + normalizedServiceName
                    + "]");
        }

        return contract;
    }

    /**
     * Returns whether a contract is currently configured for an OFBiz service.
     *
     * <p>This method performs initial loading if required.</p>
     *
     * @param serviceName OFBiz service name
     * @return true when a contract exists
     * @throws GenericServiceException if initial configuration loading fails
     */
    public boolean contains(
            String serviceName)
            throws GenericServiceException {

        String normalizedServiceName =
                requireServiceName(
                        serviceName);

        return ensureLoaded()
                .containsKey(
                        normalizedServiceName);
    }

    /**
     * Explicitly reloads the complete contract configuration.
     *
     * <p>The new configuration is loaded and fully validated before the active
     * registry snapshot is changed.</p>
     *
     * <p>If {@link AgentToolContractLoader#load()} throws an exception, this
     * method propagates that exception and does not alter the active snapshot
     * or generation.</p>
     *
     * @return new active registry generation
     * @throws GenericServiceException if the candidate configuration cannot be
     *         loaded or validated
     */
    public long reload()
            throws GenericServiceException {

        /*
         * Do not modify the active AtomicReference until loading has completed
         * successfully.
         *
         * This is the critical fail-safe property of reload().
         */
        Map<String, AgentToolContract> candidate =
                loader.load();

        validateCandidate(
                candidate);

        /*
         * AtomicReference.set() publishes the complete immutable snapshot in
         * one operation. Readers therefore see either the previous complete
         * snapshot or the new complete snapshot.
         */
        contracts.set(
                candidate);

        return generation.incrementAndGet();
    }

    /**
     * Returns the current in-process registry generation.
     *
     * <p>Zero means no configuration has yet been successfully activated.</p>
     *
     * @return registry generation
     */
    public long getGeneration() {

        return generation.get();
    }

    /**
     * Returns whether the registry has successfully loaded a configuration
     * snapshot.
     *
     * @return true after successful initial load or reload
     */
    public boolean isLoaded() {

        return contracts.get()
                != null;
    }

    /**
     * Ensures that an initial configuration snapshot exists.
     *
     * <p>After successful initialization this method performs only an atomic
     * reference read on the normal execution path.</p>
     */
    private Map<String, AgentToolContract> ensureLoaded()
            throws GenericServiceException {

        Map<String, AgentToolContract> snapshot =
                contracts.get();

        if (snapshot != null) {
            return snapshot;
        }

        synchronized (initializationLock) {

            /*
             * Another thread may have completed initialization while this
             * thread was waiting for the lock.
             */
            snapshot =
                    contracts.get();

            if (snapshot != null) {
                return snapshot;
            }

            Map<String, AgentToolContract> candidate =
                    loader.load();

            validateCandidate(
                    candidate);

            contracts.set(
                    candidate);

            generation.incrementAndGet();

            return candidate;
        }
    }

    /**
     * Performs registry-level validation of a candidate snapshot.
     *
     * <p>The loader already validates XML syntax, XSD structure and individual
     * contract semantics. The registry additionally requires a usable,
     * non-empty complete snapshot.</p>
     */
    private static void validateCandidate(
            Map<String, AgentToolContract> candidate)
            throws GenericServiceException {

        if (candidate == null) {
            throw new GenericServiceException(
                    "Agent tool contract loader returned a null configuration snapshot");
        }

        if (candidate.isEmpty()) {
            throw new GenericServiceException(
                    "Agent tool contract configuration snapshot must not be empty");
        }

        for (Map.Entry<String, AgentToolContract> entry
                : candidate.entrySet()) {

            String serviceName =
                    entry.getKey();

            AgentToolContract contract =
                    entry.getValue();

            if (serviceName == null
                    || serviceName.isBlank()) {

                throw new GenericServiceException(
                        "Agent tool contract registry contains an empty service name");
            }

            if (contract == null) {
                throw new GenericServiceException(
                        "Agent tool contract registry contains a null contract for service ["
                        + serviceName
                        + "]");
            }

            if (!serviceName.equals(
                    contract.getServiceName())) {

                throw new GenericServiceException(
                        "Agent tool contract registry key ["
                        + serviceName
                        + "] does not match contract service name ["
                        + contract.getServiceName()
                        + "]");
            }
        }
    }

    /**
     * Validates an OFBiz service name supplied for lookup.
     */
    private static String requireServiceName(
            String serviceName)
            throws GenericServiceException {

        if (serviceName == null
                || serviceName.isBlank()) {

            throw new GenericServiceException(
                    "OFBiz service name must not be empty when resolving an agent tool contract");
        }

        /*
         * Service names are identifiers, not human text. We remove accidental
         * surrounding whitespace but otherwise preserve the exact service
         * spelling and case.
         */
        return serviceName.trim();
    }
}
