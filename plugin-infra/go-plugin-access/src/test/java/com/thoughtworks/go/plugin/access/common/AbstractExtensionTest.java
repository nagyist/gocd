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
package com.thoughtworks.go.plugin.access.common;

import com.thoughtworks.go.plugin.access.ExtensionsRegistry;
import com.thoughtworks.go.plugin.access.PluginRequestHelper;
import com.thoughtworks.go.plugin.access.common.settings.PluginSettingsJsonMessageHandler1_0;
import com.thoughtworks.go.plugin.access.common.settings.PluginSettingsJsonMessageHandler2_0;
import com.thoughtworks.go.plugin.api.request.GoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.DefaultGoPluginApiResponse;
import com.thoughtworks.go.plugin.infra.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static com.thoughtworks.go.plugin.access.common.settings.PluginSettingsConstants.REQUEST_NOTIFY_PLUGIN_SETTINGS_CHANGE;
import static com.thoughtworks.go.plugin.api.response.DefaultGoPluginApiResponse.SUCCESS_RESPONSE_CODE;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AbstractExtensionTest {

    private static final List<String> goSupportedVersions = List.of("1.0", "2.0");

    private AbstractExtension extension;
    @Mock(strictness = Mock.Strictness.LENIENT)
    private PluginManager pluginManager;
    @Mock
    ExtensionsRegistry extensionsRegistry;

    private String extensionName;
    private String pluginId;

    private static class TestExtension extends AbstractExtension {

        protected TestExtension(PluginManager pluginManager, ExtensionsRegistry extensionsRegistry, PluginRequestHelper pluginRequestHelper, String extensionName) {
            super(pluginManager, extensionsRegistry, pluginRequestHelper, extensionName);
        }

        @Override
        public List<String> goSupportedVersions() {
            return goSupportedVersions;
        }
    }

    @BeforeEach
    public void setUp() {
        pluginId = "plugin_id";
        extensionName = "testExtension";
        PluginRequestHelper pluginRequestHelper = new PluginRequestHelper(pluginManager, goSupportedVersions, extensionName);
        extension = new TestExtension(pluginManager, extensionsRegistry, pluginRequestHelper, extensionName);

        when(pluginManager.isPluginOfType(extensionName, pluginId)).thenReturn(true);
    }

    @Test
    public void shouldNotifySettingsChangeForPluginWhichSupportsNotification() {
        String supportedVersion = "2.0";
        Map<String, String> settings = Map.of("foo", "bar");
        ArgumentCaptor<GoPluginApiRequest> requestArgumentCaptor = ArgumentCaptor.forClass(GoPluginApiRequest.class);

        extension.registerHandler(supportedVersion, new PluginSettingsJsonMessageHandler2_0());
        when(pluginManager.resolveExtensionVersion(pluginId, extensionName, goSupportedVersions)).thenReturn(supportedVersion);
        when(pluginManager.submitTo(eq(pluginId), eq(extensionName), requestArgumentCaptor.capture())).thenReturn(new DefaultGoPluginApiResponse(SUCCESS_RESPONSE_CODE, ""));

        extension.notifyPluginSettingsChange(pluginId, settings);

        assertRequest(requestArgumentCaptor.getValue(), extensionName,
                supportedVersion, REQUEST_NOTIFY_PLUGIN_SETTINGS_CHANGE, "{\"foo\":\"bar\"}");
    }

    @Test
    public void shouldIgnoreSettingsChangeNotificationIfPluginDoesNotSupportsNotification() {
        String supportedVersion = "1.0";
        Map<String, String> settings = Map.of("foo", "bar");

        extension.registerHandler(supportedVersion, new PluginSettingsJsonMessageHandler1_0());
        when(pluginManager.resolveExtensionVersion(pluginId, extensionName, goSupportedVersions)).thenReturn(supportedVersion);

        extension.notifyPluginSettingsChange(pluginId, settings);

        verify(pluginManager, times(0)).submitTo(anyString(), eq(extensionName), any(GoPluginApiRequest.class));
    }

    private void assertRequest(GoPluginApiRequest goPluginApiRequest, String extensionName, String version, String requestName, String requestBody) {
        assertThat(goPluginApiRequest.extension()).isEqualTo(extensionName);
        assertThat(goPluginApiRequest.extensionVersion()).isEqualTo(version);
        assertThat(goPluginApiRequest.requestName()).isEqualTo(requestName);
        assertThatJson(requestBody).isEqualTo(goPluginApiRequest.requestBody());
    }
}
