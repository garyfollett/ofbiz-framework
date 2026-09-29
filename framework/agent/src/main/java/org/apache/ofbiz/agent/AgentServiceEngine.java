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
import java.util.TimeZone;

import javax.transaction.Status;
import javax.transaction.Transaction;

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
 * <p>The agent engine is the deterministic execution coordinator for one
 * invocation of an OFBiz agent service. OFBiz remains authoritative for
 * identity, authorization, service validation, transaction semantics,
 * business data, business operations, ECAs and service callbacks.</p>
 *
 * <p>The model is deliberately treated as an untrusted reasoning component.
 * It can request only tools declared for the registered agent. Authoritative
 * execution scope, including {@code partyId} and {@code userLogin}, is supplied
 * by OFBiz and cannot be widened or replaced by model output.</p>
 *
 * <p>External LLM/network communication is prohibited while an OFBiz
 * transaction is associated with the current execution thread. If the caller
 * entered the agent service with an existing transaction, this engine suspends
 * the transaction for the complete agent orchestration and restores it before
 * returning to the Service Dispatcher.</p>
 *
 * <p>Business operations are performed only through {@link LocalDispatcher}.
 * The agent engine does not query or mutate OFBiz business entities directly.
 * Entity Engine access performed here is restricted to agent infrastructure
 * metadata.</p>
 *
 * <p>{@link AgentTrace} observes execution boundaries, payloads, timing and
 * transaction state. Tracing is non-authoritative: execution invariants remain
 * enforced by this engine independently of trace output.</p>
 */
public final class AgentServiceEngine extends GenericAsyncEngine {

    private static final String MODULE = AgentServiceEngine.class.getName();

    private static final String PROPERTY_RESOURCE = "agent";

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper();

    private static final int MAX_COMPLETION_TOKENS = 512;

    /**
     * Constructor required by the OFBiz GenericEngineFactory.
     *
     * @param dispatcher OFBiz service dispatcher
     */
    public AgentServiceEngine(
            ServiceDispatcher dispatcher) {

        super(dispatcher);
    }

    @Override
    public void runSyncIgnore(
            String localName,
            ModelService modelService,
            Map<String, Object> context)
            throws GenericServiceException {

        runSync(
                localName,
                modelService,
                context);
    }

    /**
     * Executes one governed agent invocation.
     *
     * <p>The lifecycle is deliberately bounded:</p>
     *
     * <pre>
     * validate
     *   -> establish trace
     *   -> suspend inherited transaction
     *   -> load agent metadata
     *   -> first LLM call
     *   -> validate one tool call
     *   -> invoke one governed OFBiz service
     *   -> second LLM call
     *   -> validate final response
     *   -> restore inherited transaction
     *   -> return
     * </pre>
     *
     * <p>V1 intentionally does not implement autonomous tool loops,
     * multi-agent delegation, memory, retries, human approval or policy
     * evaluation.</p>
     */
    @Override
    public Map<String, Object> runSync(
            String localName,
            ModelService modelService,
            Map<String, Object> context)
            throws GenericServiceException {

        /*
         * Perform the minimum validation necessary before creating the trace.
         *
         * We need a valid service and agent identity before agent-scoped
         * correlation fields can be installed.
         */
        if (modelService == null) {
            throw new GenericServiceException(
                    "Agent service model must not be null");
        }

        if (context == null) {
            throw new GenericServiceException(
                    "Agent service context must not be null");
        }

        DispatchContext dctx =
                getDispatcher().getLocalContext(
                        localName);

        if (dctx == null) {
            throw new GenericServiceException(
                    "Unable to obtain DispatchContext for agent service");
        }

        String agentId =
                modelService.getInvoke();

        if (agentId == null
                || agentId.isBlank()) {

            throw new GenericServiceException(
                    "Agent service ["
                    + modelService.getName()
                    + "] does not define an agent identifier in invoke");
        }

        String partyId =
                (String) context.get(
                        "partyId");

        if (partyId == null
                || partyId.isBlank()) {

            throw new GenericServiceException(
                    "Agent service requires a non-empty partyId");
        }

        Object userLogin =
                context.get(
                        "userLogin");

        if (userLogin == null) {
            throw new GenericServiceException(
                    "Agent service requires the authenticated userLogin");
        }

        AgentTrace trace =
                AgentTrace.start(
                        modelService.getName(),
                        agentId);

        /*
         * Any transaction inherited from the caller is stored here after it
         * has been suspended. It is restored in the outer finally block.
         */
        Transaction parentTransaction = null;

        /*
         * Preserve the execution failure so that:
         *
         * 1. transaction restoration is attempted before terminal tracing;
         * 2. a restoration failure can become the terminal failure; and
         * 3. the original execution failure can be retained as a suppressed
         *    exception if restoration itself fails.
         */
        Throwable executionFailure = null;

        /*
         * AGENT_SUCCESS must mean that both the agent execution and parent
         * transaction restoration completed successfully.
         */
        boolean executionCompleted = false;

        try {
            /*
             * =============================================================
             * TRANSACTION ISOLATION
             * =============================================================
             */

            parentTransaction =
                    suspendParentTransaction(
                            trace);

            assertNoActiveTransaction(
                    trace,
                    "before agent metadata load");

            /*
             * =============================================================
             * AGENT METADATA
             * =============================================================
             */

            long metadataStart =
                    trace.mark();

            trace.diagnostic(
                    AgentTrace.METADATA_LOAD_START);

            GenericValue agentDefinition =
                    loadAgentDefinition(
                            dctx,
                            agentId);

            List<GenericValue> agentTools =
                    loadAgentTools(
                            dctx,
                            agentId);

            trace.diagnostic(
                    AgentTrace.METADATA_LOAD_END,
                    "durationMs",
                    AgentTrace.elapsedMillis(
                            metadataStart),
                    "toolCount",
                    agentTools.size());

            if (agentTools.size() != 1) {
                throw new GenericServiceException(
                        "Agent ["
                        + agentId
                        + "] must have exactly one declared tool in V1");
            }

            String systemPrompt =
                    agentDefinition.getString(
                            "systemPrompt");

            String toolServiceName =
                    agentTools.get(0).getString(
                            "serviceName");

            if (systemPrompt == null
                    || systemPrompt.isBlank()) {

                throw new GenericServiceException(
                        "Agent ["
                        + agentId
                        + "] has no system prompt");
            }

            if (toolServiceName == null
                    || toolServiceName.isBlank()) {

                throw new GenericServiceException(
                        "Agent ["
                        + agentId
                        + "] has an invalid tool definition");
            }

            /*
             * =============================================================
             * MODEL CONFIGURATION
             * =============================================================
             */

            String baseUrl =
                    UtilProperties.getPropertyValue(
                            PROPERTY_RESOURCE,
                            "agent.llm.baseUrl");

            String model =
                    UtilProperties.getPropertyValue(
                            PROPERTY_RESOURCE,
                            "agent.llm.model");

            long connectTimeoutMillis =
                    UtilProperties.getPropertyAsLong(
                            PROPERTY_RESOURCE,
                            "agent.llm.connectTimeoutMillis",
                            5000L);

            long requestTimeoutMillis =
                    UtilProperties.getPropertyAsLong(
                            PROPERTY_RESOURCE,
                            "agent.llm.requestTimeoutMillis",
                            120000L);

            if (baseUrl == null
                    || baseUrl.isBlank()) {

                throw new GenericServiceException(
                        "agent.llm.baseUrl is not configured");
            }

            if (model == null
                    || model.isBlank()) {

                throw new GenericServiceException(
                        "agent.llm.model is not configured");
            }

            OpenAiCompatibleClient client =
                    new OpenAiCompatibleClient(
                            baseUrl,
                            connectTimeoutMillis,
                            requestTimeoutMillis);

            /*
             * =============================================================
             * FIRST MODEL INTERACTION
             * =============================================================
             *
             * The first request exposes exactly one conceptual OFBiz tool.
             * The model is forced to call the tool and cannot supply partyId.
             */

            ObjectNode firstRequest =
                    buildInitialRequest(
                            model,
                            systemPrompt,
                            toolServiceName);

            JsonNode firstResponse =
                    callLlm(
                            trace,
                            client,
                            1,
                            model,
                            firstRequest);

            /*
             * =============================================================
             * TOOL-CALL VALIDATION
             * =============================================================
             */

            ToolCall toolCall =
                    extractRequiredToolCall(
                            firstResponse,
                            toolServiceName);

            trace.operational(
                    AgentTrace.TOOL_SELECTED,
                    "service",
                    toolCall.functionName(),
                    "toolCallId",
                    toolCall.id());

            /*
             * The model has no authority over the actual business-service
             * scope. partyId and userLogin come from the original OFBiz call.
             */
            Map<String, Object> toolContext =
                    buildToolContext(
                            context,
                            partyId,
                            userLogin);

            LocalDispatcher localDispatcher =
                    dctx.getDispatcher();

            /*
             * =============================================================
             * GOVERNED OFBIZ TOOL EXECUTION
             * =============================================================
             */

            assertNoActiveTransaction(
                    trace,
                    "before governed tool invocation");

            long toolStart =
                    trace.mark();

            trace.operational(
                    AgentTrace.TOOL_CALL_START,
                    "service",
                    toolServiceName,
                    "toolCallId",
                    toolCall.id());

            final Map<String, Object> toolResult;

            try {
                toolResult =
                        localDispatcher.runSync(
                                toolServiceName,
                                toolContext);

            } catch (GenericServiceException e) {

                trace.operational(
                        AgentTrace.TOOL_CALL_FAILURE,
                        "service",
                        toolServiceName,
                        "toolCallId",
                        toolCall.id(),
                        "durationMs",
                        AgentTrace.elapsedMillis(
                                toolStart),
                        "failureType",
                        e.getClass().getName(),
                        "failureMessage",
                        e.getMessage());

                throw e;
            }

            /*
             * A normal OFBiz tool service owns and completes its own
             * transaction. When control returns to the agent runtime there
             * must once again be no transaction on the current thread.
             */
            assertNoActiveTransaction(
                    trace,
                    "after governed tool invocation");

            String toolResponseMessage =
                    toolResult.get("responseMessage") == null
                            ? "<none>"
                            : toolResult.get("responseMessage").toString();

            trace.operational(
                    AgentTrace.TOOL_CALL_RESULT,
                    "service",
                    toolServiceName,
                    "toolCallId",
                    toolCall.id(),
                    "durationMs",
                    AgentTrace.elapsedMillis(
                            toolStart),
                    "responseMessage",
                    toolResponseMessage);

            /*
             * This is the complete service result returned to the agent
             * runtime by LocalDispatcher.
             *
             * With payload tracing set to FULL this boundary lets us
             * distinguish:
             *
             * OFBiz tool output
             *
             * from
             *
             * data later supplied to the model.
             */
            trace.payload(
                    AgentTrace.TOOL_RESULT_RAW,
                    toolResult,
                    "service",
                    toolServiceName,
                    "toolCallId",
                    toolCall.id());

            if (ServiceUtil.isError(
                    toolResult)
                    || ServiceUtil.isFailure(
                            toolResult)) {

                trace.operational(
                        AgentTrace.TOOL_CALL_FAILURE,
                        "service",
                        toolServiceName,
                        "toolCallId",
                        toolCall.id(),
                        "responseMessage",
                        toolResponseMessage,
                        "errorMessage",
                        ServiceUtil.getErrorMessage(
                                toolResult));

                throw new GenericServiceException(
                        "Agent tool ["
                        + toolServiceName
                        + "] failed: "
                        + ServiceUtil.getErrorMessage(
                                toolResult));
            }

            /*
             * Resolve the immutable semantic contract for the governed OFBiz
             * service and map only the fields declared by that contract.
             *
             * Generic serialization of OFBiz business values is deliberately
             * prohibited at this model boundary.
             */
            AgentToolContract toolContract =
                    AgentToolContractRegistry.getDefault()
                            .getRequired(
                                    toolServiceName);

            /*
             * Timezone is authoritative OFBiz execution context.
             *
             * There is deliberately no fallback to TimeZone.getDefault().
             * A missing timezone remains null so AgentValueCodec can fail
             * closed when a date-time field requires an explicit timezone.
             */
            TimeZone effectiveTimeZone =
                    getAuthoritativeTimeZone(
                            context);

            String toolResultJson =
                    AgentToolMapper.mapToJson(
                            toolContract,
                            toolResult,
                            effectiveTimeZone);

            /*
             * toolResultJson is already the governed model-bound
             * representation. Trace its exact lexical form rather than
             * reparsing and reserializing it.
             */
            trace.payloadExact(
                    AgentTrace.TOOL_RESULT_FOR_MODEL,
                    toolResultJson,
                    "service",
                    toolServiceName,
                    "toolCallId",
                    toolCall.id());

            /*
             * =============================================================
             * SECOND MODEL INTERACTION
             * =============================================================
             *
             * No tools are exposed in this request. V1 therefore cannot enter
             * an autonomous tool loop.
             */

            ObjectNode finalRequest =
                    buildFinalRequest(
                            model,
                            systemPrompt,
                            toolCall,
                            toolResultJson);

            JsonNode finalResponse =
                    callLlm(
                            trace,
                            client,
                            2,
                            model,
                            finalRequest);

            /*
             * =============================================================
             * FINAL OUTPUT VALIDATION
             * =============================================================
             */

            String summary =
                    extractFinalSummary(
                            finalResponse);

            trace.payload(
                    AgentTrace.FINAL_SUMMARY,
                    summary);

            trace.operational(
                    AgentTrace.OUTPUT_VALIDATED,
                    "output",
                    "summary");

            Map<String, Object> result =
                    ServiceUtil.returnSuccess();

            result.put(
                    "summary",
                    summary);

            /*
             * Do not emit AGENT_SUCCESS here.
             *
             * The inherited transaction still has to be restored. A failure
             * to restore it means the overall agent invocation did not
             * complete successfully.
             */
            executionCompleted = true;

            return result;

        } catch (GenericServiceException e) {

            executionFailure = e;

            throw e;

        } catch (GenericEntityException e) {

            Debug.logError(
                    e,
                    "Unable to load agent metadata",
                    MODULE);

            GenericServiceException wrapped =
                    new GenericServiceException(
                            "Unable to load agent metadata",
                            e);

            executionFailure = wrapped;

            throw wrapped;

        } catch (IOException e) {

            Debug.logError(
                    e,
                    "Agent LLM request failed",
                    MODULE);

            GenericServiceException wrapped =
                    new GenericServiceException(
                            "Agent LLM request failed",
                            e);

            executionFailure = wrapped;

            throw wrapped;

        } catch (RuntimeException e) {

            Debug.logError(
                    e,
                    "Unexpected agent execution failure",
                    MODULE);

            GenericServiceException wrapped =
                    new GenericServiceException(
                            "Unexpected agent execution failure",
                            e);

            executionFailure = wrapped;

            throw wrapped;

        } catch (Error e) {

            /*
             * Even fatal JVM-level errors must pass through the finally block
             * so a suspended caller transaction is not silently abandoned.
             */
            executionFailure = e;

            Debug.logError(
                    e,
                    "Unexpected agent execution error",
                    MODULE);

            throw e;

        } finally {

            /*
             * =============================================================
             * PARENT TRANSACTION RESTORATION
             * =============================================================
             *
             * This happens before terminal trace status is recorded.
             */

            GenericServiceException restorationFailure =
                    null;

            try {
                resumeParentTransaction(
                        parentTransaction,
                        trace);

            } catch (GenericServiceException e) {

                restorationFailure = e;

                /*
                 * If execution had already failed, preserve that failure as
                 * evidence while treating transaction restoration failure as
                 * the terminal failure.
                 */
                if (executionFailure != null) {
                    e.addSuppressed(
                            executionFailure);
                }
            }

            /*
             * =============================================================
             * TERMINAL TRACE STATUS
             * =============================================================
             */

            if (restorationFailure != null) {

                trace.failure(
                        restorationFailure);

            } else if (executionFailure != null) {

                trace.failure(
                        executionFailure);

            } else if (executionCompleted) {

                trace.success();
            }

            /*
             * Always restore the previous Log4j ThreadContext values.
             */
            trace.close();

            /*
             * A transaction restoration failure overrides a successful
             * business result or the original execution failure.
             *
             * The original failure, when present, has been retained as a
             * suppressed exception.
             */
            if (restorationFailure != null) {
                throw restorationFailure;
            }
        }
    }

    /**
     * Performs one model invocation with transaction enforcement and agent
     * tracing.
     *
     * <p>The helper deliberately contains no provider-specific logic beyond
     * calling the existing {@link OpenAiCompatibleClient}. It centralizes the
     * invariant that no model/network call may occur while an OFBiz
     * transaction is active.</p>
     *
     * @param trace execution trace
     * @param client model transport client
     * @param sequence one-based LLM call sequence
     * @param model configured model identifier
     * @param requestBody complete model request
     * @return parsed model response
     * @throws IOException when the transport/model endpoint fails
     * @throws GenericServiceException when transaction invariants fail
     */
    private static JsonNode callLlm(
            AgentTrace trace,
            OpenAiCompatibleClient client,
            int sequence,
            String model,
            JsonNode requestBody)
            throws IOException, GenericServiceException {

        assertNoActiveTransaction(
                trace,
                "before LLM request "
                + sequence);

        long llmStart =
                trace.mark();

        trace.operational(
                AgentTrace.LLM_REQUEST,
                "sequence",
                sequence,
                "model",
                model);

        trace.payload(
                AgentTrace.LLM_REQUEST_PAYLOAD,
                requestBody,
                "sequence",
                sequence,
                "model",
                model);

        final JsonNode response;

        try {
            response =
                    client.createChatCompletion(
                            requestBody);

        } catch (IOException | RuntimeException e) {

            trace.operational(
                    AgentTrace.LLM_FAILURE,
                    "sequence",
                    sequence,
                    "model",
                    model,
                    "durationMs",
                    AgentTrace.elapsedMillis(
                            llmStart),
                    "failureType",
                    e.getClass().getName(),
                    "failureMessage",
                    e.getMessage());

            throw e;
        }

        /*
         * Network/model code must not leave a transaction associated with the
         * current thread.
         */
        assertNoActiveTransaction(
                trace,
                "after LLM response "
                + sequence);

        trace.operational(
                AgentTrace.LLM_RESPONSE,
                "sequence",
                sequence,
                "model",
                model,
                "durationMs",
                AgentTrace.elapsedMillis(
                        llmStart));

        trace.payload(
                AgentTrace.LLM_RESPONSE_PAYLOAD,
                response,
                "sequence",
                sequence,
                "model",
                model);

        return response;
    }

    /**
     * Suspends an inherited caller transaction.
     *
     * <p>No transaction is started by this method. If there is no inherited
     * transaction, {@code null} is returned.</p>
     *
     * <p>Only an ACTIVE parent transaction may be suspended. Other transaction
     * states fail closed because beginning LLM orchestration while the caller
     * transaction is preparing, rolling back, marked rollback-only or otherwise
     * abnormal would make execution semantics ambiguous.</p>
     *
     * @param trace current agent trace
     * @return suspended parent transaction or {@code null}
     * @throws GenericServiceException if transaction state cannot be safely
     *         isolated
     */
    private static Transaction suspendParentTransaction(
            AgentTrace trace)
            throws GenericServiceException {

        try {
            int status =
                    TransactionUtil.getStatus();

            if (status
                    == Status.STATUS_NO_TRANSACTION) {

                return null;
            }

            String statusString =
                    TransactionUtil.getStatusString();

            trace.operational(
                    AgentTrace.TX_PARENT_FOUND,
                    "status",
                    statusString);

            if (status
                    != Status.STATUS_ACTIVE) {

                throw new GenericServiceException(
                        "Agent execution cannot proceed with inherited "
                        + "transaction state ["
                        + statusString
                        + "]");
            }

            Transaction parentTransaction =
                    TransactionUtil.suspend();

            if (parentTransaction == null) {

                throw new GenericServiceException(
                        "Unable to suspend inherited active transaction "
                        + "before agent execution");
            }

            if (TransactionUtil.isTransactionInPlace()) {

                String remainingStatus =
                        TransactionUtil.getStatusString();

                trace.operational(
                        AgentTrace.TX_INVARIANT_FAILURE,
                        "boundary",
                        "after parent transaction suspension",
                        "status",
                        remainingStatus);

                throw new GenericServiceException(
                        "Transaction remained associated with the thread "
                        + "after parent suspension; status is ["
                        + remainingStatus
                        + "]");
            }

            trace.operational(
                    AgentTrace.TX_PARENT_SUSPENDED);

            return parentTransaction;

        } catch (GenericTransactionException e) {

            throw new GenericServiceException(
                    "Unable to isolate inherited transaction "
                    + "before agent execution",
                    e);
        }
    }

    /**
     * Restores the caller transaction after agent execution.
     *
     * <p>The agent runtime must not leave any transaction of its own associated
     * with the execution thread. If an inherited transaction exists, it is
     * resumed only after verifying that the thread is transaction-free.</p>
     *
     * @param parentTransaction transaction suspended at agent entry
     * @param trace current agent trace
     * @throws GenericServiceException if the transaction boundary has been
     *         violated or the parent cannot be restored
     */
    private static void resumeParentTransaction(
            Transaction parentTransaction,
            AgentTrace trace)
            throws GenericServiceException {

        /*
         * No inherited transaction existed.
         *
         * We still verify that the agent did not leak a tool/service
         * transaction onto the thread.
         */
        if (parentTransaction == null) {

            assertNoActiveTransaction(
                    trace,
                    "at agent completion");

            return;
        }

        try {
            if (TransactionUtil.isTransactionInPlace()) {

                String status =
                        TransactionUtil.getStatusString();

                trace.operational(
                        AgentTrace.TX_INVARIANT_FAILURE,
                        "boundary",
                        "before parent transaction restoration",
                        "status",
                        status);

                throw new GenericServiceException(
                        "Agent execution left a transaction associated "
                        + "with the thread before parent restoration; "
                        + "status is ["
                        + status
                        + "]");
            }

            TransactionUtil.resume(
                    parentTransaction);

            if (!TransactionUtil.isTransactionInPlace()) {

                trace.operational(
                        AgentTrace.TX_INVARIANT_FAILURE,
                        "boundary",
                        "after parent transaction restoration",
                        "status",
                        TransactionUtil.getStatusString());

                throw new GenericServiceException(
                        "Inherited parent transaction was not restored "
                        + "after agent execution");
            }

            trace.operational(
                    AgentTrace.TX_PARENT_RESUMED,
                    "status",
                    TransactionUtil.getStatusString());

        } catch (GenericTransactionException e) {

            throw new GenericServiceException(
                    "Unable to restore inherited transaction "
                    + "after agent execution",
                    e);
        }
    }

    /**
     * Enforces the core agent transaction invariant.
     *
     * <p>Tracing reports transaction state, but tracing is not enforcement.
     * This method independently prevents execution from crossing an LLM or
     * orchestration boundary with an active OFBiz transaction.</p>
     *
     * @param trace current agent trace
     * @param boundary textual execution boundary
     * @throws GenericServiceException if a transaction is active or its state
     *         cannot be determined
     */
    private static void assertNoActiveTransaction(
            AgentTrace trace,
            String boundary)
            throws GenericServiceException {

        try {
            if (TransactionUtil.isTransactionInPlace()) {

                String status =
                        TransactionUtil.getStatusString();

                trace.operational(
                        AgentTrace.TX_INVARIANT_FAILURE,
                        "boundary",
                        boundary,
                        "status",
                        status);

                throw new GenericServiceException(
                        "Agent transaction invariant violated at ["
                        + boundary
                        + "]; transaction status is ["
                        + status
                        + "]");
            }

        } catch (GenericTransactionException e) {

            trace.operational(
                    AgentTrace.TX_INVARIANT_FAILURE,
                    "boundary",
                    boundary,
                    "status",
                    "unknown",
                    "failureType",
                    e.getClass().getName(),
                    "failureMessage",
                    e.getMessage());

            throw new GenericServiceException(
                    "Unable to determine transaction state at agent "
                    + "execution boundary ["
                    + boundary
                    + "]",
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
            String agentId)
            throws GenericEntityException, GenericServiceException {

        GenericValue agentDefinition =
                EntityQuery.use(
                        dctx.getDelegator())
                        .from(
                                "AgentDefinition")
                        .where(
                                "agentId",
                                agentId)
                        .queryOne();

        if (agentDefinition == null) {

            throw new GenericServiceException(
                    "Agent definition ["
                    + agentId
                    + "] was not found");
        }

        return agentDefinition;
    }

    /**
     * Loads the OFBiz services explicitly permitted for this agent.
     */
    private static List<GenericValue> loadAgentTools(
            DispatchContext dctx,
            String agentId)
            throws GenericEntityException {

        return EntityQuery.use(
                        dctx.getDelegator())
                .from(
                        "AgentTool")
                .where(
                        "agentId",
                        agentId)
                .orderBy(
                        "serviceName")
                .queryList();
    }

    /**
     * Constructs the first LLM request.
     *
     * <p>The model receives a conceptual zero-argument tool. It never receives
     * partyId as a tool argument. Customer scope remains under OFBiz
     * control.</p>
     */
    private static ObjectNode buildInitialRequest(
            String model,
            String systemPrompt,
            String toolServiceName) {

        ObjectNode request =
                OBJECT_MAPPER.createObjectNode();

        request.put(
                "model",
                model);

        request.put(
                "temperature",
                0.0);

        request.put(
                "max_tokens",
                MAX_COMPLETION_TOKENS);

        ArrayNode messages =
                request.putArray(
                        "messages");

        ObjectNode systemMessage =
                messages.addObject();

        systemMessage.put(
                "role",
                "system");

        systemMessage.put(
                "content",
                systemPrompt);

        ObjectNode userMessage =
                messages.addObject();

        userMessage.put(
                "role",
                "user");

        userMessage.put(
                "content",
                "Analyse the overdue outstanding sales invoices "
                + "for the customer selected in OFBiz. "
                + "Use the provided tool before answering.");

        ArrayNode tools =
                request.putArray(
                        "tools");

        ObjectNode tool =
                tools.addObject();

        tool.put(
                "type",
                "function");

        ObjectNode function =
                tool.putObject(
                        "function");

        function.put(
                "name",
                toolServiceName);

        function.put(
                "description",
                "Retrieve overdue outstanding sales invoices "
                + "for the customer selected in OFBiz.");

        ObjectNode parameters =
                function.putObject(
                        "parameters");

        parameters.put(
                "type",
                "object");

        parameters.putObject(
                "properties");

        parameters.put(
                "additionalProperties",
                false);

        ObjectNode toolChoice =
                request.putObject(
                        "tool_choice");

        toolChoice.put(
                "type",
                "function");

        toolChoice.putObject(
                        "function")
                .put(
                        "name",
                        toolServiceName);

        return request;
    }

    /**
     * Validates the first model response against the V1 execution protocol.
     *
     * <p>The model must request exactly one function, that function must match
     * the service registered for this agent, and V1 tool arguments must be an
     * empty JSON object.</p>
     */
    private static ToolCall extractRequiredToolCall(
            JsonNode response,
            String allowedServiceName)
            throws GenericServiceException {

        JsonNode message =
                extractSingleMessage(
                        response);

        JsonNode toolCalls =
                message.get(
                        "tool_calls");

        if (toolCalls == null
                || !toolCalls.isArray()
                || toolCalls.size() != 1) {

            throw new GenericServiceException(
                    "Agent must return exactly one tool call");
        }

        JsonNode toolCall =
                toolCalls.get(
                        0);

        String type =
                textValue(
                        toolCall,
                        "type");

        if (!"function".equals(
                type)) {

            throw new GenericServiceException(
                    "Agent returned a non-function tool call");
        }

        String toolCallId =
                textValue(
                        toolCall,
                        "id");

        if (toolCallId == null
                || toolCallId.isBlank()) {

            throw new GenericServiceException(
                    "Agent tool call does not contain an id");
        }

        JsonNode function =
                toolCall.get(
                        "function");

        if (function == null
                || !function.isObject()) {

            throw new GenericServiceException(
                    "Agent tool call does not contain a function");
        }

        String functionName =
                textValue(
                        function,
                        "name");

        if (!allowedServiceName.equals(
                functionName)) {

            throw new GenericServiceException(
                    "Agent attempted to invoke undeclared tool ["
                    + functionName
                    + "]");
        }

        String arguments =
                textValue(
                        function,
                        "arguments");

        if (arguments == null
                || arguments.isBlank()) {

            throw new GenericServiceException(
                    "Agent tool call arguments are missing");
        }

        final JsonNode parsedArguments;

        try {
            parsedArguments =
                    OBJECT_MAPPER.readTree(
                            arguments);

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

        Map<String, Object> toolContext =
                new HashMap<>();

        toolContext.put(
                "partyId",
                partyId);

        toolContext.put(
                "userLogin",
                userLogin);

        if (outerContext.get(
                "locale") != null) {

            toolContext.put(
                    "locale",
                    outerContext.get(
                            "locale"));
        }

        if (outerContext.get(
                "timeZone") != null) {

            toolContext.put(
                    "timeZone",
                    outerContext.get(
                            "timeZone"));
        }

        return toolContext;
    }

    /**
     * Returns the authoritative timezone supplied by the outer OFBiz service
     * context.
     *
     * <p>The semantic boundary must not silently use the JVM default timezone.
     * A missing timezone is therefore returned as {@code null}. If the tool
     * result contains a configured date-time field, {@link AgentValueCodec}
     * will fail closed rather than inventing timezone semantics.</p>
     *
     * @param context authoritative outer OFBiz service context
     * @return authoritative timezone, or {@code null} when absent
     * @throws GenericServiceException if a non-TimeZone value was supplied
     */
    private static TimeZone getAuthoritativeTimeZone(
            Map<String, Object> context)
            throws GenericServiceException {

        Object value =
                context.get(
                        "timeZone");

        if (value == null) {
            return null;
        }

        if (!(value instanceof TimeZone)) {
            throw new GenericServiceException(
                    "Agent service context timeZone must be a "
                    + "java.util.TimeZone");
        }

        return (TimeZone) value;
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

        ObjectNode request =
                OBJECT_MAPPER.createObjectNode();

        request.put(
                "model",
                model);

        request.put(
                "temperature",
                0.0);

        request.put(
                "max_tokens",
                MAX_COMPLETION_TOKENS);

        ArrayNode messages =
                request.putArray(
                        "messages");

        ObjectNode systemMessage =
                messages.addObject();

        systemMessage.put(
                "role",
                "system");

        systemMessage.put(
                "content",
                systemPrompt);

        ObjectNode userMessage =
                messages.addObject();

        userMessage.put(
                "role",
                "user");

        userMessage.put(
                "content",
                "Analyse the overdue outstanding sales invoices "
                + "for the customer selected in OFBiz. "
                + "Use the provided tool before answering.");

        ObjectNode assistantMessage =
                messages.addObject();

        assistantMessage.put(
                "role",
                "assistant");

        assistantMessage.putNull(
                "content");

        ArrayNode toolCalls =
                assistantMessage.putArray(
                        "tool_calls");

        ObjectNode call =
                toolCalls.addObject();

        call.put(
                "id",
                toolCall.id());

        call.put(
                "type",
                "function");

        ObjectNode function =
                call.putObject(
                        "function");

        function.put(
                "name",
                toolCall.functionName());

        function.put(
                "arguments",
                toolCall.arguments());

        ObjectNode toolMessage =
                messages.addObject();

        toolMessage.put(
                "role",
                "tool");

        toolMessage.put(
                "tool_call_id",
                toolCall.id());

        toolMessage.put(
                "content",
                toolResultJson);

        return request;
    }

    /**
     * Extracts and validates the final textual answer.
     *
     * <p>A second tool call is prohibited in V1.</p>
     */
    private static String extractFinalSummary(
            JsonNode response)
            throws GenericServiceException {

        JsonNode message =
                extractSingleMessage(
                        response);

        JsonNode toolCalls =
                message.get(
                        "tool_calls");

        if (toolCalls != null
                && toolCalls.isArray()
                && toolCalls.size() > 0) {

            throw new GenericServiceException(
                    "Agent attempted an additional tool call");
        }

        String content =
                textValue(
                        message,
                        "content");

        if (content == null
                || content.isBlank()) {

            throw new GenericServiceException(
                    "Agent returned an empty final response");
        }

        return content.trim();
    }

    /**
     * Extracts exactly one assistant message from a Chat Completions response.
     */
    private static JsonNode extractSingleMessage(
            JsonNode response)
            throws GenericServiceException {

        if (response == null
                || !response.isObject()) {

            throw new GenericServiceException(
                    "LLM response is not a JSON object");
        }

        JsonNode choices =
                response.get(
                        "choices");

        if (choices == null
                || !choices.isArray()
                || choices.size() != 1) {

            throw new GenericServiceException(
                    "LLM response must contain exactly one choice");
        }

        JsonNode message =
                choices.get(0).get(
                        "message");

        if (message == null
                || !message.isObject()) {

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

        JsonNode value =
                node.get(
                        fieldName);

        if (value == null
                || value.isNull()
                || !value.isTextual()) {

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
