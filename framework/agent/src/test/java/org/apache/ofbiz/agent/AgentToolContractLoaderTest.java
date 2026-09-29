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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link AgentToolContractLoader}.
 *
 * <p>These tests load the real production agent tool configuration from:</p>
 *
 * <pre>
 * framework/agent/config/agent-tool-contracts.xml
 * </pre>
 *
 * <p>through the normal classpath loading path. The tests therefore verify
 * that:</p>
 *
 * <ul>
 *   <li>the production XML and XSD can be loaded;</li>
 *   <li>runtime XSD validation succeeds;</li>
 *   <li>the configured OFBiz service is discovered;</li>
 *   <li>the XML is converted into the intended AgentToolContract structure;</li>
 *   <li>field ordering is preserved;</li>
 *   <li>required-field semantics are preserved;</li>
 *   <li>nested list/object structure is preserved; and</li>
 *   <li>OFBiz semantic scalar types are preserved exactly.</li>
 * </ul>
 *
 * <p>This test deliberately does not duplicate the production XML in the test
 * tree. The production configuration itself is the artifact under test.</p>
 */
public class AgentToolContractLoaderTest {

    private static final String CUSTOMER_OVERDUE_SERVICE =
            "getCustomerOverdueInvoices";

    /**
     * Proves that the real production configuration loads successfully through
     * the default loader and currently defines exactly the intended tool
     * contract.
     */
    @Test
    public void testDefaultConfigurationLoadsExpectedContract() throws Exception {

        Map<String, AgentToolContract> contracts =
                AgentToolContractLoader.loadDefault();

        assertNotNull(
                contracts);

        /*
         * At this point in the platform implementation there is deliberately
         * exactly one configured agent tool contract.
         *
         * This assertion is intentionally strict. When a second governed tool
         * is added, this test should be consciously updated rather than
         * silently allowing the production configuration surface to grow.
         */
        assertEquals(
                1,
                contracts.size());

        assertEquals(
                Set.of(
                        CUSTOMER_OVERDUE_SERVICE),
                contracts.keySet());

        AgentToolContract contract =
                contracts.get(
                        CUSTOMER_OVERDUE_SERVICE);

        assertNotNull(
                contract);

        assertEquals(
                CUSTOMER_OVERDUE_SERVICE,
                contract.getServiceName());

        assertCustomerOverdueResultContract(
                contract.getResultContract());
    }

    /**
     * Verifies the complete expected model-visible result structure for
     * getCustomerOverdueInvoices.
     */
    private static void assertCustomerOverdueResultContract(
            AgentToolContract.ValueContract resultContract) {

        assertNotNull(
                resultContract);

        /*
         * OFBiz service results are maps, therefore the root contract must be
         * an OBJECT.
         */
        assertEquals(
                AgentToolContract.ValueKind.OBJECT,
                resultContract.getKind());

        assertTrue(
                resultContract.isObject());

        assertFalse(
                resultContract.isScalar());

        assertFalse(
                resultContract.isList());

        /*
         * OBJECT contracts have no scalar semantic type or list item contract.
         */
        assertEquals(
                null,
                resultContract.getOfbizType());

        assertEquals(
                null,
                resultContract.getItemContract());

        List<AgentToolContract.FieldContract> resultFields =
                resultContract.getFields();

        /*
         * Only business data explicitly permitted to cross the agent boundary
         * is declared.
         *
         * responseMessage and other OFBiz Service Engine metadata are
         * deliberately absent.
         */
        assertEquals(
                2,
                resultFields.size());

        assertScalarField(
                resultFields.get(0),
                "partyName",
                true,
                "name");

        assertInvoicePaymentInfoList(
                resultFields.get(1));
    }

    /**
     * Verifies the invoicePaymentInfoList declaration and its nested invoice
     * item object.
     */
    private static void assertInvoicePaymentInfoList(
            AgentToolContract.FieldContract listField) {

        assertEquals(
                "invoicePaymentInfoList",
                listField.getName());

        assertTrue(
                listField.isRequired());

        AgentToolContract.ValueContract listContract =
                listField.getValueContract();

        assertNotNull(
                listContract);

        assertEquals(
                AgentToolContract.ValueKind.LIST,
                listContract.getKind());

        assertTrue(
                listContract.isList());

        assertFalse(
                listContract.isObject());

        assertFalse(
                listContract.isScalar());

        assertEquals(
                null,
                listContract.getOfbizType());

        assertTrue(
                listContract.getFields()
                        .isEmpty());

        AgentToolContract.ValueContract invoiceObject =
                listContract.getItemContract();

        assertNotNull(
                invoiceObject);

        assertEquals(
                AgentToolContract.ValueKind.OBJECT,
                invoiceObject.getKind());

        assertTrue(
                invoiceObject.isObject());

        assertFalse(
                invoiceObject.isList());

        assertFalse(
                invoiceObject.isScalar());

        assertEquals(
                null,
                invoiceObject.getOfbizType());

        assertEquals(
                null,
                invoiceObject.getItemContract());

        List<AgentToolContract.FieldContract> invoiceFields =
                invoiceObject.getFields();

        /*
         * Declaration order is significant because AgentToolMapper will later
         * use contract order when constructing deterministic canonical JSON.
         */
        assertEquals(
                5,
                invoiceFields.size());

        assertScalarField(
                invoiceFields.get(0),
                "invoiceId",
                true,
                "id");

        assertScalarField(
                invoiceFields.get(1),
                "amount",
                true,
                "currency-amount");

        assertScalarField(
                invoiceFields.get(2),
                "paidAmount",
                true,
                "currency-amount");

        assertScalarField(
                invoiceFields.get(3),
                "outstandingAmount",
                true,
                "currency-amount");

        assertScalarField(
                invoiceFields.get(4),
                "dueDate",
                true,
                "date-time");
    }

    /**
     * Verifies one scalar field declaration completely.
     */
    private static void assertScalarField(
            AgentToolContract.FieldContract field,
            String expectedName,
            boolean expectedRequired,
            String expectedSemanticType) {

        assertNotNull(
                field);

        assertEquals(
                expectedName,
                field.getName());

        assertEquals(
                expectedRequired,
                field.isRequired());

        AgentToolContract.ValueContract valueContract =
                field.getValueContract();

        assertNotNull(
                valueContract);

        assertEquals(
                AgentToolContract.ValueKind.SCALAR,
                valueContract.getKind());

        assertTrue(
                valueContract.isScalar());

        assertFalse(
                valueContract.isObject());

        assertFalse(
                valueContract.isList());

        assertEquals(
                expectedSemanticType,
                valueContract.getOfbizType());

        /*
         * SCALAR contracts have no object fields or list-item definition.
         */
        assertTrue(
                valueContract.getFields()
                        .isEmpty());

        assertEquals(
                null,
                valueContract.getItemContract());
    }
}
