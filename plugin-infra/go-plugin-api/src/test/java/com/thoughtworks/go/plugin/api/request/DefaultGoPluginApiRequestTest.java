/*
 * Copyright Thoughtworks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.thoughtworks.go.plugin.api.request;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

public class DefaultGoPluginApiRequestTest {

    @Test
    public void shouldBeInstanceOfGoPluginApiRequest() {
        DefaultGoPluginApiRequest request = new DefaultGoPluginApiRequest("extension", "1.0", "request-name");
        assertThat(request).isInstanceOf(GoPluginApiRequest.class);
    }


    @Test
    public void shouldReturnUnmodifiableRequestHeaders() {
        DefaultGoPluginApiRequest request = new DefaultGoPluginApiRequest("extension", "1.0", "request-name");
        Map<String, String> requestHeaders = request.requestHeaders();
        try {
            requestHeaders.put("new-key", "new-value");
            fail("Should not allow modification of request headers");
        } catch (UnsupportedOperationException ignored) {
        }
        try {
            requestHeaders.remove("key");
            fail("Should not allow modification of request headers");
        } catch (UnsupportedOperationException ignored) {
        }
    }

    @Test
    public void shouldReturnUnmodifiableRequestParams() {
        DefaultGoPluginApiRequest request = new DefaultGoPluginApiRequest("extension", "1.0", "request-name");
        Map<String, String> requestParameters = request.requestParameters();
        try {
            requestParameters.put("new-key", "new-value");
            fail("Should not allow modification of request params");
        } catch (UnsupportedOperationException ignored) {
        }
        try {
            requestParameters.remove("key");
            fail("Should not allow modification of request params");
        } catch (UnsupportedOperationException ignored) {
        }
    }

    @Test
    public void shouldBeAbleToSetAndGetRequestBody() {
        DefaultGoPluginApiRequest request = new DefaultGoPluginApiRequest("extension", "1.0", "request-name");
        String requestBody = "request-body";
        request.setRequestBody(requestBody);
        assertThat(request.requestBody()).isEqualTo(requestBody);
    }

    @Test
    public void shouldBeAbleToInitializePluginResponse() {
        String extension = "extension";
        String version = "1.0";
        String requestName = "request-name";
        DefaultGoPluginApiRequest request = new DefaultGoPluginApiRequest(extension, version, requestName);
        assertThat(request.extension()).isEqualTo(extension);
        assertThat(request.extensionVersion()).isEqualTo(version);
        assertThat(request.requestName()).isEqualTo(requestName);
    }

    @Test
    public void shouldAbleToSetRequestHeaders() {
        final DefaultGoPluginApiRequest request = new DefaultGoPluginApiRequest("extension", "1.0", "request-name");

        final Map<String, String> headers = new HashMap<>();
        headers.put("HEADER-1", "HEADER-VALUE-1");
        headers.put("HEADER-2", "HEADER-VALUE-2");

        request.setRequestHeaders(headers);

        assertThat(request.requestHeaders()).containsEntry("HEADER-1", "HEADER-VALUE-1");
        assertThat(request.requestHeaders()).containsEntry("HEADER-2", "HEADER-VALUE-2");
    }
}
