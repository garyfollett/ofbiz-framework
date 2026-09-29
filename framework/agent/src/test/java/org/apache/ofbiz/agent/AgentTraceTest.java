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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.StringWriter;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.WriterAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link AgentTrace}.
 *
 * <p>These tests concentrate on the exact model-bound payload tracing path.
 * That path exists because a canonical representation produced by the semantic
 * boundary must be measured and hashed exactly as supplied. It must not be
 * parsed and reserialized before trace metadata is calculated.</p>
 */
public class AgentTraceTest {

    private static final String PROPERTY_RESOURCE =
            "agent";

    private static final String TRACE_LEVEL_PROPERTY =
            "agent.trace.level";

    private static final String PAYLOAD_MODE_PROPERTY =
            "agent.trace.payload";

    private static final String TEST_SERVICE =
            "testAgentService";

    private static final String TEST_AGENT =
            "TEST_AGENT";

    private static final String APPENDER_NAME =
            "AgentTraceTestAppender";

    private String previousTraceLevel;

    private String previousPayloadMode;

    private boolean previousInfoEnabled;

    private Level previousLoggerLevel;

    private Logger logger;

    private WriterAppender appender;

    private StringWriter logWriter;

    /**
     * Enables deterministic diagnostic/full payload tracing and installs an
     * in-memory Log4j appender for each test.
     */
    @BeforeEach
    public void setUp() {

        previousTraceLevel =
                UtilProperties.getPropertyValue(
                        PROPERTY_RESOURCE,
                        TRACE_LEVEL_PROPERTY,
                        "operational");

        previousPayloadMode =
                UtilProperties.getPropertyValue(
                        PROPERTY_RESOURCE,
                        PAYLOAD_MODE_PROPERTY,
                        "off");

        UtilProperties.setPropertyValueInMemory(
                PROPERTY_RESOURCE,
                TRACE_LEVEL_PROPERTY,
                "diagnostic");

        UtilProperties.setPropertyValueInMemory(
                PROPERTY_RESOURCE,
                PAYLOAD_MODE_PROPERTY,
                "full");

        previousInfoEnabled =
                Debug.get(
                        Debug.INFO);

        Debug.set(
                Debug.INFO,
                true);

        logger =
                (Logger) LogManager.getLogger(
                        AgentTrace.class.getName());

        previousLoggerLevel =
                logger.getLevel();

        logger.setLevel(
                Level.INFO);

        logWriter =
                new StringWriter();

        PatternLayout layout =
                PatternLayout.newBuilder()
                        .setPattern(
                                "%m%n")
                        .build();

        appender =
                WriterAppender.createAppender(
                        layout,
                        null,
                        logWriter,
                        APPENDER_NAME,
                        false,
                        true);

        appender.start();

        logger.addAppender(
                appender);
    }

    /**
     * Restores the logging and agent property state changed by each test.
     */
    @AfterEach
    public void tearDown() {

        if (logger != null
                && appender != null) {

            logger.removeAppender(
                    appender);
        }

        if (appender != null) {
            appender.stop();
        }

        if (logger != null) {
            logger.setLevel(
                    previousLoggerLevel);
        }

        Debug.set(
                Debug.INFO,
                previousInfoEnabled);

        UtilProperties.setPropertyValueInMemory(
                PROPERTY_RESOURCE,
                TRACE_LEVEL_PROPERTY,
                previousTraceLevel);

        UtilProperties.setPropertyValueInMemory(
                PROPERTY_RESOURCE,
                PAYLOAD_MODE_PROPERTY,
                previousPayloadMode);
    }

    /**
     * Proves that the canonical monetary JSON example is measured and hashed
     * exactly as supplied to payloadExact.
     *
     * <p>The exact input is:</p>
     *
     * <pre>
     * {"amount":"20.00"}
     * </pre>
     *
     * <p>It contains 18 Java characters and 18 UTF-8 bytes. Its SHA-256 value
     * is fixed and therefore provides a regression check against accidental
     * normalization before metadata generation.</p>
     */
    @Test
    public void testPayloadExactUsesExactCanonicalBytes() {

        String payload =
                "{\"amount\":\"20.00\"}";

        try (AgentTrace trace =
                AgentTrace.start(
                        TEST_SERVICE,
                        TEST_AGENT)) {

            trace.payloadExact(
                    AgentTrace.TOOL_RESULT_FOR_MODEL,
                    payload,
                    "service",
                    "getCustomerOverdueInvoices");
        }

        String event =
                findEvent(
                        AgentTrace.TOOL_RESULT_FOR_MODEL);

        assertTrue(
                event.contains(
                        "payloadCharacters=18"));

        assertTrue(
                event.contains(
                        "payloadBytes=18"));

        assertTrue(
                event.contains(
                        "payloadSha256=\""
                        + "af9711ed6d293d74cdde5580208111b2020a4cf4543b2412c1c150213ec8659f"
                        + "\""));

        assertTrue(
                event.contains(
                        "payload=\"{\\\"amount\\\":\\\"20.00\\\"}\""));
    }

    /**
     * Proves that payloadExact does not parse and compact JSON before
     * calculating metadata.
     *
     * <p>The exact payload deliberately contains insignificant JSON
     * whitespace:</p>
     *
     * <pre>
     * {"amount" : "20.00"}
     * </pre>
     *
     * <p>The existing general payload path parses this JSON and writes it back
     * compactly. The exact path must retain the 20-character lexical form and
     * therefore produce a different SHA-256 digest.</p>
     */
    @Test
    public void testPayloadExactDoesNotNormalizeJsonBeforeMetadata() {

        String exactPayload =
                "{\"amount\" : \"20.00\"}";

        try (AgentTrace trace =
                AgentTrace.start(
                        TEST_SERVICE,
                        TEST_AGENT)) {

            trace.payloadExact(
                    AgentTrace.TOOL_RESULT_FOR_MODEL,
                    exactPayload,
                    "tracePath",
                    "exact");

            trace.payload(
                    AgentTrace.TOOL_RESULT_RAW,
                    exactPayload,
                    "tracePath",
                    "general");
        }

        String exactEvent =
                findEvent(
                        AgentTrace.TOOL_RESULT_FOR_MODEL);

        String generalEvent =
                findEvent(
                        AgentTrace.TOOL_RESULT_RAW);

        assertTrue(
                exactEvent.contains(
                        "payloadCharacters=20"));

        assertTrue(
                exactEvent.contains(
                        "payloadBytes=20"));

        assertTrue(
                exactEvent.contains(
                        "payloadSha256=\""
                        + "94cd40118c51b68a11158859e0bbffb3348abe0d04914fc2b831e1762c701e4c"
                        + "\""));

        assertTrue(
                exactEvent.contains(
                        "payload=\"{\\\"amount\\\" : \\\"20.00\\\"}\""));

        assertTrue(
                generalEvent.contains(
                        "payloadCharacters=18"));

        assertTrue(
                generalEvent.contains(
                        "payloadBytes=18"));

        assertTrue(
                generalEvent.contains(
                        "payloadSha256=\""
                        + "af9711ed6d293d74cdde5580208111b2020a4cf4543b2412c1c150213ec8659f"
                        + "\""));

        assertFalse(
                exactEvent.contains(
                        "af9711ed6d293d74cdde5580208111b2020a4cf4543b2412c1c150213ec8659f"));
    }

    /**
     * Proves that payloadBytes represents UTF-8 bytes rather than Java
     * character count.
     *
     * <p>The umlaut occupies one Java character but two UTF-8 bytes.</p>
     */
    @Test
    public void testPayloadExactUsesUtf8ByteCount() {

        String payload =
                "{\"name\":\"München\"}";

        try (AgentTrace trace =
                AgentTrace.start(
                        TEST_SERVICE,
                        TEST_AGENT)) {

            trace.payloadExact(
                    AgentTrace.TOOL_RESULT_FOR_MODEL,
                    payload);
        }

        String event =
                findEvent(
                        AgentTrace.TOOL_RESULT_FOR_MODEL);

        assertTrue(
                event.contains(
                        "payloadCharacters=18"));

        assertTrue(
                event.contains(
                        "payloadBytes=19"));

        assertTrue(
                event.contains(
                        "payloadSha256=\""
                        + "b86cb0661ba2f692879db1ad52a19734fd6dd47c5c8e8e10ee5db94ebeca79bc"
                        + "\""));
    }

    /**
     * Finds one captured trace event by its stable event name.
     *
     * @param eventName event name to locate
     * @return complete captured log line
     */
    private String findEvent(
            String eventName) {

        String expected =
                "event="
                + eventName
                + " ";

        for (String line
                : logWriter.toString()
                        .lines()
                        .toList()) {

            if (line.contains(
                    expected)) {

                return line;
            }
        }

        fail(
                "Expected trace event was not captured: "
                + eventName
                + System.lineSeparator()
                + "Captured log:"
                + System.lineSeparator()
                + logWriter);

        return "";
    }
}
