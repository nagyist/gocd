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

import com.thoughtworks.go.domain.ClusterProfilesChangedStatus;
import com.thoughtworks.go.domain.JobIdentifier;
import com.thoughtworks.go.plugin.access.PluginRequestHelper;
import com.thoughtworks.go.plugin.access.elastic.models.AgentMetadata;
import com.thoughtworks.go.plugin.access.elastic.models.ElasticAgentInformation;
import com.thoughtworks.go.plugin.access.elastic.v5.ElasticAgentExtensionV5;
import com.thoughtworks.go.plugin.api.request.GoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.DefaultGoPluginApiResponse;
import com.thoughtworks.go.plugin.api.response.validation.ValidationError;
import com.thoughtworks.go.plugin.api.response.validation.ValidationResult;
import com.thoughtworks.go.plugin.domain.common.Image;
import com.thoughtworks.go.plugin.domain.common.Metadata;
import com.thoughtworks.go.plugin.domain.common.PluginConfiguration;
import com.thoughtworks.go.plugin.domain.common.PluginConstants;
import com.thoughtworks.go.plugin.domain.elastic.Capabilities;
import com.thoughtworks.go.plugin.infra.PluginManager;
import com.thoughtworks.go.plugin.infra.plugininfo.GoPluginDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static com.thoughtworks.go.plugin.access.elastic.v5.ElasticAgentPluginConstantsV5.*;
import static com.thoughtworks.go.plugin.domain.common.PluginConstants.ELASTIC_AGENT_EXTENSION;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ElasticAgentExtensionV5Test {
    private static final String PLUGIN_ID = "cd.go.example.plugin";
    @Mock(strictness = Mock.Strictness.LENIENT)
    private PluginManager pluginManager;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private GoPluginDescriptor descriptor;
    private ArgumentCaptor<GoPluginApiRequest> requestArgumentCaptor;
    private ElasticAgentExtensionV5 extensionV5;

    @BeforeEach
    public void setUp() {
        requestArgumentCaptor = ArgumentCaptor.forClass(GoPluginApiRequest.class);
        final List<String> goSupportedVersions = List.of("3.0", "4.0", "5.0");

        when(descriptor.id()).thenReturn(PLUGIN_ID);

        when(pluginManager.getPluginDescriptorFor(PLUGIN_ID)).thenReturn(descriptor);
        when(pluginManager.isPluginOfType(ELASTIC_AGENT_EXTENSION, PLUGIN_ID)).thenReturn(true);
        when(pluginManager.resolveExtensionVersion(PLUGIN_ID, ELASTIC_AGENT_EXTENSION, goSupportedVersions)).thenReturn("5.0");

        final PluginRequestHelper pluginRequestHelper = new PluginRequestHelper(pluginManager, goSupportedVersions, ELASTIC_AGENT_EXTENSION);
        extensionV5 = new ElasticAgentExtensionV5(pluginRequestHelper);
    }

    @Test
    public void shouldGetPluginIcon() {
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success("{\"content_type\":\"image/png\",\"data\":\"Zm9vYmEK\"}"));
        final Image icon = extensionV5.getIcon(PLUGIN_ID);

        assertThat(icon.getContentType()).isEqualTo("image/png");
        assertThat(icon.getData()).isEqualTo("Zm9vYmEK");

        assertExtensionRequest("5.0", REQUEST_GET_PLUGIN_SETTINGS_ICON, null);
    }

    @Test
    public void shouldGetCapabilitiesOfAPlugin() {
        final String responseBody = "{" +
                "    \"supports_plugin_status_report\":\"true\", " +
                "    \"supports_cluster_status_report\":\"true\", " +
                "    \"supports_agent_status_report\":\"true\"" +
                "}";
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));

        final Capabilities capabilities = extensionV5.getCapabilities(PLUGIN_ID);

        assertTrue(capabilities.supportsPluginStatusReport());
        assertTrue(capabilities.supportsClusterStatusReport());
        assertTrue(capabilities.supportsAgentStatusReport());
    }

    @Test
    public void shouldGetProfileMetadata() {
        String responseBody = "[{\"key\":\"Username\",\"metadata\":{\"required\":true,\"secure\":false}},{\"key\":\"Password\",\"metadata\":{\"required\":true,\"secure\":true}}]";
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));

        final List<PluginConfiguration> metadata = extensionV5.getElasticProfileMetadata(PLUGIN_ID);

        assertThat(metadata).containsExactlyInAnyOrder(
                new PluginConfiguration("Username", new Metadata(true, false)),
                new PluginConfiguration("Password", new Metadata(true, true))
        );

        assertExtensionRequest("5.0", REQUEST_GET_ELASTIC_AGENT_PROFILE_METADATA, null);
    }

    @Test
    public void shouldGetProfileView() {
        String responseBody = "{ \"template\": \"<div>This is profile view snippet</div>\" }";
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));

        final String view = extensionV5.getElasticProfileView(PLUGIN_ID);

        assertThat(view).isEqualTo("<div>This is profile view snippet</div>");

        assertExtensionRequest("5.0", REQUEST_GET_ELASTIC_AGENT_PROFILE_VIEW, null);
    }

    @Test
    public void shouldValidateProfile() {
        String responseBody = "[{\"message\":\"Url must not be blank.\",\"key\":\"Url\"},{\"message\":\"SearchBase must not be blank.\",\"key\":\"SearchBase\"}]";
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));

        final ValidationResult result = extensionV5.validateElasticProfile(PLUGIN_ID, Collections.emptyMap());

        assertThat(result.isSuccessful()).isEqualTo(false);
        assertThat(result.getErrors()).containsExactlyInAnyOrder(
                new ValidationError("Url", "Url must not be blank."),
                new ValidationError("SearchBase", "SearchBase must not be blank.")
        );

        assertExtensionRequest("5.0", REQUEST_VALIDATE_ELASTIC_AGENT_PROFILE, "{}");
    }


    @Test
    public void shouldGetClusterProfileMetadata() {
        String responseBody = "[{\"key\":\"Username\",\"metadata\":{\"required\":true,\"secure\":false}},{\"key\":\"Password\",\"metadata\":{\"required\":true,\"secure\":true}}]";
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));

        final List<PluginConfiguration> metadata = extensionV5.getClusterProfileMetadata(PLUGIN_ID);

        assertThat(metadata).containsExactlyInAnyOrder(
                new PluginConfiguration("Username", new Metadata(true, false)),
                new PluginConfiguration("Password", new Metadata(true, true))
        );

        assertExtensionRequest("5.0", REQUEST_GET_CLUSTER_PROFILE_METADATA, null);
    }

    @Test
    public void shouldGetClusterProfileView() {
        String responseBody = "{ \"template\": \"<div>This is profile view snippet</div>\" }";
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));

        final String view = extensionV5.getClusterProfileView(PLUGIN_ID);

        assertThat(view).isEqualTo("<div>This is profile view snippet</div>");

        assertExtensionRequest("5.0", REQUEST_GET_CLUSTER_PROFILE_VIEW, null);
    }

    @Test
    public void shouldValidateClusterProfile() {
        String responseBody = "[{\"message\":\"Url must not be blank.\",\"key\":\"Url\"},{\"message\":\"SearchBase must not be blank.\",\"key\":\"SearchBase\"}]";
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));

        final ValidationResult result = extensionV5.validateClusterProfile(PLUGIN_ID, Collections.emptyMap());

        assertThat(result.isSuccessful()).isEqualTo(false);
        assertThat(result.getErrors()).containsExactlyInAnyOrder(
                new ValidationError("Url", "Url must not be blank."),
                new ValidationError("SearchBase", "SearchBase must not be blank.")
        );

        assertExtensionRequest("5.0", REQUEST_VALIDATE_CLUSTER_PROFILE, "{}");
    }

    @Test
    public void shouldMakeCreateAgentCall() {
        final Map<String, String> profile = Map.of("Image", "alpine:latest");
        final Map<String, String> clusterProfile = Map.of("ServerURL", "https://example.com/go");
        final JobIdentifier jobIdentifier = new JobIdentifier("up42", 2, "Test", "up42_stage", "10", "up42_job");
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(null));

        extensionV5.createAgent(PLUGIN_ID, "auto-registration-key", "test-env", profile, clusterProfile, jobIdentifier);

        String expectedRequestBody = """
                {
                  "auto_register_key": "auto-registration-key",
                  "elastic_agent_profile_properties": {
                    "Image": "alpine:latest"
                  },
                  "cluster_profile_properties": {
                    "ServerURL": "https://example.com/go"
                  },
                  "environment": "test-env",
                  "job_identifier": {
                    "pipeline_name": "up42",
                    "pipeline_label": "Test",
                    "pipeline_counter": 2,
                    "stage_name": "up42_stage",
                    "stage_counter": "10",
                    "job_name": "up42_job",
                    "job_id": -1
                  }
                }""";
        assertExtensionRequest("5.0", REQUEST_CREATE_AGENT, expectedRequestBody);
    }

    @Test
    public void shouldMakeJobCompletionCall() {
        final String elasticAgentId = "ea1";
        final JobIdentifier jobIdentifier = new JobIdentifier("up42", 2, "Test", "up42_stage", "10", "up42_job");
        final Map<String, String> profile = Map.of("Image", "alpine:latest");
        final Map<String, String> clusterProfile = Map.of("ServerURL", "https://example.com/go");
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(null));

        extensionV5.jobCompletion(PLUGIN_ID, elasticAgentId, jobIdentifier, profile, clusterProfile);

        String expectedRequestBody = """
                {
                  "elastic_agent_id": "ea1",
                  "elastic_agent_profile_properties": {
                    "Image": "alpine:latest"
                  },
                  "cluster_profile_properties": {
                    "ServerURL": "https://example.com/go"
                  },
                  "job_identifier": {
                    "pipeline_name": "up42",
                    "pipeline_label": "Test",
                    "pipeline_counter": 2,
                    "stage_name": "up42_stage",
                    "stage_counter": "10",
                    "job_name": "up42_job",
                    "job_id": -1
                  }
                }""";

        assertExtensionRequest("5.0", REQUEST_JOB_COMPLETION, expectedRequestBody);
    }

    @Test
    public void shouldMakeClusterProfileChangedCall() {
        ClusterProfilesChangedStatus status = ClusterProfilesChangedStatus.CREATED;
        Map<String, String> oldClusterProfile = null;
        Map<String, String> newClusterProfile = Map.of("key1", "key2");

        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(null));

        extensionV5.clusterProfilesChanged(PLUGIN_ID, status, oldClusterProfile, newClusterProfile);

        String expectedRequestBody = "{" +
                "  \"status\":\"created\"," +
                "  \"cluster_profiles_properties\":{" +
                "    \"key1\":\"key2\"" +
                "  }" +
                "}";

        assertExtensionRequest("5.0", REQUEST_CLUSTER_PROFILE_CHANGED, expectedRequestBody);
    }

    @Test
    public void shouldSendServerPing() {
        final Map<String, String> clusterProfileProperties = Map.of("ServerURL", "https://example.com/go");
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(null));
        extensionV5.serverPing(PLUGIN_ID, List.of(clusterProfileProperties));

        String expectedRequestBody = """
                {
                  "all_cluster_profile_properties": [{
                    "ServerURL": "https://example.com/go"
                  }]
                }""";

        assertExtensionRequest("5.0", REQUEST_SERVER_PING, expectedRequestBody);
    }

    @Test
    public void shouldMakeShouldAssignWorkCall() {
        final Map<String, String> profile = Map.of("Image", "alpine:latest");
        final Map<String, String> clusterProfileProperties = Map.of("ServerURL", "https://example.com/go");
        final AgentMetadata agentMetadata = new AgentMetadata("foo-agent-id", "Idle", "Idle", "Enabled");
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success("true"));
        final boolean shouldAssignWork = extensionV5.shouldAssignWork(PLUGIN_ID, agentMetadata, "test-env", profile, clusterProfileProperties, new JobIdentifier());

        assertTrue(shouldAssignWork);

        String expectedRequestBody = """
                {
                  "cluster_profile_properties": {
                    "ServerURL": "https://example.com/go"
                  },
                  "elastic_agent_profile_properties": {
                    "Image": "alpine:latest"
                  },
                  "environment": "test-env",
                  "agent": {
                    "agent_id": "foo-agent-id",
                    "agent_state": "Idle",
                    "build_state": "Idle",
                    "config_state": "Enabled"
                  },
                  "job_identifier": {}
                }""";

        assertExtensionRequest("5.0", REQUEST_SHOULD_ASSIGN_WORK, expectedRequestBody);
    }

    @Test
    public void shouldGetPluginStatusReport() {
        final String responseBody = "{\"view\":\"<div>This is a status report snippet.</div>\"}";
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));

        Map<String, String> clusterProfile1 = new HashMap<>();
        clusterProfile1.put("key1", "value1");
        clusterProfile1.put("key2", "value2");

        Map<String, String> clusterProfile2 = new HashMap<>();
        clusterProfile2.put("key3", "value3");

        List<Map<String, String>> clusterProfiles = new ArrayList<>();
        clusterProfiles.add(clusterProfile1);
        clusterProfiles.add(clusterProfile2);

        final String statusReportView = extensionV5.getPluginStatusReport(PLUGIN_ID, clusterProfiles);
        final String requestBody = "{" +
                "\"all_cluster_profiles_properties\":[" +
                "  {" +
                "      \"key1\":\"value1\"," +
                "      \"key2\":\"value2\"" +
                "  }," +
                "  {" +
                "      \"key3\":\"value3\"" +
                "  }" +
                "]" +
                "}";

        assertThat(statusReportView).isEqualTo("<div>This is a status report snippet.</div>");
        assertExtensionRequest("5.0", REQUEST_PLUGIN_STATUS_REPORT, requestBody);
    }

    @Test
    public void shouldGetAgentStatusReport() {
        final String responseBody = "{\"view\":\"<div>This is a status report snippet.</div>\"}";
        final JobIdentifier jobIdentifier = new JobIdentifier("up42", 2, "Test", "up42_stage", "10", "up42_job");

        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));


        Map<String, String> clusterProfile = Map.of("go-server-url", "server-url-value");

        extensionV5.getAgentStatusReport(PLUGIN_ID, jobIdentifier, "GoCD193659b3b930480287b898eeef0ade37", clusterProfile);

        final String requestBody = """
                {
                  "job_identifier": {
                    "pipeline_name": "up42",
                    "pipeline_label": "Test",
                    "pipeline_counter": 2,
                    "stage_name": "up42_stage",
                    "stage_counter": "10",
                    "job_name": "up42_job",
                    "job_id": -1
                  },
                  "cluster_profile_properties":{       "go-server-url":"server-url-value"  },  "elastic_agent_id": "GoCD193659b3b930480287b898eeef0ade37"
                }""";

        assertExtensionRequest("5.0", REQUEST_AGENT_STATUS_REPORT, requestBody);
    }

    @Test
    public void shouldMigrateElasticAgentInformation() {
        String responseBody = "{" +
                "    \"plugin_settings\":{}," +
                "    \"cluster_profiles\":[]," +
                "    \"elastic_agent_profiles\":[]" +
                "}\n";
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));

        ElasticAgentInformation elasticAgentInformation = new ElasticAgentInformation(Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());
        extensionV5.migrateConfig(PLUGIN_ID, elasticAgentInformation);

        assertExtensionRequest("5.0", REQUEST_MIGRATE_CONFIGURATION, responseBody);
    }

    @Test
    public void shouldGetClusterStatusReport() {
        final String responseBody = "{\"view\":\"<div>This is a cluster status report snippet.</div>\"}";

        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(ELASTIC_AGENT_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));


        Map<String, String> clusterProfile = Map.of("key1", "value1");
        extensionV5.getClusterStatusReport(PLUGIN_ID, clusterProfile);

        final String requestBody = "{\n" +
                "  \"cluster_profile_properties\":{" +
                "       \"key1\":\"value1\"" +
                "  }" +
                "}";

        assertExtensionRequest("5.0", REQUEST_CLUSTER_STATUS_REPORT, requestBody);
    }

    @Test
    public void shouldVerifyPluginApiRequestNamesOfElasticAgentProfile() {
        assertThat(REQUEST_GET_ELASTIC_AGENT_PROFILE_METADATA).isEqualTo(String.format("%s.get-elastic-agent-profile-metadata", REQUEST_PREFIX));
        assertThat(REQUEST_GET_ELASTIC_AGENT_PROFILE_VIEW).isEqualTo(String.format("%s.get-elastic-agent-profile-view", REQUEST_PREFIX));
        assertThat(REQUEST_VALIDATE_ELASTIC_AGENT_PROFILE).isEqualTo(String.format("%s.validate-elastic-agent-profile", REQUEST_PREFIX));
    }

    @Test
    public void shouldVerifyPluginApiRequestNamesOfClusterProfile() {
        assertThat(REQUEST_GET_CLUSTER_PROFILE_METADATA).isEqualTo(String.format("%s.get-cluster-profile-metadata", REQUEST_PREFIX));
        assertThat(REQUEST_GET_CLUSTER_PROFILE_VIEW).isEqualTo(String.format("%s.get-cluster-profile-view", REQUEST_PREFIX));
        assertThat(REQUEST_VALIDATE_CLUSTER_PROFILE).isEqualTo(String.format("%s.validate-cluster-profile", REQUEST_PREFIX));
    }

    @Test
    public void allRequestMustHaveRequestPrefix() {
        assertThat(REQUEST_PREFIX).isEqualTo("cd.go.elastic-agent");

        assertThat(REQUEST_CREATE_AGENT).startsWith(REQUEST_PREFIX);
        assertThat(REQUEST_SERVER_PING).startsWith(REQUEST_PREFIX);
        assertThat(REQUEST_SHOULD_ASSIGN_WORK).startsWith(REQUEST_PREFIX);

        assertThat(REQUEST_GET_ELASTIC_AGENT_PROFILE_METADATA).startsWith(REQUEST_PREFIX);
        assertThat(REQUEST_GET_ELASTIC_AGENT_PROFILE_VIEW).startsWith(REQUEST_PREFIX);
        assertThat(REQUEST_VALIDATE_ELASTIC_AGENT_PROFILE).startsWith(REQUEST_PREFIX);
        assertThat(REQUEST_GET_PLUGIN_SETTINGS_ICON).startsWith(REQUEST_PREFIX);
    }

    private void assertExtensionRequest(String extensionVersion, String requestName, String requestBody) {
        final GoPluginApiRequest request = requestArgumentCaptor.getValue();
        assertThat(request.requestName()).isEqualTo(requestName);
        assertThat(request.extensionVersion()).isEqualTo(extensionVersion);
        assertThat(request.extension()).isEqualTo(PluginConstants.ELASTIC_AGENT_EXTENSION);
        assertThatJson(requestBody).isEqualTo(request.requestBody());
    }
}
