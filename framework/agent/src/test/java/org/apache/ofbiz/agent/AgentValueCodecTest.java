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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.TimeZone;

import org.apache.ofbiz.service.GenericServiceException;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Regression tests for {@link AgentValueCodec}.
 *
 * <p>These tests define the canonical primitive OFBiz-to-agent semantic
 * boundary. They deliberately test lexical representation as well as value
 * semantics because values crossing an LLM boundary must not depend on
 * arbitrary Java/Jackson serialization choices.</p>
 */
public class AgentValueCodecTest {

    private static final TimeZone SYDNEY_TIME_ZONE =
            TimeZone.getTimeZone(
                    "Australia/Sydney");

    private static final TimeZone UTC_TIME_ZONE =
            TimeZone.getTimeZone(
                    "UTC");

    /**
     * Regression for the defect that caused an OFBiz currency amount represented
     * internally as BigDecimal("2E+1") to reach the LLM as scientific notation.
     *
     * <p>The canonical representation must be the fixed-scale string
     * {@code "20.00"}.</p>
     */
    @Test
    public void testCurrencyAmountScientificNotationBecomesFixedScale() throws Exception {

        JsonNode encoded =
                AgentValueCodec.encode(
                        "currency-amount",
                        new BigDecimal("2E+1"),
                        null);

        assertTrue(
                encoded.isTextual());

        assertEquals(
                "20.00",
                encoded.asText());
    }

    /**
     * A whole-number currency amount must still preserve the semantic two
     * decimal places required by currency-amount.
     */
    @Test
    public void testCurrencyAmountWholeNumberPreservesTwoDecimalPlaces() throws Exception {

        JsonNode encoded =
                AgentValueCodec.encode(
                        "currency-amount",
                        new BigDecimal("20"),
                        null);

        assertEquals(
                "20.00",
                encoded.asText());
    }

    /**
     * Existing valid scale must be preserved canonically.
     */
    @Test
    public void testCurrencyAmountExistingScaleRemainsCanonical() throws Exception {

        JsonNode encoded =
                AgentValueCodec.encode(
                        "currency-amount",
                        new BigDecimal("20.00"),
                        null);

        assertEquals(
                "20.00",
                encoded.asText());
    }

    /**
     * Negative financial values must retain sign and fixed scale.
     */
    @Test
    public void testCurrencyAmountNegativeValue() throws Exception {

        JsonNode encoded =
                AgentValueCodec.encode(
                        "currency-amount",
                        new BigDecimal("-20.5"),
                        null);

        assertEquals(
                "-20.50",
                encoded.asText());
    }

    /**
     * No silent financial rounding is permitted at the semantic boundary.
     */
    @Test
    public void testCurrencyAmountRejectsValueThatRequiresRounding() {

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        "currency-amount",
                        new BigDecimal("20.001"),
                        null));
    }

    /**
     * currency-precise has three canonical decimal places.
     */
    @Test
    public void testCurrencyPreciseUsesThreeDecimalPlaces() throws Exception {

        JsonNode encoded =
                AgentValueCodec.encode(
                        "currency-precise",
                        new BigDecimal("20"),
                        null);

        assertEquals(
                "20.000",
                encoded.asText());
    }

    /**
     * fixed-point has six canonical decimal places.
     */
    @Test
    public void testFixedPointUsesSixDecimalPlaces() throws Exception {

        JsonNode encoded =
                AgentValueCodec.encode(
                        "fixed-point",
                        new BigDecimal("1.2"),
                        null);

        assertEquals(
                "1.200000",
                encoded.asText());
    }

    /**
     * fixed-point values may increase scale without changing value.
     */
    @Test
    public void testFixedPointPreservesExactValue() throws Exception {

        JsonNode encoded =
                AgentValueCodec.encode(
                        "fixed-point",
                        new BigDecimal("123.456789"),
                        null);

        assertEquals(
                "123.456789",
                encoded.asText());
    }

    /**
     * fixed-point conversion must not silently discard precision.
     */
    @Test
    public void testFixedPointRejectsExcessPrecision() {

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        "fixed-point",
                        new BigDecimal("123.4567891"),
                        null));
    }

    /**
     * Calendar dates are canonical ISO-8601 values and therefore have no
     * American/Australian numeric-date ambiguity.
     */
    @Test
    public void testDateUsesIso8601Format() throws Exception {

        Date value =
                Date.valueOf(
                        "2026-09-28");

        JsonNode encoded =
                AgentValueCodec.encode(
                        "date",
                        value,
                        null);

        assertTrue(
                encoded.isTextual());

        assertEquals(
                "2026-09-28",
                encoded.asText());
    }

    /**
     * A localized textual date is not accepted as an OFBiz date value.
     *
     * <p>This prevents values such as 04/05/2026 from being interpreted as
     * either 4 May or April 5.</p>
     */
    @Test
    public void testDateRejectsLocalizedStringInput() {

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        "date",
                        "28/09/2026",
                        null));

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        "date",
                        "09/28/2026",
                        null));
    }

    /**
     * Date-time conversion uses the explicit supplied timezone.
     *
     * <p>At this instant Sydney is on AEST, UTC+10.</p>
     */
    @Test
    public void testDateTimeUsesSydneyStandardTimeOffset() throws Exception {

        Timestamp value =
                Timestamp.from(
                        Instant.parse(
                                "2026-09-28T00:29:00.123Z"));

        JsonNode encoded =
                AgentValueCodec.encode(
                        "date-time",
                        value,
                        SYDNEY_TIME_ZONE);

        assertEquals(
                "2026-09-28T10:29:00.123+10:00",
                encoded.asText());
    }

    /**
     * Date-time conversion must use timezone rules for the actual instant,
     * rather than treating Sydney as permanently UTC+10.
     *
     * <p>At this December instant Sydney is on AEDT, UTC+11.</p>
     */
    @Test
    public void testDateTimeUsesSydneyDaylightSavingOffset() throws Exception {

        Timestamp value =
                Timestamp.from(
                        Instant.parse(
                                "2026-12-28T00:29:00.123Z"));

        JsonNode encoded =
                AgentValueCodec.encode(
                        "date-time",
                        value,
                        SYDNEY_TIME_ZONE);

        assertEquals(
                "2026-12-28T11:29:00.123+11:00",
                encoded.asText());
    }

    /**
     * UTC is represented explicitly rather than omitting timezone information.
     */
    @Test
    public void testDateTimeUsesExplicitUtcOffset() throws Exception {

        Timestamp value =
                Timestamp.from(
                        Instant.parse(
                                "2026-09-28T00:29:00.123Z"));

        JsonNode encoded =
                AgentValueCodec.encode(
                        "date-time",
                        value,
                        UTC_TIME_ZONE);

        assertEquals(
                "2026-09-28T00:29:00.123+00:00",
                encoded.asText());
    }

    /**
     * A date-time must not silently fall back to the JVM default timezone.
     */
    @Test
    public void testDateTimeRequiresExplicitTimeZone() {

        Timestamp value =
                Timestamp.from(
                        Instant.parse(
                                "2026-09-28T00:29:00.000Z"));

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        "date-time",
                        value,
                        null));
    }

    /**
     * The current canonical contract is millisecond precision. Finer precision
     * must fail rather than being silently truncated.
     */
    @Test
    public void testDateTimeRejectsSubMillisecondPrecision() {

        Timestamp value =
                Timestamp.from(
                        Instant.parse(
                                "2026-09-28T00:29:00.123456Z"));

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        "date-time",
                        value,
                        UTC_TIME_ZONE));
    }

    /**
     * SQL time values have a deterministic millisecond representation.
     */
    @Test
    public void testTimeUsesDeterministicFormat() throws Exception {

        Time value =
                Time.valueOf(
                        "09:30:15");

        JsonNode encoded =
                AgentValueCodec.encode(
                        "time",
                        value,
                        null);

        assertEquals(
                "09:30:15.000",
                encoded.asText());
    }

    /**
     * IDs are strings, even when their contents look numeric.
     */
    @Test
    public void testIdPreservesLeadingZeroes() throws Exception {

        JsonNode encoded =
                AgentValueCodec.encode(
                        "id",
                        "000123",
                        null);

        assertTrue(
                encoded.isTextual());

        assertEquals(
                "000123",
                encoded.asText());
    }

    /**
     * Long agent/business identifiers remain strings.
     */
    @Test
    public void testIdLongRemainsString() throws Exception {

        JsonNode encoded =
                AgentValueCodec.encode(
                        "id-long",
                        "CUSTOMER_ACCOUNT_ANALYST",
                        null);

        assertEquals(
                "CUSTOMER_ACCOUNT_ANALYST",
                encoded.asText());
    }

    /**
     * String content must never be reinterpreted as another semantic type.
     */
    @Test
    public void testStringLikeValueIsNotReinterpreted() throws Exception {

        JsonNode encoded =
                AgentValueCodec.encode(
                        "name",
                        "00123",
                        null);

        assertTrue(
                encoded.isTextual());

        assertEquals(
                "00123",
                encoded.asText());
    }

    /**
     * OFBiz integer values remain JSON integers.
     */
    @Test
    public void testIntegerRemainsJsonInteger() throws Exception {

        JsonNode encoded =
                AgentValueCodec.encode(
                        "integer",
                        Integer.valueOf(
                                42),
                        null);

        assertTrue(
                encoded.isIntegralNumber());

        assertEquals(
                42,
                encoded.intValue());
    }

    /**
     * OFBiz numeric maps to a Java Long and remains an integral JSON value.
     */
    @Test
    public void testNumericRemainsJsonLong() throws Exception {

        JsonNode encoded =
                AgentValueCodec.encode(
                        "numeric",
                        Long.valueOf(
                                9_223_372_036L),
                        null);

        assertTrue(
                encoded.isIntegralNumber());

        assertEquals(
                9_223_372_036L,
                encoded.longValue());
    }

    /**
     * Finite OFBiz floating-point values remain JSON numbers.
     */
    @Test
    public void testFloatingPointRemainsJsonNumber() throws Exception {

        JsonNode encoded =
                AgentValueCodec.encode(
                        "floating-point",
                        Double.valueOf(
                                1.25d),
                        null);

        assertTrue(
                encoded.isFloatingPointNumber());

        assertEquals(
                1.25d,
                encoded.doubleValue());
    }

    /**
     * NaN has no permitted canonical JSON representation.
     */
    @Test
    public void testFloatingPointRejectsNaN() {

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        "floating-point",
                        Double.NaN,
                        null));
    }

    /**
     * Positive infinity has no permitted canonical JSON representation.
     */
    @Test
    public void testFloatingPointRejectsPositiveInfinity() {

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        "floating-point",
                        Double.POSITIVE_INFINITY,
                        null));
    }

    /**
     * Negative infinity has no permitted canonical JSON representation.
     */
    @Test
    public void testFloatingPointRejectsNegativeInfinity() {

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        "floating-point",
                        Double.NEGATIVE_INFINITY,
                        null));
    }

    /**
     * Null remains explicit JSON null for a supported semantic type.
     */
    @Test
    public void testNullBecomesJsonNull() throws Exception {

        JsonNode encoded =
                AgentValueCodec.encode(
                        "currency-amount",
                        null,
                        null);

        assertTrue(
                encoded.isNull());
    }

    /**
     * Null must not allow an unsupported semantic type to bypass fail-closed
     * type validation.
     */
    @Test
    public void testNullDoesNotBypassUnsupportedTypeValidation() {

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        "unknown-type",
                        null,
                        null));
    }

    /**
     * Explicitly dangerous generic object types are not permitted.
     */
    @Test
    public void testObjectTypeFailsClosed() {

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        "object",
                        new Object(),
                        null));
    }

    /**
     * Binary values require a higher-level explicit contract.
     */
    @Test
    public void testByteArrayTypeFailsClosed() {

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        "byte-array",
                        new byte[] {1, 2, 3},
                        null));
    }

    /**
     * Blob values require a higher-level explicit contract.
     */
    @Test
    public void testBlobTypeFailsClosed() {

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        "blob",
                        null,
                        null));
    }

    /**
     * Unknown semantic types must never fall through to generic Jackson
     * serialization.
     */
    @Test
    public void testUnknownSemanticTypeFailsClosed() {

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        "made-up-type",
                        "value",
                        null));
    }

    /**
     * A Java value incompatible with the declared OFBiz semantic type must
     * fail rather than being coerced.
     */
    @Test
    public void testWrongJavaTypeFailsClosed() {

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        "currency-amount",
                        Double.valueOf(
                                20.0d),
                        null));
    }

    /**
     * An Integer must not be silently widened into an OFBiz numeric Long.
     */
    @Test
    public void testNumericRejectsIntegerJavaType() {

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        "numeric",
                        Integer.valueOf(
                                1000),
                        null));
    }

    /**
     * An OFBiz integer must receive exactly an Integer.
     */
    @Test
    public void testIntegerRejectsLongJavaType() {

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        "integer",
                        Long.valueOf(
                                42L),
                        null));
    }

    /**
     * Semantic type names are deliberately normalized for harmless whitespace
     * and case differences.
     */
    @Test
    public void testSemanticTypeNameNormalization() throws Exception {

        JsonNode encoded =
                AgentValueCodec.encode(
                        "  CURRENCY-AMOUNT  ",
                        new BigDecimal(
                                "20"),
                        null);

        assertEquals(
                "20.00",
                encoded.asText());
    }

    /**
     * Empty type names are invalid.
     */
    @Test
    public void testEmptySemanticTypeFailsClosed() {

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        " ",
                        "value",
                        null));
    }

    /**
     * Null type names are invalid.
     */
    @Test
    public void testNullSemanticTypeFailsClosed() {

        assertThrows(GenericServiceException.class, () ->
                AgentValueCodec.encode(
                        null,
                        "value",
                        null));
    }

    /**
     * Public capability reporting must agree with actual encoding support.
     */
    @Test
    public void testSupportedTypeDetection() {

        assertTrue(
                AgentValueCodec.isSupportedType(
                        "currency-amount"));

        assertTrue(
                AgentValueCodec.isSupportedType(
                        "date-time"));

        assertTrue(
                AgentValueCodec.isSupportedType(
                        "id"));

        assertTrue(
                AgentValueCodec.isSupportedType(
                        "name"));

        assertFalse(
                AgentValueCodec.isSupportedType(
                        "blob"));

        assertFalse(
                AgentValueCodec.isSupportedType(
                        "object"));

        assertFalse(
                AgentValueCodec.isSupportedType(
                        "unknown-type"));

        assertFalse(
                AgentValueCodec.isSupportedType(
                        null));
    }

    /**
     * Only date-time currently requires an explicit timezone.
     */
    @Test
    public void testRequiresTimeZoneDetection() {

        assertTrue(
                AgentValueCodec.requiresTimeZone(
                        "date-time"));

        assertTrue(
                AgentValueCodec.requiresTimeZone(
                        " DATE-TIME "));

        assertFalse(
                AgentValueCodec.requiresTimeZone(
                        "date"));

        assertFalse(
                AgentValueCodec.requiresTimeZone(
                        "time"));

        assertFalse(
                AgentValueCodec.requiresTimeZone(
                        "currency-amount"));

        assertFalse(
                AgentValueCodec.requiresTimeZone(
                        null));
    }
}
