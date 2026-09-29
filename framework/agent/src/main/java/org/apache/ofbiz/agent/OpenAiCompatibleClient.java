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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Minimal HTTP adapter for an OpenAI-compatible Chat Completions endpoint.
 *
 * <p>This class contains no OFBiz business logic and no agent orchestration.
 * It is responsible only for sending JSON to the configured
 * {@code /chat/completions} endpoint and returning the parsed JSON response.</p>
 */
public final class OpenAiCompatibleClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final URI chatCompletionsUri;
    private final Duration requestTimeout;

    /**
     * Creates a client for an OpenAI-compatible API.
     *
     * @param baseUrl base API URL, for example {@code http://192.168.56.1:8000/v1}
     * @param connectTimeoutMillis HTTP connection timeout in milliseconds
     * @param requestTimeoutMillis maximum duration of one request in milliseconds
     */
    public OpenAiCompatibleClient(
            String baseUrl,
            long connectTimeoutMillis,
            long requestTimeoutMillis) {

        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "baseUrl must not be empty");
        }

        if (connectTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "connectTimeoutMillis must be greater than zero");
        }

        if (requestTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "requestTimeoutMillis must be greater than zero");
        }

        String normalizedBaseUrl =
                baseUrl.endsWith("/")
                        ? baseUrl.substring(
                                0,
                                baseUrl.length() - 1)
                        : baseUrl;

        this.chatCompletionsUri =
                URI.create(
                        normalizedBaseUrl
                        + "/chat/completions");

        this.requestTimeout =
                Duration.ofMillis(
                        requestTimeoutMillis);

        this.httpClient =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(
                                Duration.ofMillis(
                                        connectTimeoutMillis))
                        .build();
    }

    /**
     * Sends one Chat Completions request.
     *
     * @param requestBody complete OpenAI-compatible request body
     * @return parsed JSON response
     * @throws IOException if the request cannot be sent, the endpoint returns
     *         a non-success status, or the response is not valid JSON
     */
    public JsonNode createChatCompletion(
            JsonNode requestBody)
            throws IOException {

        if (requestBody == null
                || !requestBody.isObject()) {
            throw new IllegalArgumentException(
                    "requestBody must be a JSON object");
        }

        String requestJson =
                OBJECT_MAPPER.writeValueAsString(
                        requestBody);

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(chatCompletionsUri)
                        .timeout(requestTimeout)
                        .header(
                                "Content-Type",
                                "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        requestJson))
                        .build();

        final HttpResponse<String> response;

        try {
            response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IOException(
                    "Interrupted while calling LLM endpoint",
                    e);
        }

        int statusCode =
                response.statusCode();

        String responseBody =
                response.body();

        if (statusCode < 200
                || statusCode >= 300) {

            String errorBody =
                    responseBody == null
                            || responseBody.isBlank()
                                    ? "<empty response body>"
                                    : responseBody;

            throw new IOException(
                    "LLM endpoint returned HTTP status "
                    + statusCode
                    + ": "
                    + errorBody);
        }

        if (responseBody == null
                || responseBody.isBlank()) {
            throw new IOException(
                    "LLM endpoint returned an empty response");
        }

        return OBJECT_MAPPER.readTree(
                responseBody);
    }
}
