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
package com.thoughtworks.go.server.materials;

import com.thoughtworks.go.config.*;
import com.thoughtworks.go.config.materials.mercurial.HgMaterial;
import com.thoughtworks.go.config.remote.ConfigRepoConfig;
import com.thoughtworks.go.config.remote.PartialConfig;
import com.thoughtworks.go.config.rules.Allow;
import com.thoughtworks.go.domain.materials.MaterialConfig;
import com.thoughtworks.go.helper.ConfigTestRepo;
import com.thoughtworks.go.helper.GoConfigMother;
import com.thoughtworks.go.helper.HgTestRepo;
import com.thoughtworks.go.server.cronjob.GoDiskSpaceMonitor;
import com.thoughtworks.go.server.dao.DatabaseAccessHelper;
import com.thoughtworks.go.server.persistence.MaterialRepository;
import com.thoughtworks.go.server.service.*;
import com.thoughtworks.go.serverhealth.HealthStateScope;
import com.thoughtworks.go.serverhealth.ServerHealthService;
import com.thoughtworks.go.util.ConfigElementImplementationRegistryMother;
import com.thoughtworks.go.util.GoConfigFileHelper;
import com.thoughtworks.go.util.SystemEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.File;
import java.nio.file.Path;

import static com.thoughtworks.go.helper.MaterialConfigsMother.hg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml",
        "classpath:/spring-all-servlet.xml",
})
public class ConfigMaterialUpdateListenerIntegrationTest {
    private static final GoConfigFileHelper configHelper = new GoConfigFileHelper();

    @Autowired
    private GoConfigDao goConfigDao;
    @Autowired
    private GoConfigService goConfigService;
    @Autowired
    private ServerHealthService serverHealthService;
    @Autowired
    private DatabaseAccessHelper dbHelper;
    @Autowired
    private MaterialRepository materialRepository;
    @Autowired
    private MaterialUpdateService materialUpdateService;
    @Autowired
    private GoConfigRepoConfigDataSource goConfigRepoConfigDataSource;
    @Autowired
    private SystemEnvironment systemEnvironment;
    @Autowired
    private ConfigCache configCache;
    @Autowired
    private CachedGoConfig cachedGoConfig;

    private MaterialConfig materialConfig;

    private HgTestRepo hgRepo;
    private HgMaterial material;

    private ConfigTestRepo configTestRepo;

    @BeforeEach
    public void setup(@TempDir Path tempDir) throws Exception {
        hgRepo = new HgTestRepo("testHgRepo", tempDir);

        dbHelper.onSetUp();
        configHelper.usingCruiseConfigDao(goConfigDao);
        configHelper.onSetUp();

        materialConfig = hg(hgRepo.projectRepositoryUrl(), null);
        ConfigRepoConfig config = ConfigRepoConfig.createConfigRepoConfig(materialConfig, "gocd-xml", "gocd-id");
        config.getRules().add(new Allow("refer", "*", "*"));
        configHelper.addConfigRepo(config);

        TestingEmailSender emailSender = new TestingEmailSender();
        SystemDiskSpaceChecker mockDiskSpaceChecker = mock(SystemDiskSpaceChecker.class);
        StageService stageService = mock(StageService.class);
        ConfigDbStateRepository configDbStateRepository = mock(ConfigDbStateRepository.class);
        GoDiskSpaceMonitor goDiskSpaceMonitor = new GoDiskSpaceMonitor(goConfigService, systemEnvironment,
                serverHealthService, emailSender, mockDiskSpaceChecker, mock(ArtifactsService.class),
                stageService, configDbStateRepository);
        goDiskSpaceMonitor.initialize();

        configTestRepo = new ConfigTestRepo(hgRepo, new MagicalGoConfigXmlWriter(configCache, ConfigElementImplementationRegistryMother.withNoPlugins()));
        this.material = configTestRepo.getMaterial();
    }


    @AfterEach
    public void teardown() throws Exception {
        dbHelper.onTearDown();
        configHelper.onTearDown();
    }

    @Test
    public void shouldBeInProgressUntilParsedWhenValid() throws Exception {
        materialUpdateService.updateMaterial(material);

        // because first result of parsing is left in repo data source
        // and then message is posted to take material from 'in progress' state

        assertInProgressState();
    }

    @Test
    public void shouldBeInProgressUntilParsedWhenInvalid() throws Exception {
        configTestRepo.addCodeToRepositoryAndPush("bogus.gocd.xml", "added bad config file",
                """
                        <?xml ve"?>
                        <cru>
                        </cruise>""");

        materialUpdateService.updateMaterial(material);

        // because first result of parsing is left in repo data source
        // and then message is posted to take material from 'in progress' state

        assertInProgressState();
    }

    private void assertInProgressState() throws InterruptedException {
        HealthStateScope healthStateScope = HealthStateScope.forPartialConfigRepo(material.config().getFingerprint());
        int i = 0;
        while (serverHealthService.logsSortedForScope(healthStateScope).isEmpty() && goConfigRepoConfigDataSource.getRevisionAtLastAttempt(material.config()) == null) {
            if (!materialUpdateService.isInProgress(material))
                fail("should be still in progress");

            Thread.sleep(1);
            if (i++ > 10000)
                fail("material is hung - more than 10 seconds in progress");
        }
    }

    @Test
    public void shouldParseEmptyRepository() throws Exception {
        materialUpdateService.updateMaterial(material);
        waitForMaterialNotInProgress();

        String revision = goConfigRepoConfigDataSource.getRevisionAtLastAttempt(materialConfig);
        assertNotNull(revision);

        PartialConfig partial = goConfigRepoConfigDataSource.latestPartialConfigForMaterial(materialConfig);
        assertThat(partial.getGroups().size()).isEqualTo(0);
        assertThat(partial.getEnvironments().size()).isEqualTo(0);
    }

    private void waitForMaterialNotInProgress() throws InterruptedException {
        // time for messages to pass through all services

        int i = 0;
        while (materialUpdateService.isInProgress(material)) {
            Thread.sleep(100);
            if (i++ > 100)
                fail("material is hung - more than 10 seconds in progress");
        }
    }

    @Test
    public void shouldParseAgainWhenChangesInMaterial() throws Exception {
        materialUpdateService.updateMaterial(material);
        // time for messages to pass through all services
        waitForMaterialNotInProgress();
        String revision = goConfigRepoConfigDataSource.getRevisionAtLastAttempt(materialConfig);
        assertNotNull(revision);
        PartialConfig partial = goConfigRepoConfigDataSource.latestPartialConfigForMaterial(materialConfig);

        hgRepo.commitAndPushFile("newFile.bla", "could be config file");

        materialUpdateService.updateMaterial(material);
        // time for messages to pass through all services
        waitForMaterialNotInProgress();
        PartialConfig partial2 = goConfigRepoConfigDataSource.latestPartialConfigForMaterial(materialConfig);
        assertNotSame(partial, partial2);
        assertThat(partial2.getOrigin()).isNotEqualTo(partial.getOrigin());
    }

    @Test
    public void shouldParseAndLoadValidPartialConfig() throws Exception {
        String fileName = "pipe1.gocd.xml";

        GoConfigMother mother = new GoConfigMother();
        PipelineConfig pipelineConfig = mother.cruiseConfigWithOnePipelineGroup().getAllPipelineConfigs().get(0);

        configTestRepo.addPipelineToRepositoryAndPush(fileName, pipelineConfig);

        materialUpdateService.updateMaterial(material);
        // time for messages to pass through all services
        waitForMaterialNotInProgress();
        PartialConfig partial = goConfigRepoConfigDataSource.latestPartialConfigForMaterial(materialConfig);
        assertNotNull(partial);
        assertThat(partial.getGroups().get(0).size()).isEqualTo(1);
        assertThat(partial.getGroups().get(0).get(0)).isEqualTo(pipelineConfig);
    }

    @Test
    public void shouldMergePipelineFromValidConfigRepository() throws Exception {
        String fileName = "pipe1.gocd.xml";

        GoConfigMother mother = new GoConfigMother();
        PipelineConfig pipelineConfig = mother.cruiseConfigWithOnePipelineGroup().getAllPipelineConfigs().get(0);

        configTestRepo.addPipelineToRepositoryAndPush(fileName, pipelineConfig);

        materialUpdateService.updateMaterial(material);
       assertThat(materialUpdateService.isInProgress(material)).isTrue();
        // time for messages to pass through all services
        waitForMaterialNotInProgress();

        cachedGoConfig.forceReload();

        assertThat(goConfigService.hasPipelineNamed(pipelineConfig.name())).isTrue();
        assertThat(goConfigService.pipelineConfigNamed(pipelineConfig.name())).isEqualTo(pipelineConfig);
    }

    @Test
    public void shouldCheckoutNewMaterial() throws Exception {
        GoConfigMother mother = new GoConfigMother();
        PipelineConfig pipelineConfig = mother.cruiseConfigWithOnePipelineGroup().getAllPipelineConfigs().get(0);

        configTestRepo.addPipelineToRepositoryAndPush("pipe1.gocd.xml", pipelineConfig);

        materialUpdateService.updateMaterial(material);
        // time for messages to pass through all services
        waitForMaterialNotInProgress();

        File flyweightDir = materialRepository.folderFor(material);
       assertThat(flyweightDir.exists()).isTrue();
       assertThat(new File(flyweightDir, "pipe1.gocd.xml").exists()).isTrue();
    }

    @Test
    public void shouldCheckoutChangedInExistingMaterial() throws Exception {
        GoConfigMother mother = new GoConfigMother();
        PipelineConfig pipelineConfig = mother.cruiseConfigWithOnePipelineGroup().getAllPipelineConfigs().get(0);

        configTestRepo.addPipelineToRepositoryAndPush("pipe1.gocd.xml", pipelineConfig);

        materialUpdateService.updateMaterial(material);
        // time for messages to pass through all services
        waitForMaterialNotInProgress();

        configTestRepo.addPipelineToRepositoryAndPush("pipe2.gocd.xml", pipelineConfig);

        materialUpdateService.updateMaterial(material);
        // time for messages to pass through all services
        waitForMaterialNotInProgress();

        File flyweightDir = materialRepository.folderFor(material);
       assertThat(flyweightDir.exists()).isTrue();
       assertThat(new File(flyweightDir, "pipe1.gocd.xml").exists()).isTrue();
       assertThat(new File(flyweightDir, "pipe2.gocd.xml").exists()).isTrue();
    }

    @Test
    public void shouldNotMergeFromInvalidConfigRepository_AndShouldKeepLastValidPart() throws Exception {
        String fileName = "pipe1.gocd.xml";

        GoConfigMother mother = new GoConfigMother();
        PipelineConfig pipelineConfig = mother.cruiseConfigWithOnePipelineGroup().getAllPipelineConfigs().get(0);

        configTestRepo.addPipelineToRepositoryAndPush(fileName, pipelineConfig);

        materialUpdateService.updateMaterial(material);
        // time for messages to pass through all services
        waitForMaterialNotInProgress();

        cachedGoConfig.forceReload();

        assertThat(goConfigService.hasPipelineNamed(pipelineConfig.name())).isTrue();
        assertThat(goConfigService.pipelineConfigNamed(pipelineConfig.name())).isEqualTo(pipelineConfig);

        configTestRepo.addCodeToRepositoryAndPush("badPipe.gocd.xml", "added bad config file", """
                <?xml version="1.0" encoding="utf-8"?>
                <cruise xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="cruise-config.xsd" schemaVersion="38">
                <pipelines group="changed">
                  <pipeline name="badPipe">
                    <materials>
                      <svn url="file:///tmp/foo" />
                      <svn url="file:///tmp/foo" />
                    </materials>
                  </pipeline>
                </pipelines></cruise>""");
        materialUpdateService.updateMaterial(material);
        // time for messages to pass through all services
        waitForMaterialNotInProgress();

        cachedGoConfig.forceReload();
        // but we still have the old part
        assertThat(goConfigService.hasPipelineNamed(pipelineConfig.name())).isTrue();
        assertThat(goConfigService.pipelineConfigNamed(pipelineConfig.name())).isEqualTo(pipelineConfig);
        // and no trace of badPipe
        assertThat(goConfigService.hasPipelineNamed(new CaseInsensitiveString("badPipe"))).isFalse();
    }

}
