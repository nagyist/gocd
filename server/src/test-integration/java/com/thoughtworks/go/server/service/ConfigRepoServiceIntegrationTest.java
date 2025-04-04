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
package com.thoughtworks.go.server.service;

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.GoConfigDao;
import com.thoughtworks.go.config.UpdateConfigCommand;
import com.thoughtworks.go.config.remote.ConfigRepoConfig;
import com.thoughtworks.go.config.remote.ConfigReposConfig;
import com.thoughtworks.go.domain.config.Admin;
import com.thoughtworks.go.domain.materials.MaterialConfig;
import com.thoughtworks.go.plugin.access.configrepo.ConfigRepoExtension;
import com.thoughtworks.go.presentation.TriStateSelection;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.materials.MaterialUpdateService;
import com.thoughtworks.go.server.service.result.HttpLocalizedOperationResult;
import com.thoughtworks.go.util.GoConfigFileHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;

import static com.thoughtworks.go.helper.MaterialConfigsMother.git;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml",
        "classpath:/spring-all-servlet.xml",
})
public class ConfigRepoServiceIntegrationTest {
    @Autowired
    private GoConfigDao goConfigDao;
    @Autowired
    private GoConfigService goConfigService;
    @Autowired
    private ConfigRepoService configRepoService;
    @Autowired
    private EntityHashingService entityHashingService;
    @Autowired
    private SecurityService securityService;
    @Autowired
    private MaterialUpdateService materialUpdateService;
    @Autowired
    private MaterialConfigConverter materialConfigConverter;

    @Mock
    private ConfigRepoExtension configRepoExtension;

    private String repoId, pluginId;
    private Username user;
    private ConfigRepoConfig configRepo;

    private GoConfigFileHelper configHelper = new GoConfigFileHelper();

    @BeforeEach
    public void setUp() throws Exception {
        user = new Username(new CaseInsensitiveString("current"));
        UpdateConfigCommand command = goConfigService.modifyAdminPrivilegesCommand(List.of(user.getUsername().toString()), new TriStateSelection(Admin.GO_SYSTEM_ADMIN, TriStateSelection.Action.add));
        goConfigService.updateConfig(command);

        this.repoId = "repo-1";
        this.pluginId = "json-config-repo-plugin";
        MaterialConfig repoMaterial = git("https://foo.git", "master");
        this.configRepo = ConfigRepoConfig.createConfigRepoConfig(repoMaterial, pluginId, repoId);

        configHelper.usingCruiseConfigDao(goConfigDao);
        configHelper.onSetUp();

        goConfigService.forceNotifyListeners();
    }

    @AfterEach
    public void tearDown() {
        configHelper.onTearDown();
    }

    @Test
    public void shouldFindConfigRepoWithSpecifiedId() {
        configHelper.enableSecurity();
        goConfigService.getConfigForEditing().getConfigRepos().add(configRepo);
        assertThat(configRepoService.getConfigRepo(repoId)).isEqualTo(configRepo);
    }

    @Test
    public void shouldReturnNullWhenConfigRepoWithSpecifiedIdIsNotPresent() {
        configHelper.enableSecurity();
        assertNull(configRepoService.getConfigRepo(repoId));
    }

    @Test
    public void shouldFindAllConfigRepos() {
        configHelper.enableSecurity();
        goConfigService.getConfigForEditing().getConfigRepos().add(configRepo);
        assertThat(configRepoService.getConfigRepos()).isEqualTo(new ConfigReposConfig(configRepo));
    }

    @Test
    public void shouldDeleteSpecifiedConfigRepository() {
        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();
        configHelper.enableSecurity();
        goConfigDao.updateConfig(cruiseConfig -> {
            cruiseConfig.getConfigRepos().add(configRepo);
            return cruiseConfig;
        });

        assertThat(configRepoService.getConfigRepo(repoId)).isEqualTo(configRepo);

        configRepoService.deleteConfigRepo(repoId, user, result);

        assertNull(configRepoService.getConfigRepo(repoId));
        assertThat(result.isSuccessful()).describedAs(result.toString()).isTrue();
    }

    @Test
    public void shouldCreateSpecifiedConfigRepository() {
        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();
        configHelper.enableSecurity();
        configRepoService = new ConfigRepoService(goConfigService, entityHashingService, configRepoExtension, materialUpdateService, materialConfigConverter);

        when(configRepoExtension.canHandlePlugin(any())).thenReturn(true);

        assertNull(configRepoService.getConfigRepo(repoId));

        configRepoService.createConfigRepo(configRepo, user, result);

        assertThat(configRepoService.getConfigRepo(repoId)).isEqualTo(configRepo);
        assertThat(result.isSuccessful()).describedAs(result.toString()).isTrue();
    }

    @Test
    public void shouldUpdateSpecifiedConfigRepository() {
        configRepoService = new ConfigRepoService(goConfigService, entityHashingService, configRepoExtension, materialUpdateService, materialConfigConverter);

        when(configRepoExtension.canHandlePlugin(any())).thenReturn(true);
        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();
        configHelper.enableSecurity();
        goConfigDao.updateConfig(cruiseConfig -> {
            cruiseConfig.getConfigRepos().add(configRepo);
            return cruiseConfig;
        });
        String newRepoId = "repo-2";
        ConfigRepoConfig toUpdateWith = ConfigRepoConfig.createConfigRepoConfig(git("http://bar.git", "master"), "yaml-plugin", newRepoId);

        assertThat(configRepoService.getConfigRepos().size()).isEqualTo(1);
        assertThat(configRepoService.getConfigRepo(repoId)).isEqualTo(configRepo);

        configRepoService.updateConfigRepo(repoId, toUpdateWith, entityHashingService.hashForEntity(configRepo), user, result);

        assertThat(result.isSuccessful()).describedAs(result.toString()).isTrue();

        assertThat(configRepoService.getConfigRepos().size()).isEqualTo(1);
        assertThat(configRepoService.getConfigRepo(newRepoId)).isEqualTo(toUpdateWith);
    }
}
