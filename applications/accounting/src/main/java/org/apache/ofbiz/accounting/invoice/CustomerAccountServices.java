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
package org.apache.ofbiz.accounting.invoice;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Services exposing bounded customer-account information through the
 * normal OFBiz Service Engine.
 */
public final class CustomerAccountServices {

    private static final String MODULE = CustomerAccountServices.class.getName();

    private CustomerAccountServices() {
    }

    /**
     * Returns the customer's name and overdue outstanding sales invoices.
     *
     * <p>The service deliberately obtains all business information through
     * existing OFBiz services. It does not query accounting or party entities
     * directly.</p>
     *
     * @param dctx OFBiz dispatch context
     * @param context service input context
     * @return standard OFBiz service result
     */
    public static Map<String, Object> getCustomerOverdueInvoices(
            DispatchContext dctx, Map<String, Object> context) {

        LocalDispatcher dispatcher = dctx.getDispatcher();

        String partyId = (String) context.get("partyId");
        Object userLogin = context.get("userLogin");

        try {
            Map<String, Object> partyNameContext = new HashMap<>();
            partyNameContext.put("partyId", partyId);
            partyNameContext.put("userLogin", userLogin);

            if (context.get("locale") != null) {
                partyNameContext.put("locale", context.get("locale"));
            }
            if (context.get("timeZone") != null) {
                partyNameContext.put("timeZone", context.get("timeZone"));
            }

            Map<String, Object> partyNameResult =
                    dispatcher.runSync("getPartyNameForDate", partyNameContext);

            if (ServiceUtil.isError(partyNameResult)
                    || ServiceUtil.isFailure(partyNameResult)) {
                return ServiceUtil.returnError(
                        ServiceUtil.getErrorMessage(partyNameResult));
            }

            Map<String, Object> invoiceContext = new HashMap<>();
            invoiceContext.put("invoiceTypeId", "SALES_INVOICE");
            invoiceContext.put("daysOffset", 0L);
            invoiceContext.put("partyId", partyId);
            invoiceContext.put("userLogin", userLogin);

            if (context.get("locale") != null) {
                invoiceContext.put("locale", context.get("locale"));
            }
            if (context.get("timeZone") != null) {
                invoiceContext.put("timeZone", context.get("timeZone"));
            }

            Map<String, Object> invoiceResult =
                    dispatcher.runSync(
                            "getInvoicePaymentInfoListByDueDateOffset",
                            invoiceContext);

            if (ServiceUtil.isError(invoiceResult)
                    || ServiceUtil.isFailure(invoiceResult)) {
                return ServiceUtil.returnError(
                        ServiceUtil.getErrorMessage(invoiceResult));
            }

            Object invoicePaymentInfoList =
                    invoiceResult.get("invoicePaymentInfoList");

            if (invoicePaymentInfoList == null) {
                invoicePaymentInfoList = List.of();
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("partyName", partyNameResult.get("fullName"));
            result.put("invoicePaymentInfoList", invoicePaymentInfoList);

            return result;

        } catch (GenericServiceException e) {
            Debug.logError(e, "Unable to retrieve customer overdue invoices", MODULE);
            return ServiceUtil.returnError(
                    "Unable to retrieve customer overdue invoices.");
        }
    }
}
