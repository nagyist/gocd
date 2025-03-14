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
package com.thoughtworks.go.plugin.access.elastic;

import com.thoughtworks.go.domain.JobIdentifier;
import com.thoughtworks.go.plugin.access.ExtensionsRegistry;
import com.thoughtworks.go.plugin.access.common.AbstractExtension;
import com.thoughtworks.go.plugin.api.request.GoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.DefaultGoPluginApiResponse;
import com.thoughtworks.go.plugin.infra.PluginManager;
import com.thoughtworks.go.plugin.infra.plugininfo.GoPluginDescriptor;
import com.thoughtworks.go.util.ReflectionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static com.thoughtworks.go.plugin.access.elastic.ElasticAgentExtension.SUPPORTED_VERSIONS;
import static com.thoughtworks.go.plugin.access.elastic.v4.ElasticAgentPluginConstantsV4.REQUEST_GET_PLUGIN_SETTINGS_ICON;
import static com.thoughtworks.go.plugin.domain.common.PluginConstants.ELASTIC_AGENT_EXTENSION;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

public class ElasticAgentExtensionTest {
    private static final String PLUGIN_ID = "cd.go.example.plugin";

    protected PluginManager pluginManager;
    private ExtensionsRegistry extensionsRegistry;
    protected ArgumentCaptor<GoPluginApiRequest> requestArgumentCaptor;
    protected GoPluginDescriptor descriptor;
    protected ElasticAgentExtension extension;

    @BeforeEach
    public void setUp() {
        pluginManager = mock(PluginManager.class);
        extensionsRegistry = mock(ExtensionsRegistry.class);
        requestArgumentCaptor = ArgumentCaptor.forClass(GoPluginApiRequest.class);
        descriptor = mock(GoPluginDescriptor.class);
        extension = new ElasticAgentExtension(pluginManager, extensionsRegistry);

        when(descriptor.id()).thenReturn(PLUGIN_ID);

        when(pluginManager.getPluginDescriptorFor(PLUGIN_ID)).thenReturn(descriptor);
        when(pluginManager.isPluginOfType(ELASTIC_AGENT_EXTENSION, PLUGIN_ID)).thenReturn(true);

        when(pluginManager.resolveExtensionVersion(PLUGIN_ID, ELASTIC_AGENT_EXTENSION, SUPPORTED_VERSIONS)).thenReturn("4.0");
    }

    @Test
    public void shouldHaveVersionedElasticAgentExtensionForAllSupportedVersions() {
        for (String supportedVersion : SUPPORTED_VERSIONS) {
            final String message = String.format("Must define versioned extension class for %s extension with version %s", ELASTIC_AGENT_EXTENSION, supportedVersion);

            when(pluginManager.resolveExtensionVersion(PLUGIN_ID, ELASTIC_AGENT_EXTENSION, SUPPORTED_VERSIONS)).thenReturn(supportedVersion);

            final VersionedElasticAgentExtension extension = this.extension.getVersionedElasticAgentExtension(PLUGIN_ID);

            assertNotNull(extension, message);

            String version = ReflectionUtil.getField(extension, "VERSION");
            assertThat(version).isEqualTo(supportedVersion);
        }
    }

    @Test
    public void shouldCallTheVersionedExtensionBasedOnResolvedVersion() {
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success("{\"content_type\":\"image/png\",\"data\":\"Zm9vYmEK\"}"));

        extension.getIcon(PLUGIN_ID);

        assertExtensionRequest("4.0", REQUEST_GET_PLUGIN_SETTINGS_ICON, null);
    }

    @Test
    public void shouldExtendAbstractExtension() {
        assertThat(new ElasticAgentExtension(pluginManager, extensionsRegistry)).isInstanceOf(AbstractExtension.class);
    }

    @Test
    public void shouldMakeJobCompletionCall() {
        when(pluginManager.resolveExtensionVersion(PLUGIN_ID, ELASTIC_AGENT_EXTENSION, SUPPORTED_VERSIONS)).thenReturn("4.0");

        final String elasticAgentId = "ea1";
        final JobIdentifier jobIdentifier = new JobIdentifier("up42", 2, "Test", "up42_stage", "10", "up42_job");
        final Map<String, String> elasticProfileConfiguration = Map.of("Image", "alpine:latest");
        final Map<String, String> clusterProfileConfiguration = Map.of("ServerURL", "https://example.com/go");
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(null));

        extension.reportJobCompletion(PLUGIN_ID, elasticAgentId, jobIdentifier, elasticProfileConfiguration, clusterProfileConfiguration);

        verify(pluginManager, times(1)).submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), any(GoPluginApiRequest.class));
    }

    private void assertExtensionRequest(String extensionVersion, String requestName, String requestBody) {
        final GoPluginApiRequest request = requestArgumentCaptor.getValue();
        assertThat(request.requestName()).isEqualTo(requestName);
        assertThat(request.extensionVersion()).isEqualTo(extensionVersion);
        assertThat(request.extension()).isEqualTo(ELASTIC_AGENT_EXTENSION);
        assertThatJson(requestBody).isEqualTo(request.requestBody());
    }
}
