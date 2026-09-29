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

import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.apache.ofbiz.service.GenericServiceException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Maps OFBiz service results into the governed canonical JSON representation
 * exposed to an agent/model.
 *
 * <p>The mapper is the structural boundary between OFBiz runtime values and
 * model-visible JSON:</p>
 *
 * <pre>
 * OFBiz service result
 *         |
 *         v
 * AgentToolContract
 *         |
 *         v
 * AgentToolMapper
 *         |
 *         +---- object/list structure
 *         +---- field whitelisting
 *         +---- required-field enforcement
 *         |
 *         v
 * AgentValueCodec
 *         |
 *         +---- semantic scalar conversion
 *         |
 *         v
 * canonical model-facing JSON
 * </pre>
 *
 * <p>The mapper deliberately does not use generic Jackson conversion such as
 * {@code valueToTree()} for OFBiz runtime values. Every scalar value must cross
 * the boundary through {@link AgentValueCodec} using an explicitly configured
 * semantic type.</p>
 *
 * <p>Fields that are present in an OFBiz service result but absent from the
 * {@link AgentToolContract} are not exposed to the model.</p>
 */
public final class AgentToolMapper {

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper();

    private static final JsonNodeFactory JSON =
            JsonNodeFactory.instance;

    private AgentToolMapper() {
    }

    /**
     * Maps an OFBiz service result into its canonical model-facing JSON tree.
     *
     * @param contract governed agent tool contract
     * @param serviceResult OFBiz service result
     * @param timeZone effective execution timezone used by date-time semantics
     * @return canonical JSON object
     * @throws GenericServiceException if the result does not conform to the
     *         configured contract
     */
    public static ObjectNode map(
            AgentToolContract contract,
            Map<String, ?> serviceResult,
            TimeZone timeZone)
            throws GenericServiceException {

        if (contract == null) {
            throw new GenericServiceException(
                    "Agent tool contract must not be null");
        }

        if (serviceResult == null) {
            throw new GenericServiceException(
                    "OFBiz service result must not be null for agent tool ["
                    + contract.getServiceName()
                    + "]");
        }

        AgentToolContract.ValueContract resultContract =
                contract.getResultContract();

        if (resultContract == null
                || !resultContract.isObject()) {

            throw new GenericServiceException(
                    "Agent tool contract root must be an object for OFBiz service ["
                    + contract.getServiceName()
                    + "]");
        }

        JsonNode mapped =
                mapValue(
                        resultContract,
                        serviceResult,
                        timeZone,
                        "$");

        if (!(mapped instanceof ObjectNode)) {
            throw new GenericServiceException(
                    "Agent tool contract root mapping did not produce an object for OFBiz service ["
                    + contract.getServiceName()
                    + "]");
        }

        return (ObjectNode) mapped;
    }

    /**
     * Maps an OFBiz service result and serializes the resulting canonical JSON
     * tree.
     *
     * <p>The returned string is suitable for use as the exact model-bound tool
     * result payload. Financial fixed-point values remain JSON strings, so
     * lexical scale such as {@code "20.00"} is preserved.</p>
     *
     * @param contract governed agent tool contract
     * @param serviceResult OFBiz service result
     * @param timeZone effective execution timezone
     * @return canonical JSON string
     * @throws GenericServiceException if mapping or serialization fails
     */
    public static String mapToJson(
            AgentToolContract contract,
            Map<String, ?> serviceResult,
            TimeZone timeZone)
            throws GenericServiceException {

        ObjectNode mapped =
                map(
                        contract,
                        serviceResult,
                        timeZone);

        try {
            return OBJECT_MAPPER.writeValueAsString(
                    mapped);

        } catch (JsonProcessingException e) {

            throw new GenericServiceException(
                    "Unable to serialize canonical agent tool result for OFBiz service ["
                    + contract.getServiceName()
                    + "]",
                    e);
        }
    }

    /**
     * Maps one value according to its structural contract.
     */
    private static JsonNode mapValue(
            AgentToolContract.ValueContract contract,
            Object value,
            TimeZone timeZone,
            String path)
            throws GenericServiceException {

        if (contract == null) {
            throw mappingFailure(
                    path,
                    "value contract is null");
        }

        /*
         * Null is a legitimate explicit value.
         *
         * Required means that an object key must be present. It does not mean
         * the value associated with that key must be non-null.
         *
         * Scalar nulls would also be accepted by AgentValueCodec, but handling
         * null here gives object and list contracts the same explicit-null
         * semantics without attempting structural traversal.
         */
        if (value == null) {
            return NullNode.getInstance();
        }

        switch (contract.getKind()) {

        case SCALAR:
            return mapScalar(
                    contract,
                    value,
                    timeZone,
                    path);

        case OBJECT:
            return mapObject(
                    contract,
                    value,
                    timeZone,
                    path);

        case LIST:
            return mapList(
                    contract,
                    value,
                    timeZone,
                    path);

        default:
            throw mappingFailure(
                    path,
                    "unsupported contract value kind ["
                    + contract.getKind()
                    + "]");
        }
    }

    /**
     * Maps one scalar through AgentValueCodec.
     */
    private static JsonNode mapScalar(
            AgentToolContract.ValueContract contract,
            Object value,
            TimeZone timeZone,
            String path)
            throws GenericServiceException {

        String semanticType =
                contract.getOfbizType();

        if (semanticType == null
                || semanticType.isBlank()) {

            throw mappingFailure(
                    path,
                    "scalar contract has no semantic type");
        }

        try {
            return AgentValueCodec.encode(
                    semanticType,
                    value,
                    timeZone);

        } catch (GenericServiceException e) {

            throw new GenericServiceException(
                    "Unable to map agent tool value at ["
                    + path
                    + "] using semantic type ["
                    + semanticType
                    + "]: "
                    + e.getMessage(),
                    e);
        }
    }

    /**
     * Maps an OFBiz map-shaped value to a JSON object.
     *
     * <p>{@code GenericEntity} and {@code GenericValue} implement
     * {@code Map<String,Object>}, so they naturally satisfy this boundary
     * without special-case serialization.</p>
     */
    private static ObjectNode mapObject(
            AgentToolContract.ValueContract contract,
            Object value,
            TimeZone timeZone,
            String path)
            throws GenericServiceException {

        if (!(value instanceof Map<?, ?>)) {
            throw mappingFailure(
                    path,
                    "expected map/object value but received ["
                    + value.getClass().getName()
                    + "]");
        }

        Map<?, ?> source =
                (Map<?, ?>) value;

        ObjectNode result =
                JSON.objectNode();

        for (AgentToolContract.FieldContract field
                : contract.getFields()) {

            String fieldName =
                    field.getName();

            String fieldPath =
                    childPath(
                            path,
                            fieldName);

            boolean present =
                    source.containsKey(
                            fieldName);

            if (!present) {

                if (field.isRequired()) {
                    throw mappingFailure(
                            fieldPath,
                            "required field is absent");
                }

                /*
                 * Optional and absent fields are omitted completely from the
                 * model-facing JSON.
                 */
                continue;
            }

            Object sourceValue =
                    source.get(
                            fieldName);

            JsonNode mappedValue =
                    mapValue(
                            field.getValueContract(),
                            sourceValue,
                            timeZone,
                            fieldPath);

            result.set(
                    fieldName,
                    mappedValue);
        }

        /*
         * Source fields that are not declared by the contract are ignored.
         *
         * This is the model-visible whitelist boundary.
         */
        return result;
    }

    /**
     * Maps an ordered OFBiz list into a JSON array.
     *
     * <p>Only {@link List} is accepted. Arbitrary collections such as sets are
     * deliberately rejected because their iteration order may not provide a
     * stable model-facing representation.</p>
     */
    private static ArrayNode mapList(
            AgentToolContract.ValueContract contract,
            Object value,
            TimeZone timeZone,
            String path)
            throws GenericServiceException {

        if (!(value instanceof List<?>)) {
            throw mappingFailure(
                    path,
                    "expected ordered List value but received ["
                    + value.getClass().getName()
                    + "]");
        }

        AgentToolContract.ValueContract itemContract =
                contract.getItemContract();

        if (itemContract == null) {
            throw mappingFailure(
                    path,
                    "list contract has no item contract");
        }

        List<?> source =
                (List<?>) value;

        ArrayNode result =
                JSON.arrayNode();

        for (int index = 0;
                index < source.size();
                index++) {

            Object item =
                    source.get(
                            index);

            String itemPath =
                    path
                    + "["
                    + index
                    + "]";

            JsonNode mappedItem =
                    mapValue(
                            itemContract,
                            item,
                            timeZone,
                            itemPath);

            result.add(
                    mappedItem);
        }

        return result;
    }

    /**
     * Builds a readable object-field path for mapping diagnostics.
     */
    private static String childPath(
            String parentPath,
            String fieldName) {

        return parentPath
                + "."
                + fieldName;
    }

    /**
     * Creates a mapping exception carrying the exact contract path at which the
     * boundary violation occurred.
     */
    private static GenericServiceException mappingFailure(
            String path,
            String message) {

        return new GenericServiceException(
                "Agent tool result mapping failed at ["
                + path
                + "]: "
                + message);
    }
}
