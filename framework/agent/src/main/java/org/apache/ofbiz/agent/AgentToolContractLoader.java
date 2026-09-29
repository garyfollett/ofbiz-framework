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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.apache.ofbiz.service.GenericServiceException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Loads declarative agent tool contracts from the agent component XML
 * configuration.
 *
 * <p>The loader converts:</p>
 *
 * <pre>
 * agent-tool-contracts.xml
 *          |
 *          v
 * XML Schema validation
 *          |
 *          v
 * AgentToolContract objects
 * </pre>
 *
 * <p>The loader is deliberately responsible only for configuration loading and
 * structural interpretation. It does not:</p>
 *
 * <ul>
 *   <li>invoke OFBiz services;</li>
 *   <li>map service result values;</li>
 *   <li>perform semantic value conversion;</li>
 *   <li>call an LLM;</li>
 *   <li>make authorization decisions.</li>
 * </ul>
 *
 * <p>Those responsibilities belong elsewhere in the agent runtime.</p>
 *
 * <p>The loader fails closed. Missing configuration, invalid XML, unsupported
 * contract versions, duplicate service contracts, unsupported semantic types,
 * or malformed contract structures cause loading to fail.</p>
 */
public final class AgentToolContractLoader {

    /**
     * Default XML configuration resource.
     *
     * <p>The agent component places its config directory on the OFBiz
     * application classpath.</p>
     */
    public static final String DEFAULT_CONTRACT_RESOURCE =
            "agent-tool-contracts.xml";

    /**
     * XSD defining the supported XML contract syntax.
     */
    public static final String DEFAULT_SCHEMA_RESOURCE =
            "agent-tool-contracts.xsd";

    /**
     * Configuration format currently understood by this runtime.
     */
    public static final String SUPPORTED_VERSION =
            "1.0";

    /**
     * Defensive limit on recursively nested object/list contracts.
     */
    private static final int MAX_NESTING_DEPTH =
            32;

    private final ClassLoader classLoader;

    private final String contractResource;

    private final String schemaResource;

    /**
     * Creates a loader for the standard agent component configuration.
     */
    public AgentToolContractLoader() {

        this(
                resolveDefaultClassLoader(),
                DEFAULT_CONTRACT_RESOURCE,
                DEFAULT_SCHEMA_RESOURCE);
    }

    /**
     * Creates a loader using explicit classpath resource names.
     *
     * @param classLoader class loader from which XML resources are read
     * @param contractResource contract XML classpath resource
     * @param schemaResource XSD classpath resource
     */
    public AgentToolContractLoader(
            ClassLoader classLoader,
            String contractResource,
            String schemaResource) {

        if (classLoader == null) {
            throw new IllegalArgumentException(
                    "Agent tool contract class loader must not be null");
        }

        this.classLoader =
                classLoader;

        this.contractResource =
                requireResourceName(
                        contractResource,
                        "contract resource");

        this.schemaResource =
                requireResourceName(
                        schemaResource,
                        "schema resource");
    }

    /**
     * Loads the default agent tool contract configuration.
     *
     * @return immutable map keyed by OFBiz service name
     * @throws GenericServiceException when configuration cannot be loaded
     */
    public static Map<String, AgentToolContract> loadDefault()
            throws GenericServiceException {

        return new AgentToolContractLoader()
                .load();
    }

    /**
     * Loads, validates and parses all configured agent tool contracts.
     *
     * @return immutable contracts keyed by service name
     * @throws GenericServiceException when configuration is invalid
     */
    public Map<String, AgentToolContract> load()
            throws GenericServiceException {

        Schema schema =
                loadSchema();

        Document document =
                loadDocument(
                        schema);

        return parseDocument(
                document);
    }

    /**
     * Loads the configured XSD using JAXP secure processing.
     *
     * <p>Do not use XMLConstants.ACCESS_EXTERNAL_DTD or
     * XMLConstants.ACCESS_EXTERNAL_SCHEMA here. The Xerces SchemaFactory used
     * by OFBiz does not recognise those properties in this runtime
     * configuration. The local schema contains no imports or includes, and
     * secure processing is enabled.</p>
     */
    private Schema loadSchema()
            throws GenericServiceException {

        SchemaFactory schemaFactory =
                SchemaFactory.newInstance(
                        XMLConstants.W3C_XML_SCHEMA_NS_URI);

        try {
            schemaFactory.setFeature(
                    XMLConstants.FEATURE_SECURE_PROCESSING,
                    true);

        } catch (SAXException e) {

            throw new GenericServiceException(
                    "Unable to configure secure XML schema processing",
                    e);
        }

        try (InputStream schemaStream =
                openRequiredResource(
                        schemaResource)) {

            StreamSource schemaSource =
                    new StreamSource(
                            schemaStream);

            schemaSource.setSystemId(
                    schemaResource);

            return schemaFactory.newSchema(
                    schemaSource);

        } catch (IOException | SAXException e) {

            throw new GenericServiceException(
                    "Unable to load agent tool contract schema resource ["
                    + schemaResource
                    + "]",
                    e);
        }
    }

    /**
     * Loads and validates the contract XML document.
     */
    private Document loadDocument(
            Schema schema)
            throws GenericServiceException {

        DocumentBuilderFactory factory =
                DocumentBuilderFactory.newInstance();

        factory.setNamespaceAware(
                true);

        factory.setXIncludeAware(
                false);

        factory.setExpandEntityReferences(
                false);

        /*
         * Supplying the Schema directly makes validation independent of the
         * xsi:noNamespaceSchemaLocation value in the document.
         */
        factory.setSchema(
                schema);

        configureSecureParser(
                factory);

        try {

            DocumentBuilder builder =
                    factory.newDocumentBuilder();

            builder.setErrorHandler(
                    new StrictErrorHandler());

            try (InputStream contractStream =
                    openRequiredResource(
                            contractResource)) {

                Document document =
                        builder.parse(
                                contractStream);

                document.getDocumentElement()
                        .normalize();

                return document;
            }

        } catch (ParserConfigurationException
                | SAXException
                | IOException e) {

            throw new GenericServiceException(
                    "Unable to load agent tool contract resource ["
                    + contractResource
                    + "]",
                    e);
        }
    }

    /**
     * Applies the same core secure parser controls used by OFBiz UtilXml.
     *
     * <p>External general entities, external parameter entities and external
     * DTD loading are disabled. XInclude and entity-reference expansion are
     * also disabled by the caller.</p>
     */
    private static void configureSecureParser(
            DocumentBuilderFactory factory)
            throws GenericServiceException {

        try {
            factory.setFeature(
                    XMLConstants.FEATURE_SECURE_PROCESSING,
                    true);

            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities",
                    false);

            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities",
                    false);

            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false);

        } catch (ParserConfigurationException e) {

            throw new GenericServiceException(
                    "Unable to configure secure agent tool XML parser",
                    e);
        }
    }

    /**
     * Converts the validated DOM document into immutable
     * {@link AgentToolContract} objects.
     */
    private Map<String, AgentToolContract> parseDocument(
            Document document)
            throws GenericServiceException {

        Element root =
                document.getDocumentElement();

        requireElementName(
                root,
                "agent-tool-contracts");

        String version =
                requireAttribute(
                        root,
                        "version");

        if (!SUPPORTED_VERSION.equals(
                version)) {

            throw new GenericServiceException(
                    "Unsupported agent tool contract configuration version ["
                    + version
                    + "]; supported version is ["
                    + SUPPORTED_VERSION
                    + "]");
        }

        Map<String, AgentToolContract> contracts =
                new LinkedHashMap<>();

        List<Element> toolElements =
                directChildElements(
                        root,
                        "agent-tool");

        if (toolElements.isEmpty()) {
            throw new GenericServiceException(
                    "Agent tool contract configuration contains no agent-tool entries");
        }

        for (Element toolElement : toolElements) {

            AgentToolContract contract =
                    parseAgentTool(
                            toolElement);

            AgentToolContract existing =
                    contracts.putIfAbsent(
                            contract.getServiceName(),
                            contract);

            if (existing != null) {
                throw new GenericServiceException(
                        "Duplicate agent tool contract for OFBiz service ["
                        + contract.getServiceName()
                        + "]");
            }
        }

        return Map.copyOf(
                contracts);
    }

    /**
     * Parses one configured OFBiz service exposure contract.
     */
    private AgentToolContract parseAgentTool(
            Element toolElement)
            throws GenericServiceException {

        String serviceName =
                requireAttribute(
                        toolElement,
                        "service-name");

        Element resultElement =
                requireSingleDirectChild(
                        toolElement,
                        "result");

        Element rootObject =
                requireSingleDirectChild(
                        resultElement,
                        "object");

        AgentToolContract.ValueContract resultContract =
                parseObject(
                        rootObject,
                        1);

        try {
            return AgentToolContract.forService(
                    serviceName,
                    resultContract);

        } catch (IllegalArgumentException e) {

            throw new GenericServiceException(
                    "Invalid agent tool contract for OFBiz service ["
                    + serviceName
                    + "]",
                    e);
        }
    }

    /**
     * Parses an object contract.
     */
    private AgentToolContract.ValueContract parseObject(
            Element objectElement,
            int depth)
            throws GenericServiceException {

        checkDepth(
                depth);

        List<Element> fieldElements =
                directChildElements(
                        objectElement,
                        "field");

        if (fieldElements.isEmpty()) {
            throw new GenericServiceException(
                    "Agent tool object contract must contain at least one field");
        }

        List<AgentToolContract.FieldContract> fields =
                new ArrayList<>(
                        fieldElements.size());

        for (Element fieldElement : fieldElements) {

            fields.add(
                    parseField(
                            fieldElement,
                            depth + 1));
        }

        try {
            return AgentToolContract.object(
                    fields.toArray(
                            new AgentToolContract.FieldContract[0]));

        } catch (IllegalArgumentException e) {

            throw new GenericServiceException(
                    "Invalid agent tool object contract",
                    e);
        }
    }

    /**
     * Parses one named field inside an object contract.
     */
    private AgentToolContract.FieldContract parseField(
            Element fieldElement,
            int depth)
            throws GenericServiceException {

        checkDepth(
                depth);

        String fieldName =
                requireAttribute(
                        fieldElement,
                        "name");

        boolean required =
                parseRequiredAttribute(
                        fieldElement);

        AgentToolContract.ValueContract valueContract =
                parseSingleValueDefinition(
                        fieldElement,
                        depth + 1);

        try {
            if (required) {
                return AgentToolContract.required(
                        fieldName,
                        valueContract);
            }

            return AgentToolContract.optional(
                    fieldName,
                    valueContract);

        } catch (IllegalArgumentException e) {

            throw new GenericServiceException(
                    "Invalid agent tool field contract ["
                    + fieldName
                    + "]",
                    e);
        }
    }

    /**
     * Parses exactly one scalar, object or list child.
     */
    private AgentToolContract.ValueContract parseSingleValueDefinition(
            Element parent,
            int depth)
            throws GenericServiceException {

        checkDepth(
                depth);

        List<Element> children =
                directChildElements(
                        parent);

        if (children.size() != 1) {
            throw new GenericServiceException(
                    "Agent tool value definition under element ["
                    + parent.getTagName()
                    + "] must contain exactly one scalar, object or list");
        }

        Element valueElement =
                children.get(0);

        switch (valueElement.getTagName()) {

        case "scalar":
            return parseScalar(
                    valueElement);

        case "object":
            return parseObject(
                    valueElement,
                    depth + 1);

        case "list":
            return parseList(
                    valueElement,
                    depth + 1);

        default:
            throw new GenericServiceException(
                    "Unsupported agent tool value element ["
                    + valueElement.getTagName()
                    + "]");
        }
    }

    /**
     * Parses a scalar semantic contract.
     */
    private AgentToolContract.ValueContract parseScalar(
            Element scalarElement)
            throws GenericServiceException {

        String semanticType =
                requireAttribute(
                        scalarElement,
                        "semantic-type");

        if (!AgentValueCodec.isSupportedType(
                semanticType)) {

            throw new GenericServiceException(
                    "Unsupported OFBiz semantic type ["
                    + semanticType
                    + "] in agent tool contract");
        }

        try {
            return AgentToolContract.scalar(
                    semanticType);

        } catch (IllegalArgumentException e) {

            throw new GenericServiceException(
                    "Invalid scalar agent tool contract for semantic type ["
                    + semanticType
                    + "]",
                    e);
        }
    }

    /**
     * Parses a list contract and its single item definition.
     */
    private AgentToolContract.ValueContract parseList(
            Element listElement,
            int depth)
            throws GenericServiceException {

        checkDepth(
                depth);

        AgentToolContract.ValueContract itemContract =
                parseSingleValueDefinition(
                        listElement,
                        depth + 1);

        try {
            return AgentToolContract.list(
                    itemContract);

        } catch (IllegalArgumentException e) {

            throw new GenericServiceException(
                    "Invalid agent tool list contract",
                    e);
        }
    }

    /**
     * Reads the field required attribute.
     *
     * <p>The XSD default is true. XML Schema boolean lexical values
     * {@code true}, {@code false}, {@code 1} and {@code 0} are supported.</p>
     */
    private static boolean parseRequiredAttribute(
            Element fieldElement)
            throws GenericServiceException {

        String value =
                fieldElement.getAttribute(
                        "required");

        if (value == null
                || value.isBlank()
                || "true".equals(value)
                || "1".equals(value)) {

            return true;
        }

        if ("false".equals(value)
                || "0".equals(value)) {

            return false;
        }

        throw new GenericServiceException(
                "Invalid boolean value ["
                + value
                + "] for required attribute on field ["
                + fieldElement.getAttribute("name")
                + "]");
    }

    /**
     * Enforces the recursive contract-depth limit.
     */
    private static void checkDepth(
            int depth)
            throws GenericServiceException {

        if (depth > MAX_NESTING_DEPTH) {
            throw new GenericServiceException(
                    "Agent tool contract exceeds maximum nesting depth ["
                    + MAX_NESTING_DEPTH
                    + "]");
        }
    }

    /**
     * Opens a required classpath resource.
     */
    private InputStream openRequiredResource(
            String resourceName)
            throws GenericServiceException {

        InputStream stream =
                classLoader.getResourceAsStream(
                        resourceName);

        if (stream == null) {
            throw new GenericServiceException(
                    "Required agent tool configuration resource ["
                    + resourceName
                    + "] was not found on the classpath");
        }

        return stream;
    }

    /**
     * Returns all direct child elements.
     */
    private static List<Element> directChildElements(
            Element parent) {

        List<Element> elements =
                new ArrayList<>();

        NodeList children =
                parent.getChildNodes();

        for (int index = 0;
                index < children.getLength();
                index++) {

            Node child =
                    children.item(
                            index);

            if (child.getNodeType()
                    == Node.ELEMENT_NODE) {

                elements.add(
                        (Element) child);
            }
        }

        return elements;
    }

    /**
     * Returns direct child elements with the supplied element name.
     */
    private static List<Element> directChildElements(
            Element parent,
            String elementName) {

        List<Element> elements =
                new ArrayList<>();

        for (Element child
                : directChildElements(
                        parent)) {

            if (elementName.equals(
                    child.getTagName())) {

                elements.add(
                        child);
            }
        }

        return elements;
    }

    /**
     * Requires exactly one named direct child.
     */
    private static Element requireSingleDirectChild(
            Element parent,
            String elementName)
            throws GenericServiceException {

        List<Element> children =
                directChildElements(
                        parent,
                        elementName);

        if (children.size() != 1) {
            throw new GenericServiceException(
                    "Element ["
                    + parent.getTagName()
                    + "] must contain exactly one direct child ["
                    + elementName
                    + "] but contained ["
                    + children.size()
                    + "]");
        }

        return children.get(0);
    }

    /**
     * Requires a non-empty XML attribute.
     */
    private static String requireAttribute(
            Element element,
            String attributeName)
            throws GenericServiceException {

        String value =
                element.getAttribute(
                        attributeName);

        if (value == null
                || value.isBlank()) {

            throw new GenericServiceException(
                    "Element ["
                    + element.getTagName()
                    + "] requires non-empty attribute ["
                    + attributeName
                    + "]");
        }

        return value;
    }

    /**
     * Verifies the expected root element name.
     */
    private static void requireElementName(
            Element element,
            String expectedName)
            throws GenericServiceException {

        if (!expectedName.equals(
                element.getTagName())) {

            throw new GenericServiceException(
                    "Expected XML element ["
                    + expectedName
                    + "] but found ["
                    + element.getTagName()
                    + "]");
        }
    }

    /**
     * Validates constructor resource names.
     */
    private static String requireResourceName(
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

    /**
     * Resolves the class loader used for component configuration.
     */
    private static ClassLoader resolveDefaultClassLoader() {

        ClassLoader contextLoader =
                Thread.currentThread()
                        .getContextClassLoader();

        if (contextLoader != null) {
            return contextLoader;
        }

        return AgentToolContractLoader.class
                .getClassLoader();
    }

    /**
     * Strict SAX error handler.
     */
    private static final class StrictErrorHandler
            implements ErrorHandler {

        @Override
        public void warning(
                SAXParseException exception) {
            /*
             * Warnings are intentionally non-fatal.
             */
        }

        @Override
        public void error(
                SAXParseException exception)
                throws SAXException {

            throw exception;
        }

        @Override
        public void fatalError(
                SAXParseException exception)
                throws SAXException {

            throw exception;
        }
    }
}
