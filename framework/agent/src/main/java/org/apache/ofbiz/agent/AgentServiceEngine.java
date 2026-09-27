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

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.transaction.GenericTransactionException;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ModelService;
import org.apache.ofbiz.service.ServiceDispatcher;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.service.engine.GenericAsyncEngine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * OFBiz Service Engine for governed AI agent execution.
 *
 * <p>The engine deliberately separates agent reasoning from business-data
 * access. Agent metadata is read through the Entity Engine, while business
 * operations are performed only by declared OFBiz services invoked through
 * {@link LocalDispatcher}.</p>
 */
public final class AgentServiceEngine extends GenericAsyncEngine {

    private static final String MODULE = AgentServiceEngine.class.getName();
    private static final String PROPERTY_RESOURCE = "agent";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final int MAX_COMPLETION_TOKENS = 512;

    /**
     * Constructor required by the OFBiz GenericEngineFactory.
     *
     * @param dispatcher OFBiz service dispatcher
     */
    public AgentServiceEngine(ServiceDispatcher dispatcher) {
        super(dispatcher);
    }

    @Override
    public void runSyncIgnore(
            String localName,
            ModelService modelService,
            Map<String, Object> context) throws GenericServiceException {

        runSync(localName, modelService, context);
    }

    @Override
    public Map<String, Object> runSync(
            String localName,
            ModelService modelService,
            Map<String, Object> context) throws GenericServiceException {

        rejectActiveTransaction();

        if (modelService == null) {
            throw new GenericServiceException("Agent service model must not be null");
        }

        if (context == null) {
            throw new GenericServiceException("Agent service context must not be null");
        }

        DispatchContext dctx = getDispatcher().getLocalContext(localName);

        if (dctx == null) {
            throw new GenericServiceException(
                    "Unable to obtain DispatchContext for agent service");
        }

        String agentId = modelService.getInvoke();

        if (agentId == null || agentId.isBlank()) {
            throw new GenericServiceException(
                    "Agent service [" + modelService.getName()
                    + "] does not define an agent identifier in invoke");
        }

        String partyId = (String) context.get("partyId");

        if (partyId == null || partyId.isBlank()) {
            throw new GenericServiceException(
                    "Agent service requires a non-empty partyId");
        }

        Object userLogin = context.get("userLogin");

        if (userLogin == null) {
            throw new GenericServiceException(
                    "Agent service requires the authenticated userLogin");
        }

        try {
            GenericValue agentDefinition = loadAgentDefinition(dctx, agentId);
            List<GenericValue> agentTools = loadAgentTools(dctx, agentId);

            if (agentTools.size() != 1) {
                throw new GenericServiceException(
                        "Agent [" + agentId
                        + "] must have exactly one declared tool in V1");
            }

            String systemPrompt = agentDefinition.getString("systemPrompt");
            String toolServiceName = agentTools.get(0).getString("serviceName");

            if (systemPrompt == null || systemPrompt.isBlank()) {
                throw new GenericServiceException(
                        "Agent [" + agentId + "] has no system prompt");
            }

            if (toolServiceName == null || toolServiceName.isBlank()) {
                throw new GenericServiceException(
                        "Agent [" + agentId + "] has an invalid tool definition");
            }

            String baseUrl = UtilProperties.getPropertyValue(
                    PROPERTY_RESOURCE,
                    "agent.llm.baseUrl");

            String model = UtilProperties.getPropertyValue(
                    PROPERTY_RESOURCE,
                    "agent.llm.model");

            long connectTimeoutMillis = UtilProperties.getPropertyAsLong(
                    PROPERTY_RESOURCE,
                    "agent.llm.connectTimeoutMillis",
                    5000L);

            long requestTimeoutMillis = UtilProperties.getPropertyAsLong(
                    PROPERTY_RESOURCE,
                    "agent.llm.requestTimeoutMillis",
                    120000L);

            if (baseUrl == null || baseUrl.isBlank()) {
                throw new GenericServiceException(
                        "agent.llm.baseUrl is not configured");
            }

            if (model == null || model.isBlank()) {
                throw new GenericServiceException(
                        "agent.llm.model is not configured");
            }

            OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                    baseUrl,
                    connectTimeoutMillis,
                    requestTimeoutMillis);

            ObjectNode firstRequest = buildInitialRequest(
                    model,
                    systemPrompt,
                    toolServiceName);

            JsonNode firstResponse =
                    client.createChatCompletion(firstRequest);

            ToolCall toolCall = extractRequiredToolCall(
                    firstResponse,
                    toolServiceName);

            Map<String, Object> toolContext =
                    buildToolContext(context, partyId, userLogin);

            LocalDispatcher localDispatcher = dctx.getDispatcher();

            Map<String, Object> toolResult =
                    localDispatcher.runSync(
                            toolServiceName,
                            toolContext);

            if (ServiceUtil.isError(toolResult)
                    || ServiceUtil.isFailure(toolResult)) {
                throw new GenericServiceException(
                        "Agent tool [" + toolServiceName + "] failed: "
                        + ServiceUtil.getErrorMessage(toolResult));
            }

            String toolResultJson = buildToolResultJson(toolResult);

            ObjectNode finalRequest = buildFinalRequest(
                    model,
                    systemPrompt,
                    toolCall,
                    toolResultJson);

            JsonNode finalResponse =
                    client.createChatCompletion(finalRequest);

            String summary = extractFinalSummary(finalResponse);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("summary", summary);

            return result;

        } catch (GenericServiceException e) {
            throw e;
        } catch (GenericEntityException e) {
            Debug.logError(e, "Unable to load agent metadata", MODULE);
            throw new GenericServiceException(
                    "Unable to load agent metadata", e);
        } catch (IOException e) {
            Debug.logError(e, "Agent LLM request failed", MODULE);
            throw new GenericServiceException(
                    "Agent LLM request failed", e);
        } catch (RuntimeException e) {
            Debug.logError(e, "Unexpected agent execution failure", MODULE);
            throw new GenericServiceException(
                    "Unexpected agent execution failure", e);
        }
    }

    /**
     * Prevents an external LLM call from occurring while an OFBiz transaction
     * is active on the current thread.
     */
    private static void rejectActiveTransaction()
            throws GenericServiceException {

        try {
            if (TransactionUtil.isTransactionInPlace()) {
                throw new GenericServiceException(
                        "Agent execution is not permitted inside an active transaction");
            }
        } catch (GenericTransactionException e) {
            throw new GenericServiceException(
                    "Unable to determine transaction state before agent execution",
                    e);
        }
    }

    /**
     * Loads the durable agent definition.
     *
     * <p>This Entity Engine access is restricted to agent infrastructure
     * metadata. Business entities are not queried by the agent engine.</p>
     */
    private static GenericValue loadAgentDefinition(
            DispatchContext dctx,
            String agentId) throws GenericEntityException, GenericServiceException {

        GenericValue agentDefinition = EntityQuery.use(dctx.getDelegator())
                .from("AgentDefinition")
                .where("agentId", agentId)
                .queryOne();

        if (agentDefinition == null) {
            throw new GenericServiceException(
                    "Agent definition [" + agentId + "] was not found");
        }

        return agentDefinition;
    }

    /**
     * Loads the OFBiz services explicitly permitted for this agent.
     */
    private static List<GenericValue> loadAgentTools(
            DispatchContext dctx,
            String agentId) throws GenericEntityException {

        return EntityQuery.use(dctx.getDelegator())
                .from("AgentTool")
                .where("agentId", agentId)
                .orderBy("serviceName")
                .queryList();
    }

    /**
     * Constructs the first LLM request.
     *
     * <p>The model receives a conceptual zero-argument tool. It never receives
     * partyId as a tool argument. Customer scope remains under OFBiz control.</p>
     */
    private static ObjectNode buildInitialRequest(
            String model,
            String systemPrompt,
            String toolServiceName) {

        ObjectNode request = OBJECT_MAPPER.createObjectNode();

        request.put("model", model);
        request.put("temperature", 0.0);
        request.put("max_tokens", MAX_COMPLETION_TOKENS);

        ArrayNode messages = request.putArray("messages");

        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemPrompt);

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put(
                "content",
                "Analyse the overdue outstanding sales invoices "
                + "for the customer selected in OFBiz. "
                + "Use the provided tool before answering.");

        ArrayNode tools = request.putArray("tools");

        ObjectNode tool = tools.addObject();
        tool.put("type", "function");

        ObjectNode function = tool.putObject("function");
        function.put("name", toolServiceName);
        function.put(
                "description",
                "Retrieve overdue outstanding sales invoices "
                + "for the customer selected in OFBiz.");

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        parameters.putObject("properties");
        parameters.put("additionalProperties", false);

        ObjectNode toolChoice = request.putObject("tool_choice");
        toolChoice.put("type", "function");
        toolChoice.putObject("function")
                .put("name", toolServiceName);

        return request;
    }

    /**
     * Validates the first model response against the V1 execution protocol.
     */
    private static ToolCall extractRequiredToolCall(
            JsonNode response,
            String allowedServiceName) throws GenericServiceException {

        JsonNode message = extractSingleMessage(response);

        JsonNode toolCalls = message.get("tool_calls");

        if (toolCalls == null
                || !toolCalls.isArray()
                || toolCalls.size() != 1) {
            throw new GenericServiceException(
                    "Agent must return exactly one tool call");
        }

        JsonNode toolCall = toolCalls.get(0);

        String type = textValue(toolCall, "type");

        if (!"function".equals(type)) {
            throw new GenericServiceException(
                    "Agent returned a non-function tool call");
        }

        String toolCallId = textValue(toolCall, "id");

        if (toolCallId == null || toolCallId.isBlank()) {
            throw new GenericServiceException(
                    "Agent tool call does not contain an id");
        }

        JsonNode function = toolCall.get("function");

        if (function == null || !function.isObject()) {
            throw new GenericServiceException(
                    "Agent tool call does not contain a function");
        }

        String functionName = textValue(function, "name");

        if (!allowedServiceName.equals(functionName)) {
            throw new GenericServiceException(
                    "Agent attempted to invoke undeclared tool ["
                    + functionName + "]");
        }

        String arguments = textValue(function, "arguments");

        if (arguments == null || arguments.isBlank()) {
            throw new GenericServiceException(
                    "Agent tool call arguments are missing");
        }

        final JsonNode parsedArguments;

        try {
            parsedArguments = OBJECT_MAPPER.readTree(arguments);
        } catch (IOException e) {
            throw new GenericServiceException(
                    "Agent tool call arguments are not valid JSON",
                    e);
        }

        if (!parsedArguments.isObject()
                || parsedArguments.size() != 0) {
            throw new GenericServiceException(
                    "Agent tool call must not supply arguments");
        }

        return new ToolCall(
                toolCallId,
                functionName,
                arguments);
    }

    /**
     * Creates the actual OFBiz business-service context.
     *
     * <p>The authoritative partyId and authenticated userLogin are injected
     * from the original OFBiz service call, not from model output.</p>
     */
    private static Map<String, Object> buildToolContext(
            Map<String, Object> outerContext,
            String partyId,
            Object userLogin) {

        Map<String, Object> toolContext = new HashMap<>();

        toolContext.put("partyId", partyId);
        toolContext.put("userLogin", userLogin);

        if (outerContext.get("locale") != null) {
            toolContext.put("locale", outerContext.get("locale"));
        }

        if (outerContext.get("timeZone") != null) {
            toolContext.put("timeZone", outerContext.get("timeZone"));
        }

        return toolContext;
    }

    /**
     * Serializes only the declared business outputs from the OFBiz tool.
     */
    private static String buildToolResultJson(
            Map<String, Object> toolResult) throws IOException {

        ObjectNode businessResult = OBJECT_MAPPER.createObjectNode();

        Object partyName = toolResult.get("partyName");

        if (partyName == null) {
            businessResult.putNull("partyName");
        } else {
            businessResult.put("partyName", partyName.toString());
        }

        Object invoicePaymentInfoList =
                toolResult.get("invoicePaymentInfoList");

        if (invoicePaymentInfoList == null) {
            businessResult.putArray("invoicePaymentInfoList");
        } else {
            businessResult.set(
                    "invoicePaymentInfoList",
                    OBJECT_MAPPER.valueToTree(invoicePaymentInfoList));
        }

        return OBJECT_MAPPER.writeValueAsString(businessResult);
    }

    /**
     * Constructs the second and final LLM request.
     *
     * <p>No tools are offered on this request, so V1 cannot enter an
     * autonomous tool loop.</p>
     */
    private static ObjectNode buildFinalRequest(
            String model,
            String systemPrompt,
            ToolCall toolCall,
            String toolResultJson) {

        ObjectNode request = OBJECT_MAPPER.createObjectNode();

        request.put("model", model);
        request.put("temperature", 0.0);
        request.put("max_tokens", MAX_COMPLETION_TOKENS);

        ArrayNode messages = request.putArray("messages");

        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemPrompt);

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put(
                "content",
                "Analyse the overdue outstanding sales invoices "
                + "for the customer selected in OFBiz. "
                + "Use the provided tool before answering.");

        ObjectNode assistantMessage = messages.addObject();
        assistantMessage.put("role", "assistant");
        assistantMessage.putNull("content");

        ArrayNode toolCalls = assistantMessage.putArray("tool_calls");

        ObjectNode call = toolCalls.addObject();
        call.put("id", toolCall.id());
        call.put("type", "function");

        ObjectNode function = call.putObject("function");
        function.put("name", toolCall.functionName());
        function.put("arguments", toolCall.arguments());

        ObjectNode toolMessage = messages.addObject();
        toolMessage.put("role", "tool");
        toolMessage.put("tool_call_id", toolCall.id());
        toolMessage.put("content", toolResultJson);

        return request;
    }

    /**
     * Extracts the final textual answer.
     */
    private static String extractFinalSummary(
            JsonNode response) throws GenericServiceException {

        JsonNode message = extractSingleMessage(response);

        JsonNode toolCalls = message.get("tool_calls");

        if (toolCalls != null
                && toolCalls.isArray()
                && toolCalls.size() > 0) {
            throw new GenericServiceException(
                    "Agent attempted an additional tool call");
        }

        String content = textValue(message, "content");

        if (content == null || content.isBlank()) {
            throw new GenericServiceException(
                    "Agent returned an empty final response");
        }

        return content.trim();
    }

    /**
     * Extracts exactly one assistant message from a Chat Completions response.
     */
    private static JsonNode extractSingleMessage(
            JsonNode response) throws GenericServiceException {

        if (response == null || !response.isObject()) {
            throw new GenericServiceException(
                    "LLM response is not a JSON object");
        }

        JsonNode choices = response.get("choices");

        if (choices == null
                || !choices.isArray()
                || choices.size() != 1) {
            throw new GenericServiceException(
                    "LLM response must contain exactly one choice");
        }

        JsonNode message = choices.get(0).get("message");

        if (message == null || !message.isObject()) {
            throw new GenericServiceException(
                    "LLM response does not contain an assistant message");
        }

        return message;
    }

    /**
     * Returns a textual JSON field or null.
     */
    private static String textValue(
            JsonNode node,
            String fieldName) {

        if (node == null) {
            return null;
        }

        JsonNode value = node.get(fieldName);

        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }

        return value.asText();
    }

    /**
     * Immutable representation of the single permitted V1 tool call.
     */
    private record ToolCall(
            String id,
            String functionName,
            String arguments) {
    }
}
