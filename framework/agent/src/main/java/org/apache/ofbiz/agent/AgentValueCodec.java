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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import org.apache.ofbiz.service.GenericServiceException;

import com.fasterxml.jackson.databind.JsonNode;
/*import com.fasterxml.jackson.databind.node.BooleanNode;*/
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Converts OFBiz semantic values into the canonical representation used at the
 * OFBiz-to-agent boundary.
 *
 * <p>This class is deliberately stricter than a general-purpose JSON
 * serializer. Its purpose is not to serialize arbitrary Java objects. Its
 * purpose is to preserve OFBiz business semantics when values are exposed to
 * an LLM or another agent-runtime component.</p>
 *
 * <p>The codec therefore requires both:</p>
 *
 * <ul>
 *   <li>the OFBiz semantic field type; and</li>
 *   <li>the corresponding Java value.</li>
 * </ul>
 *
 * <p>The Java runtime type alone is insufficient. For example, a
 * {@link BigDecimal} might represent a currency amount, a precise currency
 * value or a six-decimal fixed-point value. Those OFBiz semantic types require
 * different canonical representations.</p>
 *
 * <p>Important invariants:</p>
 *
 * <ul>
 *   <li>No arbitrary Java object is serialized.</li>
 *   <li>No generic Jackson {@code valueToTree()} fallback exists.</li>
 *   <li>No {@code String.valueOf()} fallback exists for unsupported types.</li>
 *   <li>Fixed-point decimal values never use scientific notation.</li>
 *   <li>No decimal value is silently rounded.</li>
 *   <li>Calendar dates always use ISO-8601 {@code YYYY-MM-DD}.</li>
 *   <li>Date-times always use an explicit timezone offset.</li>
 *   <li>Locale-specific numeric date formats never cross this boundary.</li>
 *   <li>Unsupported semantic types fail closed.</li>
 * </ul>
 *
 * <p>This first version implements the outbound OFBiz-to-agent direction.
 * Inbound agent-to-OFBiz decoding will be implemented separately after the
 * outbound boundary has been proven.</p>
 */
public final class AgentValueCodec {

    /*
     * OFBiz fixed-point scales.
     *
     * These correspond to the standard OFBiz field type definitions:
     *
     * currency-amount   DECIMAL(18,2)
     * currency-precise  DECIMAL(18,3)
     * fixed-point       DECIMAL(18,6)
     */
    private static final int CURRENCY_AMOUNT_SCALE = 2;

    private static final int CURRENCY_PRECISE_SCALE = 3;

    private static final int FIXED_POINT_SCALE = 6;

    /*
     * Canonical date-time representation.
     *
     * OFBiz date-time fields use millisecond precision. The representation is
     * deliberately fixed at three fractional digits so equivalent timestamps
     * always produce the same lexical representation.
     *
     * Examples:
     *
     * 2026-09-28T10:29:00.000+10:00
     * 2026-12-28T10:29:00.000+11:00
     * 2026-09-28T00:29:00.000+00:00
     *
     * The explicit offset removes US/Australian/European date ambiguity and
     * makes the business timezone visible to the model.
     */
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            new DateTimeFormatterBuilder()
                    .appendPattern("uuuu-MM-dd'T'HH:mm:ss.SSS")
                    .appendOffset("+HH:MM", "+00:00")
                    .toFormatter(Locale.ROOT);

    /*
     * Canonical SQL time representation.
     *
     * Time is a local wall-clock value and does not itself contain a timezone.
     * Timezone semantics belong to date-time values or to the surrounding
     * business contract.
     */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern(
                    "HH:mm:ss.SSS",
                    Locale.ROOT);

    /*
     * OFBiz semantic field types represented canonically as JSON strings.
     *
     * These remain strings even when their contents happen to look numeric.
     * For example, an OFBiz ID "000123" must remain "000123", not become 123.
     */
    private static final Set<String> STRING_TYPES =
            Set.of(
                    "id",
                    "id-long",
                    "id-vlong",
                    "indicator",
                    "very-short",
                    "short-varchar",
                    "long-varchar",
                    "very-long",
                    "comment",
                    "description",
                    "name",
                    "value",
                    "credit-card-number",
                    "credit-card-date",
                    "email",
                    "url",
                    "tel-number");

    /*
     * These OFBiz types are intentionally unsupported at the generic agent
     * boundary. An explicit higher-level AgentToolContract must define a safe
     * semantic representation before such values can be exposed.
     */
    private static final Set<String> EXPLICITLY_UNSUPPORTED_TYPES =
            Set.of(
                    "blob",
                    "byte-array",
                    "object");

    private AgentValueCodec() {
    }

    /**
     * Converts one OFBiz semantic value into its canonical agent
     * representation.
     *
     * <p>For {@code date-time} values, {@code timeZone} is mandatory because a
     * {@link Timestamp} alone does not define the business timezone in which
     * the instant should be presented.</p>
     *
     * <p>For all other currently supported semantic types, the timezone is
     * ignored.</p>
     *
     * @param ofbizType OFBiz semantic field type
     * @param value Java value to encode
     * @param timeZone effective OFBiz/user timezone when required
     * @return canonical JSON value
     * @throws GenericServiceException if the semantic type is unsupported,
     *         the Java value is incompatible with the semantic type, required
     *         timezone information is missing, or conversion would lose
     *         information
     */
    public static JsonNode encode(
            String ofbizType,
            Object value,
            TimeZone timeZone)
            throws GenericServiceException {

        String normalizedType =
                normalizeType(
                        ofbizType);

        /*
         * Null is a valid canonical value for every supported semantic type.
         *
         * We still validate the semantic type first so an unsupported field
         * type cannot silently pass through merely because its current value
         * happens to be null.
         */
        validateSupportedType(
                normalizedType);

        if (value == null) {
            return NullNode.getInstance();
        }

        switch (normalizedType) {

        case "currency-amount":
            return encodeFixedPoint(
                    normalizedType,
                    value,
                    CURRENCY_AMOUNT_SCALE);

        case "currency-precise":
            return encodeFixedPoint(
                    normalizedType,
                    value,
                    CURRENCY_PRECISE_SCALE);

        case "fixed-point":
            return encodeFixedPoint(
                    normalizedType,
                    value,
                    FIXED_POINT_SCALE);

        case "floating-point":
            return encodeFloatingPoint(
                    normalizedType,
                    value);

        case "integer":
            return encodeInteger(
                    normalizedType,
                    value);

        case "numeric":
            return encodeLong(
                    normalizedType,
                    value);

        case "date":
            return encodeDate(
                    normalizedType,
                    value);

        case "date-time":
            return encodeDateTime(
                    normalizedType,
                    value,
                    timeZone);

        case "time":
            return encodeTime(
                    normalizedType,
                    value);

        default:
            if (STRING_TYPES.contains(
                    normalizedType)) {

                return encodeString(
                        normalizedType,
                        value);
            }

            /*
             * validateSupportedType() should already have rejected anything
             * outside the supported set. Retain a fail-closed defensive check
             * so future changes cannot accidentally create a generic fallback.
             */
            throw new GenericServiceException(
                    "No canonical agent encoder exists for OFBiz type ["
                    + normalizedType
                    + "]");
        }
    }

    /**
     * Returns whether an OFBiz semantic type is explicitly supported by the
     * outbound codec.
     *
     * @param ofbizType OFBiz semantic field type
     * @return true when the type has a defined canonical representation
     */
    public static boolean isSupportedType(
            String ofbizType) {

        if (ofbizType == null
                || ofbizType.isBlank()) {

            return false;
        }

        String normalized =
                ofbizType.trim()
                        .toLowerCase(
                                Locale.ROOT);

        if (STRING_TYPES.contains(
                normalized)) {

            return true;
        }

        switch (normalized) {

        case "currency-amount":
        case "currency-precise":
        case "fixed-point":
        case "floating-point":
        case "integer":
        case "numeric":
        case "date":
        case "date-time":
        case "time":
            return true;

        default:
            return false;
        }
    }

    /**
     * Returns whether conversion of the semantic type requires an explicit
     * timezone.
     *
     * @param ofbizType OFBiz semantic type
     * @return true for date-time values
     */
    public static boolean requiresTimeZone(
            String ofbizType) {

        if (ofbizType == null) {
            return false;
        }

        return "date-time".equals(
                ofbizType.trim()
                        .toLowerCase(
                                Locale.ROOT));
    }

    /**
     * Encodes an OFBiz fixed-point decimal as a fixed-scale JSON string.
     *
     * <p>Strings are used deliberately rather than JSON numbers because JSON
     * numbers do not preserve decimal scale. For enterprise financial data,
     * {@code "20.00"} carries stronger semantics than a JSON number that a
     * parser may normalize to {@code 20}, {@code 20.0} or {@code 2E+1}.</p>
     *
     * <p>No rounding is permitted. A value requiring rounding to fit the OFBiz
     * semantic scale is rejected.</p>
     */
    private static JsonNode encodeFixedPoint(
            String ofbizType,
            Object value,
            int scale)
            throws GenericServiceException {

        if (!(value instanceof BigDecimal)) {
            throw incompatibleType(
                    ofbizType,
                    BigDecimal.class,
                    value);
        }

        BigDecimal decimal =
                (BigDecimal) value;

        final BigDecimal scaled;

        try {
            /*
             * RoundingMode.UNNECESSARY is critical.
             *
             * It permits harmless scale normalization:
             *
             *     BigDecimal("2E+1") -> "20.00"
             *
             * but rejects information-destroying conversion:
             *
             *     BigDecimal("20.123") as currency-amount
             *
             * rather than silently converting it to "20.12".
             */
            scaled =
                    decimal.setScale(
                            scale,
                            RoundingMode.UNNECESSARY);

        } catch (ArithmeticException e) {

            throw new GenericServiceException(
                    "OFBiz value for semantic type ["
                    + ofbizType
                    + "] cannot be represented at required scale ["
                    + scale
                    + "] without rounding",
                    e);
        }

        /*
         * toPlainString() explicitly prevents exponent notation.
         *
         * BigDecimal("2E+1") therefore becomes "20.00", never "2E+1".
         */
        return TextNode.valueOf(
                scaled.toPlainString());
    }

    /**
     * Encodes an OFBiz floating-point value.
     *
     * <p>NaN and infinity are rejected because they are not valid interoperable
     * JSON numeric values and have no safe canonical LLM representation in this
     * boundary contract.</p>
     */
    private static JsonNode encodeFloatingPoint(
            String ofbizType,
            Object value)
            throws GenericServiceException {

        if (!(value instanceof Double)) {
            throw incompatibleType(
                    ofbizType,
                    Double.class,
                    value);
        }

        double number =
                (Double) value;

        if (!Double.isFinite(
                number)) {

            throw new GenericServiceException(
                    "OFBiz value for semantic type ["
                    + ofbizType
                    + "] must be finite");
        }

        return DoubleNode.valueOf(
                number);
    }

    /**
     * Encodes an OFBiz integer.
     */
    private static JsonNode encodeInteger(
            String ofbizType,
            Object value)
            throws GenericServiceException {

        if (!(value instanceof Integer)) {
            throw incompatibleType(
                    ofbizType,
                    Integer.class,
                    value);
        }

        return IntNode.valueOf(
                (Integer) value);
    }

    /**
     * Encodes an OFBiz numeric value.
     *
     * <p>The standard OFBiz field type maps {@code numeric} to Java
     * {@link Long}.</p>
     */
    private static JsonNode encodeLong(
            String ofbizType,
            Object value)
            throws GenericServiceException {

        if (!(value instanceof Long)) {
            throw incompatibleType(
                    ofbizType,
                    Long.class,
                    value);
        }

        return LongNode.valueOf(
                (Long) value);
    }

    /**
     * Encodes an OFBiz calendar date.
     *
     * <p>The canonical format is always ISO-8601:</p>
     *
     * <pre>
     * YYYY-MM-DD
     * </pre>
     *
     * <p>No locale-specific formatting occurs here. The same date therefore
     * has exactly the same canonical representation for Australian, American,
     * British and other users.</p>
     */
    private static JsonNode encodeDate(
            String ofbizType,
            Object value)
            throws GenericServiceException {

        if (!(value instanceof Date)) {
            throw incompatibleType(
                    ofbizType,
                    Date.class,
                    value);
        }

        Date date =
                (Date) value;

        return TextNode.valueOf(
                date.toLocalDate()
                        .toString());
    }

    /**
     * Encodes an OFBiz date-time using an explicit business timezone.
     *
     * <p>A Timestamp represents the instant but not the business presentation
     * timezone. The effective OFBiz/user timezone must therefore be supplied
     * explicitly.</p>
     *
     * <p>The canonical form is:</p>
     *
     * <pre>
     * YYYY-MM-DDTHH:mm:ss.SSS+HH:mm
     * </pre>
     *
     * <p>The timezone offset is calculated for the timestamp itself, so
     * daylight-saving transitions are handled by the Java timezone rules.
     * Sydney therefore naturally produces {@code +10:00} or {@code +11:00}
     * depending on the date.</p>
     */
    private static JsonNode encodeDateTime(
            String ofbizType,
            Object value,
            TimeZone timeZone)
            throws GenericServiceException {

        if (!(value instanceof Timestamp)) {
            throw incompatibleType(
                    ofbizType,
                    Timestamp.class,
                    value);
        }

        if (timeZone == null) {
            throw new GenericServiceException(
                    "OFBiz semantic type [date-time] requires an explicit timezone");
        }

        Timestamp timestamp =
                (Timestamp) value;

        /*
         * Standard OFBiz date-time storage is millisecond precision.
         *
         * Reject finer precision rather than silently truncating it at the
         * canonical boundary.
         */
        int nanos =
                timestamp.getNanos();

        if ((nanos % 1_000_000) != 0) {
            throw new GenericServiceException(
                    "OFBiz date-time contains sub-millisecond precision "
                    + "that cannot be represented by the canonical "
                    + "millisecond date-time contract");
        }

        ZoneId zoneId =
                timeZone.toZoneId();

        ZonedDateTime dateTime =
                timestamp.toInstant()
                        .atZone(
                                zoneId);

        String formatted =
                DATE_TIME_FORMATTER.format(
                        dateTime);

        return TextNode.valueOf(
                formatted);
    }

    /**
     * Encodes an OFBiz SQL time as a deterministic local wall-clock time.
     *
     * <p>A SQL time does not by itself represent an instant and therefore does
     * not receive a timezone offset. If a business operation requires an
     * instant, the tool contract should use a date-time semantic type instead.</p>
     */
    private static JsonNode encodeTime(
            String ofbizType,
            Object value)
            throws GenericServiceException {

        if (!(value instanceof Time)) {
            throw incompatibleType(
                    ofbizType,
                    Time.class,
                    value);
        }

        Time time =
                (Time) value;

        LocalTime localTime =
                time.toLocalTime();

        return TextNode.valueOf(
                TIME_FORMATTER.format(
                        localTime));
    }

    /**
     * Encodes an OFBiz string-like semantic type.
     *
     * <p>No attempt is made to infer numbers, booleans, dates or identifiers
     * from string content.</p>
     */
    private static JsonNode encodeString(
            String ofbizType,
            Object value)
            throws GenericServiceException {

        if (!(value instanceof String)) {
            throw incompatibleType(
                    ofbizType,
                    String.class,
                    value);
        }

        return TextNode.valueOf(
                (String) value);
    }

    /**
     * Normalizes and validates the supplied semantic type name.
     */
    private static String normalizeType(
            String ofbizType)
            throws GenericServiceException {

        if (ofbizType == null
                || ofbizType.isBlank()) {

            throw new GenericServiceException(
                    "OFBiz semantic type must not be empty");
        }

        return ofbizType.trim()
                .toLowerCase(
                        Locale.ROOT);
    }

    /**
     * Ensures the semantic type has an explicitly defined canonical
     * representation.
     */
    private static void validateSupportedType(
            String ofbizType)
            throws GenericServiceException {

        if (EXPLICITLY_UNSUPPORTED_TYPES.contains(
                ofbizType)) {

            throw new GenericServiceException(
                    "OFBiz semantic type ["
                    + ofbizType
                    + "] is not permitted at the generic agent boundary; "
                    + "an explicit higher-level agent contract is required");
        }

        if (!isSupportedType(
                ofbizType)) {

            throw new GenericServiceException(
                    "Unsupported OFBiz semantic type ["
                    + ofbizType
                    + "] at the agent boundary");
        }
    }

    /**
     * Creates a consistent fail-closed exception for Java values that do not
     * match the Java type required by the OFBiz semantic type.
     */
    private static GenericServiceException incompatibleType(
            String ofbizType,
            Class<?> expectedClass,
            Object value) {

        String actualClass =
                value == null
                        ? "<null>"
                        : value.getClass()
                                .getName();

        return new GenericServiceException(
                "OFBiz semantic type ["
                + ofbizType
                + "] requires Java type ["
                + expectedClass.getName()
                + "] but received ["
                + actualClass
                + "]");
    }
}
