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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.testtools.JunitJupiterTest;
import org.apache.ofbiz.testtools.JupiterTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Live end-to-end acceptance test for AgentServiceEngine using the configured
 * OpenAI-compatible vLLM endpoint.
 *
 * <p>This test deliberately does not replace or mock the LLM endpoint. It
 * exercises the complete live path:</p>
 *
 * <pre>
 * OFBiz test dispatcher
 *     -> analyseCustomerAccount
 *     -> AgentServiceEngine
 *     -> configured vLLM endpoint
 *     -> configured model
 *     -> model tool call
 *     -> getCustomerOverdueInvoices
 *     -> AgentToolContractRegistry
 *     -> AgentToolMapper
 *     -> AgentValueCodec
 *     -> canonical tool result
 *     -> configured vLLM endpoint
 *     -> configured model
 *     -> final summary
 *     -> OFBiz service result
 * </pre>
 *
 * <p>The test uses the same deterministic AgentIntCustomer fixture as
 * AgentServiceEngineIntegrationTest. The fixture's invoice identifier,
 * AGENT_INT_1000, does not occur in the agent prompt. Requiring the live model
 * response to contain that identifier therefore provides evidence that the
 * model received and used the real OFBiz tool result.</p>
 *
 * <p>This test is intentionally excluded unless the environment variable
 * {@code OFBIZ_AGENT_VLLM_E2E=true} is set. A normal integration-test run must
 * not depend on a locally running model server.</p>
 *
 * <p>The final model prose is not compared with an exact expected sentence.
 * Only stable business facts needed to prove the live end-to-end path are
 * asserted.</p>
 */
@JunitJupiterTest
public class AgentServiceEngineVllmEndToEndTest implements JupiterTestHelper {

    private static final String ENABLE_ENVIRONMENT_VARIABLE =
            "OFBIZ_AGENT_VLLM_E2E";

    private static final String PROPERTY_RESOURCE =
            "agent";

    private static final String BASE_URL_PROPERTY =
            "agent.llm.baseUrl";

    private static final String MODEL_PROPERTY =
            "agent.llm.model";

    private static final String AGENT_SERVICE_NAME =
            "analyseCustomerAccount";

    private static final String TEST_PARTY_ID =
            "AgentIntCustomer";

    private static final String TEST_INVOICE_ID =
            "AGENT_INT_1000";

    private static final String EXPECTED_AMOUNT =
            "20.00";

    private static final TimeZone SYDNEY_TIME_ZONE =
            TimeZone.getTimeZone(
                    "Australia/Sydney");

    private TimeZone previousDefaultTimeZone;

    /**
     * Enables the live test only when explicitly requested and fixes the JVM
     * default timezone for the legacy OFBiz invoice due-date calculation used
     * by the integration fixture.
     */
    @BeforeEach
    public void setUp() {

        boolean enabled =
                Boolean.parseBoolean(
                        System.getenv(
                                ENABLE_ENVIRONMENT_VARIABLE));

        assumeTrue(
                enabled,
                "Set OFBIZ_AGENT_VLLM_E2E=true to run the live vLLM test");

        previousDefaultTimeZone =
                TimeZone.getDefault();

        TimeZone.setDefault(
                SYDNEY_TIME_ZONE);
    }

    /**
     * Restores process-level timezone state changed by this test.
     */
    @AfterEach
    public void tearDown() {

        if (previousDefaultTimeZone != null) {
            TimeZone.setDefault(
                    previousDefaultTimeZone);
        }
    }

    /**
     * Executes analyseCustomerAccount against the configured live vLLM
     * endpoint and verifies that the real model consumes the real OFBiz tool
     * result and returns a usable final service result.
     *
     * @throws Exception if the OFBiz service or live model call fails
     */
    @Test
    public void testAnalyseCustomerAccountAgainstLiveVllm()
            throws Exception {

        String baseUrl =
                UtilProperties.getPropertyValue(
                        PROPERTY_RESOURCE,
                        BASE_URL_PROPERTY);

        String model =
                UtilProperties.getPropertyValue(
                        PROPERTY_RESOURCE,
                        MODEL_PROPERTY);

        assertNotNull(
                baseUrl);

        assertFalse(
                baseUrl.isBlank());

        assertNotNull(
                model);

        assertFalse(
                model.isBlank());

        logInfo(
                "Running live AgentServiceEngine vLLM end-to-end test against "
                + baseUrl
                + " using model "
                + model);

        GenericValue userLogin =
                getUserLogin();

        assertNotNull(
                userLogin);

        Map<String, Object> context =
                buildAuthoritativeContext(
                        userLogin);

        /*
         * This is the only business operation invoked by the test.
         *
         * No direct call to getCustomerOverdueInvoices is made here. The live
         * model must participate in the real AgentServiceEngine flow and issue
         * the governed tool call itself.
         */
        Map<String, Object> result =
                getDispatcher().runSync(
                        AGENT_SERVICE_NAME,
                        context);

        assertFalse(
                ServiceUtil.isError(
                        result),
                ServiceUtil.getErrorMessage(
                        result));

        assertFalse(
                ServiceUtil.isFailure(
                        result),
                ServiceUtil.getErrorMessage(
                        result));

        assertEquals(
                "success",
                result.get(
                        "responseMessage"));

        Object rawSummary =
                result.get(
                        "summary");

        assertNotNull(
                rawSummary);

        assertTrue(
                rawSummary instanceof String);

        String summary =
                (String) rawSummary;

        assertFalse(
                summary.isBlank());

        /*
         * AGENT_INT_1000 does not occur in the system prompt or user prompt.
         *
         * The live model can only know this value after AgentServiceEngine has
         * executed getCustomerOverdueInvoices and supplied the governed tool
         * result to the second model request.
         */
        assertTrue(
                summary.contains(
                        TEST_INVOICE_ID),
                "Live vLLM summary must contain the tool-returned invoice ID");

        /*
         * Do not assert exact prose. Preserve only the stable financial fact
         * expected from the canonical model-facing tool result.
         */
        assertTrue(
                summary.contains(
                        EXPECTED_AMOUNT),
                "Live vLLM summary must contain the canonical invoice amount");

        logInfo(
                "Live AgentServiceEngine vLLM end-to-end summary: "
                + summary);
    }

    /**
     * Constructs the authoritative OFBiz service context.
     *
     * <p>The customer scope and timezone are supplied by OFBiz and are not
     * model-controlled values.</p>
     *
     * @param userLogin authenticated OFBiz user
     * @return authoritative service context
     */
    private static Map<String, Object> buildAuthoritativeContext(
            GenericValue userLogin) {

        Map<String, Object> context =
                new HashMap<>();

        context.put(
                "partyId",
                TEST_PARTY_ID);

        context.put(
                "userLogin",
                userLogin);

        context.put(
                "locale",
                Locale.ENGLISH);

        context.put(
                "timeZone",
                SYDNEY_TIME_ZONE);

        return context;
    }
}
