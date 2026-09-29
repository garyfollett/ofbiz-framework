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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.apache.ofbiz.service.GenericServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for {@link AgentToolContractRegistry}.
 *
 * <p>The registry is intended to provide a stable, immutable configuration
 * snapshot to the agent execution path. XML is loaded only on initial access
 * or explicit reload. Normal lookups must not re-read configuration.</p>
 *
 * <p>These tests prove:</p>
 *
 * <ul>
 *   <li>the production contract can be resolved through the registry;</li>
 *   <li>initial loading occurs lazily;</li>
 *   <li>normal repeated lookups use the cached snapshot;</li>
 *   <li>unknown services fail closed;</li>
 *   <li>explicit successful reload replaces the active snapshot;</li>
 *   <li>successful reload advances the registry generation; and</li>
 *   <li>failed reload preserves both the previous valid snapshot and its
 *       generation.</li>
 * </ul>
 */
public class AgentToolContractRegistryTest {

    private static final String PRODUCTION_SERVICE =
            "getCustomerOverdueInvoices";

    private static final String TEST_SCHEMA_RESOURCE =
            "registry-test-agent-tool-contracts.xsd";

    private static final String TEST_CONTRACT_RESOURCE =
            "registry-test-agent-tool-contracts.xml";

    /**
     * Verifies the normal production path using the actual configured agent
     * tool contract.
     */
    @Test
    public void testProductionConfigurationResolvesThroughRegistry()
            throws Exception {

        AgentToolContractRegistry registry =
                new AgentToolContractRegistry(
                        new AgentToolContractLoader());

        assertFalse(
                registry.isLoaded());

        assertEquals(
                0L,
                registry.getGeneration());

        AgentToolContract first =
                registry.getRequired(
                        PRODUCTION_SERVICE);

        assertNotNull(
                first);

        assertEquals(
                PRODUCTION_SERVICE,
                first.getServiceName());

        assertTrue(
                registry.isLoaded());

        assertEquals(
                1L,
                registry.getGeneration());

        /*
         * A second lookup should return the same immutable contract instance
         * from the current registry snapshot.
         */
        AgentToolContract second =
                registry.getRequired(
                        PRODUCTION_SERVICE);

        assertSame(
                first,
                second);

        /*
         * Normal lookups do not create new generations.
         */
        assertEquals(
                1L,
                registry.getGeneration());
    }

    /**
     * Verifies that an unknown service fails closed rather than returning null
     * or silently constructing an unrestricted contract.
     */
    @Test
    public void testUnknownServiceFailsClosed()
            throws Exception {

        AgentToolContractRegistry registry =
                new AgentToolContractRegistry(
                        new AgentToolContractLoader());

        GenericServiceException exception =
                assertThrows(GenericServiceException.class, () ->
                        registry.getRequired(
                                "serviceThatIsNotConfiguredForAgents"));

        assertTrue(
                exception.getMessage()
                        .contains(
                                "No agent tool contract is configured"));
    }

    /**
     * Proves that changing the underlying XML does not affect normal lookups
     * until an explicit reload is requested.
     *
     * <p>The test:</p>
     *
     * <ol>
     *   <li>loads valid XML;</li>
     *   <li>obtains a contract;</li>
     *   <li>replaces the XML with invalid configuration;</li>
     *   <li>performs another normal lookup; and</li>
     *   <li>proves the cached contract is still returned.</li>
     * </ol>
     *
     * <p>If getRequired() were reparsing XML on every invocation, the second
     * lookup would fail.</p>
     */
    @Test
    public void testRepeatedLookupDoesNotReloadXml(
            @TempDir Path tempDirectory)
            throws Exception {

        Path contractPath =
                prepareTemporaryConfiguration(
                        tempDirectory,
                        validConfiguration(
                                "testCachedService"));

        try (URLClassLoader classLoader =
                createConfigurationClassLoader(
                        tempDirectory)) {

            AgentToolContractLoader loader =
                    new AgentToolContractLoader(
                            classLoader,
                            TEST_CONTRACT_RESOURCE,
                            TEST_SCHEMA_RESOURCE);

            AgentToolContractRegistry registry =
                    new AgentToolContractRegistry(
                            loader);

            AgentToolContract first =
                    registry.getRequired(
                            "testCachedService");

            assertNotNull(
                    first);

            assertEquals(
                    1L,
                    registry.getGeneration());

            /*
             * Deliberately corrupt the underlying XML after the registry has
             * obtained its valid snapshot.
             */
            writeInvalidConfiguration(
                    contractPath);

            /*
             * This must still succeed because normal lookups use the existing
             * immutable in-memory snapshot.
             */
            AgentToolContract second =
                    registry.getRequired(
                            "testCachedService");

            assertSame(
                    first,
                    second);

            assertEquals(
                    1L,
                    registry.getGeneration());
        }
    }

    /**
     * Proves that an explicit successful reload atomically activates a new
     * configuration snapshot and advances the generation.
     */
    @Test
    public void testSuccessfulReloadReplacesSnapshot(
            @TempDir Path tempDirectory)
            throws Exception {

        Path contractPath =
                prepareTemporaryConfiguration(
                        tempDirectory,
                        validConfiguration(
                                "serviceVersionOne"));

        try (URLClassLoader classLoader =
                createConfigurationClassLoader(
                        tempDirectory)) {

            AgentToolContractLoader loader =
                    new AgentToolContractLoader(
                            classLoader,
                            TEST_CONTRACT_RESOURCE,
                            TEST_SCHEMA_RESOURCE);

            AgentToolContractRegistry registry =
                    new AgentToolContractRegistry(
                            loader);

            AgentToolContract versionOne =
                    registry.getRequired(
                            "serviceVersionOne");

            assertNotNull(
                    versionOne);

            assertEquals(
                    1L,
                    registry.getGeneration());

            /*
             * Replace the underlying configuration with another valid
             * complete snapshot.
             */
            Files.writeString(
                    contractPath,
                    validConfiguration(
                            "serviceVersionTwo"),
                    StandardCharsets.UTF_8);

            long generation =
                    registry.reload();

            assertEquals(
                    2L,
                    generation);

            assertEquals(
                    2L,
                    registry.getGeneration());

            AgentToolContract versionTwo =
                    registry.getRequired(
                            "serviceVersionTwo");

            assertNotNull(
                    versionTwo);

            assertEquals(
                    "serviceVersionTwo",
                    versionTwo.getServiceName());

            /*
             * The new snapshot replaces the old snapshot; it is not merged
             * with it.
             */
            assertThrows(GenericServiceException.class, () ->
                    registry.getRequired(
                            "serviceVersionOne"));
        }
    }

    /**
     * Proves the fail-safe reload invariant:
     *
     * <pre>
     * valid snapshot A
     *       |
     *       v
     * invalid XML reload attempt
     *       |
     *       +---- exception
     *       |
     *       v
     * snapshot A remains active
     * </pre>
     */
    @Test
    public void testFailedReloadRetainsPreviousSnapshot(
            @TempDir Path tempDirectory)
            throws Exception {

        Path contractPath =
                prepareTemporaryConfiguration(
                        tempDirectory,
                        validConfiguration(
                                "stableService"));

        try (URLClassLoader classLoader =
                createConfigurationClassLoader(
                        tempDirectory)) {

            AgentToolContractLoader loader =
                    new AgentToolContractLoader(
                            classLoader,
                            TEST_CONTRACT_RESOURCE,
                            TEST_SCHEMA_RESOURCE);

            AgentToolContractRegistry registry =
                    new AgentToolContractRegistry(
                            loader);

            AgentToolContract original =
                    registry.getRequired(
                            "stableService");

            assertNotNull(
                    original);

            assertEquals(
                    1L,
                    registry.getGeneration());

            /*
             * Replace the XML with a document that violates the XSD.
             */
            writeInvalidConfiguration(
                    contractPath);

            assertThrows(
                    GenericServiceException.class,
                    registry::reload);

            /*
             * Failed reload must not advance the generation.
             */
            assertEquals(
                    1L,
                    registry.getGeneration());

            assertTrue(
                    registry.isLoaded());

            /*
             * Most importantly, the previously validated contract remains
             * active and is the same immutable object.
             */
            AgentToolContract afterFailedReload =
                    registry.getRequired(
                            "stableService");

            assertSame(
                    original,
                    afterFailedReload);
        }
    }

    /**
     * Creates a temporary configuration directory containing:
     *
     * <pre>
     * registry-test-agent-tool-contracts.xsd
     * registry-test-agent-tool-contracts.xml
     * </pre>
     *
     * <p>The real production XSD is copied into the temporary classpath. The
     * XML itself is test-specific because registry tests need to mutate it
     * without changing production configuration.</p>
     */
    private static Path prepareTemporaryConfiguration(
            Path tempDirectory,
            String contractXml)
            throws IOException {

        Path schemaPath =
                tempDirectory.resolve(
                        TEST_SCHEMA_RESOURCE);

        copyProductionSchema(
                schemaPath);

        Path contractPath =
                tempDirectory.resolve(
                        TEST_CONTRACT_RESOURCE);

        Files.writeString(
                contractPath,
                contractXml,
                StandardCharsets.UTF_8);

        return contractPath;
    }

    /**
     * Copies the actual production agent-tool-contracts.xsd into the temporary
     * test classpath.
     *
     * <p>This prevents the registry tests from maintaining a second,
     * independently drifting schema definition.</p>
     */
    private static void copyProductionSchema(
            Path destination)
            throws IOException {

        ClassLoader classLoader =
                Thread.currentThread()
                        .getContextClassLoader();

        if (classLoader == null) {
            classLoader =
                    AgentToolContractRegistryTest.class
                            .getClassLoader();
        }

        try (InputStream input =
                classLoader.getResourceAsStream(
                        AgentToolContractLoader.DEFAULT_SCHEMA_RESOURCE)) {

            if (input == null) {
                throw new IOException(
                        "Production agent tool contract schema could not be found on the test classpath");
            }

            Files.copy(
                    input,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Creates a class loader whose first URL is the temporary configuration
     * directory.
     */
    private static URLClassLoader createConfigurationClassLoader(
            Path tempDirectory)
            throws IOException {

        URL configurationUrl =
                tempDirectory.toUri()
                        .toURL();

        return new URLClassLoader(
                new URL[] {
                        configurationUrl
                },
                AgentToolContractRegistryTest.class
                        .getClassLoader());
    }

    /**
     * Builds a minimal valid agent tool contract using the production XSD.
     *
     * <p>The contract deliberately contains only one model-visible scalar
     * field because these tests concern registry lifecycle rather than mapping
     * semantics.</p>
     */
    private static String validConfiguration(
            String serviceName) {

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <agent-tool-contracts
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        version="1.0">

                    <agent-tool service-name="%s">
                        <result>
                            <object>
                                <field name="resultValue"
                                        required="true">
                                    <scalar semantic-type="name"/>
                                </field>
                            </object>
                        </result>
                    </agent-tool>

                </agent-tool-contracts>
                """.formatted(
                        serviceName);
    }

    /**
     * Writes syntactically valid XML that deliberately violates the production
     * XSD because "broken" is not a permitted child of
     * agent-tool-contracts.
     */
    private static void writeInvalidConfiguration(
            Path contractPath)
            throws IOException {

        String invalidXml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <agent-tool-contracts version="1.0">
                    <broken/>
                </agent-tool-contracts>
                """;

        Files.writeString(
                contractPath,
                invalidXml,
                StandardCharsets.UTF_8);
    }
}
