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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import org.apache.ofbiz.service.GenericServiceException;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Regression tests for {@link AgentToolMapper}.
 *
 * <p>The tests exercise the complete outbound semantic boundary:</p>
 *
 * <pre>
 * production XML
 *      |
 *      v
 * AgentToolContractLoader
 *      |
 *      v
 * AgentToolContract
 *      |
 * raw OFBiz service result
 *      |
 *      v
 * AgentToolMapper
 *      |
 *      v
 * AgentValueCodec
 *      |
 *      v
 * canonical model-facing JSON
 * </pre>
 *
 * <p>The principal regression case recreates the data shape that originally
 * caused a model to interpret the OFBiz {@code BigDecimal} value
 * {@code 2E+1} incorrectly. The governed boundary must expose that value as
 * the JSON string {@code "20.00"}.</p>
 */
public class AgentToolMapperTest {

    private static final String SERVICE_NAME =
            "getCustomerOverdueInvoices";

    private static final TimeZone SYDNEY_TIME_ZONE =
            TimeZone.getTimeZone(
                    "Australia/Sydney");

    /*
     * Original dueDate value observed in the OFBiz service result:
     *
     *     1145973599000
     *
     * UTC:
     *
     *     2006-04-25T13:59:59Z
     *
     * Australia/Sydney on that date:
     *
     *     2006-04-25T23:59:59.000+10:00
     */
    private static final long ORIGINAL_DUE_DATE_MILLIS =
            1145973599000L;

    /**
     * Recreates the original data-fidelity failure and proves that the
     * governed mapper produces the intended canonical representation.
     */
    @Test
    public void testOriginalScientificNotationIncidentMapsCanonically()
            throws Exception {

        AgentToolContract contract =
                loadProductionContract();

        Map<String, Object> rawResult =
                originalStyleServiceResult();

        String json =
                AgentToolMapper.mapToJson(
                        contract,
                        rawResult,
                        SYDNEY_TIME_ZONE);

        /*
         * The exact output is deliberately asserted.
         *
         * This proves:
         *
         * 1. field declaration order is preserved;
         * 2. responseMessage is not exposed;
         * 3. 2E+1 becomes the semantic currency value "20.00";
         * 4. zero becomes "0.00";
         * 5. dueDate becomes deterministic ISO date-time;
         * 6. monetary scale is represented inside JSON strings.
         */
        assertEquals(
                """
                {"partyName":"Euro Customer","invoicePaymentInfoList":[{"invoiceId":"demo11000","amount":"20.00","paidAmount":"0.00","outstandingAmount":"20.00","dueDate":"2006-04-25T23:59:59.000+10:00"}]}\
                """,
                json);
    }

    /**
     * Proves the actual JSON node types produced at the boundary.
     *
     * <p>Currency values must be JSON strings rather than JSON numbers. This
     * preserves fixed-point lexical scale and prevents downstream JSON parsers
     * from normalising values such as 20.00, 20.0 and 2E+1 into an ambiguous
     * numeric representation.</p>
     */
    @Test
    public void testCurrencyValuesAreJsonStrings()
            throws Exception {

        AgentToolContract contract =
                loadProductionContract();

        ObjectNode mapped =
                AgentToolMapper.map(
                        contract,
                        originalStyleServiceResult(),
                        SYDNEY_TIME_ZONE);

        JsonNode invoice =
                mapped.get(
                        "invoicePaymentInfoList")
                        .get(0);

        JsonNode amount =
                invoice.get(
                        "amount");

        JsonNode paidAmount =
                invoice.get(
                        "paidAmount");

        JsonNode outstandingAmount =
                invoice.get(
                        "outstandingAmount");

        assertTrue(
                amount.isTextual());

        assertTrue(
                paidAmount.isTextual());

        assertTrue(
                outstandingAmount.isTextual());

        assertEquals(
                "20.00",
                amount.textValue());

        assertEquals(
                "0.00",
                paidAmount.textValue());

        assertEquals(
                "20.00",
                outstandingAmount.textValue());
    }

    /**
     * Proves that fields not explicitly declared by the agent tool contract
     * cannot cross the model boundary.
     */
    @Test
    public void testUndeclaredFieldsAreNotExposed()
            throws Exception {

        AgentToolContract contract =
                loadProductionContract();

        Map<String, Object> rawResult =
                originalStyleServiceResult();

        rawResult.put(
                "internalOnly",
                "must-not-cross-agent-boundary");

        @SuppressWarnings("unchecked")
        Map<String, Object> invoice =
                (Map<String, Object>) ((List<?>) rawResult.get(
                        "invoicePaymentInfoList"))
                        .get(0);

        invoice.put(
                "internalInvoiceField",
                "also-must-not-cross");

        ObjectNode mapped =
                AgentToolMapper.map(
                        contract,
                        rawResult,
                        SYDNEY_TIME_ZONE);

        assertFalse(
                mapped.has(
                        "responseMessage"));

        assertFalse(
                mapped.has(
                        "internalOnly"));

        JsonNode mappedInvoice =
                mapped.get(
                        "invoicePaymentInfoList")
                        .get(0);

        assertFalse(
                mappedInvoice.has(
                        "internalInvoiceField"));

        assertEquals(
                2,
                mapped.size());

        assertEquals(
                5,
                mappedInvoice.size());
    }

    /**
     * Proves that a required contract field means that the source object key
     * must exist.
     */
    @Test
    public void testMissingRequiredRootFieldFailsClosed()
            throws Exception {

        AgentToolContract contract =
                loadProductionContract();

        Map<String, Object> rawResult =
                originalStyleServiceResult();

        rawResult.remove(
                "partyName");

        GenericServiceException exception =
                assertThrows(
                        GenericServiceException.class,
                        () -> AgentToolMapper.map(
                                contract,
                                rawResult,
                                SYDNEY_TIME_ZONE));

        assertTrue(
                exception.getMessage()
                        .contains(
                                "$.partyName"));

        assertTrue(
                exception.getMessage()
                        .contains(
                                "required field is absent"));
    }

    /**
     * Proves that required-field enforcement also applies recursively inside
     * nested list objects.
     */
    @Test
    public void testMissingRequiredNestedFieldFailsClosed()
            throws Exception {

        AgentToolContract contract =
                loadProductionContract();

        Map<String, Object> rawResult =
                originalStyleServiceResult();

        @SuppressWarnings("unchecked")
        Map<String, Object> invoice =
                (Map<String, Object>) ((List<?>) rawResult.get(
                        "invoicePaymentInfoList"))
                        .get(0);

        invoice.remove(
                "outstandingAmount");

        GenericServiceException exception =
                assertThrows(
                        GenericServiceException.class,
                        () -> AgentToolMapper.map(
                                contract,
                                rawResult,
                                SYDNEY_TIME_ZONE));

        assertTrue(
                exception.getMessage()
                        .contains(
                                "$.invoicePaymentInfoList[0].outstandingAmount"));

        assertTrue(
                exception.getMessage()
                        .contains(
                                "required field is absent"));
    }

    /**
     * Proves that the mapper does not perform permissive numeric coercion.
     *
     * <p>A currency-amount contract expects the Java/OFBiz type required by
     * AgentValueCodec. A Double cannot silently cross a fixed-point financial
     * boundary.</p>
     */
    @Test
    public void testWrongFinancialJavaTypeFailsClosed()
            throws Exception {

        AgentToolContract contract =
                loadProductionContract();

        Map<String, Object> rawResult =
                originalStyleServiceResult();

        @SuppressWarnings("unchecked")
        Map<String, Object> invoice =
                (Map<String, Object>) ((List<?>) rawResult.get(
                        "invoicePaymentInfoList"))
                        .get(0);

        invoice.put(
                "amount",
                20.0d);

        GenericServiceException exception =
                assertThrows(
                        GenericServiceException.class,
                        () -> AgentToolMapper.map(
                                contract,
                                rawResult,
                                SYDNEY_TIME_ZONE));

        assertTrue(
                exception.getMessage()
                        .contains(
                                "$.invoicePaymentInfoList[0].amount"));

        assertTrue(
                exception.getMessage()
                        .contains(
                                "currency-amount"));
    }

    /**
     * Proves that a date-time value cannot be mapped without an explicit
     * effective timezone.
     */
    @Test
    public void testDateTimeWithoutTimeZoneFailsClosed()
            throws Exception {

        AgentToolContract contract =
                loadProductionContract();

        GenericServiceException exception =
                assertThrows(
                        GenericServiceException.class,
                        () -> AgentToolMapper.map(
                                contract,
                                originalStyleServiceResult(),
                                null));

        assertTrue(
                exception.getMessage()
                        .contains(
                                "$.invoicePaymentInfoList[0].dueDate"));

        assertTrue(
                exception.getMessage()
                        .toLowerCase()
                        .contains(
                                "timezone"));
    }

    /**
     * Proves that explicit null is different from an absent required key.
     *
     * <p>The contract semantics deliberately define required as key presence,
     * not non-nullability.</p>
     */
    @Test
    public void testRequiredFieldMayBeExplicitNull()
            throws Exception {

        AgentToolContract contract =
                loadProductionContract();

        Map<String, Object> rawResult =
                originalStyleServiceResult();

        rawResult.put(
                "partyName",
                null);

        ObjectNode mapped =
                AgentToolMapper.map(
                        contract,
                        rawResult,
                        SYDNEY_TIME_ZONE);

        assertTrue(
                mapped.has(
                        "partyName"));

        assertTrue(
                mapped.get(
                        "partyName")
                        .isNull());
    }

    /**
     * Proves that lists must have deterministic ordering.
     *
     * <p>The mapper accepts List rather than arbitrary Collection. A Set
     * cannot silently become model-facing JSON because its ordering contract
     * is not sufficiently explicit.</p>
     */
    @Test
    public void testUnorderedCollectionIsRejected()
            throws Exception {

        AgentToolContract contract =
                loadProductionContract();

        Map<String, Object> rawResult =
                originalStyleServiceResult();

        rawResult.put(
                "invoicePaymentInfoList",
                Set.of(
                        invoiceRecord()));

        GenericServiceException exception =
                assertThrows(
                        GenericServiceException.class,
                        () -> AgentToolMapper.map(
                                contract,
                                rawResult,
                                SYDNEY_TIME_ZONE));

        assertTrue(
                exception.getMessage()
                        .contains(
                                "$.invoicePaymentInfoList"));

        assertTrue(
                exception.getMessage()
                        .contains(
                                "expected ordered List"));
    }

    /**
     * Proves that an optional absent field is omitted rather than materialised
     * as JSON null.
     *
     * <p>This uses an in-memory contract because the current production
     * contract deliberately contains only required fields.</p>
     */
    @Test
    public void testOptionalAbsentFieldIsOmitted()
            throws Exception {

        AgentToolContract contract =
                AgentToolContract.forService(
                        "testOptionalService",
                        AgentToolContract.object(
                                AgentToolContract.required(
                                        "requiredValue",
                                        AgentToolContract.scalar(
                                                "name")),
                                AgentToolContract.optional(
                                        "optionalValue",
                                        AgentToolContract.scalar(
                                                "name"))));

        Map<String, Object> source =
                new LinkedHashMap<>();

        source.put(
                "requiredValue",
                "present");

        ObjectNode mapped =
                AgentToolMapper.map(
                        contract,
                        source,
                        SYDNEY_TIME_ZONE);

        assertEquals(
                "present",
                mapped.get(
                        "requiredValue")
                        .textValue());

        assertFalse(
                mapped.has(
                        "optionalValue"));

        assertEquals(
                1,
                mapped.size());
    }

    /**
     * Proves that an optional field that is present with an explicit null value
     * remains present as JSON null.
     */
    @Test
    public void testOptionalExplicitNullIsPreserved()
            throws Exception {

        AgentToolContract contract =
                AgentToolContract.forService(
                        "testOptionalNullService",
                        AgentToolContract.object(
                                AgentToolContract.required(
                                        "requiredValue",
                                        AgentToolContract.scalar(
                                                "name")),
                                AgentToolContract.optional(
                                        "optionalValue",
                                        AgentToolContract.scalar(
                                                "name"))));

        Map<String, Object> source =
                new LinkedHashMap<>();

        source.put(
                "requiredValue",
                "present");

        source.put(
                "optionalValue",
                null);

        ObjectNode mapped =
                AgentToolMapper.map(
                        contract,
                        source,
                        SYDNEY_TIME_ZONE);

        assertTrue(
                mapped.has(
                        "optionalValue"));

        assertTrue(
                mapped.get(
                        "optionalValue")
                        .isNull());
    }

    /**
     * Loads the actual production contract through the production loader.
     */
    private static AgentToolContract loadProductionContract()
            throws GenericServiceException {

        Map<String, AgentToolContract> contracts =
                AgentToolContractLoader.loadDefault();

        AgentToolContract contract =
                contracts.get(
                        SERVICE_NAME);

        assertNotNull(
                contract);

        return contract;
    }

    /**
     * Builds the raw service result shape that reproduced the original
     * data-fidelity incident.
     */
    private static Map<String, Object> originalStyleServiceResult() {

        Map<String, Object> result =
                new LinkedHashMap<>();

        result.put(
                "partyName",
                "Euro Customer");

        /*
         * This is normal OFBiz Service Engine metadata but is deliberately not
         * declared in the agent-facing contract.
         */
        result.put(
                "responseMessage",
                "success");

        List<Map<String, Object>> invoices =
                new ArrayList<>();

        invoices.add(
                invoiceRecord());

        result.put(
                "invoicePaymentInfoList",
                invoices);

        return result;
    }

    /**
     * Builds the invoice record from the original incident.
     */
    private static Map<String, Object> invoiceRecord() {

        Map<String, Object> invoice =
                new LinkedHashMap<>();

        invoice.put(
                "invoiceId",
                "demo11000");

        /*
         * Preserve the exact problematic Java BigDecimal lexical form.
         *
         * BigDecimal("2E+1") represents 20 with scale -1.
         *
         * AgentValueCodec must turn it into the governed currency boundary
         * representation "20.00".
         */
        invoice.put(
                "amount",
                new BigDecimal(
                        "2E+1"));

        invoice.put(
                "paidAmount",
                BigDecimal.ZERO);

        invoice.put(
                "outstandingAmount",
                new BigDecimal(
                        "2E+1"));

        invoice.put(
                "dueDate",
                Timestamp.from(
                        Instant.ofEpochMilli(
                                ORIGINAL_DUE_DATE_MILLIS)));

        return invoice;
    }
}
