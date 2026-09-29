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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Defines the explicit semantic contract for data exposed by a governed OFBiz
 * service to the agent runtime.
 *
 * <p>This class is deliberately declarative. It describes:</p>
 *
 * <ul>
 *   <li>which OFBiz service the contract belongs to;</li>
 *   <li>which result fields are permitted to cross the agent boundary;</li>
 *   <li>the structural shape of those fields;</li>
 *   <li>the OFBiz semantic type of scalar values; and</li>
 *   <li>whether a declared field must be present in the OFBiz result.</li>
 * </ul>
 *
 * <p>This class does not perform value conversion. Primitive semantic
 * conversion is the responsibility of {@link AgentValueCodec}. Recursive
 * traversal and mapping of an actual OFBiz service result will be performed by
 * {@code AgentToolMapper}.</p>
 *
 * <p>The contract acts as a whitelist. Fields present in an OFBiz service
 * result but not declared in this contract are not model-visible. This is
 * important because normal OFBiz service results commonly contain framework
 * fields such as {@code responseMessage}, which are operational service
 * metadata rather than business data intended for the model.</p>
 *
 * <p>The first version of this class models the outbound OFBiz-to-agent
 * boundary only. Model-controlled input parameters and authority
 * classifications such as AUTHORITATIVE, MODEL and DERIVED will be added when
 * the inbound agent-to-OFBiz boundary is implemented.</p>
 */
public final class AgentToolContract {

    private final String serviceName;

    private final ValueContract resultContract;

    private AgentToolContract(
            String serviceName,
            ValueContract resultContract) {

        this.serviceName =
                requireName(
                        serviceName,
                        "service name");

        if (resultContract == null) {
            throw new IllegalArgumentException(
                    "Agent tool result contract must not be null");
        }

        if (resultContract.getKind()
                != ValueKind.OBJECT) {

            throw new IllegalArgumentException(
                    "Agent tool result contract for service ["
                    + this.serviceName
                    + "] must have OBJECT as its root kind");
        }

        this.resultContract =
                resultContract;
    }

    /**
     * Creates an outbound agent tool contract for an OFBiz service.
     *
     * <p>The root contract must be an OBJECT because an OFBiz service result is
     * represented as a named result map.</p>
     *
     * @param serviceName OFBiz service name
     * @param resultContract model-visible result structure
     * @return immutable tool contract
     */
    public static AgentToolContract forService(
            String serviceName,
            ValueContract resultContract) {

        return new AgentToolContract(
                serviceName,
                resultContract);
    }

    /**
     * Creates a scalar value contract.
     *
     * <p>The semantic type must be one explicitly supported by
     * {@link AgentValueCodec}. This validation occurs when the contract is
     * constructed so an invalid contract fails before any service result is
     * mapped.</p>
     *
     * @param ofbizType OFBiz semantic field type
     * @return scalar value contract
     */
    public static ValueContract scalar(
            String ofbizType) {

        return ValueContract.scalar(
                ofbizType);
    }

    /**
     * Creates an object value contract.
     *
     * <p>Field declaration order is preserved. The mapper will later use this
     * order when constructing canonical JSON, giving deterministic field
     * ordering independent of the order of the source OFBiz Map.</p>
     *
     * @param fields declared fields
     * @return object value contract
     */
    public static ValueContract object(
            FieldContract... fields) {

        return ValueContract.object(
                fields);
    }

    /**
     * Creates a list value contract.
     *
     * <p>The item contract may itself describe a scalar, object or nested
     * list.</p>
     *
     * @param itemContract contract applied to every list item
     * @return list value contract
     */
    public static ValueContract list(
            ValueContract itemContract) {

        return ValueContract.list(
                itemContract);
    }

    /**
     * Creates a required field declaration.
     *
     * <p>"Required" means that the source object must contain the declared key.
     * It does not mean the value must be non-null. A present null value remains
     * an explicit canonical JSON null. This distinction allows the boundary to
     * preserve the difference between a missing field and a field whose
     * business value is null.</p>
     *
     * @param name field name
     * @param valueContract field value contract
     * @return required field contract
     */
    public static FieldContract required(
            String name,
            ValueContract valueContract) {

        return new FieldContract(
                name,
                valueContract,
                true);
    }

    /**
     * Creates an optional field declaration.
     *
     * <p>If an optional field is absent from the source object, it may be
     * omitted from the canonical result. If it is present with a null value,
     * that null remains explicit.</p>
     *
     * @param name field name
     * @param valueContract field value contract
     * @return optional field contract
     */
    public static FieldContract optional(
            String name,
            ValueContract valueContract) {

        return new FieldContract(
                name,
                valueContract,
                false);
    }

    /**
     * Returns the OFBiz service name governed by this contract.
     *
     * @return service name
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * Returns the model-visible service result contract.
     *
     * @return root result contract
     */
    public ValueContract getResultContract() {
        return resultContract;
    }

    /**
     * Structural kinds supported by the outbound semantic boundary.
     */
    public enum ValueKind {

        /**
         * Primitive value whose semantics are defined by an OFBiz field type
         * and encoded by AgentValueCodec.
         */
        SCALAR,

        /**
         * Named set of declared child fields.
         */
        OBJECT,

        /**
         * Ordered collection whose members all follow the same item contract.
         */
        LIST
    }

    /**
     * Immutable description of one value in an agent tool contract.
     *
     * <p>A value has exactly one of three forms:</p>
     *
     * <ul>
     *   <li>SCALAR - carries an OFBiz semantic type;</li>
     *   <li>OBJECT - carries an ordered list of named fields;</li>
     *   <li>LIST - carries one item contract.</li>
     * </ul>
     *
     * <p>Invalid combinations cannot be constructed through the public factory
     * methods.</p>
     */
    public static final class ValueContract {

        private final ValueKind kind;

        private final String ofbizType;

        private final List<FieldContract> fields;

        private final ValueContract itemContract;

        private ValueContract(
                ValueKind kind,
                String ofbizType,
                List<FieldContract> fields,
                ValueContract itemContract) {

            this.kind =
                    kind;

            this.ofbizType =
                    ofbizType;

            this.fields =
                    fields;

            this.itemContract =
                    itemContract;
        }

        private static ValueContract scalar(
                String ofbizType) {

            String normalizedType =
                    normalizeSemanticType(
                            ofbizType);

            if (!AgentValueCodec.isSupportedType(
                    normalizedType)) {

                throw new IllegalArgumentException(
                        "Unsupported OFBiz semantic type ["
                        + normalizedType
                        + "] in agent tool contract");
            }

            return new ValueContract(
                    ValueKind.SCALAR,
                    normalizedType,
                    List.of(),
                    null);
        }

        private static ValueContract object(
                FieldContract... fields) {

            if (fields == null) {
                throw new IllegalArgumentException(
                        "Agent tool object fields must not be null");
            }

            if (fields.length == 0) {
                throw new IllegalArgumentException(
                        "Agent tool object contract must declare at least one field");
            }

            List<FieldContract> validatedFields =
                    new ArrayList<>(
                            fields.length);

            Set<String> fieldNames =
                    new HashSet<>();

            for (int index = 0;
                    index < fields.length;
                    index++) {

                FieldContract field =
                        fields[index];

                if (field == null) {
                    throw new IllegalArgumentException(
                            "Agent tool object field at index ["
                            + index
                            + "] must not be null");
                }

                if (!fieldNames.add(
                        field.getName())) {

                    throw new IllegalArgumentException(
                            "Duplicate field ["
                            + field.getName()
                            + "] in agent tool object contract");
                }

                validatedFields.add(
                        field);
            }

            return new ValueContract(
                    ValueKind.OBJECT,
                    null,
                    List.copyOf(
                            validatedFields),
                    null);
        }

        private static ValueContract list(
                ValueContract itemContract) {

            if (itemContract == null) {
                throw new IllegalArgumentException(
                        "Agent tool list item contract must not be null");
            }

            return new ValueContract(
                    ValueKind.LIST,
                    null,
                    List.of(),
                    itemContract);
        }

        /**
         * Returns the structural kind of this value.
         *
         * @return value kind
         */
        public ValueKind getKind() {
            return kind;
        }

        /**
         * Returns the OFBiz semantic type for a scalar.
         *
         * <p>This method returns null for OBJECT and LIST contracts.</p>
         *
         * @return normalized OFBiz semantic type, or null
         */
        public String getOfbizType() {
            return ofbizType;
        }

        /**
         * Returns the ordered field definitions for an OBJECT.
         *
         * <p>The returned list is immutable. SCALAR and LIST contracts return
         * an empty list.</p>
         *
         * @return immutable ordered fields
         */
        public List<FieldContract> getFields() {
            return fields;
        }

        /**
         * Returns the item contract for a LIST.
         *
         * <p>This method returns null for SCALAR and OBJECT contracts.</p>
         *
         * @return item contract, or null
         */
        public ValueContract getItemContract() {
            return itemContract;
        }

        /**
         * Returns whether this value is a scalar.
         *
         * @return true for SCALAR
         */
        public boolean isScalar() {
            return kind
                    == ValueKind.SCALAR;
        }

        /**
         * Returns whether this value is an object.
         *
         * @return true for OBJECT
         */
        public boolean isObject() {
            return kind
                    == ValueKind.OBJECT;
        }

        /**
         * Returns whether this value is a list.
         *
         * @return true for LIST
         */
        public boolean isList() {
            return kind
                    == ValueKind.LIST;
        }
    }

    /**
     * Immutable named field declaration within an OBJECT value contract.
     */
    public static final class FieldContract {

        private final String name;

        private final ValueContract valueContract;

        private final boolean required;

        private FieldContract(
                String name,
                ValueContract valueContract,
                boolean required) {

            this.name =
                    requireName(
                            name,
                            "field name");

            if (valueContract == null) {
                throw new IllegalArgumentException(
                        "Agent tool field ["
                        + this.name
                        + "] must have a value contract");
            }

            this.valueContract =
                    valueContract;

            this.required =
                    required;
        }

        /**
         * Returns the field name exactly as it must appear in the OFBiz result
         * and canonical agent object.
         *
         * @return field name
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the semantic/structural contract for this field.
         *
         * @return field value contract
         */
        public ValueContract getValueContract() {
            return valueContract;
        }

        /**
         * Returns whether this field must be present in the source object.
         *
         * <p>A required field may still contain null. Required describes key
         * presence, not nullability.</p>
         *
         * @return true when the field key must be present
         */
        public boolean isRequired() {
            return required;
        }
    }

    /**
     * Normalizes an OFBiz semantic type.
     */
    private static String normalizeSemanticType(
            String ofbizType) {

        if (ofbizType == null
                || ofbizType.isBlank()) {

            throw new IllegalArgumentException(
                    "OFBiz semantic type must not be empty");
        }

        return ofbizType.trim()
                .toLowerCase(
                        Locale.ROOT);
    }

    /**
     * Validates a required identifier or field name without altering its
     * business spelling.
     */
    private static String requireName(
            String value,
            String description) {

        if (value == null
                || value.isBlank()) {

            throw new IllegalArgumentException(
                    "Agent tool "
                    + description
                    + " must not be empty");
        }

        return value;
    }
}
