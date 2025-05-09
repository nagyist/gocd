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
package com.thoughtworks.go.config;

import com.thoughtworks.go.config.helper.ValidationContextMother;
import com.thoughtworks.go.config.policy.Allow;
import com.thoughtworks.go.config.policy.Policy;
import com.thoughtworks.go.config.policy.SupportedAction;
import com.thoughtworks.go.domain.config.ConfigurationKey;
import com.thoughtworks.go.domain.config.ConfigurationProperty;
import com.thoughtworks.go.domain.config.ConfigurationValue;
import com.thoughtworks.go.plugin.access.authorization.AuthorizationMetadataStore;
import com.thoughtworks.go.plugin.api.info.PluginDescriptor;
import com.thoughtworks.go.plugin.domain.authorization.AuthorizationPluginInfo;
import com.thoughtworks.go.plugin.domain.authorization.SupportedAuthType;
import com.thoughtworks.go.plugin.domain.common.Metadata;
import com.thoughtworks.go.plugin.domain.common.PluggableInstanceSettings;
import com.thoughtworks.go.plugin.domain.common.PluginConfiguration;
import com.thoughtworks.go.security.CryptoException;
import com.thoughtworks.go.security.GoCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.thoughtworks.go.config.policy.SupportedEntity.ENVIRONMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PluginRoleConfigTest {
    @AfterEach
    public void teardown() {
        AuthorizationMetadataStore.instance().clear();
    }

    @Test
    public void validate_shouldValidatePresenceOfRoleName() {
        validatePresenceOfRoleName(PluginRoleConfig::validate);
    }

    @Test
    public void validate_shouldValidateNullRoleName() {
        validateNullRoleName(PluginRoleConfig::validate);
    }

    @Test
    public void validate_presenceAuthConfigId() {
        validatePresenceAuthConfigId(PluginRoleConfig::validate);
    }

    @Test
    public void validate_presenceOfAuthConfigIdInSecurityConfig() {
        validatePresenceOfAuthConfigIdInSecurityConfig(PluginRoleConfig::validate);
    }

    @Test
    public void validate_uniquenessOfRoleName() {
        validateUniquenessOfRoleName(PluginRoleConfig::validate);
    }

    @Test
    public void validateTree_shouldValidatePresenceOfRoleName() {
        validatePresenceOfRoleName((pluginRoleConfig, context) -> assertFalse(pluginRoleConfig.validateTree(context)));
    }

    @Test
    public void validateTree_shouldValidateNullRoleName() {
        validateNullRoleName(Role::validateTree);
    }

    @Test
    public void validateTree_presenceAuthConfigId() {
        validatePresenceAuthConfigId((pluginRoleConfig, context) -> assertFalse(pluginRoleConfig.validateTree(context)));
    }

    @Test
    public void validateTree_presenceOfAuthConfigIdInSecurityConfig() {
        validatePresenceOfAuthConfigIdInSecurityConfig((pluginRoleConfig, context) -> assertFalse(pluginRoleConfig.validateTree(context)));
    }

    @Test
    public void validateTree_uniquenessOfRoleName() {
        validateUniquenessOfRoleName((pluginRoleConfig, context) -> assertFalse(pluginRoleConfig.validateTree(context)));
    }

    @Test
    public void hasErrors_shouldBeTrueIfRoleHasErrors() {
        Role role = new PluginRoleConfig("", "auth_config_id");

        SecurityConfig securityConfig = new SecurityConfig();
        securityConfig.securityAuthConfigs().add(new SecurityAuthConfig("auth_config_id", "plugin_id"));

        role.validate(ValidationContextMother.validationContext(securityConfig));

        assertTrue(role.hasErrors());
    }

    @Test
    public void hasErrors_shouldBeTrueIfConfigurationPropertiesHasErrors() {
        ConfigurationProperty property = new ConfigurationProperty(new ConfigurationKey("username"), new ConfigurationValue("view"));
        PluginRoleConfig roleConfig = new PluginRoleConfig("admin", "auth_id", property);

        property.addError("username", "username format is incorrect");

        assertTrue(roleConfig.hasErrors());
        assertTrue(roleConfig.errors().isEmpty());
    }

    @Test
    public void shouldAnswerWhetherItHasPermissionsForGivenEntityOfTypeAndName() {
        final Policy directives = new Policy();
        directives.add(new Allow("view", ENVIRONMENT.getType(), "env_1"));
        RoleConfig role = new RoleConfig(new CaseInsensitiveString(""), new Users(), directives);

        assertTrue(role.hasPermissionsFor(SupportedAction.VIEW, EnvironmentConfig.class, "env_1"));
        assertFalse(role.hasPermissionsFor(SupportedAction.VIEW, EnvironmentConfig.class, "env_2"));
        assertFalse(role.hasPermissionsFor(SupportedAction.VIEW, PipelineConfig.class, "*"));
    }

    private void validatePresenceOfRoleName(Validator v) {
        PluginRoleConfig role = new PluginRoleConfig("", "auth_config_id");

        SecurityConfig securityConfig = new SecurityConfig();
        securityConfig.securityAuthConfigs().add(new SecurityAuthConfig("auth_config_id", "plugin_id"));

        v.validate(role, ValidationContextMother.validationContext(securityConfig));

        assertTrue(role.hasErrors());
        assertThat(role.errors().size()).isEqualTo(1);
        assertThat(role.errors().get("name").get(0)).isEqualTo("Invalid role name name ''. This must be alphanumeric and can" +
                " contain underscores, hyphens and periods (however, it cannot start with a period). The maximum allowed length is 255 characters.");
    }

    private void validateNullRoleName(Validator v) {
        PluginRoleConfig role = new PluginRoleConfig("", "auth_config_id");
        role.setName(null);

        SecurityConfig securityConfig = new SecurityConfig();
        securityConfig.securityAuthConfigs().add(new SecurityAuthConfig("auth_config_id", "plugin_id"));

        v.validate(role, ValidationContextMother.validationContext(securityConfig));

        assertTrue(role.hasErrors());
        assertThat(role.errors().size()).isEqualTo(1);
        assertThat(role.errors().get("name").get(0)).isEqualTo("Invalid role name name 'null'. This must be alphanumeric and can" +
                " contain underscores, hyphens and periods (however, it cannot start with a period). The maximum allowed length is 255 characters.");
    }

    public void validatePresenceAuthConfigId(Validator v) {
        PluginRoleConfig role = new PluginRoleConfig("admin", "");

        SecurityConfig securityConfig = new SecurityConfig();

        v.validate(role, ValidationContextMother.validationContext(securityConfig));

        assertThat(role.errors().size()).isEqualTo(1);
        assertThat(role.errors().get("authConfigId").size()).isEqualTo(1);
        assertThat(role.errors().get("authConfigId").get(0)).isEqualTo("Invalid plugin role authConfigId name ''. This must be alphanumeric and can" +
                " contain underscores, hyphens and periods (however, it cannot start with a period). The maximum allowed length is 255 characters.");
    }

    public void validatePresenceOfAuthConfigIdInSecurityConfig(Validator v) {
        PluginRoleConfig role = new PluginRoleConfig("admin", "auth_config_id");
        SecurityConfig securityConfig = new SecurityConfig();

        v.validate(role, ValidationContextMother.validationContext(securityConfig));

        assertThat(role.errors().size()).isEqualTo(1);
        assertThat(role.errors().get("authConfigId").size()).isEqualTo(1);
        assertThat(role.errors().get("authConfigId").get(0)).isEqualTo("No such security auth configuration present for id: `auth_config_id`");
    }

    public void validateUniquenessOfRoleName(Validator v) {
        PluginRoleConfig role = new PluginRoleConfig("admin", "auth_config_id");
        SecurityConfig securityConfig = new SecurityConfig();
        ValidationContext validationContext = ValidationContextMother.validationContext(securityConfig);

        securityConfig.securityAuthConfigs().add(new SecurityAuthConfig("auth_config_id", "plugin_id"));
        securityConfig.getRoles().add(new RoleConfig(new CaseInsensitiveString("admin")));
        securityConfig.getRoles().add(role);

        v.validate(role, validationContext);

        assertThat(role.errors().size()).isEqualTo(1);
        assertThat(role.errors().get("name").get(0)).isEqualTo("Role names should be unique. Role with the same name exists.");
    }

    @Test
    public void shouldEncryptSecurePluginProperties() throws CryptoException {
        setAuthorizationPluginInfo();
        String authConfigId = "auth_config_id";
        String pluginId = "cd.go.github";

        BasicCruiseConfig basicCruiseConfig = new BasicCruiseConfig();
        basicCruiseConfig.server().security().securityAuthConfigs().add(new SecurityAuthConfig(authConfigId, pluginId));
        PluginRoleConfig role = new PluginRoleConfig("admin", authConfigId);
        role.addConfigurations(List.of(
                new ConfigurationProperty(new ConfigurationKey("k1"), new ConfigurationValue("pub_v1")),
                new ConfigurationProperty(new ConfigurationKey("k2"), new ConfigurationValue("pub_v2")),
                new ConfigurationProperty(new ConfigurationKey("k3"), new ConfigurationValue("pub_v3"))));

        GoCipher goCipher = new GoCipher();

        assertThat(role.getProperty("k1").getEncryptedValue()).isNull();
        assertThat(role.getProperty("k1").getConfigValue()).isEqualTo("pub_v1");
        assertThat(role.getProperty("k1").getValue()).isEqualTo("pub_v1");
        assertThat(role.getProperty("k2").getEncryptedValue()).isNull();
        assertThat(role.getProperty("k2").getConfigValue()).isEqualTo("pub_v2");
        assertThat(role.getProperty("k2").getValue()).isEqualTo("pub_v2");
        assertThat(role.getProperty("k3").getEncryptedValue()).isNull();
        assertThat(role.getProperty("k3").getConfigValue()).isEqualTo("pub_v3");
        assertThat(role.getProperty("k3").getValue()).isEqualTo("pub_v3");

        role.encryptSecureProperties(basicCruiseConfig);

        assertThat(role.getProperty("k1").getEncryptedValue()).isEqualTo(goCipher.encrypt("pub_v1"));
        assertThat(role.getProperty("k1").getConfigValue()).isNull();
        assertThat(role.getProperty("k1").getValue()).isEqualTo("pub_v1");
        assertThat(role.getProperty("k2").getEncryptedValue()).isNull();
        assertThat(role.getProperty("k2").getConfigValue()).isEqualTo("pub_v2");
        assertThat(role.getProperty("k2").getValue()).isEqualTo("pub_v2");
        assertThat(role.getProperty("k3").getEncryptedValue()).isEqualTo(goCipher.encrypt("pub_v3"));
        assertThat(role.getProperty("k3").getConfigValue()).isNull();
        assertThat(role.getProperty("k3").getValue()).isEqualTo("pub_v3");
    }

    @Test
    public void shouldNotEncryptSecurePluginProperties_WhenPluginInfosIsAbsent() {
        String authConfigId = "auth_config_id";
        String pluginId = "cd.go.github";

        BasicCruiseConfig basicCruiseConfig = new BasicCruiseConfig();
        basicCruiseConfig.server().security().securityAuthConfigs().add(new SecurityAuthConfig(authConfigId, pluginId));
        PluginRoleConfig role = new PluginRoleConfig("admin", authConfigId);
        role.addConfigurations(List.of(
                new ConfigurationProperty(new ConfigurationKey("k1"), new ConfigurationValue("pub_v1")),
                new ConfigurationProperty(new ConfigurationKey("k2"), new ConfigurationValue("pub_v2")),
                new ConfigurationProperty(new ConfigurationKey("k3"), new ConfigurationValue("pub_v3"))));

        assertThat(role.getProperty("k1").getEncryptedValue()).isNull();
        assertThat(role.getProperty("k1").getConfigValue()).isEqualTo("pub_v1");
        assertThat(role.getProperty("k1").getValue()).isEqualTo("pub_v1");
        assertThat(role.getProperty("k2").getEncryptedValue()).isNull();
        assertThat(role.getProperty("k2").getConfigValue()).isEqualTo("pub_v2");
        assertThat(role.getProperty("k2").getValue()).isEqualTo("pub_v2");
        assertThat(role.getProperty("k3").getEncryptedValue()).isNull();
        assertThat(role.getProperty("k3").getConfigValue()).isEqualTo("pub_v3");
        assertThat(role.getProperty("k3").getValue()).isEqualTo("pub_v3");

        role.encryptSecureProperties(basicCruiseConfig);

        assertThat(role.getProperty("k1").getEncryptedValue()).isNull();
        assertThat(role.getProperty("k1").getConfigValue()).isEqualTo("pub_v1");
        assertThat(role.getProperty("k1").getValue()).isEqualTo("pub_v1");
        assertThat(role.getProperty("k2").getEncryptedValue()).isNull();
        assertThat(role.getProperty("k2").getConfigValue()).isEqualTo("pub_v2");
        assertThat(role.getProperty("k2").getValue()).isEqualTo("pub_v2");
        assertThat(role.getProperty("k3").getEncryptedValue()).isNull();
        assertThat(role.getProperty("k3").getConfigValue()).isEqualTo("pub_v3");
        assertThat(role.getProperty("k3").getValue()).isEqualTo("pub_v3");
    }

    @Test
    public void shouldNotEncryptSecurePluginProperties_WhenReferencedAuthConfigDoesNotExists() {
        setAuthorizationPluginInfo();
        String authConfigId = "auth_config_id";

        BasicCruiseConfig basicCruiseConfig = new BasicCruiseConfig();
        PluginRoleConfig role = new PluginRoleConfig("admin", authConfigId);
        role.addConfigurations(List.of(
                new ConfigurationProperty(new ConfigurationKey("k1"), new ConfigurationValue("pub_v1")),
                new ConfigurationProperty(new ConfigurationKey("k2"), new ConfigurationValue("pub_v2")),
                new ConfigurationProperty(new ConfigurationKey("k3"), new ConfigurationValue("pub_v3"))));

        assertThat(role.getProperty("k1").getEncryptedValue()).isNull();
        assertThat(role.getProperty("k1").getConfigValue()).isEqualTo("pub_v1");
        assertThat(role.getProperty("k1").getValue()).isEqualTo("pub_v1");
        assertThat(role.getProperty("k2").getEncryptedValue()).isNull();
        assertThat(role.getProperty("k2").getConfigValue()).isEqualTo("pub_v2");
        assertThat(role.getProperty("k2").getValue()).isEqualTo("pub_v2");
        assertThat(role.getProperty("k3").getEncryptedValue()).isNull();
        assertThat(role.getProperty("k3").getConfigValue()).isEqualTo("pub_v3");
        assertThat(role.getProperty("k3").getValue()).isEqualTo("pub_v3");

        role.encryptSecureProperties(basicCruiseConfig);

        assertThat(role.getProperty("k1").getEncryptedValue()).isNull();
        assertThat(role.getProperty("k1").getConfigValue()).isEqualTo("pub_v1");
        assertThat(role.getProperty("k1").getValue()).isEqualTo("pub_v1");
        assertThat(role.getProperty("k2").getEncryptedValue()).isNull();
        assertThat(role.getProperty("k2").getConfigValue()).isEqualTo("pub_v2");
        assertThat(role.getProperty("k2").getValue()).isEqualTo("pub_v2");
        assertThat(role.getProperty("k3").getEncryptedValue()).isNull();
        assertThat(role.getProperty("k3").getConfigValue()).isEqualTo("pub_v3");
        assertThat(role.getProperty("k3").getValue()).isEqualTo("pub_v3");
    }

    interface Validator {
        void validate(PluginRoleConfig pluginRoleConfig, ValidationContext context);
    }

    private void setAuthorizationPluginInfo() {
        PluginDescriptor pluginDescriptor = mock(PluginDescriptor.class);

        PluginConfiguration k1 = new PluginConfiguration("k1", new Metadata(false, true));
        PluginConfiguration k2 = new PluginConfiguration("k2", new Metadata(false, false));
        PluginConfiguration k3 = new PluginConfiguration("k3", new Metadata(false, true));

        PluggableInstanceSettings authConfigSettins = new PluggableInstanceSettings(List.of(k1, k2, k3));
        PluggableInstanceSettings roleConfigSettings = new PluggableInstanceSettings(List.of(k1, k2, k3));

        com.thoughtworks.go.plugin.domain.authorization.Capabilities capabilities = new com.thoughtworks.go.plugin.domain.authorization.Capabilities(SupportedAuthType.Web, true, true, true);
        AuthorizationPluginInfo artifactPluginInfo = new AuthorizationPluginInfo(pluginDescriptor, authConfigSettins, roleConfigSettings, null, capabilities);
        when(pluginDescriptor.id()).thenReturn("cd.go.github");
        AuthorizationMetadataStore.instance().setPluginInfo(artifactPluginInfo);
    }
}
