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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.ThreadContext;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.entity.transaction.GenericTransactionException;
import org.apache.ofbiz.entity.transaction.TransactionUtil;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Structured tracing helper for OFBiz agent execution.
 *
 * <p>This class provides agent-specific observability while using the existing
 * OFBiz logging infrastructure. It does not perform agent orchestration,
 * business processing, service invocation, or LLM communication.</p>
 *
 * <p>Each agent execution receives a unique {@code agentRunId}. The identifier
 * is placed into Log4j's {@link ThreadContext}, together with the agent ID and
 * OFBiz service name, so agent trace events can be correlated with ordinary
 * OFBiz log messages produced on the same thread.</p>
 *
 * <p>Tracing is explicitly non-authoritative. A tracing failure must never
 * alter the outcome of an agent execution. Internal tracing errors are caught
 * and suppressed wherever practical.</p>
 *
 * <p>Configuration is read from {@code agent.properties}:</p>
 *
 * <pre>
 * agent.trace.level=operational
 * agent.trace.payload=off
 * </pre>
 *
 * <p>Supported trace levels:</p>
 *
 * <ul>
 *   <li>{@code operational} - lifecycle, LLM calls, tool calls and failures.</li>
 *   <li>{@code diagnostic} - operational events plus detailed diagnostic
 *       boundaries and optionally payload information.</li>
 * </ul>
 *
 * <p>Supported payload modes:</p>
 *
 * <ul>
 *   <li>{@code off} - no payload content is logged.</li>
 *   <li>{@code metadata} - payload size and SHA-256 are logged.</li>
 *   <li>{@code full} - sanitized payload content is logged.</li>
 * </ul>
 */
public final class AgentTrace implements AutoCloseable {

    private static final String MODULE = AgentTrace.class.getName();

    private static final String PROPERTY_RESOURCE = "agent";

    private static final String TRACE_LEVEL_PROPERTY =
            "agent.trace.level";

    private static final String PAYLOAD_MODE_PROPERTY =
            "agent.trace.payload";

    private static final String DEFAULT_TRACE_LEVEL =
            "operational";

    private static final String DEFAULT_PAYLOAD_MODE =
            "off";

    private static final String MDC_AGENT_RUN_ID =
            "agentRunId";

    private static final String MDC_AGENT_ID =
            "agentId";

    private static final String MDC_AGENT_SERVICE =
            "agentService";

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper();

    /*
     * Fields that must not be emitted when payload tracing is enabled.
     *
     * The comparison is case-insensitive.
     *
     * This is a defensive measure only. Callers must still avoid supplying
     * authentication/session objects such as userLogin as trace payloads.
     */
    private static final Set<String> REDACTED_FIELD_NAMES =
            Set.of(
                    "authorization",
                    "proxy-authorization",
                    "cookie",
                    "set-cookie",
                    "password",
                    "passwd",
                    "apikey",
                    "api_key",
                    "api-key",
                    "access_token",
                    "refresh_token",
                    "token",
                    "secret",
                    "client_secret",
                    "userlogin");

    /*
     * Stable event vocabulary.
     *
     * These constants prevent arbitrary event names from proliferating
     * throughout the agent runtime.
     */

    public static final String AGENT_START =
            "AGENT_START";

    public static final String AGENT_END =
            "AGENT_END";

    public static final String AGENT_SUCCESS =
            "AGENT_SUCCESS";

    public static final String AGENT_FAILURE =
            "AGENT_FAILURE";

    public static final String TX_PARENT_FOUND =
            "TX_PARENT_FOUND";

    public static final String TX_PARENT_SUSPENDED =
            "TX_PARENT_SUSPENDED";

    public static final String TX_PARENT_RESUMED =
            "TX_PARENT_RESUMED";

    public static final String TX_INVARIANT_FAILURE =
            "TX_INVARIANT_FAILURE";

    public static final String METADATA_LOAD_START =
            "METADATA_LOAD_START";

    public static final String METADATA_LOAD_END =
            "METADATA_LOAD_END";

    public static final String LLM_REQUEST =
            "LLM_REQUEST";

    public static final String LLM_RESPONSE =
            "LLM_RESPONSE";

    public static final String LLM_FAILURE =
            "LLM_FAILURE";

    public static final String LLM_REQUEST_PAYLOAD =
            "LLM_REQUEST_PAYLOAD";

    public static final String LLM_RESPONSE_PAYLOAD =
            "LLM_RESPONSE_PAYLOAD";

    public static final String TOOL_SELECTED =
            "TOOL_SELECTED";

    public static final String TOOL_CALL_START =
            "TOOL_CALL_START";

    public static final String TOOL_CALL_RESULT =
            "TOOL_CALL_RESULT";

    public static final String TOOL_CALL_FAILURE =
            "TOOL_CALL_FAILURE";

    public static final String TOOL_RESULT_RAW =
            "TOOL_RESULT_RAW";

    public static final String TOOL_RESULT_FOR_MODEL =
            "TOOL_RESULT_FOR_MODEL";

    public static final String OUTPUT_VALIDATED =
            "OUTPUT_VALIDATED";

    public static final String OUTPUT_REJECTED =
            "OUTPUT_REJECTED";

    public static final String FINAL_SUMMARY =
            "FINAL_SUMMARY";

    /**
     * Agent trace verbosity.
     */
    private enum TraceLevel {
        OPERATIONAL,
        DIAGNOSTIC
    }

    /**
     * Payload recording mode.
     */
    private enum PayloadMode {
        OFF,
        METADATA,
        FULL
    }

    private final String agentRunId;

    private final String agentId;

    private final String agentService;

    private final TraceLevel traceLevel;

    private final PayloadMode payloadMode;

    private final long agentStartNanos;

    /*
     * Previous MDC values are preserved so nested agent executions do not
     * destroy the parent's logging context when the nested trace closes.
     */
    private final String previousAgentRunId;

    private final String previousAgentId;

    private final String previousAgentService;

    private boolean terminalEventLogged;

    private boolean closed;

    /**
     * Creates and starts a trace for one agent execution.
     *
     * <p>Callers should normally use this method with try/finally or
     * try-with-resources so the Log4j ThreadContext is always restored.</p>
     *
     * @param agentService OFBiz service that initiated the agent execution
     * @param agentId durable agent identifier
     * @return trace instance
     */
    public static AgentTrace start(
            String agentService,
            String agentId) {

        return new AgentTrace(
                agentService,
                agentId);
    }

    private AgentTrace(
            String agentService,
            String agentId) {

        this.agentRunId =
                UUID.randomUUID().toString();

        this.agentService =
                safeIdentifier(
                        agentService,
                        "<unknown-service>");

        this.agentId =
                safeIdentifier(
                        agentId,
                        "<unknown-agent>");

        this.traceLevel =
                resolveTraceLevel();

        this.payloadMode =
                resolvePayloadMode();

        this.agentStartNanos =
                System.nanoTime();

        this.previousAgentRunId =
                safeThreadContextGet(
                        MDC_AGENT_RUN_ID);

        this.previousAgentId =
                safeThreadContextGet(
                        MDC_AGENT_ID);

        this.previousAgentService =
                safeThreadContextGet(
                        MDC_AGENT_SERVICE);

        installThreadContext();

        operational(
                AGENT_START,
                "traceLevel",
                traceLevel.name().toLowerCase(Locale.ROOT),
                "payloadMode",
                payloadMode.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the unique identifier for this agent execution.
     *
     * @return agent run identifier
     */
    public String getAgentRunId() {
        return agentRunId;
    }

    /**
     * Returns true when diagnostic tracing is enabled.
     *
     * @return whether diagnostic tracing is active
     */
    public boolean isDiagnostic() {
        return traceLevel == TraceLevel.DIAGNOSTIC;
    }

    /**
     * Returns true when exact payload content may be recorded.
     *
     * @return whether full payload tracing is active
     */
    public boolean isFullPayloadEnabled() {
        return traceLevel == TraceLevel.DIAGNOSTIC
                && payloadMode == PayloadMode.FULL;
    }

    /**
     * Returns a monotonic timer marker suitable for later use with
     * {@link #elapsedMillis(long)}.
     *
     * @return current monotonic nanosecond value
     */
    public long mark() {
        return System.nanoTime();
    }

    /**
     * Returns elapsed milliseconds from a marker returned by {@link #mark()}.
     *
     * @param startedNanos start marker
     * @return elapsed milliseconds
     */
    public static long elapsedMillis(
            long startedNanos) {

        long elapsedNanos =
                System.nanoTime()
                - startedNanos;

        if (elapsedNanos <= 0) {
            return 0L;
        }

        return elapsedNanos
                / 1_000_000L;
    }

    /**
     * Emits an operational trace event.
     *
     * <p>Operational events are always emitted while AgentTrace is active.</p>
     *
     * @param eventName stable event name
     * @param attributes alternating key/value pairs
     */
    public void operational(
            String eventName,
            Object... attributes) {

        safeLog(
                eventName,
                attributes);
    }

    /**
     * Emits a diagnostic trace event.
     *
     * <p>The event is emitted only when
     * {@code agent.trace.level=diagnostic}.</p>
     *
     * @param eventName stable event name
     * @param attributes alternating key/value pairs
     */
    public void diagnostic(
            String eventName,
            Object... attributes) {

        if (traceLevel
                != TraceLevel.DIAGNOSTIC) {
            return;
        }

        safeLog(
                eventName,
                attributes);
    }

    /**
     * Emits a payload trace according to the configured payload mode.
     *
     * <p>Payload tracing is available only when diagnostic tracing is enabled.
     * In metadata mode only size and SHA-256 are emitted. In full mode the
     * sanitized payload is emitted.</p>
     *
     * @param eventName stable payload event name
     * @param payload payload to trace
     * @param attributes alternating key/value pairs
     */
    public void payload(
            String eventName,
            Object payload,
            Object... attributes) {

        if (traceLevel
                != TraceLevel.DIAGNOSTIC) {
            return;
        }

        if (payloadMode
                == PayloadMode.OFF) {
            return;
        }

        try {
            String serializedPayload =
                    serializePayload(
                            payload);

            byte[] payloadBytes =
                    serializedPayload.getBytes(
                            StandardCharsets.UTF_8);

            String payloadSha256 =
                    sha256(
                            payloadBytes);

            if (payloadMode
                    == PayloadMode.METADATA) {

                Object[] combined =
                        appendAttributes(
                                attributes,
                                "payloadCharacters",
                                serializedPayload.length(),
                                "payloadBytes",
                                payloadBytes.length,
                                "payloadSha256",
                                payloadSha256);

                safeLog(
                        eventName,
                        combined);

                return;
            }

            String sanitizedPayload =
                    sanitizeLogLine(
                            serializedPayload);

            Object[] combined =
                    appendAttributes(
                            attributes,
                            "payloadCharacters",
                            serializedPayload.length(),
                            "payloadBytes",
                            payloadBytes.length,
                            "payloadSha256",
                            payloadSha256,
                            "payload",
                            sanitizedPayload);

            safeLog(
                    eventName,
                    combined);

        } catch (RuntimeException e) {
            safeTracingFailure(
                    "Unable to record agent payload trace",
                    e);
        }
    }

    /**
     * Emits a successful terminal event for the agent run.
     *
     * @param attributes alternating key/value pairs
     */
    public void success(
            Object... attributes) {

        if (terminalEventLogged) {
            return;
        }

        terminalEventLogged = true;

        Object[] combined =
                appendAttributes(
                        attributes,
                        "durationMs",
                        elapsedMillis(
                                agentStartNanos));

        operational(
                AGENT_SUCCESS,
                combined);
    }

    /**
     * Emits a failed terminal event for the agent run.
     *
     * @param failure execution failure
     * @param attributes alternating key/value pairs
     */
    public void failure(
            Throwable failure,
            Object... attributes) {

        if (terminalEventLogged) {
            return;
        }

        terminalEventLogged = true;

        String failureType =
                failure == null
                        ? "<unknown>"
                        : failure.getClass().getName();

        String failureMessage =
                failure == null
                        ? "<unknown>"
                        : sanitizeLogLine(
                                failure.getMessage());

        Object[] combined =
                appendAttributes(
                        attributes,
                        "durationMs",
                        elapsedMillis(
                                agentStartNanos),
                        "failureType",
                        failureType,
                        "failureMessage",
                        failureMessage);

        operational(
                AGENT_FAILURE,
                combined);
    }

    /**
     * Restores the previous Log4j ThreadContext values.
     *
     * <p>If neither {@link #success(Object...)} nor
     * {@link #failure(Throwable, Object...)} was called, an
     * {@code AGENT_END} event with {@code outcome=unreported} is emitted.
     * This helps identify incomplete instrumentation paths.</p>
     */
    @Override
    public void close() {

        if (closed) {
            return;
        }

        closed = true;

        if (!terminalEventLogged) {
            operational(
                    AGENT_END,
                    "outcome",
                    "unreported",
                    "durationMs",
                    elapsedMillis(
                            agentStartNanos));
        }

        restoreThreadContext();
    }

    /**
     * Emits one log event.
     *
     * <p>Transaction state is attached automatically to every event so the
     * agent's no-network-inside-transaction invariant is observable.</p>
     */
    private void safeLog(
            String eventName,
            Object... attributes) {

        try {
            StringBuilder message =
                    new StringBuilder();

            message.append("event=")
                    .append(
                            safeValue(
                                    eventName));

            message.append(" tx=")
                    .append(
                            quoteValue(
                                    transactionState()));

            appendRenderedAttributes(
                    message,
                    attributes);

            Debug.logInfo(
                    message.toString(),
                    MODULE);

        } catch (RuntimeException e) {
            /*
             * Logging must never alter the agent execution path.
             *
             * Do not recursively invoke AgentTrace logging here.
             */
        }
    }

    /**
     * Appends alternating key/value pairs to an event message.
     */
    private static void appendRenderedAttributes(
            StringBuilder message,
            Object... attributes) {

        if (attributes == null
                || attributes.length == 0) {
            return;
        }

        int pairCount =
                attributes.length / 2;

        for (int i = 0;
                i < pairCount;
                i++) {

            Object key =
                    attributes[i * 2];

            Object value =
                    attributes[(i * 2) + 1];

            String keyText =
                    key == null
                            ? "<null-key>"
                            : sanitizeKey(
                                    key.toString());

            message.append(' ')
                    .append(keyText)
                    .append('=')
                    .append(
                            quoteValue(
                                    value));
        }

        /*
         * An odd number of attribute arguments is a tracing defect, not an
         * agent execution defect. Record it without throwing.
         */
        if ((attributes.length % 2) != 0) {
            message.append(
                    " traceAttributeError=\"odd attribute count\"");
        }
    }

    /**
     * Returns current OFBiz transaction state without allowing a transaction
     * diagnostic failure to affect agent processing.
     */
    private static String transactionState() {

        try {
            return TransactionUtil.getStatusString();

        } catch (GenericTransactionException e) {
            return "UNKNOWN:"
                    + e.getClass().getSimpleName();
        }
    }

    /**
     * Serializes a payload to compact text and redacts known sensitive JSON
     * fields.
     */
    private static String serializePayload(
            Object payload) {

        if (payload == null) {
            return "null";
        }

        try {
            JsonNode node;

            if (payload instanceof JsonNode) {

                node = ((JsonNode) payload).deepCopy();

            } else if (payload instanceof CharSequence) {

                String text =
                        payload.toString();

                try {
                    node =
                            OBJECT_MAPPER.readTree(
                                    text);

                    if (node == null) {
                        return text;
                    }

                } catch (JsonProcessingException e) {
                    /*
                     * Ordinary text payload rather than JSON.
                     */
                    return text;
                }

            } else {

                node =
                        OBJECT_MAPPER.valueToTree(
                                payload);
            }

            JsonNode sanitized =
                    redactNode(
                            node);

            return OBJECT_MAPPER.writeValueAsString(
                    sanitized);

        } catch (JsonProcessingException
                | IllegalArgumentException e) {

            /*
             * Tracing must remain best-effort. Fall back to toString rather
             * than allowing serialization problems into the execution path.
             */
            return String.valueOf(
                    payload);
        }
    }

    /**
     * Recursively redacts known sensitive fields from JSON payloads.
     */
    private static JsonNode redactNode(
            JsonNode node) {

        if (node == null) {
            return OBJECT_MAPPER.nullNode();
        }

        if (node.isObject()) {

            ObjectNode object =
                    (ObjectNode) node.deepCopy();

            object.fieldNames()
                    .forEachRemaining(
                            fieldName -> {

                                String normalized =
                                        fieldName.toLowerCase(
                                                Locale.ROOT);

                                if (REDACTED_FIELD_NAMES.contains(
                                        normalized)) {

                                    object.put(
                                            fieldName,
                                            "<redacted>");

                                } else {

                                    JsonNode child =
                                            object.get(
                                                    fieldName);

                                    if (child != null) {
                                        object.set(
                                                fieldName,
                                                redactNode(
                                                        child));
                                    }
                                }
                            });

            return object;
        }

        if (node.isArray()) {

            ArrayNode array =
                    OBJECT_MAPPER.createArrayNode();

            for (JsonNode child : node) {
                array.add(
                        redactNode(
                                child));
            }

            return array;
        }

        return node.deepCopy();
    }

    /**
     * Computes a lower-case hexadecimal SHA-256 digest.
     */
    private static String sha256(
            byte[] bytes) {

        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256");

            byte[] hash =
                    digest.digest(
                            bytes);

            StringBuilder hex =
                    new StringBuilder(
                            hash.length * 2);

            for (byte value : hash) {
                hex.append(
                        String.format(
                                Locale.ROOT,
                                "%02x",
                                value & 0xff));
            }

            return hex.toString();

        } catch (NoSuchAlgorithmException e) {
            /*
             * SHA-256 is mandatory in the Java platform. Retain a defensive
             * fallback because tracing must not alter application behaviour.
             */
            return "<sha256-unavailable>";
        }
    }

    /**
     * Combines an existing attribute array with additional key/value pairs.
     */
    private static Object[] appendAttributes(
            Object[] existing,
            Object... additional) {

        int existingLength =
                existing == null
                        ? 0
                        : existing.length;

        int additionalLength =
                additional == null
                        ? 0
                        : additional.length;

        Object[] combined =
                new Object[
                        existingLength
                        + additionalLength];

        if (existingLength > 0) {
            System.arraycopy(
                    existing,
                    0,
                    combined,
                    0,
                    existingLength);
        }

        if (additionalLength > 0) {
            System.arraycopy(
                    additional,
                    0,
                    combined,
                    existingLength,
                    additionalLength);
        }

        return combined;
    }

    /**
     * Resolves configured trace level.
     */
    private static TraceLevel resolveTraceLevel() {

        String configured =
                safeProperty(
                        TRACE_LEVEL_PROPERTY,
                        DEFAULT_TRACE_LEVEL);

        if ("diagnostic".equalsIgnoreCase(
                configured)) {
            return TraceLevel.DIAGNOSTIC;
        }

        if ("operational".equalsIgnoreCase(
                configured)) {
            return TraceLevel.OPERATIONAL;
        }

        safeConfigurationWarning(
                TRACE_LEVEL_PROPERTY,
                configured,
                DEFAULT_TRACE_LEVEL);

        return TraceLevel.OPERATIONAL;
    }

    /**
     * Resolves configured payload mode.
     */
    private static PayloadMode resolvePayloadMode() {

        String configured =
                safeProperty(
                        PAYLOAD_MODE_PROPERTY,
                        DEFAULT_PAYLOAD_MODE);

        if ("full".equalsIgnoreCase(
                configured)) {
            return PayloadMode.FULL;
        }

        if ("metadata".equalsIgnoreCase(
                configured)) {
            return PayloadMode.METADATA;
        }

        if ("off".equalsIgnoreCase(
                configured)) {
            return PayloadMode.OFF;
        }

        safeConfigurationWarning(
                PAYLOAD_MODE_PROPERTY,
                configured,
                DEFAULT_PAYLOAD_MODE);

        return PayloadMode.OFF;
    }

    /**
     * Reads an agent property without allowing configuration lookup failure to
     * affect agent execution.
     */
    private static String safeProperty(
            String propertyName,
            String defaultValue) {

        try {
            String value =
                    UtilProperties.getPropertyValue(
                            PROPERTY_RESOURCE,
                            propertyName,
                            defaultValue);

            if (value == null
                    || value.isBlank()) {
                return defaultValue;
            }

            return value.trim();

        } catch (RuntimeException e) {
            safeTracingFailure(
                    "Unable to read agent tracing property ["
                    + propertyName
                    + "]; using default ["
                    + defaultValue
                    + "]",
                    e);

            return defaultValue;
        }
    }

    /**
     * Installs agent correlation fields into Log4j ThreadContext.
     */
    private void installThreadContext() {

        safeThreadContextPut(
                MDC_AGENT_RUN_ID,
                agentRunId);

        safeThreadContextPut(
                MDC_AGENT_ID,
                agentId);

        safeThreadContextPut(
                MDC_AGENT_SERVICE,
                agentService);
    }

    /**
     * Restores MDC values present before this AgentTrace was started.
     */
    private void restoreThreadContext() {

        restoreThreadContextValue(
                MDC_AGENT_RUN_ID,
                previousAgentRunId);

        restoreThreadContextValue(
                MDC_AGENT_ID,
                previousAgentId);

        restoreThreadContextValue(
                MDC_AGENT_SERVICE,
                previousAgentService);
    }

    /**
     * Restores one previous MDC value.
     */
    private static void restoreThreadContextValue(
            String key,
            String previousValue) {

        try {
            if (previousValue == null) {
                ThreadContext.remove(
                        key);
            } else {
                ThreadContext.put(
                        key,
                        previousValue);
            }
        } catch (RuntimeException e) {
            /*
             * Do not allow logging-context cleanup failure to alter agent
             * execution.
             */
        }
    }

    /**
     * Safely reads one MDC value.
     */
    private static String safeThreadContextGet(
            String key) {

        try {
            return ThreadContext.get(
                    key);

        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Safely writes one MDC value.
     */
    private static void safeThreadContextPut(
            String key,
            String value) {

        try {
            ThreadContext.put(
                    key,
                    value);

        } catch (RuntimeException e) {
            /*
             * Correlation failure must not alter agent execution.
             */
        }
    }

    /**
     * Emits a configuration warning without throwing.
     */
    private static void safeConfigurationWarning(
            String propertyName,
            String configuredValue,
            String defaultValue) {

        try {
            Debug.logWarning(
                    "Invalid agent tracing configuration "
                    + propertyName
                    + "=["
                    + configuredValue
                    + "]; using ["
                    + defaultValue
                    + "]",
                    MODULE);

        } catch (RuntimeException e) {
            /*
             * Logging failures are deliberately ignored.
             */
        }
    }

    /**
     * Emits an internal tracing failure without throwing.
     */
    private static void safeTracingFailure(
            String message,
            Throwable failure) {

        try {
            Debug.logWarning(
                    failure,
                    message,
                    MODULE);

        } catch (RuntimeException e) {
            /*
             * Never recurse or propagate logging failures.
             */
        }
    }

    /**
     * Produces a safe fallback identifier.
     */
    private static String safeIdentifier(
            String value,
            String fallback) {

        if (value == null
                || value.isBlank()) {
            return fallback;
        }

        return sanitizeLogLine(
                value.trim());
    }

    /**
     * Sanitizes attribute keys.
     */
    private static String sanitizeKey(
            String key) {

        if (key == null
                || key.isBlank()) {
            return "unknown";
        }

        return key.replaceAll(
                "[^A-Za-z0-9_.-]",
                "_");
    }

    /**
     * Formats an attribute value.
     */
    private static String quoteValue(
            Object value) {

        if (value == null) {
            return "null";
        }

        if (value instanceof Number
                || value instanceof Boolean) {
            return value.toString();
        }

        return "\""
                + safeValue(
                        value)
                + "\"";
    }

    /**
     * Converts a value to a single-line log-safe representation.
     */
    private static String safeValue(
            Object value) {

        if (value == null) {
            return "null";
        }

        return sanitizeLogLine(
                value.toString())
                .replace(
                        "\"",
                        "\\\"");
    }

    /**
     * Prevents payload or exception text from producing additional physical
     * log lines.
     */
    private static String sanitizeLogLine(
            String value) {

        if (value == null) {
            return "null";
        }

        return value
                .replace(
                        "\r",
                        "\\r")
                .replace(
                        "\n",
                        "\\n")
                .replace(
                        "\t",
                        "\\t");
    }
}
