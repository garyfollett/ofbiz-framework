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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.transaction.GenericTransactionException;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.testtools.JunitJupiterTest;
import org.apache.ofbiz.testtools.JupiterTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Integration test for the complete outbound AgentServiceEngine semantic
 * boundary.
 *
 * <p>This test executes the real OFBiz service path:</p>
 *
 * <pre>
 * analyseCustomerAccount
 *     -> AgentServiceEngine
 *     -> first OpenAI-compatible request
 *     -> getCustomerOverdueInvoices
 *     -> AgentToolContractRegistry
 *     -> AgentToolMapper
 *     -> AgentValueCodec
 *     -> canonical model-facing JSON
 *     -> second OpenAI-compatible request
 * </pre>
 *
 * <p>The OFBiz Delegator and LocalDispatcher are supplied by the normal OFBiz
 * integration-test container. The only external dependency replaced by this
 * test is the LLM endpoint. A deterministic local HTTP server implements the
 * two Chat Completions responses required by the V1 agent lifecycle.</p>
 *
 * <p>The principal assertion is made against the actual second HTTP request
 * emitted by {@link OpenAiCompatibleClient}. This proves that semantic mapping
 * is wired into the live AgentServiceEngine execution path rather than merely
 * working in isolated mapper tests.</p>
 */
@JunitJupiterTest
public class AgentServiceEngineIntegrationTest implements JupiterTestHelper {

    private static final String PROPERTY_RESOURCE =
            "agent";

    private static final String BASE_URL_PROPERTY =
            "agent.llm.baseUrl";

    private static final String TEST_PARTY_ID =
            "AgentIntCustomer";

    private static final String TEST_INVOICE_ID =
            "AGENT_INT_1000";

    private static final String EXPECTED_PARTY_NAME =
            "Euro Customer";

    private static final String TOOL_SERVICE_NAME =
            "getCustomerOverdueInvoices";

    private static final String AGENT_SERVICE_NAME =
            "analyseCustomerAccount";

    private static final String EXPECTED_FINAL_SUMMARY =
            "Integration summary accepted.";

    private static final String EXPECTED_DUE_DATE =
            "2006-04-25T23:59:59.000+10:00";

    private static final TimeZone SYDNEY_TIME_ZONE =
            TimeZone.getTimeZone(
                    "Australia/Sydney");

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(
                            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                            Locale.ROOT)
                    .withZone(
                            SYDNEY_TIME_ZONE.toZoneId());

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper();

    /**
     * Exact canonical representation that must cross the model boundary.
     *
     * <p>Field ordering is intentional and follows the configured immutable
     * AgentToolContract.</p>
     */
    private static final String EXPECTED_CANONICAL_TOOL_RESULT =
            "{"
            + "\"partyName\":\"Euro Customer\","
            + "\"invoicePaymentInfoList\":[{"
            + "\"invoiceId\":\"AGENT_INT_1000\","
            + "\"amount\":\"20.00\","
            + "\"paidAmount\":\"0.00\","
            + "\"outstandingAmount\":\"20.00\","
            + "\"dueDate\":\"2006-04-25T23:59:59.000+10:00\""
            + "}]"
            + "}";

    private String previousBaseUrl;

    private TimeZone previousDefaultTimeZone;

    private HttpServer fakeLlmServer;

    private ExecutorService fakeLlmExecutor;

    private final AtomicInteger requestSequence =
            new AtomicInteger();

    private final AtomicReference<Throwable> handlerFailure =
            new AtomicReference<>();

    private final List<String> capturedRequests =
            new CopyOnWriteArrayList<>();

    /**
     * Establishes deterministic timezone semantics and starts the local
     * OpenAI-compatible endpoint.
     *
     * <p>The existing OFBiz payment service derives invoice payment due dates
     * through UtilDateTime.getDayEnd without an explicit timezone argument.
     * Therefore the JVM default timezone is pinned to Australia/Sydney for this
     * integration test and restored afterwards.</p>
     *
     * <p>This does not replace the Agent semantic-boundary timezone contract.
     * AgentServiceEngine still receives the authoritative timeZone explicitly
     * in its service context, and AgentValueCodec remains responsible for
     * model-bound date-time conversion.</p>
     *
     * @throws IOException if the deterministic HTTP endpoint cannot be started
     */
    @BeforeEach
    public void setUp()
            throws IOException {

        previousDefaultTimeZone =
                TimeZone.getDefault();

        TimeZone.setDefault(
                SYDNEY_TIME_ZONE);

        previousBaseUrl =
                UtilProperties.getPropertyValue(
                        PROPERTY_RESOURCE,
                        BASE_URL_PROPERTY);

        InetSocketAddress address =
                new InetSocketAddress(
                        "127.0.0.1",
                        0);

        fakeLlmServer =
                HttpServer.create(
                        address,
                        0);

        fakeLlmExecutor =
                Executors.newSingleThreadExecutor();

        fakeLlmServer.setExecutor(
                fakeLlmExecutor);

        fakeLlmServer.createContext(
                "/v1/chat/completions",
                this::handleChatCompletion);

        fakeLlmServer.start();

        String fakeBaseUrl =
                "http://127.0.0.1:"
                + fakeLlmServer.getAddress()
                        .getPort()
                + "/v1";

        UtilProperties.setPropertyValueInMemory(
                PROPERTY_RESOURCE,
                BASE_URL_PROPERTY,
                fakeBaseUrl);
    }

    /**
     * Restores all process-level state changed by the integration test.
     */
    @AfterEach
    public void tearDown() {

        if (previousBaseUrl != null) {
            UtilProperties.setPropertyValueInMemory(
                    PROPERTY_RESOURCE,
                    BASE_URL_PROPERTY,
                    previousBaseUrl);
        }

        if (fakeLlmServer != null) {
            fakeLlmServer.stop(
                    0);
        }

        if (fakeLlmExecutor != null) {
            fakeLlmExecutor.shutdownNow();
        }

        if (previousDefaultTimeZone != null) {
            TimeZone.setDefault(
                    previousDefaultTimeZone);
        }
    }

    /**
     * Executes the complete agent service and proves that the real second model
     * request contains the governed canonical representation of the real OFBiz
     * tool result.
     *
     * @throws Exception if service execution or assertion inspection fails
     */
    @Test
    public void testAnalyseCustomerAccountUsesCanonicalToolResult()
            throws Exception {

        GenericValue userLogin =
                getUserLogin();

        assertNotNull(
                userLogin);

        Map<String, Object> context =
                buildAuthoritativeContext(
                        userLogin);

        /*
         * Validate the dedicated integration fixture through the same real
         * governed OFBiz business service used by AgentServiceEngine.
         *
         * This gives failures in the fixture/business-service layer a clear
         * diagnostic point rather than allowing them to masquerade as semantic
         * boundary failures.
         */
        assertIntegrationFixture(
                context);

        boolean transactionPresentBefore =
                isTransactionInPlace();

        String transactionStatusBefore =
                transactionStatus();

        /*
         * This is the integration operation under test.
         *
         * It passes through the OFBiz Service Dispatcher and therefore selects
         * AgentServiceEngine from the actual service model.
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
                EXPECTED_FINAL_SUMMARY,
                result.get(
                        "summary"));

        assertNull(handlerFailure.get(), () ->
                "Fake LLM endpoint failed: "
                        + handlerFailure.get());

        /*
         * V1 requires exactly two model calls:
         *
         * 1. select the single governed tool;
         * 2. consume the governed tool result and produce the summary.
         */
        assertEquals(
                2,
                requestSequence.get());

        assertEquals(
                2,
                capturedRequests.size());

        /*
         * AgentServiceEngine must leave the caller transaction state exactly
         * as it found it.
         */
        assertEquals(
                transactionPresentBefore,
                isTransactionInPlace());

        assertEquals(
                transactionStatusBefore,
                transactionStatus());

        JsonNode firstRequest =
                OBJECT_MAPPER.readTree(
                        capturedRequests.get(
                                0));

        JsonNode secondRequest =
                OBJECT_MAPPER.readTree(
                        capturedRequests.get(
                                1));

        assertInitialRequest(
                firstRequest);

        assertFinalRequest(
                secondRequest);
    }

    /**
     * Constructs the authoritative OFBiz execution context.
     *
     * <p>partyId, userLogin and timeZone originate outside model control.</p>
     *
     * @param userLogin authenticated OFBiz user
     * @return service context
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

    /**
     * Proves that AgentIntegrationTestData.xml produces the exact business
     * scenario expected by this integration test.
     *
     * <p>This deliberately calls the real governed OFBiz service rather than
     * querying invoice or party tables directly.</p>
     *
     * @param context authoritative service context
     * @throws Exception if service execution or inspection fails
     */
    private void assertIntegrationFixture(
            Map<String, Object> context)
            throws Exception {

        Map<String, Object> rawResult =
                getDispatcher().runSync(
                        TOOL_SERVICE_NAME,
                        context);

        assertFalse(
                ServiceUtil.isError(
                        rawResult),
                ServiceUtil.getErrorMessage(
                        rawResult));

        assertFalse(
                ServiceUtil.isFailure(
                        rawResult),
                ServiceUtil.getErrorMessage(
                        rawResult));

        /*
         * Standard Service Engine metadata exists at the raw OFBiz boundary.
         * The semantic contract must subsequently prevent it crossing the
         * model boundary.
         */
        assertTrue(
                rawResult.containsKey(
                        "responseMessage"));

        assertEquals(
                EXPECTED_PARTY_NAME,
                rawResult.get(
                        "partyName"));

        Object rawInvoices =
                rawResult.get(
                        "invoicePaymentInfoList");

        if (!(rawInvoices instanceof List<?>)) {
            fail(
                    "invoicePaymentInfoList must be a List");
        }

        List<?> invoices =
                (List<?>) rawInvoices;

        assertEquals(
                1,
                invoices.size());

        Object rawInvoice =
                invoices.get(
                        0);

        if (!(rawInvoice instanceof Map<?, ?>)) {
            fail(
                    "invoicePaymentInfoList entry must be a Map");
        }

        Map<?, ?> invoice =
                (Map<?, ?>) rawInvoice;

        assertEquals(
                TEST_INVOICE_ID,
                invoice.get(
                        "invoiceId"));

        assertBigDecimalValue(
                invoice.get(
                        "amount"),
                "20");

        assertBigDecimalValue(
                invoice.get(
                        "paidAmount"),
                "0");

        assertBigDecimalValue(
                invoice.get(
                        "outstandingAmount"),
                "20");

        Object rawDueDate =
                invoice.get(
                        "dueDate");

        if (!(rawDueDate instanceof Timestamp)) {
            fail(
                    "dueDate must be a Timestamp");
        }

        Timestamp dueDateTimestamp =
                (Timestamp) rawDueDate;

        String dueDate =
                DATE_TIME_FORMATTER.format(
                        dueDateTimestamp.toInstant());

        assertEquals(
                EXPECTED_DUE_DATE,
                dueDate);
    }

    /**
     * Asserts the numeric value of one raw OFBiz BigDecimal without imposing a
     * lexical representation on the raw service layer.
     *
     * <p>The lexical representation is an Agent semantic-boundary concern and
     * is asserted separately against the actual second model request.</p>
     *
     * @param value raw service value
     * @param expected expected numeric value
     */
    private static void assertBigDecimalValue(
            Object value,
            String expected) {

        if (!(value instanceof BigDecimal)) {
            fail(
                    "Expected BigDecimal but received "
                    + (value == null
                            ? "null"
                            : value.getClass()
                                    .getName()));
        }

        BigDecimal actualValue =
                (BigDecimal) value;

        assertEquals(
                0,
                actualValue.compareTo(
                        new BigDecimal(
                                expected)));
    }

    /**
     * Verifies that the first request exposes exactly the governed conceptual
     * tool and gives the model no ability to supply or change partyId.
     *
     * @param request first captured Chat Completions request
     */
    private static void assertInitialRequest(
            JsonNode request) {

        assertTrue(
                request.isObject());

        JsonNode tools =
                request.get(
                        "tools");

        assertNotNull(
                tools);

        assertTrue(
                tools.isArray());

        assertEquals(
                1,
                tools.size());

        JsonNode function =
                tools.get(
                                0)
                        .path(
                                "function");

        assertEquals(
                TOOL_SERVICE_NAME,
                function.path(
                                "name")
                        .asText());

        JsonNode parameters =
                function.path(
                        "parameters");

        assertEquals(
                "object",
                parameters.path(
                                "type")
                        .asText());

        assertTrue(
                parameters.path(
                                "properties")
                        .isObject());

        assertEquals(
                0,
                parameters.path(
                                "properties")
                        .size());

        assertFalse(
                parameters.path(
                                "additionalProperties")
                        .asBoolean(
                                true));

        assertEquals(
                TOOL_SERVICE_NAME,
                request.path(
                                "tool_choice")
                        .path(
                                "function")
                        .path(
                                "name")
                        .asText());

        /*
         * The authoritative customer identifier must not be present anywhere
         * in the model request.
         */
        assertFalse(
                request.toString()
                        .contains(
                                TEST_PARTY_ID));
    }

    /**
     * Verifies the complete second model request.
     *
     * <p>The critical assertion compares the actual tool-message content sent
     * over HTTP with the exact canonical JSON required by the semantic
     * contract.</p>
     *
     * @param request second captured Chat Completions request
     * @throws Exception if nested canonical JSON cannot be parsed
     */
    private static void assertFinalRequest(
            JsonNode request)
            throws Exception {

        assertTrue(
                request.isObject());

        /*
         * No tools are exposed in the second V1 request. This prevents
         * autonomous tool iteration.
         */
        assertFalse(
                request.has(
                        "tools"));

        JsonNode assistantMessage =
                findMessageByRole(
                        request,
                        "assistant");

        JsonNode toolCalls =
                assistantMessage.path(
                        "tool_calls");

        assertTrue(
                toolCalls.isArray());

        assertEquals(
                1,
                toolCalls.size());

        JsonNode assistantToolCall =
                toolCalls.get(
                        0);

        assertEquals(
                TOOL_SERVICE_NAME,
                assistantToolCall.path(
                                "function")
                        .path(
                                "name")
                        .asText());

        assertEquals(
                "{}",
                assistantToolCall.path(
                                "function")
                        .path(
                                "arguments")
                        .asText());

        JsonNode toolMessage =
                findMessageByRole(
                        request,
                        "tool");

        assertEquals(
                assistantToolCall.path(
                                "id")
                        .asText(),
                toolMessage.path(
                                "tool_call_id")
                        .asText());

        String toolResultJson =
                toolMessage.path(
                                "content")
                        .asText();

        /*
         * PRIMARY INTEGRATION ASSERTION
         *
         * This is the actual model-facing string that crossed the HTTP
         * boundary in the second LLM request.
         */
        assertEquals(
                EXPECTED_CANONICAL_TOOL_RESULT,
                toolResultJson);

        /*
         * Service-engine metadata must not cross the governed semantic
         * boundary.
         */
        assertFalse(
                toolResultJson.contains(
                        "responseMessage"));

        /*
         * Scientific notation or unscaled monetary output must not cross the
         * boundary.
         */
        assertFalse(
                toolResultJson.contains(
                        "2E+1"));

        JsonNode canonicalResult =
                OBJECT_MAPPER.readTree(
                        toolResultJson);

        assertEquals(
                EXPECTED_PARTY_NAME,
                canonicalResult.path(
                                "partyName")
                        .asText());

        assertFalse(
                canonicalResult.has(
                        "responseMessage"));

        JsonNode invoices =
                canonicalResult.path(
                        "invoicePaymentInfoList");

        assertTrue(
                invoices.isArray());

        assertEquals(
                1,
                invoices.size());

        JsonNode invoice =
                invoices.get(
                        0);

        /*
         * The contract contains exactly five invoice fields.
         */
        assertEquals(
                5,
                invoice.size());

        assertEquals(
                TEST_INVOICE_ID,
                invoice.path(
                                "invoiceId")
                        .asText());

        assertTrue(
                invoice.path(
                                "amount")
                        .isTextual());

        assertEquals(
                "20.00",
                invoice.path(
                                "amount")
                        .asText());

        assertTrue(
                invoice.path(
                                "paidAmount")
                        .isTextual());

        assertEquals(
                "0.00",
                invoice.path(
                                "paidAmount")
                        .asText());

        assertTrue(
                invoice.path(
                                "outstandingAmount")
                        .isTextual());

        assertEquals(
                "20.00",
                invoice.path(
                                "outstandingAmount")
                        .asText());

        assertTrue(
                invoice.path(
                                "dueDate")
                        .isTextual());

        assertEquals(
                EXPECTED_DUE_DATE,
                invoice.path(
                                "dueDate")
                        .asText());
    }

    /**
     * Finds one Chat Completions message by role.
     *
     * @param request captured request
     * @param role required OpenAI message role
     * @return matching message
     */
    private static JsonNode findMessageByRole(
            JsonNode request,
            String role) {

        JsonNode messages =
                request.path(
                        "messages");

        if (!messages.isArray()) {
            fail(
                    "Captured LLM request does not contain a messages array");
        }

        for (JsonNode message : messages) {

            if (role.equals(
                    message.path(
                                    "role")
                            .asText())) {

                return message;
            }
        }

        fail(
                "Captured LLM request does not contain role ["
                + role
                + "]");

        return null;
    }

    /**
     * Handles one deterministic OpenAI-compatible Chat Completions request.
     *
     * <p>The first response requests the one registered OFBiz tool. The second
     * returns a deterministic final summary.</p>
     *
     * @param exchange HTTP exchange
     */
    private void handleChatCompletion(
            HttpExchange exchange) {

        try {
            if (!"POST".equals(
                    exchange.getRequestMethod())) {

                sendJson(
                        exchange,
                        405,
                        "{\"error\":\"POST required\"}");

                return;
            }

            String requestBody =
                    new String(
                            exchange.getRequestBody()
                                    .readAllBytes(),
                            StandardCharsets.UTF_8);

            capturedRequests.add(
                    requestBody);

            int sequence =
                    requestSequence.incrementAndGet();

            if (sequence == 1) {

                sendJson(
                        exchange,
                        200,
                        firstModelResponse());

                return;
            }

            if (sequence == 2) {

                sendJson(
                        exchange,
                        200,
                        secondModelResponse());

                return;
            }

            sendJson(
                    exchange,
                    500,
                    "{\"error\":\"Unexpected additional LLM request\"}");

        } catch (Throwable e) {

            handlerFailure.compareAndSet(
                    null,
                    e);

            try {
                sendJson(
                        exchange,
                        500,
                        "{\"error\":\"Fake LLM endpoint failure\"}");

            } catch (IOException ignored) {
                /*
                 * The original failure is retained in handlerFailure.
                 */
            }
        }
    }

    /**
     * Returns the deterministic first model response.
     *
     * <p>The model requests exactly one permitted function and supplies an
     * empty JSON argument object.</p>
     *
     * @return OpenAI-compatible Chat Completions response
     */
    private static String firstModelResponse() {

        return """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [
                          {
                            "id": "integration-tool-call-1",
                            "type": "function",
                            "function": {
                              "name": "getCustomerOverdueInvoices",
                              "arguments": "{}"
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """;
    }

    /**
     * Returns the deterministic second model response.
     *
     * @return OpenAI-compatible Chat Completions response
     */
    private static String secondModelResponse() {

        return """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "Integration summary accepted."
                      }
                    }
                  ]
                }
                """;
    }

    /**
     * Sends one deterministic JSON response.
     *
     * @param exchange HTTP exchange
     * @param statusCode HTTP response code
     * @param body JSON response body
     * @throws IOException if the response cannot be written
     */
    private static void sendJson(
            HttpExchange exchange,
            int statusCode,
            String body)
            throws IOException {

        byte[] responseBytes =
                body.getBytes(
                        StandardCharsets.UTF_8);

        exchange.getResponseHeaders()
                .set(
                        "Content-Type",
                        "application/json");

        exchange.sendResponseHeaders(
                statusCode,
                responseBytes.length);

        try {
            exchange.getResponseBody()
                    .write(
                            responseBytes);

        } finally {
            exchange.close();
        }
    }

    /**
     * Reads whether an OFBiz transaction is currently associated with this
     * thread.
     *
     * @return transaction-presence state
     * @throws GenericTransactionException if transaction state cannot be read
     */
    private static boolean isTransactionInPlace()
            throws GenericTransactionException {

        return TransactionUtil.isTransactionInPlace();
    }

    /**
     * Reads the current OFBiz transaction status.
     *
     * @return transaction status description
     * @throws GenericTransactionException if transaction state cannot be read
     */
    private static String transactionStatus()
            throws GenericTransactionException {

        return TransactionUtil.getStatusString();
    }
}
