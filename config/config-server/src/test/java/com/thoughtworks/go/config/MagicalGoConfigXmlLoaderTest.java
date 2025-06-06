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

import com.thoughtworks.go.config.elastic.ElasticProfile;
import com.thoughtworks.go.config.exceptions.GoConfigInvalidException;
import com.thoughtworks.go.config.materials.*;
import com.thoughtworks.go.config.materials.git.GitMaterialConfig;
import com.thoughtworks.go.config.materials.mercurial.HgMaterialConfig;
import com.thoughtworks.go.config.materials.perforce.P4MaterialConfig;
import com.thoughtworks.go.config.materials.svn.SvnMaterialConfig;
import com.thoughtworks.go.config.materials.tfs.TfsMaterialConfig;
import com.thoughtworks.go.config.merge.MergeConfigOrigin;
import com.thoughtworks.go.config.pluggabletask.PluggableTask;
import com.thoughtworks.go.config.preprocessor.ConfigParamPreprocessor;
import com.thoughtworks.go.config.preprocessor.ConfigRepoPartialPreprocessor;
import com.thoughtworks.go.config.registry.ConfigElementImplementationRegistrar;
import com.thoughtworks.go.config.registry.ConfigElementImplementationRegistry;
import com.thoughtworks.go.config.remote.ConfigRepoConfig;
import com.thoughtworks.go.config.remote.FileConfigOrigin;
import com.thoughtworks.go.config.remote.PartialConfig;
import com.thoughtworks.go.config.rules.Allow;
import com.thoughtworks.go.config.rules.Deny;
import com.thoughtworks.go.config.rules.Rules;
import com.thoughtworks.go.config.validation.ArtifactDirValidator;
import com.thoughtworks.go.config.validation.ServerIdImmutabilityValidator;
import com.thoughtworks.go.config.validation.TokenGenerationKeyImmutabilityValidator;
import com.thoughtworks.go.domain.*;
import com.thoughtworks.go.domain.config.Arguments;
import com.thoughtworks.go.domain.config.Configuration;
import com.thoughtworks.go.domain.config.ConfigurationProperty;
import com.thoughtworks.go.domain.config.RepositoryMetadataStoreHelper;
import com.thoughtworks.go.domain.label.PipelineLabel;
import com.thoughtworks.go.domain.materials.MaterialConfig;
import com.thoughtworks.go.domain.packagerepository.PackageDefinition;
import com.thoughtworks.go.domain.packagerepository.PackageRepository;
import com.thoughtworks.go.helper.MaterialConfigsMother;
import com.thoughtworks.go.helper.StageConfigMother;
import com.thoughtworks.go.plugin.access.artifact.ArtifactMetadataStore;
import com.thoughtworks.go.plugin.access.packagematerial.PackageConfiguration;
import com.thoughtworks.go.plugin.access.packagematerial.PackageConfigurations;
import com.thoughtworks.go.plugin.access.packagematerial.PackageMetadataStore;
import com.thoughtworks.go.plugin.access.packagematerial.RepositoryMetadataStore;
import com.thoughtworks.go.plugin.access.pluggabletask.PluggableTaskConfigStore;
import com.thoughtworks.go.plugin.access.pluggabletask.TaskPreference;
import com.thoughtworks.go.plugin.api.config.Property;
import com.thoughtworks.go.plugin.api.info.PluginDescriptor;
import com.thoughtworks.go.plugin.api.material.packagerepository.PackageMaterialProperty;
import com.thoughtworks.go.plugin.api.material.packagerepository.RepositoryConfiguration;
import com.thoughtworks.go.plugin.api.response.validation.ValidationResult;
import com.thoughtworks.go.plugin.api.task.TaskConfig;
import com.thoughtworks.go.plugin.api.task.TaskExecutor;
import com.thoughtworks.go.plugin.api.task.TaskView;
import com.thoughtworks.go.plugin.domain.artifact.ArtifactPluginInfo;
import com.thoughtworks.go.plugin.domain.artifact.Capabilities;
import com.thoughtworks.go.plugin.domain.common.Metadata;
import com.thoughtworks.go.plugin.domain.common.PluggableInstanceSettings;
import com.thoughtworks.go.plugin.domain.common.PluginConfiguration;
import com.thoughtworks.go.plugin.infra.plugininfo.GoPluginDescriptor;
import com.thoughtworks.go.security.AESCipherProvider;
import com.thoughtworks.go.security.AESEncrypter;
import com.thoughtworks.go.security.GoCipher;
import com.thoughtworks.go.security.ResetCipher;
import com.thoughtworks.go.util.*;
import com.thoughtworks.go.util.command.UrlArgument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.thoughtworks.go.config.PipelineConfig.*;
import static com.thoughtworks.go.domain.packagerepository.ConfigurationPropertyMother.create;
import static com.thoughtworks.go.helper.ConfigFileFixture.*;
import static com.thoughtworks.go.helper.MaterialConfigsMother.git;
import static com.thoughtworks.go.helper.MaterialConfigsMother.tfs;
import static com.thoughtworks.go.plugin.api.config.Property.*;
import static com.thoughtworks.go.util.GoConstants.CONFIG_SCHEMA_VERSION;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.*;


@ExtendWith(ResetCipher.class)
public class MagicalGoConfigXmlLoaderTest {
    private MagicalGoConfigXmlLoader xmlLoader;
    private static final String INVALID_DESTINATION_DIRECTORY_MESSAGE = "Invalid destination directory. Every material needs a different destination directory and the directories should not be nested";
    private final ConfigCache configCache = new ConfigCache();
    private final SystemEnvironment systemEnvironment = new SystemEnvironment();
    private MagicalGoConfigXmlWriter xmlWriter;
    private GoConfigMigration goConfigMigration;

    @BeforeEach
    void setup() {
        RepositoryMetadataStoreHelper.clear();
        ConfigElementImplementationRegistry registry = ConfigElementImplementationRegistryMother.withNoPlugins();
        new ConfigElementImplementationRegistrar(registry).initialize();
        xmlLoader = new MagicalGoConfigXmlLoader(configCache, registry);
        xmlWriter = new MagicalGoConfigXmlWriter(configCache, registry);
        goConfigMigration = new GoConfigMigration(new TimeProvider());
    }

    @AfterEach
    void tearDown() {
        systemEnvironment.setProperty("go.enforce.server.immutability", "N");
        RepositoryMetadataStoreHelper.clear();
        ArtifactMetadataStore.instance().clear();
    }

    @Test
    void shouldLoadConfigFile() throws Exception {
        CruiseConfig cruiseConfig = xmlLoader.loadConfigHolder(CONFIG).config;
        PipelineConfig pipelineConfig1 = cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("pipeline1"));
        assertThat(pipelineConfig1.size()).isEqualTo(2);
        assertThat(pipelineConfig1.getLabelTemplate()).isEqualTo(PipelineLabel.COUNT_TEMPLATE);

        StageConfig stage1 = pipelineConfig1.get(0);
        assertThat(stage1.name()).isEqualTo(new CaseInsensitiveString("stage1"));
        assertThat(stage1.allBuildPlans().size()).isEqualTo(1);
        assertThat(stage1.requiresApproval()).as("Should require approval").isTrue();
        AdminsConfig admins = stage1.getApproval().getAuthConfig();
        assertThat(admins).contains(new AdminRole(new CaseInsensitiveString("admin")));
        assertThat(admins).contains(new AdminRole(new CaseInsensitiveString("qa_lead")));
        assertThat(admins).contains(new AdminUser(new CaseInsensitiveString("jez")));

        StageConfig stage2 = pipelineConfig1.get(1);
        assertThat(stage2.requiresApproval()).as("Should not require approval").isFalse();

        JobConfig plan = stage1.jobConfigByInstanceName("plan1", true);
        assertThat(plan.name()).isEqualTo(new CaseInsensitiveString("plan1"));
        assertThat(plan.resourceConfigs().resourceNames()).contains("tiger", "lion");
        assertThat(plan.getTabs().size()).isEqualTo(2);
        assertThat(plan.getTabs().first().getName()).isEqualTo("Emma");
        assertThat(plan.getTabs().first().getPath()).isEqualTo("logs/emma/index.html");
        assertThat(pipelineConfig1.materialConfigs().size()).isEqualTo(1);
        shouldBeSvnMaterial(pipelineConfig1.materialConfigs().first());

        PipelineConfig pipelineConfig2 = cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("pipeline2"));
        shouldBeHgMaterial(pipelineConfig2.materialConfigs().first());

        PipelineConfig pipelineConfig3 = cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("pipeline3"));
        MaterialConfig p4Material = pipelineConfig3.materialConfigs().first();
        shouldBeP4Material(p4Material);

        PipelineConfig pipelineConfig4 = cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("pipeline4"));
        shouldBeGitMaterial(pipelineConfig4.materialConfigs().first());
    }

    @Test
    void shouldLoadConfigWithConfigRepo() throws Exception {
        CruiseConfig cruiseConfig = xmlLoader.loadConfigHolder(ONE_CONFIG_REPO).config;
        assertThat(cruiseConfig.getConfigRepos().size()).isEqualTo(1);
        ConfigRepoConfig configRepo = cruiseConfig.getConfigRepos().get(0);
        assertThat(configRepo.getRepo()).isEqualTo(git("https://github.com/tomzo/gocd-indep-config-part.git"));
    }

    @Test
    void shouldLoadConfigWithConfigRepoAndPluginName() throws Exception {
        CruiseConfig cruiseConfig = xmlLoader.loadConfigHolder(configWithConfigRepos(
                """
                          <config-repos>
                            <config-repo pluginId="myplugin" id="repo-id">
                              <git url="https://github.com/tomzo/gocd-indep-config-part.git" />
                            </config-repo >
                          </config-repos>
                        """
        )).config;
        assertThat(cruiseConfig.getConfigRepos().size()).isEqualTo(1);
        ConfigRepoConfig configRepo = cruiseConfig.getConfigRepos().get(0);
        assertThat(configRepo.getPluginId()).isEqualTo("myplugin");
    }

    @Test
    void shouldLoadConfigWith2ConfigRepos() throws Exception {
        CruiseConfig cruiseConfig = xmlLoader.loadConfigHolder(configWithConfigRepos(
                """
                          <config-repos>
                            <config-repo pluginId="myplugin" id="repo-id1">
                              <git url="https://github.com/tomzo/gocd-indep-config-part.git" />
                            </config-repo >
                            <config-repo pluginId="myplugin" id="repo-id2">
                              <git url="https://github.com/tomzo/gocd-refmain-config-part.git" />
                            </config-repo >
                          </config-repos>
                        """
        )).config;
        assertThat(cruiseConfig.getConfigRepos().size()).isEqualTo(2);
        ConfigRepoConfig configRepo1 = cruiseConfig.getConfigRepos().get(0);
        assertThat(configRepo1.getRepo()).isEqualTo(git("https://github.com/tomzo/gocd-indep-config-part.git"));
        ConfigRepoConfig configRepo2 = cruiseConfig.getConfigRepos().get(1);
        assertThat(configRepo2.getRepo()).isEqualTo(git("https://github.com/tomzo/gocd-refmain-config-part.git"));
    }

    @Test
    void shouldLoadConfigWithConfigRepoAndConfiguration() throws Exception {
        CruiseConfig cruiseConfig = xmlLoader.loadConfigHolder(configWithConfigRepos(
                """
                          <config-repos>
                            <config-repo id="id1" pluginId="gocd-xml">
                              <git url="https://github.com/tomzo/gocd-indep-config-part.git" />
                              <configuration>
                                <property>
                                  <key>pattern</key>
                                  <value>*.gocd.xml</value>
                                </property>
                              </configuration>
                            </config-repo >
                          </config-repos>
                        """
        )).config;
        assertThat(cruiseConfig.getConfigRepos().size()).isEqualTo(1);
        ConfigRepoConfig configRepo = cruiseConfig.getConfigRepos().get(0);

        assertThat(configRepo.getConfiguration().size()).isEqualTo(1);
        assertThat(configRepo.getConfiguration().getProperty("pattern").getValue()).isEqualTo("*.gocd.xml");
    }

    @Test
    void shouldThrowXsdValidationException_WhenNoRepository() {
        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(configWithConfigRepos(
                """
                          <config-repos>
                            <config-repo pluginId="myplugin">
                            </config-repo >
                          </config-repos>
                        """
        ))).isInstanceOf(XsdValidationException.class);
    }

    @Test
    void shouldThrowXsdValidationException_When2RepositoriesInSameConfigElement() {
        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(configWithConfigRepos(
                """
                          <config-repos>
                            <config-repo pluginId="myplugin">
                              <git url="https://github.com/tomzo/gocd-indep-config-part.git" />
                              <git url="https://github.com/tomzo/gocd-refmain-config-part.git" />
                            </config-repo >
                          </config-repos>
                        """
        ))).isInstanceOf(XsdValidationException.class);
    }

    @Test
    void shouldFailValidation_WhenSameMaterialUsedBy2ConfigRepos() {
        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(configWithConfigRepos(
                """
                          <config-repos>
                            <config-repo pluginId="myplugin" id="id1">
                              <git url="https://github.com/tomzo/gocd-indep-config-part.git" />
                            </config-repo >
                            <config-repo pluginId="myotherplugin" id="id2">
                              <git url="https://github.com/tomzo/gocd-indep-config-part.git" />
                            </config-repo >
                          </config-repos>
                        """
        )))
                .isInstanceOf(GoConfigInvalidException.class);
    }

    @Test
    void shouldSetConfigOriginInCruiseConfig_AfterLoadingConfigFile() throws Exception {
        GoConfigHolder goConfigHolder = xmlLoader.loadConfigHolder(CONFIG, cruiseConfig -> cruiseConfig.setPartials(List.of(new PartialConfig())));
        assertThat(goConfigHolder.config.getOrigin()).isEqualTo(new MergeConfigOrigin());
        assertThat(goConfigHolder.configForEdit.getOrigin()).isEqualTo(new FileConfigOrigin());
    }

    @Test
    void shouldSetConfigOriginInPipeline_AfterLoadingConfigFile() throws Exception {
        CruiseConfig cruiseConfig = xmlLoader.loadConfigHolder(CONFIG).config;
        PipelineConfig pipelineConfig1 = cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("pipeline1"));
        assertThat(pipelineConfig1.getOrigin()).isEqualTo(new FileConfigOrigin());
    }

    @Test
    void shouldSetConfigOriginInEnvironment_AfterLoadingConfigFile() throws Exception {
        String content = configWithEnvironments(
                """
                        <environments>
                          <environment name='uat'>
                          </environment>
                        </environments>""", CONFIG_SCHEMA_VERSION);
        EnvironmentsConfig environmentsConfig = xmlLoader.deserializeConfig(content).getEnvironments();
        EnvironmentConfig uat = environmentsConfig.get(0);
        assertThat(uat.getOrigin()).isEqualTo(new FileConfigOrigin());
    }

    @Test
    void shouldLoadAntBuilder() throws Exception {
        CruiseConfig cruiseConfig = xmlLoader.loadConfigHolder(CONFIG_WITH_ANT_BUILDER).config;
        JobConfig plan = cruiseConfig.jobConfigByName("pipeline1", "mingle", "cardlist", true);

        assertThat(plan.tasks()).hasSize(1);
        AntTask builder = (AntTask) plan.tasks().first();
        assertThat(builder.getTarget()).isEqualTo("all");
        final ArtifactTypeConfigs cardListArtifacts = cruiseConfig.jobConfigByName("pipeline1", "mingle",
                "cardlist", true).artifactTypeConfigs();
        assertThat(cardListArtifacts.size()).isEqualTo(1);
        ArtifactTypeConfig artifactConfigPlan = cardListArtifacts.get(0);
        assertThat(artifactConfigPlan.getArtifactType()).isEqualTo(ArtifactType.test);
    }

    @Test
    void shouldLoadNAntBuilder() throws Exception {
        CruiseConfig cruiseConfig = xmlLoader.deserializeConfig(
                CONFIG_WITH_NANT_AND_EXEC_BUILDER);
        JobConfig plan = cruiseConfig.jobConfigByName("pipeline1", "mingle", "cardlist", true);
        BuildTask builder = (BuildTask) plan.tasks().findFirstByType(NantTask.class);
        assertThat(builder.getTarget()).isEqualTo("all");
    }

    @Test
    void shouldLoadExecBuilder() throws Exception {

        CruiseConfig cruiseConfig = xmlLoader.deserializeConfig(CONFIG_WITH_NANT_AND_EXEC_BUILDER);
        JobConfig plan = cruiseConfig.jobConfigByName("pipeline1", "mingle", "cardlist", true);
        ExecTask builder = (ExecTask) plan.tasks().findFirstByType(ExecTask.class);
        assertThat(builder).isEqualTo(new ExecTask("ls", "-la", "workdir"));

        builder = (ExecTask) plan.tasks().get(2);
        assertThat(builder).isEqualTo(new ExecTask("ls", "", (String) null));
    }

    @Test
    void shouldLoadRakeBuilderWithEmptyOnCancel() throws Exception {

        CruiseConfig cruiseConfig = xmlLoader.deserializeConfig(CONFIG_WITH_NANT_AND_EXEC_BUILDER);
        JobConfig plan = cruiseConfig.jobConfigByName("pipeline1", "mingle", "cardlist", true);
        RakeTask builder = (RakeTask) plan.tasks().findFirstByType(RakeTask.class);
        assertThat(builder).isNotNull();
    }

    @Test
    void shouldRetainArtifactSourceThatIsNotWhitespace() throws Exception {
        CruiseConfig cruiseConfig = xmlLoader.deserializeConfig(goConfigMigration.upgradeIfNecessary(configWithArtifactSourceAs("t ")));
        JobConfig plan = cruiseConfig.jobConfigByName("pipeline", "stage", "job", true);
        assertThat(plan.artifactTypeConfigs().getBuiltInArtifactConfigs().get(0).getSource()).isEqualTo("t ");
    }

    @Test
    void shouldLoadBuildPlanFromXmlPartial() throws Exception {
        String buildXmlPartial =
                """
                        <job name="functional">
                          <artifacts>
                            <artifact type="build" src="artifact1.xml" dest="cruise-output" />
                          </artifacts>
                        </job>""";
        JobConfig build = xmlLoader.fromXmlPartial(buildXmlPartial, JobConfig.class);
        assertThat(build.name()).isEqualTo(new CaseInsensitiveString("functional"));
        assertThat(build.artifactTypeConfigs().size()).isEqualTo(1);
    }


    @Test
    void shouldLoadIgnoresFromSvnPartial() throws Exception {
        String buildXmlPartial =
                """
                        <svn url="file:///tmp/testSvnRepo/project1/trunk" >
                                    <filter>
                                        <ignore pattern="x"/>
                                    </filter>
                                </svn>""";
        MaterialConfig svnMaterial = xmlLoader.fromXmlPartial(buildXmlPartial, SvnMaterialConfig.class);
        Filter parsedFilter = svnMaterial.filter();
        Filter expectedFilter = new Filter();
        expectedFilter.add(new IgnoredFiles("x"));
        assertThat(parsedFilter).isEqualTo(expectedFilter);
    }

    @Test
    void shouldLoadIgnoresFromHgPartial() throws Exception {
        String buildXmlPartial =
                """
                        <hg url="file:///tmp/testSvnRepo/project1/trunk" >
                                    <filter>
                                        <ignore pattern="x"/>
                                    </filter>
                                </hg>""";
        MaterialConfig hgMaterial = xmlLoader.fromXmlPartial(buildXmlPartial, HgMaterialConfig.class);
        Filter parsedFilter = hgMaterial.filter();
        Filter expectedFilter = new Filter();
        expectedFilter.add(new IgnoredFiles("x"));
        assertThat(parsedFilter).isEqualTo(expectedFilter);
    }

    @Test
    void shouldLoadMaterialWithAutoUpdate() throws Exception {
        MaterialConfig material = xmlLoader.fromXmlPartial("<hg url=\"file:///tmp/testSvnRepo/project1/trunk\" autoUpdate=\"false\"/>", HgMaterialConfig.class);
        assertThat(material.isAutoUpdate()).isFalse();
        material = xmlLoader.fromXmlPartial("<git url=\"file:///tmp/testSvnRepo/project1/trunk\" autoUpdate=\"false\"/>", GitMaterialConfig.class);
        assertThat(material.isAutoUpdate()).isFalse();
        material = xmlLoader.fromXmlPartial("<svn url=\"file:///tmp/testSvnRepo/project1/trunk\" autoUpdate=\"false\"/>", SvnMaterialConfig.class);
        assertThat(material.isAutoUpdate()).isFalse();
        material = xmlLoader.fromXmlPartial("<p4 port='localhost:1666' autoUpdate='false' ><view/></p4>", P4MaterialConfig.class);
        assertThat(material.isAutoUpdate()).isFalse();
    }

    @Test
    void autoUpdateShouldBeTrueByDefault() throws Exception {
        MaterialConfig hgMaterial = xmlLoader.fromXmlPartial("<hg url=\"file:///tmp/testSvnRepo/project1/trunk\"/>", HgMaterialConfig.class);
        assertThat(hgMaterial.isAutoUpdate()).isTrue();
    }

    @Test
    void autoUpdateShouldUnderstandTrue() throws Exception {
        MaterialConfig hgMaterial = xmlLoader.fromXmlPartial("<hg url=\"file:///tmp/testSvnRepo/project1/trunk\" autoUpdate=\"true\"/>", HgMaterialConfig.class);
        assertThat(hgMaterial.isAutoUpdate()).isTrue();
    }

    @Test
    void shouldValidateBooleanAutoUpdateOnMaterials() throws Exception {
        String noAutoUpdate =
                """
                          <materials>
                            <svn url="/hgrepo2" />
                          </materials>
                        """;
        MagicalGoConfigXmlLoaderFixture.assertValid(noAutoUpdate);
        String validAutoUpdate =
                """
                          <materials>
                            <svn url="/hgrepo2" autoUpdate='true'/>
                          </materials>
                        """;
        MagicalGoConfigXmlLoaderFixture.assertValid(validAutoUpdate);
        String invalidautoUpdate =
                """
                          <materials>
                            <git url="/hgrepo2" autoUpdate="fooo"/>
                          </materials>
                        """;
        MagicalGoConfigXmlLoaderFixture.assertNotValid("'fooo' is not a valid value for 'boolean'.", invalidautoUpdate);
    }

    @Test
    void shouldInvalidateAutoUpdateOnDependencyMaterial() {
        String noAutoUpdate =
                """
                          <materials>
                            <pipeline pipelineName="pipeline" stageName="stage" autoUpdate="true"/>
                          </materials>
                        """;
        MagicalGoConfigXmlLoaderFixture.assertNotValid("Attribute 'autoUpdate' is not allowed to appear in element 'pipeline'.", noAutoUpdate);
    }

    @Test
    void shouldInvalidateAutoUpdateIfTheSameMaterialHasDifferentValuesForAutoUpdate() {
        String noAutoUpdate =
                """
                          <materials>
                            <svn url="/hgrepo2" autoUpdate='true' dest='first'/>
                            <svn url="/hgrepo2" autoUpdate='false' dest='second'/>
                          </materials>
                        """;
        MagicalGoConfigXmlLoaderFixture.assertNotValid(
                "The material of type Subversion (/hgrepo2) is used elsewhere with a different value for autoUpdate (poll for changes). Those values should be the same. Pipelines:\n pipeline (auto update enabled)",
                noAutoUpdate);
    }

    @Test
    void shouldLoadFromSvnPartial() throws Exception {
        String buildXmlPartial =
                "<svn url=\"https://foo.bar\" username=\"cruise\" password=\"password\" materialName=\"https___foo.bar\"/>";

        MaterialConfig materialConfig = xmlLoader.fromXmlPartial(buildXmlPartial, SvnMaterialConfig.class);
        MaterialConfig svnMaterial = MaterialConfigsMother.svnMaterialConfig("https://foo.bar", null, "cruise", "password", false, null);
        assertThat(materialConfig).isEqualTo(svnMaterial);
    }

    @Test
    void shouldLoadGetFromSvnPartialForDir() throws Exception {
        String buildXmlPartial =
                """
                        <jobs>
                          <job name="functional">
                             <tasks>
                                 <fetchartifact artifactOrigin='gocd' stage='dev' job='unit' srcdir='dist' dest='lib' />
                              </tasks>
                            </job>
                        </jobs>""";

        JobConfigs jobs = xmlLoader.fromXmlPartial(buildXmlPartial, JobConfigs.class);
        JobConfig job = jobs.first();
        Tasks fetch = job.tasks();
        assertThat(fetch.size()).isEqualTo(1);
        FetchTask task = (FetchTask) fetch.first();
        assertThat(task.getStage()).isEqualTo(new CaseInsensitiveString("dev"));
        assertThat(task.getJob().toString()).isEqualTo("unit");
        assertThat(task.getSrc()).isEqualTo("dist");
        assertThat(task.getDest()).isEqualTo("lib");
    }

    @Test
    void shouldAllowEmptyOnCancel() throws Exception {
        String buildXmlPartial =
                """
                        <jobs>
                          <job name="functional">
                             <tasks>
                                 <exec command='ls'>
                                     <oncancel/>
                                 </exec>
                              </tasks>
                            </job>
                        </jobs>""";

        JobConfigs jobs = xmlLoader.fromXmlPartial(buildXmlPartial, JobConfigs.class);
        JobConfig job = jobs.first();
        Tasks tasks = job.tasks();
        assertThat(tasks.size()).isEqualTo(1);
        ExecTask execTask = (ExecTask) tasks.get(0);
        assertThat(execTask.cancelTask()).isInstanceOf(NullTask.class);
    }

    @Test
    void shouldLoadIgnoresFromGitPartial() throws Exception {
        String gitPartial =
                """
                        <git url='file:///tmp/testGitRepo/project1' >
                                    <filter>
                                        <ignore pattern='x'/>
                                    </filter>
                                </git>""";
        GitMaterialConfig gitMaterial = xmlLoader.fromXmlPartial(gitPartial, GitMaterialConfig.class);
        assertThat(gitMaterial.getBranch()).isEqualTo(GitMaterialConfig.DEFAULT_BRANCH);
        Filter parsedFilter = gitMaterial.filter();
        Filter expectedFilter = new Filter();
        expectedFilter.add(new IgnoredFiles("x"));
        assertThat(parsedFilter).isEqualTo(expectedFilter);
    }

    @Test
    void shouldLoadShallowFlagFromGitPartial() throws Exception {
        String gitPartial = "<git url='file:///tmp/testGitRepo/project1' shallowClone=\"true\" />";
        GitMaterialConfig gitMaterial = xmlLoader.fromXmlPartial(gitPartial, GitMaterialConfig.class);
        assertThat(gitMaterial.isShallowClone()).isTrue();
    }

    @Test
    void shouldLoadBranchFromGitPartial() throws Exception {
        String gitPartial = "<git url='file:///tmp/testGitRepo/project1' branch='foo'/>";
        GitMaterialConfig gitMaterial = xmlLoader.fromXmlPartial(gitPartial, GitMaterialConfig.class);
        assertThat(gitMaterial.getBranch()).isEqualTo("foo");
    }

    @Test
    void shouldLoadIgnoresFromP4Partial() throws Exception {
        String gitPartial =
                """
                        <p4 port="localhost:8080">
                                    <filter>
                                        <ignore pattern="x"/>
                                    </filter>
                         <view></view>
                        </p4>""";
        MaterialConfig p4Material = xmlLoader.fromXmlPartial(gitPartial, P4MaterialConfig.class);
        Filter parsedFilter = p4Material.filter();
        Filter expectedFilter = new Filter();
        expectedFilter.add(new IgnoredFiles("x"));
        assertThat(parsedFilter).isEqualTo(expectedFilter);
    }

    @Test
    void shouldLoadStageFromXmlPartial() throws Exception {
        String stageXmlPartial =
                """
                        <stage name="mingle">
                          <jobs>
                            <job name="functional">
                              <artifacts>
                                <log src="artifact1.xml" dest="cruise-output" />
                              </artifacts>
                            </job>
                          </jobs>
                        </stage>
                        """;
        StageConfig stage = xmlLoader.fromXmlPartial(stageXmlPartial, StageConfig.class);
        assertThat(stage.name()).isEqualTo(new CaseInsensitiveString("mingle"));
        assertThat(stage.allBuildPlans().size()).isEqualTo(1);
        assertThat(stage.jobConfigByInstanceName("functional", true)).isNotNull();
    }

    @Test
    void shouldLoadStageArtifactPurgeSettingsFromXmlPartial() throws Exception {
        String stageXmlPartial =
                """
                        <stage name="mingle" artifactCleanupProhibited="true">
                          <jobs>
                            <job name="functional">
                              <artifacts>
                                <log src="artifact1.xml" dest="cruise-output" />
                              </artifacts>
                            </job>
                          </jobs>
                        </stage>
                        """;
        StageConfig stage = xmlLoader.fromXmlPartial(stageXmlPartial, StageConfig.class);
        assertThat(stage.isArtifactCleanupProhibited()).isTrue();

        stageXmlPartial =
                """
                        <stage name="mingle" artifactCleanupProhibited="false">
                          <jobs>
                            <job name="functional">
                              <artifacts>
                                <log src="artifact1.xml" dest="cruise-output" />
                              </artifacts>
                            </job>
                          </jobs>
                        </stage>
                        """;
        stage = xmlLoader.fromXmlPartial(stageXmlPartial, StageConfig.class);
        assertThat(stage.isArtifactCleanupProhibited()).isFalse();

        stageXmlPartial =
                """
                        <stage name="mingle">
                          <jobs>
                            <job name="functional">
                              <artifacts>
                                <log src="artifact1.xml" dest="cruise-output" />
                              </artifacts>
                            </job>
                          </jobs>
                        </stage>
                        """;
        stage = xmlLoader.fromXmlPartial(stageXmlPartial, StageConfig.class);
        assertThat(stage.isArtifactCleanupProhibited()).isFalse();
    }

    @Test
    void shouldLoadPartialConfigWithPipeline() throws Exception {
        String partialConfigWithPipeline =
                ("""
                        <cruise schemaVersion='%d'>
                        <pipelines group="first">
                        <pipeline name="pipeline">
                          <materials>
                            <hg url="/hgrepo"/>
                          </materials>
                          <stage name="mingle">
                            <jobs>
                              <job name="functional">
                                <artifacts>
                                  <log src="artifact1.xml" dest="cruise-output" />
                                </artifacts>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>
                        </pipelines>
                        </cruise>
                        """).formatted(CONFIG_SCHEMA_VERSION);
        PartialConfig partialConfig = xmlLoader.fromXmlPartial(partialConfigWithPipeline, PartialConfig.class);
        assertThat(partialConfig.getGroups().size()).isEqualTo(1);
        PipelineConfig pipeline = partialConfig.getGroups().get(0).getPipelines().get(0);
        assertThat(pipeline.name()).isEqualTo(new CaseInsensitiveString("pipeline"));
        assertThat(pipeline.size()).isEqualTo(1);
        assertThat(pipeline.findBy(new CaseInsensitiveString("mingle")).jobConfigByInstanceName("functional", true)).isNotNull();
    }

    @Test
    void shouldLoadPartialConfigWithEnvironment() throws Exception {
        String partialConfigWithPipeline = configWithEnvironments(
                """
                        <environments>
                          <environment name='uat'>
                             <pipelines>
                                 <pipeline name='pipeline1' />
                             </pipelines>
                          </environment>
                          <environment name='prod' />
                        </environments>""", CONFIG_SCHEMA_VERSION);
        PartialConfig partialConfig = xmlLoader.fromXmlPartial(partialConfigWithPipeline, PartialConfig.class);
        EnvironmentsConfig environmentsConfig = partialConfig.getEnvironments();
        assertThat(environmentsConfig.size()).isEqualTo(2);
        assertThat(environmentsConfig.get(0).containsPipeline(new CaseInsensitiveString("pipeline1"))).isTrue();
        assertThat(environmentsConfig.get(1).getPipelines().size()).isEqualTo(0);
    }

    @Test
    void shouldLoadPipelineFromXmlPartial() throws Exception {
        String pipelineXmlPartial =
                """
                        <pipeline name="pipeline">
                          <materials>
                            <hg url="/hgrepo"/>
                          </materials>
                          <stage name="mingle">
                            <jobs>
                              <job name="functional">
                                <artifacts>
                                  <log src="artifact1.xml" dest="cruise-output" />
                                </artifacts>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>
                        """;
        PipelineConfig pipeline = xmlLoader.fromXmlPartial(pipelineXmlPartial, PipelineConfig.class);
        assertThat(pipeline.name()).isEqualTo(new CaseInsensitiveString("pipeline"));
        assertThat(pipeline.size()).isEqualTo(1);
        assertThat(pipeline.findBy(new CaseInsensitiveString("mingle")).jobConfigByInstanceName("functional", true)).isNotNull();
    }

    @Test
    void shouldBeAbleToExplicitlyLockAPipeline() throws Exception {
        String pipelineXmlPartial =
                ("""
                        <pipeline name="pipeline" lockBehavior="%s">
                          <materials>
                            <hg url="/hgrepo"/>
                          </materials>
                          <stage name="mingle">
                            <jobs>
                              <job name="functional">
                                <artifacts>
                                  <log src="artifact1.xml" dest="cruise-output" />
                                </artifacts>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>
                        """).formatted(LOCK_VALUE_LOCK_ON_FAILURE);
        PipelineConfig pipeline = xmlLoader.fromXmlPartial(pipelineXmlPartial, PipelineConfig.class);

        assertThat(pipeline.hasExplicitLock()).isTrue();
        assertThat(pipeline.explicitLock()).isTrue();
    }

    @Test
    void shouldBeAbleToExplicitlyUnlockAPipeline() throws Exception {
        String pipelineXmlPartial =
                ("""
                        <pipeline name="pipeline" lockBehavior="%s">
                          <materials>
                            <hg url="/hgrepo"/>
                          </materials>
                          <stage name="mingle">
                            <jobs>
                              <job name="functional">
                                <artifacts>
                                  <log src="artifact1.xml" dest="cruise-output" />
                                </artifacts>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>
                        """).formatted(PipelineConfig.LOCK_VALUE_NONE);
        PipelineConfig pipeline = xmlLoader.fromXmlPartial(pipelineXmlPartial, PipelineConfig.class);

        assertThat(pipeline.hasExplicitLock()).isTrue();
        assertThat(pipeline.explicitLock()).isFalse();
    }

    @Test
    void shouldUnderstandNoExplicitLockOnAPipeline() throws Exception {
        String pipelineXmlPartial =
                """
                        <pipeline name="pipeline">
                          <materials>
                            <hg url="/hgrepo"/>
                          </materials>
                          <stage name="mingle">
                            <jobs>
                              <job name="functional">
                                <artifacts>
                                  <log src="artifact1.xml" dest="cruise-output" />
                                </artifacts>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>
                        """;
        PipelineConfig pipeline = xmlLoader.fromXmlPartial(pipelineXmlPartial, PipelineConfig.class);

        assertThat(pipeline.hasExplicitLock()).isFalse();
        assertThatThrownBy(pipeline::explicitLock)
            .as("Should throw exception if call explicit lock without first checking to see if there is one")
            .hasMessageContaining("There is no explicit lock on the pipeline 'pipeline'.");
    }

    @Test
    void shouldLoadPipelineWithP4MaterialFromXmlPartial() throws Exception {
        String pipelineWithP4MaterialXmlPartial =
                """
                        <pipeline name="pipeline">
                          <materials>
                            <p4 port="10.18.3.241:9999" username="cruise" password="password"         useTickets="true">
                                  <view><![CDATA[//depot/dev/... //lumberjack/...]]></view>
                            </p4>
                          </materials>
                          <stage name="mingle">
                            <jobs>
                              <job name="functional">
                                <artifacts>
                                  <log src="artifact1.xml" dest="cruise-output" />
                                </artifacts>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>
                        """;
        PipelineConfig pipeline = xmlLoader.fromXmlPartial(pipelineWithP4MaterialXmlPartial, PipelineConfig.class);
        assertThat(pipeline.name()).isEqualTo(new CaseInsensitiveString("pipeline"));
        MaterialConfig material = pipeline.materialConfigs().first();
        assertThat(material).isInstanceOf(P4MaterialConfig.class);
        assertThat(((P4MaterialConfig) material).getUseTickets()).isTrue();
    }

    @Test
    void shouldThrowExceptionWhenXmlDoesNotMapToXmlPartial() {
        String stageXmlPartial =
                """
                        <stage name="mingle">
                          <jobs>
                            <job name="functional">
                              <artifacts>
                                <log src="artifact1.xml" dest="cruise-output" />
                              </artifacts>
                            </job>
                          </jobs>
                        </stage>
                        """;

        assertThatThrownBy(() -> xmlLoader.fromXmlPartial(stageXmlPartial, JobConfig.class))
            .as("Should not be able to load stage into jobConfig")
            .hasMessage("Unable to parse element <stage> for class JobConfig");
    }


    @Test
    void shouldThrowExceptionWhenCommandIsEmpty() {
        String jobWithCommand =
                """
                        <job name="functional">
                              <tasks>
                                <exec command="" arguments="" />
                              </tasks>
                            </job>
                        """;
        String configWithInvalidCommand = withCommand(jobWithCommand);

        assertThatThrownBy(() -> xmlLoader.deserializeConfig(configWithInvalidCommand))
            .as("Should not allow empty command")
            .hasMessageContaining("Command is invalid. \"\" should conform to the pattern - \\S(.*\\S)?");
    }

    @Test
    void shouldThrowExceptionWhenCommandsContainTrailingSpaces() {
        String configXml =
                ("""
                        <cruise schemaVersion='%d'>
                          <pipelines group='first'>
                            <pipeline name='Test'>
                              <materials>
                                <hg url='../manual-testing/ant_hg/dummy' />
                              </materials>
                              <stage name='Functional'>
                                <jobs>
                                  <job name='Functional'>
                                    <tasks>
                                      <exec command='bundle  ' args='arguments' />
                                    </tasks>
                                   </job>
                                </jobs>
                              </stage>
                            </pipeline>
                          </pipelines>
                        </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> xmlLoader.deserializeConfig(configXml))
            .as("Should not allow command with trailing spaces")
            .hasMessageContaining("Command is invalid. \"bundle  \" should conform to the pattern - \\S(.*\\S)?");
    }

    @Test
    void shouldThrowExceptionWhenCommandsContainLeadingSpaces() {
        String configXml =
                ("""
                        <cruise schemaVersion='%d'>
                          <pipelines group='first'>
                            <pipeline name='Test'>
                              <materials>
                                <hg url='../manual-testing/ant_hg/dummy' />
                              </materials>
                              <stage name='Functional'>
                                <jobs>
                                  <job name='Functional'>
                                    <tasks>
                                      <exec command='    bundle' args='arguments' />
                                    </tasks>
                                   </job>
                                </jobs>
                              </stage>
                            </pipeline>
                          </pipelines>
                        </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> xmlLoader.deserializeConfig(configXml))
            .as("Should not allow command with trailing spaces")
            .hasMessageContaining("Command is invalid. \"    bundle\" should conform to the pattern - \\S(.*\\S)?");
    }

    @Test
    void shouldSupportCommandWithWhiteSpace() throws Exception {
        String jobWithCommand =
                """
                        <job name="functional">
                              <tasks>
                                <exec command="c:\\program files\\cmd.exe" args="arguments" />
                              </tasks>
                            </job>
                        """;
        String configWithCommand = withCommand(jobWithCommand);
        CruiseConfig cruiseConfig = xmlLoader.deserializeConfig(configWithCommand);
        Task task = cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("pipeline1")).first().allBuildPlans().first().tasks().first();

        assertThat(task).isInstanceOf(ExecTask.class);
        assertThat(task).isEqualTo(new ExecTask("c:\\program files\\cmd.exe", "arguments", (String) null));
    }

    private void shouldBeSvnMaterial(MaterialConfig material) {
        assertThat(material).isInstanceOf(SvnMaterialConfig.class);
        SvnMaterialConfig svnMaterial = (SvnMaterialConfig) material;
        assertThat(svnMaterial.getUrl()).isEqualTo("svnUrl");
        assertThat(svnMaterial.isCheckExternals()).isTrue();
    }

    private void shouldBeHgMaterial(MaterialConfig material) {
        assertThat(material).isInstanceOf(HgMaterialConfig.class);
        HgMaterialConfig hgMaterial = (HgMaterialConfig) material;
        assertThat(hgMaterial.getUrl()).isEqualTo("https://hgUrl.com");
        assertThat(hgMaterial.getUserName()).isEqualTo("username");
        assertThat(hgMaterial.getPassword()).isEqualTo("password");
    }

    private void shouldBeP4Material(MaterialConfig material) {
        assertThat(material).isInstanceOf(P4MaterialConfig.class);
        P4MaterialConfig p4Material = (P4MaterialConfig) material;
        assertThat(p4Material.getServerAndPort()).isEqualTo("localhost:1666");
        assertThat(p4Material.getUserName()).isEqualTo("cruise");
        assertThat(p4Material.getPassword()).isEqualTo("password");
        assertThat(p4Material.getView()).isEqualTo("//depot/dir1/... //lumberjack/...");
    }

    private void shouldBeGitMaterial(MaterialConfig material) {
        assertThat(material).isInstanceOf(GitMaterialConfig.class);
        GitMaterialConfig gitMaterial = (GitMaterialConfig) material;
        assertThat(gitMaterial.getUrl()).isEqualTo("git://username:password@gitUrl");
    }

    @Test
    void shouldNotAllowEmptyAuthInApproval() {
        assertXsdFailureDuringLoad(STAGE_WITH_EMPTY_AUTH,
                "The content of element 'authorization' is not complete. One of '{user, role}' is expected.");
    }

    @Test
    void shouldNotAllowEmptyRoles() {
        assertXsdFailureDuringLoad(CONFIG_WITH_EMPTY_ROLES,
                "The content of element 'roles' is not complete. One of '{baseRole}' is expected.");
    }

    @Test
    void shouldNotAllowEmptyUser() {
        assertXsdFailureDuringLoad(CONFIG_WITH_EMPTY_USER,
                "Value '' with length = '0' is not facet-valid with respect to minLength '1' for type '#AnonType_userusersroleType'.");
    }

    @Test
    void shouldNotAllowDuplicateRoles() {
        assertFailureDuringLoad(CONFIG_WITH_DUPLICATE_ROLE, GoConfigInvalidException.class, "Role names should be unique. Duplicate names found.");
    }

    @Test
    void shouldNotAllowDuplicateUsersInARole() {
        assertFailureDuringLoad(CONFIG_WITH_DUPLICATE_USER, GoConfigInvalidException.class, "User 'ps' already exists in 'admin'.");
    }

    /**
     * This is a test for a specific bug at a customer installation caused by a StackOverflowException in Xerces.
     * It seems to be caused by a regex bug in nonEmptyString.
     */
    @Test
    void shouldLoadConfigurationFileWithComplexNonEmptyString() throws Exception {
        String customerXML = goConfigMigration.upgradeIfNecessary(TestFileUtil.resourceToString("/data/p4_heavy_cruise_config.xml"));
        assertThat(xmlLoader.deserializeConfig(customerXML)).isNotNull();
    }

    @Test
    void shouldNotAllowEmptyViewForPerforce() throws IOException {
        String config = TestFileUtil.resourceToString(("/data/p4-cruise-config-empty-view.xml"));
        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(goConfigMigration.upgradeIfNecessary(config)))
            .as("Should not accept p4 section with empty view.")
            .hasMessageContaining("P4 view cannot be empty.");
    }

    @Test
    void shouldLoadPipelineWithMultipleMaterials() throws Exception {
        String pipelineXmlPartial =
                """
                        <pipeline name="pipeline">
                          <materials>
                            <svn url="/hgrepo1" dest="folder1" />
                            <svn url="/hgrepo2" dest="folder2" />
                            <svn url="/hgrepo3" dest="folder3" />
                          </materials>
                          <stage name="mingle">
                            <jobs>
                              <job name="functional">
                                <artifacts>
                                  <log src="artifact1.xml" dest="cruise-output" />
                                </artifacts>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>
                        """;
        PipelineConfig pipeline = xmlLoader.fromXmlPartial(pipelineXmlPartial, PipelineConfig.class);
        assertThat(pipeline.materialConfigs().size()).isEqualTo(3);
        ScmMaterialConfig material = (ScmMaterialConfig) pipeline.materialConfigs().get(0);
        assertThat(material.getFolder()).isEqualTo("folder1");
    }

    @Test
    void shouldThrowErrorIfMultipleMaterialsHaveSameFolders() {
        String materials =
                """
                          <materials>
                            <svn url="/hgrepo1" dest="folder1" />
                            <svn url="/hgrepo2" dest="folder1" />
                          </materials>
                        """;
        MagicalGoConfigXmlLoaderFixture.assertNotValid(INVALID_DESTINATION_DIRECTORY_MESSAGE, materials);
    }

    @Test
    void shouldThrowErrorIfOneOfMultipleMaterialsHasNoFolder() {
        String materials =
                """
                          <materials>
                            <svn url="/hgrepo1" />
                            <svn url="/hgrepo2" dest="folder1" />
                          </materials>
                        """;
        String message = "Destination directory is required when a pipeline has multiple SCM materials.";
        MagicalGoConfigXmlLoaderFixture.assertNotValid(message, materials);
    }

    @Test
    void shouldThrowErrorIfOneOfMultipleMaterialsIsNested() {
        String materials =
                """
                          <materials>
                            <svn url="/hgrepo1" dest="folder1"/>
                            <svn url="/hgrepo2" dest="folder1/folder2" />
                          </materials>
                        """;
        MagicalGoConfigXmlLoaderFixture.assertNotValid(INVALID_DESTINATION_DIRECTORY_MESSAGE, materials);
    }

    //This is bug #2337
    @Test
    void shouldNotThrowErrorIfMultipleMaterialsHaveSimilarNamesBug2337() {
        String materials =
                """
                          <materials>
                            <svn url="/hgrepo1" dest="folder1/folder2"/>
                            <svn url="/hgrepo2" dest="folder1/folder2different" />
                          </materials>
                        """;
        assertValidMaterials(materials);
    }

    //This is bug #2337
    @Test
    void shouldNotThrowErrorIfMultipleMaterialsHaveSimilarNamesInDifferentOrder() {
        String materials =
                """
                          <materials>
                            <svn url="/hgrepo2" dest="folder1/folder2different" />
                            <svn url="/hgrepo1" dest="folder1/folder2"/>
                          </materials>
                        """;
        assertValidMaterials(materials);
    }

    @Test
    void shouldNotAllowfoldersOutsideWorkingDirectory() throws Exception {
        String materials =
                """
                          <materials>
                            <svn url="/hgrepo2" dest="folder1/folder2/../folder3" />
                          </materials>
                        """;
        MagicalGoConfigXmlLoaderFixture.assertValid(materials);
        String materials2 =
                """
                          <materials>
                            <svn url="/hgrepo2" dest="../../.." />
                          </materials>
                        """;
        MagicalGoConfigXmlLoaderFixture.assertNotValid(
                "File path is invalid. \"../../..\" should conform to the pattern - (([.]\\/)?[.][^. ]+)|([^. ].+[^. ])|([^. ][^. ])|([^. ])", materials2);
    }

    @Test
    void shouldAllowPathStartWithDotSlash() throws Exception {
        String materials =
                """
                          <materials>
                            <svn url="/hgrepo2" dest="./folder3" />
                          </materials>
                        """;
        MagicalGoConfigXmlLoaderFixture.assertValid(materials);
    }

    @Test
    void shouldAllowHiddenFolders() throws Exception {
        String materials =
                """
                          <materials>
                            <svn url="/hgrepo2" dest=".folder3" />
                          </materials>
                        """;
        MagicalGoConfigXmlLoaderFixture.assertValid(materials);

        materials =
                """
                          <materials>
                            <svn url="/hgrepo2" dest="./.folder3" />
                          </materials>
                        """;
        MagicalGoConfigXmlLoaderFixture.assertValid(materials);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void shouldNotAllowAbsoluteDestFolderNamesOnLinux() {
        String materials1 =
                """
                          <materials>
                            <svn url="/hgrepo2" dest="/tmp/foo" />
                          </materials>
                        """;
        MagicalGoConfigXmlLoaderFixture.assertNotValid("Dest folder '/tmp/foo' is not valid. It must be a sub-directory of the working folder.",
                materials1);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void shouldNotAllowAbsoluteDestFolderNamesOnWindows() {
        String materials1 =
                """
                          <materials>
                            <svn url="/hgrepo2" dest="C:\\tmp\\foo" />
                          </materials>
                        """;
        MagicalGoConfigXmlLoaderFixture.assertNotValid("Dest folder 'C:\\tmp\\foo' is not valid. It must be a sub-directory of the working folder.",
                materials1);
    }

    @Test
    void shouldNotThrowErrorIfMultipleMaterialsHaveSameNames() {
        String materials =
                """
                          <materials>
                            <svn url="/hgrepo1" dest="folder1/folder2"/>
                            <svn url="/hgrepo2" dest="folder1/folder2" />
                          </materials>
                        """;
        MagicalGoConfigXmlLoaderFixture.assertNotValid(INVALID_DESTINATION_DIRECTORY_MESSAGE, materials);
    }

    @Test
    void shouldSupportHgGitSvnP4ForMultipleMaterials() throws Exception {
        String materials =
                """
                          <materials>
                            <svn url="/hgrepo1" dest="folder1"/>
                            <git url="/hgrepo2" dest="folder2"/>
                            <hg url="/hgrepo2" dest="folder3"/>
                            <p4 port="localhost:1666" dest="folder4">
                                  <view>asd</view>
                            </p4>
                          </materials>
                        """;
        MagicalGoConfigXmlLoaderFixture.assertValid(materials);
    }

    @Test
    void shouldLoadPipelinesWithGroupName() throws Exception {
        CruiseConfig config = xmlLoader.deserializeConfig(PIPELINE_GROUPS);
        assertThat(config.getGroups().first().getGroup()).isEqualTo("studios");
        assertThat(config.getGroups().get(1).getGroup()).isEqualTo("perfessionalservice");
    }

    @Test
    void shouldLoadTasksWithExecutionCondition() throws Exception {
        CruiseConfig config = xmlLoader.deserializeConfig(TASKS_WITH_CONDITION);
        JobConfig job = config.jobConfigByName("pipeline1", "mingle", "cardlist", true);

        assertThat(job.tasks().size()).isEqualTo(2);
        assertThat(job.tasks().findFirstByType(AntTask.class).getConditions().get(0)).isEqualTo(new RunIfConfig("failed"));

        RunIfConfigs conditions = job.tasks().findFirstByType(NantTask.class).getConditions();
        assertThat(conditions.get(0)).isEqualTo(new RunIfConfig("failed"));
        assertThat(conditions.get(1)).isEqualTo(new RunIfConfig("any"));
        assertThat(conditions.get(2)).isEqualTo(new RunIfConfig("passed"));
    }

    @Test
    void shouldLoadTasksWithOnCancel() throws Exception {
        CruiseConfig config = xmlLoader.deserializeConfig(TASKS_WITH_ON_CANCEL);
        JobConfig job = config.jobConfigByName("pipeline1", "mingle", "cardlist", true);

        Task task = job.tasks().findFirstByType(AntTask.class);
        assertThat(task.hasCancelTask()).isTrue();
        assertThat(task.cancelTask()).isEqualTo(new ExecTask("kill.rb", "", "utils"));

        Task task2 = job.tasks().findFirstByType(ExecTask.class);
        assertThat(task2.hasCancelTask()).isFalse();
    }

    @Test
    void shouldNotLoadTasksWithOnCancelTaskNested() {

        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(TASKS_WITH_ON_CANCEL_NESTED))
            .as("Should not allow nesting of 'oncancel' within task inside oncancel")
            .isInstanceOf(GoConfigInvalidException.class)
            .hasMessage("Cannot nest 'oncancel' within a cancel task");
    }

    @Test
    void shouldAllowBothCounterAndMaterialNameInLabelTemplate() throws Exception {
        CruiseConfig cruiseConfig = xmlLoader.deserializeConfig(LABEL_TEMPLATE_WITH_LABEL_TEMPLATE("1.3.0-${COUNT}-${git}"));
        assertThat(cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("cruise")).getLabelTemplate()).isEqualTo("1.3.0-${COUNT}-${git}");
    }

    @Test
    void shouldAllowBothCounterAndTruncatedGitMaterialInLabelTemplate() throws Exception {
        CruiseConfig cruiseConfig = xmlLoader.deserializeConfig(LABEL_TEMPLATE_WITH_LABEL_TEMPLATE("1.3.0-${COUNT}-${git[:7]}"));
        assertThat(cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("cruise")).getLabelTemplate()).isEqualTo("1.3.0-${COUNT}-${git[:7]}");
    }

    @Test
    void shouldAllowHashCharacterInLabelTemplate() throws Exception {
        GoConfigHolder goConfigHolder = xmlLoader.loadConfigHolder(LABEL_TEMPLATE_WITH_LABEL_TEMPLATE("1.3.0-${COUNT}-${git}##"));
        assertThat(goConfigHolder.config.pipelineConfigByName(new CaseInsensitiveString("cruise")).getLabelTemplate()).isEqualTo("1.3.0-${COUNT}-${git}#");
        assertThat(goConfigHolder.configForEdit.pipelineConfigByName(new CaseInsensitiveString("cruise")).getLabelTemplate()).isEqualTo("1.3.0-${COUNT}-${git}##");
    }

    @Test
    void shouldLoadMaterialNameIfPresent() throws Exception {
        CruiseConfig config = xmlLoader.deserializeConfig(MATERIAL_WITH_NAME);
        MaterialConfigs materialConfigs = config.pipelineConfigByName(new CaseInsensitiveString("pipeline")).materialConfigs();
        assertThat(materialConfigs.get(0).getName()).isEqualTo(new CaseInsensitiveString("svn"));
        assertThat(materialConfigs.get(1).getName()).isEqualTo(new CaseInsensitiveString("hg"));
    }

    @Test
    void shouldLoadPipelineWithTimer() throws Exception {
        CruiseConfig config = xmlLoader.deserializeConfig(PIPELINE_WITH_TIMER);
        PipelineConfig pipelineConfig = config.pipelineConfigByName(new CaseInsensitiveString("pipeline"));
        assertThat(pipelineConfig.getTimer()).isEqualTo(new TimerConfig("0 15 10 ? * MON-FRI", false));
    }

    @Test
    void shouldLoadConfigWithEnvironment() throws Exception {
        String content = configWithEnvironments(
                """
                        <environments>
                          <environment name='uat' />
                          <environment name='prod' />
                        </environments>""", CONFIG_SCHEMA_VERSION);
        EnvironmentsConfig environmentsConfig = xmlLoader.loadConfigHolder(content).config.getEnvironments();
        EnvironmentPipelineMatchers matchers = environmentsConfig.matchers();
        assertThat(matchers.size()).isEqualTo(2);
    }

    @Test
    void shouldLoadConfigWithNoEnvironment() throws Exception {
        String content = configWithEnvironments("", CONFIG_SCHEMA_VERSION);
        EnvironmentsConfig environmentsConfig = xmlLoader.loadConfigHolder(content).config.getEnvironments();
        EnvironmentPipelineMatchers matchers = environmentsConfig.matchers();
        assertThat(matchers).isNotNull();
        assertThat(matchers.size()).isEqualTo(0);
    }

    @Test
    void shouldNotLoadConfigWithEmptyTemplates() {
        String content = configWithTemplates(
                "<templates>\n"
                        + "</templates>");

        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(content))
            .as("Should not allow empty templates block")
            .hasMessageContaining("The content of element 'templates' is not complete. One of '{pipeline}' is expected.");
    }

    @Test
    void shouldNotLoadConfigWhenPipelineHasNoStages() {
        String content = ("""
                <cruise schemaVersion='%d'>
                <server />
                <pipelines>
                <pipeline name='pipeline1'>
                    <materials>
                      <svn url ="svnurl"/>
                    </materials>
                </pipeline>
                </pipelines>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> ConfigMigrator.loadWithMigration(content))
            .as("Should not allow Pipeline with No Stages")
            .hasMessageContaining("Pipeline 'pipeline1' does not have any stages configured. A pipeline must have at least one stage.");
    }

    @Test
    void shouldNotAllowReferencingTemplateThatDoesNotExist() {
        String content = ("""
                <cruise schemaVersion='%d'>
                <server />
                <pipelines>
                <pipeline name='pipeline1' template='abc'>
                    <materials>
                      <svn url ="svnurl"/>
                    </materials>
                </pipeline>
                </pipelines>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> ConfigMigrator.loadWithMigration(content))
            .as("should not allow referencing template that does not exist")
            .hasMessageContaining("Pipeline 'pipeline1' refers to non-existent template 'abc'.");
    }

    @Test
    void shouldAllowPipelineToReferenceTemplate() {
        String content = ("""
                <cruise schemaVersion='%d'>
                <server>
                </server>
                <pipelines>
                <pipeline name='pipeline1' template='abc'>
                    <materials>
                      <svn url ="svnurl"/>
                    </materials>
                </pipeline>
                </pipelines>
                <templates>
                  <pipeline name='abc'>
                    <stage name='stage1'>
                      <jobs>
                        <job name='job1'><tasks><exec command='echo'><runif status='passed' /></exec></tasks></job>
                      </jobs>
                    </stage>
                  </pipeline>
                </templates>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);
        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(content).config;
        PipelineConfig pipelineConfig = cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("pipeline1"));
        assertThat(pipelineConfig.size()).isEqualTo(1);
    }

    @Test
    void shouldAllowAdminInPipelineGroups() {
        String content = ("""
                <cruise schemaVersion='%d'>
                <server >
                </server>
                <pipelines group="first">
                <authorization>
                     <admins>
                         <user>foo</user>
                      </admins>
                </authorization>
                <pipeline name='pipeline1' template='abc'>
                    <materials>
                      <svn url ="svnurl"/>
                    </materials>
                </pipeline>
                </pipelines>
                <templates>
                  <pipeline name='abc'>
                    <stage name='stage1'>
                      <jobs>
                        <job name='job1'><tasks><exec command='echo'><runif status='passed' /></exec></tasks></job>
                      </jobs>
                    </stage>
                  </pipeline>
                </templates>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);
        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(content).config;
        assertThat(cruiseConfig.schemaVersion()).isEqualTo(CONFIG_SCHEMA_VERSION);
        assertThat(cruiseConfig.findGroup("first").isUserAnAdmin(new CaseInsensitiveString("foo"), new ArrayList<>())).isTrue();
    }

    @Test
    void shouldAllowAdminWithRoleInPipelineGroups() {
        String content = ("""
                <cruise schemaVersion='%d'>
                <server >
                <security>
                      <roles>
                        <role name="bar">
                          <users>
                             <user>foo</user>
                          </users>
                        </role>
                      </roles>
                </security>
                </server>
                <pipelines group="first">
                <authorization>
                     <admins>
                         <role>bar</role>
                      </admins>
                </authorization>
                <pipeline name='pipeline1' template='abc'>
                    <materials>
                      <svn url ="svnurl"/>
                    </materials>
                </pipeline>
                </pipelines>
                <templates>
                  <pipeline name='abc'>
                    <stage name='stage1'>
                      <jobs>
                        <job name='job1'><tasks><exec command='echo'><runif status='passed' /></exec></tasks></job>
                      </jobs>
                    </stage>
                  </pipeline>
                </templates>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);
        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(content).config;
        assertThat(cruiseConfig.schemaVersion()).isEqualTo(CONFIG_SCHEMA_VERSION);
        assertThat(cruiseConfig.findGroup("first").isUserAnAdmin(new CaseInsensitiveString("foo"), List.of(new RoleConfig(new CaseInsensitiveString("bar"))))).isTrue();
    }

    @Test
    void shouldAddJobTimeoutAttributeToServerTagAndDefaultItTo60_37xsl() {
        String content = ("""
                <cruise schemaVersion='%d'>
                <server>
                <siteUrls>
                <siteUrl>https://www.someurl.com/go</siteUrl>
                <secureSiteUrl>https://www.someotherurl.com/go</secureSiteUrl> </siteUrls>
                </server></cruise>""").formatted(CONFIG_SCHEMA_VERSION);
        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(content).config;
        assertThat(cruiseConfig.server().getJobTimeout()).isEqualTo("0");
    }

    @Test
    void shouldGetTheJobTimeoutFromServerTag_37xsl() {
        String content = ("""
                <cruise schemaVersion='%d'>
                <server jobTimeout='30'>
                <siteUrls>
                <siteUrl>https://www.someurl.com/go</siteUrl>
                <secureSiteUrl>https://www.someotherurl.com/go</secureSiteUrl>
                </siteUrls>
                </server></cruise>""").formatted(CONFIG_SCHEMA_VERSION);
        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(content).config;
        assertThat(cruiseConfig.server().getJobTimeout()).isEqualTo("30");
    }

    @Test
    void shouldHaveJobTimeoutAttributeOnJob_37xsl() {
        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(CONFIG_WITH_ANT_BUILDER).config;
        JobConfig jobConfig = cruiseConfig.findJob("pipeline1", "mingle", "cardlist");
        assertThat(jobConfig.getTimeout()).isEqualTo("5");
    }

    @Test
    void shouldAllowSiteUrlAndSecureSiteUrlAttributes() {
        String content = ("""
                <cruise schemaVersion='%d'>
                <server>
                <siteUrls>
                <siteUrl>https://www.someurl.com/go</siteUrl>
                <secureSiteUrl>https://www.someotherurl.com/go</secureSiteUrl>
                </siteUrls>
                </server></cruise>""").formatted(CONFIG_SCHEMA_VERSION);
        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(content).config;
        assertThat(cruiseConfig.server().getSiteUrl()).isEqualTo(new SiteUrl("https://www.someurl.com/go"));
        assertThat(cruiseConfig.server().getSecureSiteUrl()).isEqualTo(new SecureSiteUrl("https://www.someotherurl.com/go"));
    }

    @Test
    void shouldAllowPurgeStartAndPurgeUptoAttributes() {
        String content = ("""
                <cruise schemaVersion='%d'>
                <server>
                <artifacts>
                 <purgeSettings>
                   <purgeStartDiskSpace>1</purgeStartDiskSpace>
                   <purgeUptoDiskSpace>3</purgeUptoDiskSpace>
                 </purgeSettings>
                </artifacts>
                </server>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);
        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(content).config;
        assertThat(cruiseConfig.server().getPurgeStart()).isEqualTo(1.0);
        assertThat(cruiseConfig.server().getPurgeUpto()).isEqualTo(3.0);
    }

    @Test
    void shouldAllowDoublePurgeStartAndPurgeUptoAttributes() {
        String content = ("""
                <cruise schemaVersion='%d'>
                <server>
                <artifacts>
                 <purgeSettings>
                   <purgeStartDiskSpace>1.2</purgeStartDiskSpace>
                   <purgeUptoDiskSpace>3.4</purgeUptoDiskSpace>
                 </purgeSettings>
                </artifacts>
                </server></cruise>""").formatted(CONFIG_SCHEMA_VERSION);
        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(content).config;
        assertThat(cruiseConfig.server().getPurgeStart()).isEqualTo(1.2);
        assertThat(cruiseConfig.server().getPurgeUpto()).isEqualTo(3.4);
    }

    @Test
    void shouldAllowNullPurgeStartAndEnd() {
        String content = ("""
                <cruise schemaVersion='%d'>
                <server>
                </server></cruise>""").formatted(CONFIG_SCHEMA_VERSION);
        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(content).config;
        assertThat(cruiseConfig.server().getPurgeStart()).isNull();
        assertThat(cruiseConfig.server().getPurgeUpto()).isNull();
    }


    @Test
    void shouldNotAllowAPipelineThatReferencesATemplateToHaveStages() {
        String content = ("""
                <cruise schemaVersion='%d'>
                <server />
                <pipelines>
                <pipeline name='pipeline1' template='abc'>
                    <materials>
                      <svn url ="svnurl"/>
                    </materials>
                    <stage name='badstage'>
                      <jobs>
                        <job name='job1'><tasks><exec command='echo'><runif status='passed' /></exec></tasks></job>
                      </jobs>
                    </stage>
                </pipeline>
                </pipelines>
                <templates>
                  <pipeline name='abc'>
                    <stage name='stage1'>
                      <jobs>
                        <job name='job1'><tasks><exec command='echo'><runif status='passed' /></exec></tasks></job>
                      </jobs>
                    </stage>
                  </pipeline>
                </templates>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> ConfigMigrator.loadWithMigration(content))
            .as("shouldn't have stages and template")
            .hasMessageContaining("Cannot add stage 'badstage' to pipeline 'pipeline1', which already references template 'abc'.");
    }

    @Test
    void shouldLoadConfigWithPipelineTemplate() {
        String content = configWithTemplates(
                """
                        <templates>
                          <pipeline name='erbshe'>
                            <stage name='stage1'>
                              <jobs>
                                <job name='job1'><tasks><ant /></tasks></job>
                              </jobs>
                            </stage>
                          </pipeline>
                        </templates>""");
        TemplatesConfig templates = ConfigMigrator.loadWithMigration(content).config.getTemplates();
        assertThat(templates.size()).isEqualTo(1);
        assertThat(templates.get(0).size()).isEqualTo(1);
        assertThat(templates.get(0).get(0)).isEqualTo(StageConfigMother.custom("stage1", "job1"));
    }

    @Test
    void shouldLoadConfigWith2PipelineTemplates() {
        String content = configWithTemplates(
                """
                        <templates>
                          <pipeline name='erbshe'>
                            <stage name='stage1'>
                              <jobs>
                                <job name='job1'><tasks><exec command='echo'><runif status='passed' /></exec></tasks></job>
                              </jobs>
                            </stage>
                          </pipeline>
                          <pipeline name='erbshe2'>
                            <stage name='stage1'>
                              <jobs>
                                <job name='job1'><tasks><exec command='echo'><runif status='passed' /></exec></tasks></job>
                              </jobs>
                            </stage>
                          </pipeline>
                        </templates>""");
        TemplatesConfig templates = ConfigMigrator.loadWithMigration(content).config.getTemplates();
        assertThat(templates.size()).isEqualTo(2);
        assertThat(templates.get(0).name()).isEqualTo(new CaseInsensitiveString("erbshe"));
        assertThat(templates.get(1).name()).isEqualTo(new CaseInsensitiveString("erbshe2"));
    }


    @Test
    void shouldOnlySupportUniquePipelineTemplates() {
        String content = configWithTemplates(
                """
                        <templates>
                          <pipeline name='erbshe'>
                            <stage name='stage1'>
                              <jobs>
                                <job name='job1'><tasks><exec command='echo'><runif status='passed' /></exec></tasks></job>
                              </jobs>
                            </stage>
                          </pipeline>
                          <pipeline name='erbshe'>
                            <stage name='stage1'>
                              <jobs>
                                <job name='job1'><tasks><exec command='echo'><runif status='passed' /></exec></tasks></job>
                              </jobs>
                            </stage>
                          </pipeline>
                        </templates>""");

        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(content))
            .as("should not allow same template names")
            .hasMessageContaining("Duplicate unique value [erbshe] declared for identity constraint");
    }

    @Test
    void shouldNotAllowEmptyPipelineTemplates() {
        String content = configWithTemplates(
                """
                        <templates>
                          <pipeline name='erbshe'>
                          </pipeline>
                        </templates>""");

        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(content))
            .as("should NotAllowEmptyPipelineTemplates")
            .hasMessageContaining("The content of element 'pipeline' is not complete. One of '{authorization, stage}' is expected");
    }

    @Test
    void shouldNotAllowJobToHaveTheRunOnAllAgentsMarkerInItsName() {
        String invalidJobName = format("%s-%s-%s", "invalid-name", RunOnAllAgentsJobTypeConfig.MARKER, 1);
        testForInvalidJobName(invalidJobName, RunOnAllAgentsJobTypeConfig.MARKER);
    }

    @Test
    void shouldNotAllowJobToHaveTheRunInstanceMarkerInItsName() {
        String invalidJobName = format("%s-%s-%s", "invalid-name", RunMultipleInstanceJobTypeConfig.MARKER, 1);
        testForInvalidJobName(invalidJobName, RunMultipleInstanceJobTypeConfig.MARKER);
    }

    private void testForInvalidJobName(String invalidJobName, String marker) {
        String content = configWithPipeline(
                ("""
                            <pipeline name="dev">
                              <materials>
                                <svn url="file:///tmp/svn/repos/fifth" />
                              </materials>
                              <stage name="AutoStage">
                                <jobs>
                                  <job name="%s">
                                    <tasks>
                                      <exec command="ls" args="-lah" />
                                    </tasks>
                                  </job>
                                </jobs>
                              </stage>
                            </pipeline>
                        """).formatted(invalidJobName)
        );

        assertThatThrownBy(() -> ConfigMigrator.loadWithMigration(content))
            .as("should not allow jobs with with name '" + marker + "'")
            .hasMessageContaining(String.format("A job cannot have '%s' in it's name: %s because it is a reserved keyword", marker, invalidJobName));
    }

    @Test
    void shouldAllow_NonRunOnAllAgentJobToHavePartsOfTheRunOnAll_and_NonRunMultipleInstanceJobToHavePartsOfTheRunInstance_AgentsMarkerInItsName() {
        String content = configWithPipeline(
                """
                            <pipeline name="dev">
                              <materials>
                                <svn url="file:///tmp/svn/repos/fifth" />
                              </materials>
                              <stage name="AutoStage">
                                <jobs>
                                  <job name="valid-name-runOnAll" >
                                    <tasks>
                                      <exec command="ls" args="-lah" />
                                    </tasks>
                                  </job>
                                  <job name="valid-name-runInstance" >
                                    <tasks>
                                      <exec command="ls" args="-lah" />
                                    </tasks>
                                  </job>
                                </jobs>
                              </stage>
                            </pipeline>\
                        """);
        ConfigMigrator.loadWithMigration(content); // should not fail with a validation exception
    }

    @Test
    void shouldLoadConfigWithPipelinesMatchingUpWithPipelineDefinitionCaseInsensitively() {
        String content = configWithEnvironments(
                """
                        <environments>
                          <environment name='uat'>
                            <pipelines>
                              <pipeline name='pipeline1'/>
                            </pipelines>
                          </environment>
                        </environments>""", CONFIG_SCHEMA_VERSION);
        EnvironmentsConfig environmentsConfig = ConfigMigrator.loadWithMigration(content).config.getEnvironments();
        EnvironmentPipelineMatcher matcher = environmentsConfig.matchersForPipeline("pipeline1");
        assertThat(matcher).isEqualTo(new EnvironmentPipelineMatcher(new CaseInsensitiveString("uat"), new ArrayList<>(),
                new EnvironmentPipelinesConfig(new CaseInsensitiveString("pipeline1"))));
    }

    @Test
    void shouldLoadConfigWithPipelinesNotMatchingUpWithPipelineDefinitionCaseInsensitively() {
        String content = configWithEnvironments(
                """
                        <environments>
                          <environment name='uat'>
                            <pipelines>
                              <pipeline name='pipeline1'/>
                            </pipelines>
                          </environment>
                        </environments>""", CONFIG_SCHEMA_VERSION);
        EnvironmentsConfig environmentsConfig = ConfigMigrator.loadWithMigration(content).config.getEnvironments();
        EnvironmentPipelineMatcher matcher = environmentsConfig.matchersForPipeline("non-existing-pipeline");
        assertThat(matcher).isNull();
    }

    @Test
    void shouldLoadConfigWithPipelinesMatchingUpWithFirstPipelineDefinitionCaseInsensitively() {
        String content = configWithEnvironments(
                """
                        <environments>
                          <environment name='uat'>
                            <pipelines>
                              <pipeline name='pipeline1'/>
                            </pipelines>
                          </environment>
                        </environments>""", CONFIG_SCHEMA_VERSION);

        EnvironmentsConfig environmentsConfig = ConfigMigrator.loadWithMigration(content).config.getEnvironments();
        EnvironmentPipelineMatcher matcher = environmentsConfig.matchersForPipeline("pipeline1");
        assertThat(matcher).isEqualTo(new EnvironmentPipelineMatcher(new CaseInsensitiveString("uat"), new ArrayList<>(),
                new EnvironmentPipelinesConfig(new CaseInsensitiveString("pipeline1"))));
    }

    @Test
    void shouldNotAllowConfigWithUnknownPipeline() {
        String content = configWithEnvironments(
                """
                        <environments>
                          <environment name='uat'>
                            <pipelines>
                              <pipeline name='notpresent'/>
                            </pipelines>
                          </environment>
                        </environments>""", CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> ConfigMigrator.loadWithMigration(content))
            .as("Should not have allowed referencing of an unknown pipeline under an environment.")
            .hasMessageContaining("Environment 'uat' refers to an unknown pipeline 'notpresent'.");
    }

    @Test
    void shouldNotAllowDuplicatePipelineAcrossEnvironments() {
        String content = configWithEnvironments(
                """
                        <environments>
                          <environment name='uat'>
                            <pipelines>
                              <pipeline name='pipeline1'/>
                            </pipelines>
                          </environment>
                          <environment name='prod'>
                            <pipelines>
                              <pipeline name='Pipeline1'/>
                            </pipelines>
                          </environment>
                        </environments>""", CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> ConfigMigrator.loadWithMigration(content))
            .as("Should not have allowed duplicate pipeline reference across environments")
            .hasMessageContaining("Associating pipeline(s) which is already part of uat environment");
    }

    @Test
    void shouldNotAllowDuplicatePipelinesInASingleEnvironment() {
        String content = configWithEnvironments(
                """
                        <environments>
                          <environment name='uat'>
                            <pipelines>
                              <pipeline name='pipeline1'/>
                              <pipeline name='Pipeline1'/>
                            </pipelines>
                          </environment>
                        </environments>""", CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> ConfigMigrator.loadWithMigration(content))
            .as("Should not have allowed duplicate pipeline reference under an environment")
            .hasMessageContaining("Cannot add pipeline 'Pipeline1' to the environment");
    }

    @Test
    void shouldNotAllowConfigWithEnvironmentsWithSameNames() {
        String content = configWithEnvironments(
                """
                        <environments>
                          <environment name='uat' />
                          <environment name='uat' />
                        </environments>""", CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(content))
            .as("Should not support 2 environments with the same same")
            .satisfiesAnyOf(
                t -> assertThat(t.getMessage()).contains("Duplicate unique value [uat] declared for identity constraint of element \"environments\"."),
                t -> assertThat(t.getMessage()).contains("Duplicate unique value [uat] declared for identity constraint \"uniqueEnvironmentName\" of element \"environments\".")
            );
    }

    @Test
    void shouldNotAllowConfigWithInvalidName() {
        String content = configWithEnvironments(
                """
                        <environments>
                          <environment name='exclamation is invalid !' />
                        </environments>""", CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(content))
            .as("XSD should not allow invalid characters")
            .hasMessageContaining("\"exclamation is invalid !\" should conform to the pattern - [a-zA-Z0-9_\\-]{1}[a-zA-Z0-9_\\-.]*");
    }

    @Test
    void shouldAllowConfigWithEmptyPipeline() {
        String content = configWithEnvironments(
                """
                        <environments>
                          <environment name='uat'>
                            <pipelines/>
                          </environment>
                        </environments>""", CONFIG_SCHEMA_VERSION);

        assertThatCode(() -> ConfigMigrator.loadWithMigration(content))
            .as("should not allow empty pipelines block under an environment")
            .doesNotThrowAnyException();
    }

    @Test
    void shouldNotAllowConfigWithDuplicateAgentUuidInEnvironment() {
        String content = configWithEnvironments("""
                <environments>
                  <environment name='uat'>
                    <agents>
                      <physical uuid='1' />
                      <physical uuid='1' />
                    </agents>
                  </environment>
                </environments>""", 110);

        assertThatThrownBy(() ->  ConfigMigrator.migrate(content, 110, CONFIG_SCHEMA_VERSION))
            .as("XSD should not allow duplicate agent uuid in environment")
            .hasCauseExactlyInstanceOf(XsdValidationException.class)
            .satisfiesAnyOf(
                t -> assertThat(t.getCause().getMessage()).contains("Duplicate unique value [1] declared for identity constraint of element \"agents\"."),
                t -> assertThat(t.getCause().getMessage()).contains("Duplicate unique value [1] declared for identity constraint \"uniqueEnvironmentAgentsUuid\" of element \"agents\".")
            );
    }

    @Test
    void shouldNotAllowConfigWithEmptyEnvironmentsBlock() {
        String content = configWithEnvironments(
                "<environments>\n"
                        + "</environments>", CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(content))
            .as("XSD should not allow empty environments block")
            .hasMessageContaining("The content of element 'environments' is not complete. One of '{environment}' is expected.");
    }

    @Test
    void shouldAllowConfigWithNoAgentsAndNoPipelinesInEnvironment() {
        String content = configWithEnvironments(
                """
                        <environments>
                          <environment name='uat' />
                        </environments>""", CONFIG_SCHEMA_VERSION);
        CruiseConfig config = ConfigMigrator.loadWithMigration(content).config;
        assertThat(config.getEnvironments().size()).isEqualTo(1);
    }

    @Test
    void shouldSupportEnvironmentVariablesInEnvironment() {
        String content = configWithEnvironments("""
                <environments>
                  <environment name='uat'>
                     <environmentvariables>
                         <variable name='VAR_NAME_1'><value>variable_name_value_1</value></variable>
                         <variable name='CRUISE_ENVIRONEMNT_NAME'><value>variable_name_value_2</value></variable>
                     </environmentvariables>
                   </environment>
                </environments>""", CONFIG_SCHEMA_VERSION);
        CruiseConfig config = ConfigMigrator.loadWithMigration(content).config;
        EnvironmentConfig element = new BasicEnvironmentConfig(new CaseInsensitiveString("uat"));
        element.addEnvironmentVariable("VAR_NAME_1", "variable_name_value_1");
        element.addEnvironmentVariable("CRUISE_ENVIRONEMNT_NAME", "variable_name_value_2");
        assertThat(config.getEnvironments()).contains(element);
    }

    @Test
    void shouldAllowCDATAInEnvironmentVariableValues() {
        //TODO : This should be fixed as part of #4865
        //String multiLinedata = "\nsome data\nfoo bar";
        String multiLinedata = "some data\nfoo bar";
        String content = configWithEnvironments(("""
                <environments>
                  <environment name='uat'>
                     <environmentvariables>
                       <variable name='cdata'><value><![CDATA[%s]]></value></variable>
                     </environmentvariables>
                  </environment>
                </environments>""").formatted(multiLinedata), CONFIG_SCHEMA_VERSION);
        CruiseConfig config = ConfigMigrator.loadWithMigration(content).config;
        EnvironmentConfig element = new BasicEnvironmentConfig(new CaseInsensitiveString("uat"));
        element.addEnvironmentVariable("cdata", multiLinedata);
        assertThat(config.getEnvironments().get(0)).isEqualTo(element);
    }

    @Test
    void shouldAllowOnlyOneTimerOnAPipeline() {
        String content = configWithPipeline(
                """
                        <pipeline name='pipeline1'>
                            <timer>1 1 1 * * ? *</timer>
                            <timer>2 2 2 * * ? *</timer>
                            <materials>
                              <svn url ='svnurl'/>
                            </materials>
                          <stage name='mingle'>
                            <jobs>
                              <job name='cardlist'><tasks><exec command='echo'><runif status='passed' /></exec></tasks></job>
                            </jobs>
                          </stage>
                        </pipeline>""", CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(content))
            .as("XSD should not allow duplicate timer in pipeline")
            .hasMessageContaining("Invalid content was found starting with element 'timer'.");
    }

    @Test
    void shouldValidateTimerSpec() {
        String content = configWithPipeline(
                """
                        <pipeline name='pipeline1'>
                            <timer>BAD BAD TIMER!!!!!</timer>
                            <materials>
                              <svn url ='svnurl'/>
                            </materials>
                          <stage name='mingle'>
                            <jobs>
                              <job name='cardlist'><tasks><exec command='echo'><runif status='passed' /></exec></tasks></job>
                            </jobs>
                          </stage>
                        </pipeline>""", CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> ConfigMigrator.loadWithMigration(content))
            .as("XSD should validate timer spec")
            .hasMessageContaining("Invalid cron syntax");
    }

    @Test
    void shouldNotAllowIllegalValueForRunOnAllAgents() {

        assertThatThrownBy(() -> loadJobWithRunOnAllAgents("bad_value"))
            .as("should have failed as runOnAllAgents' value is not valid(boolean)")
            .hasMessageContaining("'bad_value' is not a valid value for 'boolean'");
    }

    @Test
    void shouldNotAllowIllegalValueForRunMultipleInstanceJob() {

        assertThatThrownBy(() -> loadJobWithRunMultipleInstance("-1"))
            .as("should have failed as runOnAllAgents' value is not valid(boolean)")
            .hasMessageContaining("'-1' is not facet-valid with respect to minInclusive '1' for type 'positiveInteger'");


        assertThatThrownBy(() -> loadJobWithRunMultipleInstance("abcd"))
            .as("should have failed as runOnAllAgents' value is not valid(boolean)")
            .hasMessageContaining("'abcd' is not a valid value for 'integer'");
    }

    @Test
    void shouldSupportEnvironmentVariablesInAJob() {
        String content = configWithPipeline(
                """
                        <pipeline name='pipeline1'>
                            <materials>
                              <svn url ='svnurl'/>
                            </materials>
                          <stage name='mingle'>
                            <jobs>
                              <job name='do-something'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              <environmentvariables>
                                 <variable name='JOB_VARIABLE'><value>job variable</value></variable>
                              </environmentvariables>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", CONFIG_SCHEMA_VERSION);
        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(content).config;

        JobConfig jobConfig = new JobConfig("do-something");
        jobConfig.addVariable("JOB_VARIABLE", "job variable");
        ExecTask echo = new ExecTask("echo", "", new Arguments());
        echo.runIfConfigs.add(RunIfConfig.PASSED);
        jobConfig.addTask(echo);
        assertThat(cruiseConfig.findJob("pipeline1", "mingle", "do-something")).isEqualTo(jobConfig);
    }

    @Test
    void shouldSupportEnvironmentVariablesInAPipeline() {
        String content = configWithPipeline(
                """
                        <pipeline name='pipeline1'>
                          <environmentvariables>
                            <variable name='PIPELINE_VARIABLE'><value>pipeline variable</value></variable>
                          </environmentvariables>
                          <materials>
                            <svn url ='svnurl'/>
                          </materials>
                          <stage name='mingle'>
                            <jobs>
                              <job name='do-something'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", CONFIG_SCHEMA_VERSION);
        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(content).config;

        assertThat(cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("pipeline1")).getVariables()).contains(new EnvironmentVariableConfig("PIPELINE_VARIABLE", "pipeline variable"));
    }

    @Test
    void shouldSupportEnvironmentVariablesInAStage() {
        String content = configWithPipeline(
                """
                        <pipeline name='pipeline1'>
                          <materials>
                            <svn url ='svnurl'/>
                          </materials>
                          <stage name='mingle'>
                            <environmentvariables>
                              <variable name='STAGE_VARIABLE'><value>stage variable</value></variable>
                            </environmentvariables>
                            <jobs>
                              <job name='job1'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", CONFIG_SCHEMA_VERSION);
        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(content).config;

        assertThat(cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("pipeline1")).getFirstStageConfig().getVariables()).contains(new EnvironmentVariableConfig("STAGE_VARIABLE", "stage variable"));
    }

    @Test
    void shouldNotAllowDuplicateEnvironmentVariablesInAJob() {
        String content = configWithPipeline(
                """
                        <pipeline name='pipeline1'>
                            <materials>
                              <svn url ='svnurl'/>
                            </materials>
                          <stage name='mingle'>
                            <jobs>
                              <job name='do-something'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              <environmentvariables>
                                 <variable name='JOB_VARIABLE'><value>job variable</value></variable>
                                 <variable name='JOB_VARIABLE'><value>job variable</value></variable>
                              </environmentvariables>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> ConfigMigrator.loadWithMigration(content))
            .as("Should not allow duplicate variable names")
            .hasMessageContaining("Environment Variable name 'JOB_VARIABLE' is not unique for job 'do-something'.");
    }

    @Test
    void shouldNotAllowDuplicateParamsInAPipeline() {
        String content = ("""
                <cruise schemaVersion='%d'>
                <server />
                <pipelines>
                <pipeline name='dev'>
                    <params>
                        <param name='same-name'>ls</param>
                        <param name='same-name'>/tmp</param>
                    </params>
                    <materials>
                      <svn url ="svnurl"/>
                    </materials>
                    <stage name='mingle'>
                      <jobs>
                        <job name='do-something'><tasks><exec command='echo'><runif status='passed' /></exec></tasks></job>
                      </jobs>
                    </stage>
                </pipeline>
                </pipelines>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> ConfigMigrator.loadWithMigration(content))
            .as("Should not allow duplicate params")
            .hasMessageContaining("Param name 'same-name' is not unique for pipeline 'dev'.");
    }

    @Test
    void shouldNotAllowParamsToBeUsedInNames() {
        String content = ("""
                <cruise schemaVersion='%d'>
                <server />
                <pipelines>
                <pipeline name='dev'>
                    <params>
                        <param name='command'>ls</param>
                    </params>
                    <materials>
                      <svn url ="svnurl"/>
                    </materials>
                    <stage name='stage#{command}ab'>
                      <jobs>
                        <job name='job1'>
                            <tasks>
                                <exec command='/bin/#{command}##{b}' args='#{dir}'/>
                            </tasks>
                        </job>
                      </jobs>
                    </stage>
                </pipeline>
                </pipelines>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() ->  xmlLoader.loadConfigHolder(content))
            .as("Should not allow params in stage name")
            .hasMessageContaining("\"stage#{command}ab\" should conform to the pattern - [a-zA-Z0-9_\\-]{1}[a-zA-Z0-9_\\-.]*");
    }

    @Test
    void shouldNotAllowDuplicateEnvironmentVariablesInAPipeline() {
        String content = configWithPipeline(
                """
                        <pipeline name='pipeline1'>
                              <environmentvariables>
                                 <variable name='PIPELINE_VARIABLE'><value>pipeline variable</value></variable>
                                 <variable name='PIPELINE_VARIABLE'><value>pipeline variable</value></variable>
                              </environmentvariables>
                            <materials>
                              <svn url ='svnurl'/>
                            </materials>
                          <stage name='mingle'>
                            <jobs>
                              <job name='do-something'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> ConfigMigrator.loadWithMigration(content))
            .as("Should not allow duplicate variable names")
            .hasMessageContaining("Variable name 'PIPELINE_VARIABLE' is not unique for pipeline 'pipeline1'.");
    }

    @Test
    void shouldNotAllowDuplicateEnvironmentVariablesInAStage() {
        String content = configWithPipeline(
                """
                        <pipeline name='pipeline1'>
                            <materials>
                              <svn url ='svnurl'/>
                            </materials>
                          <stage name='mingle'>
                              <environmentvariables>
                                 <variable name='STAGE_VARIABLE'><value>stage variable</value></variable>
                                 <variable name='STAGE_VARIABLE'><value>stage variable</value></variable>
                              </environmentvariables>
                            <jobs>
                              <job name='do-something'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> ConfigMigrator.loadWithMigration(content))
            .as("Should not allow duplicate variable names")
            .hasMessageContaining("Variable name 'STAGE_VARIABLE' is not unique for stage 'mingle'.");
    }

    @Test
    void shouldNotAllowDuplicateEnvironmentVariablesInAnEnvironment() {
        String content = configWithEnvironments("""
                <environments>
                  <environment name='uat'>
                     <environmentvariables>
                         <variable name='FOO'><value>foo</value></variable>
                         <variable name='FOO'><value>foo</value></variable>
                     </environmentvariables>
                   </environment>
                </environments>""", CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> ConfigMigrator.loadWithMigration(content))
            .as("Should not allow duplicate variable names")
            .hasMessageContaining("Variable name 'FOO' is not unique for environment 'uat'.");
    }

    @Test
    void shouldAllowParamsInEnvironmentVariablesInAPipeline() {
        String content = configWithPipeline(
                """
                        <pipeline name='pipeline1'>
                            <params>
                                 <param name="some_param">param_name</param>
                            </params>
                              <environmentvariables>
                                 <variable name='#{some_param}'><value>stage variable</value></variable>
                              </environmentvariables>
                            <materials>
                              <svn url ='svnurl'/>
                            </materials>
                          <stage name='mingle'>
                            <jobs>
                              <job name='do-something'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", CONFIG_SCHEMA_VERSION);
        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(content).config;

        assertThat(cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("pipeline1")).getVariables()).contains(new EnvironmentVariableConfig("param_name", "stage variable"));
    }

    @Test
    void shouldSupportRunOnAllAgents() throws Exception {
        CruiseConfig cruiseConfig = loadJobWithRunOnAllAgents("true");
        JobConfig job = cruiseConfig.findJob("pipeline1", "mingle", "do-something");
        JobConfig jobConfig = new JobConfig("do-something");
        ExecTask echo = new ExecTask("echo", "", new Arguments());
        echo.runIfConfigs.add(RunIfConfig.PASSED);
        jobConfig.addTask(echo);
        jobConfig.setRunOnAllAgents(true);
        assertThat(job).isEqualTo(jobConfig);
    }

    @Test
    void shouldSupportRunMultipleInstance() throws Exception {
        CruiseConfig cruiseConfig = loadJobWithRunMultipleInstance("10");
        JobConfig job = cruiseConfig.findJob("pipeline1", "mingle", "do-something");
        JobConfig jobConfig = new JobConfig("do-something");
        ExecTask echo = new ExecTask("echo", "", new Arguments());
        echo.runIfConfigs.add(RunIfConfig.PASSED);
        jobConfig.addTask(echo);
        jobConfig.setRunInstanceCount(10);
        assertThat(job).isEqualTo(jobConfig);
    }

    @Test
    void shouldUnderstandEncryptedPasswordAttributeForSvnMaterial() throws Exception {
        String password = "abc";
        String encryptedPassword = new GoCipher().encrypt(password);
        String content = configWithPipeline(format(
                """
                        <pipeline name='pipeline1'>
                            <materials>
                              <svn url='svnurl' username='admin' encryptedPassword='%s'/>
                            </materials>
                          <stage name='mingle'>
                            <jobs>
                              <job name='do-something'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", encryptedPassword), CONFIG_SCHEMA_VERSION);
        GoConfigHolder configHolder = ConfigMigrator.loadWithMigration(content);
        CruiseConfig cruiseConfig = configHolder.config;
        SvnMaterialConfig svnMaterialConfig = (SvnMaterialConfig) cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("pipeline1")).materialConfigs().get(0);
        assertThat(svnMaterialConfig.getEncryptedPassword()).isEqualTo(encryptedPassword);
        assertThat(svnMaterialConfig.getPassword()).isEqualTo(password);

        CruiseConfig configForEdit = configHolder.configForEdit;
        svnMaterialConfig = (SvnMaterialConfig) configForEdit.pipelineConfigByName(new CaseInsensitiveString("pipeline1")).materialConfigs().get(0);
        assertThat(svnMaterialConfig.getEncryptedPassword()).isEqualTo(encryptedPassword);
        assertThat(svnMaterialConfig.getPassword()).isEqualTo("abc");
        assertThat(ReflectionUtil.<String>getField(svnMaterialConfig, "password")).isNull();
    }

    @Test
    void shouldSupportEmptyPipelineGroup() throws Exception {
        PipelineConfigs group = new BasicPipelineConfigs("defaultGroup", new Authorization());
        CruiseConfig config = new BasicCruiseConfig(group);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        new MagicalGoConfigXmlWriter(configCache, ConfigElementImplementationRegistryMother.withNoPlugins()).write(config, stream, true);
        GoConfigHolder configHolder = new MagicalGoConfigXmlLoader(new ConfigCache(), ConfigElementImplementationRegistryMother.withNoPlugins())
                .loadConfigHolder(stream.toString());
        assertThat(configHolder.config.findGroup("defaultGroup")).isEqualTo(group);
    }

    private CruiseConfig loadJobWithRunOnAllAgents(String value) throws Exception {
        String content = configWithPipeline(
                ("""
                        <pipeline name='pipeline1'>
                            <materials>
                              <svn url ='svnurl'/>
                            </materials>
                          <stage name='mingle'>
                            <jobs>
                              <job name='do-something' runOnAllAgents='%s'>
                                 <tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""").formatted(value), CONFIG_SCHEMA_VERSION);
        return xmlLoader.loadConfigHolder(content).config;
    }

    private CruiseConfig loadJobWithRunMultipleInstance(String value) throws Exception {
        String content = configWithPipeline(
                ("""
                        <pipeline name='pipeline1'>
                            <materials>
                              <svn url ='svnurl'/>
                            </materials>
                          <stage name='mingle'>
                            <jobs>
                              <job name='do-something' runInstanceCount='%s'>
                                 <tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""").formatted(value), CONFIG_SCHEMA_VERSION);
        return xmlLoader.loadConfigHolder(content).config;
    }

    private void assertValidMaterials(String materials) {
        String pipelineXmlPartial =
                ("""
                        <?xml version="1.0" encoding="utf-8"?>
                        <cruise         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"         xsi:noNamespaceSchemaLocation="cruise-config.xsd"         schemaVersion='%d'>
                          <server>
                            <artifacts>
                              <artifactsDir>logs</artifactsDir>
                            </artifacts>
                          </server>
                        <pipelines>
                          <pipeline name="pipeline-name">
                        %s    <stage name="mingle">
                              <jobs>
                                <job name="functional">
                                  <tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                                  <artifacts>
                                    <artifact type="build" src="artifact1.xml" dest="cruise-output" />
                                  </artifacts>
                                </job>
                              </jobs>
                            </stage>
                          </pipeline>
                        </pipelines>
                        </cruise>
                        """).formatted(CONFIG_SCHEMA_VERSION, materials);

        ConfigMigrator.loadWithMigration(pipelineXmlPartial);
    }

    @Test
    void shouldAllowResourcesWithParamsForJobs() {
        CruiseConfig cruiseConfig = new BasicCruiseConfig();
        cruiseConfig.initializeServer();
        cruiseConfig.addTemplate(new PipelineTemplateConfig(new CaseInsensitiveString("template"), stageWithJobResource("#{PLATFORM}")));

        PipelineConfig pipelineConfig = new PipelineConfig(new CaseInsensitiveString("pipeline"), new MaterialConfigs());
        pipelineConfig.setTemplateName(new CaseInsensitiveString("template"));
        pipelineConfig.addParam(new ParamConfig("PLATFORM", "windows"));
        cruiseConfig.addPipeline("group", pipelineConfig);

        List<ConfigErrors> errors = MagicalGoConfigXmlLoader.validate(cruiseConfig);
        assertThat(errors.isEmpty()).isTrue();
    }

    //BUG: #5209
    @Test
    void shouldAllowRoleWithParamsForStageInTemplate() {
        CruiseConfig cruiseConfig = new BasicCruiseConfig();
        cruiseConfig.initializeServer();
        cruiseConfig.server().security().addRole(new RoleConfig(new CaseInsensitiveString("role")));

        cruiseConfig.addTemplate(new PipelineTemplateConfig(new CaseInsensitiveString("template"), stageWithAuth("#{ROLE}")));

        PipelineConfig pipelineConfig = new PipelineConfig(new CaseInsensitiveString("pipeline"), new MaterialConfigs());
        pipelineConfig.setTemplateName(new CaseInsensitiveString("template"));
        pipelineConfig.addParam(new ParamConfig("ROLE", "role"));

        cruiseConfig.addPipeline("group", pipelineConfig);

        List<ConfigErrors> errors = MagicalGoConfigXmlLoader.validate(cruiseConfig);
        assertThat(errors.isEmpty()).isTrue();
    }

    @SuppressWarnings("SameParameterValue")
    private StageConfig stageWithAuth(String role) {
        StageConfig stage = stageWithJobResource("foo");
        stage.getApproval().getAuthConfig().add(new AdminRole(new CaseInsensitiveString(role)));
        return stage;
    }

    @Test
    void shouldAllowOnlyOneOfTrackingToolOrMingleConfigInSourceXml() {
        String content = configWithPipeline(
                """
                        <pipeline name='pipeline1'>
                        <trackingtool link="https://some-tracking-tool/projects/go/cards/${ID}" regex="##(\\d+)" />
                              <mingle baseUrl="https://some-tracking-tool/" projectIdentifier="go">
                                <mqlGroupingConditions>status &gt; 'In Dev'</mqlGroupingConditions>
                              </mingle>
                            <materials>
                              <svn url='svnurl'/>
                            </materials>
                          <stage name='mingle'>
                            <jobs>
                              <job name='(.*)'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(content))
            .as("Should not allow mingle config and tracking tool together")
            .hasMessageContaining("Invalid content was found starting with element 'mingle'.");
    }

    @Test
    void shouldAllowTFSMaterial() {
        String content = configWithPipeline(
                """
                        <pipeline name='some_pipeline'>
                            <materials>
                              <tfs url='tfsurl' username='foo' password='bar' projectPath='project-path' />
                            </materials>
                          <stage name='some_stage'>
                            <jobs>
                              <job name='some_job'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", CONFIG_SCHEMA_VERSION);
        GoConfigHolder goConfigHolder = ConfigMigrator.loadWithMigration(content);
        MaterialConfigs materialConfigs = goConfigHolder.config.pipelineConfigByName(new CaseInsensitiveString("some_pipeline")).materialConfigs();
        assertThat(materialConfigs.size()).isEqualTo(1);
        TfsMaterialConfig materialConfig = (TfsMaterialConfig) materialConfigs.get(0);
        assertThat(materialConfig).isEqualTo(tfs(new GoCipher(), UrlArgument.create("tfsurl"), "foo", "", "bar", "project-path"));
    }

    @Test
    void shouldAllowAnEnvironmentVariableToBeMarkedAsSecure_WithValueInItsOwnTag() throws Exception {
        String cipherText = new GoCipher().encrypt("plainText");
        String content = configWithPipeline(
                ("""
                        <pipeline name='some_pipeline'>
                        <environmentvariables>
                                <variable name="var_name" secure="true"><encryptedValue>%s</encryptedValue></variable>
                              </environmentvariables>
                            <materials>
                              <svn url='svnurl'/>
                            </materials>
                          <stage name='some_stage'>
                            <jobs>
                              <job name='plan1'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""").formatted(cipherText), CONFIG_SCHEMA_VERSION);
        CruiseConfig config = ConfigMigrator.loadWithMigration(content).config;
        PipelineConfig pipelineConfig = config.pipelineConfigByName(new CaseInsensitiveString("some_pipeline"));
        EnvironmentVariablesConfig variables = pipelineConfig.getVariables();
        assertThat(variables.size()).isEqualTo(1);
        EnvironmentVariableConfig environmentVariableConfig = variables.get(0);
        assertThat(environmentVariableConfig.getEncryptedValue()).isEqualTo(cipherText);
        assertThat(environmentVariableConfig.isSecure()).isTrue();
    }

    @Test
    void shouldMigrateEmptyEnvironmentVariable() {
        String content = configWithPipeline(
                """
                        <pipeline name='some_pipeline'>
                        <environmentvariables>
                                <variable name="var_name" />
                              </environmentvariables>
                            <materials>
                              <svn url='svnurl'/>
                            </materials>
                          <stage name='some_stage'>
                            <jobs>
                              <job name='some_job'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", 48);
        CruiseConfig config = ConfigMigrator.loadWithMigration(content).config;
        PipelineConfig pipelineConfig = config.pipelineConfigByName(new CaseInsensitiveString("some_pipeline"));
        EnvironmentVariablesConfig variables = pipelineConfig.getVariables();
        assertThat(variables.size()).isEqualTo(1);
        EnvironmentVariableConfig environmentVariableConfig = variables.get(0);
        assertThat(environmentVariableConfig.getName()).isEqualTo("var_name");
        assertThat(environmentVariableConfig.getValue().isEmpty()).isTrue();
    }

    @Test
    void shouldAllowAnEnvironmentVariableToBeMarkedAsSecure_WithEncryptedValueInItsOwnTag() throws Exception {
        String value = "abc";
        String encryptedValue = new GoCipher().encrypt(value);
        String content = configWithPipeline(format(
                """
                        <pipeline name='some_pipeline'>
                        <environmentvariables>
                                <variable name="var_name" secure="true"><encryptedValue>%s</encryptedValue></variable>
                              </environmentvariables>
                            <materials>
                              <svn url='svnurl'/>
                            </materials>
                          <stage name='some_stage'>
                            <jobs>
                              <job name='plan1'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", encryptedValue), CONFIG_SCHEMA_VERSION);
        CruiseConfig config = ConfigMigrator.loadWithMigration(content).config;
        PipelineConfig pipelineConfig = config.pipelineConfigByName(new CaseInsensitiveString("some_pipeline"));
        EnvironmentVariablesConfig variables = pipelineConfig.getVariables();
        assertThat(variables.size()).isEqualTo(1);
        EnvironmentVariableConfig environmentVariableConfig = variables.get(0);
        assertThat(environmentVariableConfig.getEncryptedValue()).isEqualTo(encryptedValue);
        assertThat(environmentVariableConfig.isSecure()).isTrue();
    }

    @Test
    void shouldNotAllowWorkspaceOwnerAndWorkspaceAsAttributesOnTfsMaterial() {
        String content = configWithPipeline(
                """
                        <pipeline name='some_pipeline'>
                            <materials>
                              <tfs url='tfsurl' username='foo' password='bar' projectPath='project-path' />
                            </materials>
                          <stage name='some_stage'>
                            <jobs>
                              <job name='do-something'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", CONFIG_SCHEMA_VERSION);

        assertThatCode(() -> ConfigMigrator.loadWithMigration(content))
            .as("Valid TFS tag for migration 51 and above")
            .doesNotThrowAnyException();
    }

    @Test
    void shouldMigrateConfigToSplitUsernameAndDomainAsAttributeOnTfsMaterial() {
        String content = configWithPipeline(
                """
                        <pipeline name='some_pipeline'>
                            <materials>
                              <tfs url='tfsurl' username='domain\\username' password='bar' projectPath='project-path' />
                            </materials>
                          <stage name='some_stage'>
                            <jobs>
                              <job name='some_job'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", 52);

        CruiseConfig config = ConfigMigrator.loadWithMigration(content).config;
        PipelineConfig pipeline = config.pipelineConfigByName(new CaseInsensitiveString("some_pipeline"));
        TfsMaterialConfig material = (TfsMaterialConfig) pipeline.materialConfigs().get(0);
        assertThat(material.getUserName()).isEqualTo("username");
        assertThat(material.getDomain()).isEqualTo("domain");
    }

    @Test
    void shouldAllowUserToSpecify_PathFromAncestor_forFetchArtifactFromAncestor() {
        String content = configWithPipeline(
                """
                        <pipeline name='uppest_pipeline'>
                            <materials>
                              <git url="foo" />
                            </materials>
                          <stage name='uppest_stage'>
                            <jobs>
                              <job name='uppest_job'><tasks><ant /></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>
                        <pipeline name='up_pipeline'>
                            <materials>
                              <pipeline pipelineName="uppest_pipeline" stageName="uppest_stage"/>
                            </materials>
                          <stage name='up_stage'>
                            <jobs>
                              <job name='up_job'><tasks><ant /></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>
                        <pipeline name='down_pipeline'>
                            <materials>
                              <pipeline pipelineName="up_pipeline" stageName="up_stage"/>
                            </materials>
                          <stage name='down_stage'>
                            <jobs>
                              <job name='down_job'><tasks><ant /></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>
                        <pipeline name='downest_pipeline'>
                            <materials>
                              <pipeline pipelineName="down_pipeline" stageName="down_stage"/>
                            </materials>
                          <stage name='downest_stage'>
                            <jobs>
                              <job name='downest_job'>
                                <tasks>
                                  <fetchartifact artifactOrigin='gocd' pipeline="uppest_pipeline/up_pipeline/down_pipeline" stage="uppest_stage" job="uppest_job" srcfile="src" dest="dest"/>
                                </tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", CONFIG_SCHEMA_VERSION);

        GoConfigHolder holder = ConfigMigrator.loadWithMigration(content);
        assertThat(holder.config.pipelineConfigByName(new CaseInsensitiveString("downest_pipeline")).getFetchTasks().get(0)).isEqualTo(new FetchTask(new CaseInsensitiveString("uppest_pipeline/up_pipeline/down_pipeline"), new CaseInsensitiveString("uppest_stage"), new CaseInsensitiveString("uppest_job"), "src", "dest"));
    }

    @Test
    void should_NOT_allowUserToSpecifyFetchStage_afterUpstreamStage() {
        String content = configWithPipeline(
                """
                        <pipeline name='up_pipeline'>
                          <materials>
                            <git url="/tmp/git"/>
                          </materials>
                          <stage name='up_stage'>
                            <jobs>
                              <job name='up42_job'><tasks><ant /></tasks>
                              </job>
                            </jobs>
                          </stage>
                          <stage name='up_stage_2'>
                            <jobs>
                              <job name='up_job'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                          <stage name='up_stage_3'>
                            <jobs>
                              <job name='up_job'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>
                        <pipeline name='down_pipeline'>
                            <materials>
                              <pipeline pipelineName="up_pipeline" stageName="up_stage"/>
                            </materials>
                          <stage name='down_stage'>
                            <jobs>
                              <job name='down_job'>
                                <tasks>
                                  <fetchartifact artifactOrigin='gocd' pipeline="up_pipeline" stage="up_stage_2" job="up_job" srcfile="src" dest="dest"/>
                                </tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> ConfigMigrator.loadWithMigration(content))
            .as("should not have permitted fetch from parent pipeline's stage after the one downstream depends on")
            .hasMessageContaining("\"down_pipeline :: down_stage :: down_job\" tries to fetch artifact from stage \"up_pipeline :: up_stage_2\" which does not complete before \"down_pipeline\" pipeline's dependencies.");
    }

    @Test
    void shouldDeserializeGroupXml() throws Exception {
        String partialXml = """
                <pipelines group="group_name">
                  <pipeline name="new_name">
                    <materials>
                      <svn url="file:///tmp/foo" />
                    </materials>
                    <stage name="stage_name">
                      <jobs>
                        <job name="job_name" />
                      </jobs>
                    </stage>
                  </pipeline>
                </pipelines>""";
        PipelineConfigs pipelineConfigs = xmlLoader.fromXmlPartial(partialXml, BasicPipelineConfigs.class);
        PipelineConfig pipeline = pipelineConfigs.findBy(new CaseInsensitiveString("new_name"));
        assertThat(pipeline).isNotNull();
        assertThat(pipeline.materialConfigs().size()).isEqualTo(1);
        MaterialConfig material = pipeline.materialConfigs().get(0);
        assertThat(material).isInstanceOf(SvnMaterialConfig.class);
        assertThat(material.getUriForDisplay()).isEqualTo("file:///tmp/foo");
        assertThat(pipeline.size()).isEqualTo(1);
        assertThat(pipeline.get(0).getJobs().size()).isEqualTo(1);
    }

    @Test
    void shouldRegisterAllGoConfigValidators() {
        Stream<String> names = MagicalGoConfigXmlLoader.VALIDATORS.stream().map(o -> o.getClass().getCanonicalName());

        assertThat(names).containsExactly(
            ArtifactDirValidator.class.getCanonicalName(),
            ServerIdImmutabilityValidator.class.getCanonicalName(),
            TokenGenerationKeyImmutabilityValidator.class.getCanonicalName())
        ;
    }

    @Test
    void shouldResolvePackageReferenceElementForAMaterialInConfig() throws Exception {
        String xml = ("""
                <cruise schemaVersion='%d'>
                <repositories>
                    <repository id='repo-id' name='name'>
                    <pluginConfiguration id='plugin-id' version='1.0'/>
                      <configuration>
                        <property>
                          <key>url</key>
                          <value>https://go</value>
                        </property>
                      </configuration>
                      <packages>
                        <package id='package-id' name='name'>
                          <configuration>
                            <property>
                              <key>name</key>
                              <value>go-agent</value>
                            </property>
                          </configuration>
                        </package>
                      </packages>
                    </repository>
                  </repositories>
                <pipelines group="group_name">
                  <pipeline name="new_name">
                    <materials>
                      <package ref='package-id' />
                    </materials>
                    <stage name="stage_name">
                      <jobs>
                        <job name="job_name">
                            <tasks>
                              <exec command="echo">
                                <runif status="passed" />
                              </exec>
                            </tasks>
                          </job>
                      </jobs>
                    </stage>
                  </pipeline>
                </pipelines></cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        GoConfigHolder goConfigHolder = xmlLoader.loadConfigHolder(xml);
        PackageDefinition packageDefinition = goConfigHolder.config.getPackageRepositories().first().getPackages().first();
        PipelineConfig pipelineConfig = goConfigHolder.config.pipelineConfigByName(new CaseInsensitiveString("new_name"));
        PackageMaterialConfig packageMaterialConfig = (PackageMaterialConfig) pipelineConfig.materialConfigs().get(0);
        assertThat(packageMaterialConfig.getPackageDefinition()).isEqualTo(packageDefinition);
    }

    @Test
    void shouldBeAbleToResolveSecureConfigPropertiesForPackages() throws Exception {
        String encryptedValue = new GoCipher().encrypt("secure-two");
        String xml = ("""
                <cruise schemaVersion='%d'>
                <repositories>
                    <repository id='repo-id' name='name'>
                    <pluginConfiguration id='plugin-id' version='1.0'/>
                      <configuration>
                        <property>
                          <key>plain</key>
                          <value>value</value>
                        </property>
                        <property>
                          <key>secure-one</key>
                          <value>secure-value</value>
                        </property>
                        <property>
                          <key>secure-two</key>
                          <encryptedValue>%s</encryptedValue>
                        </property>
                      </configuration>
                      <packages>
                        <package id='package-id' name='name'>
                          <configuration>
                              <property>
                                <key>plain</key>
                                <value>value</value>
                              </property>
                              <property>
                                <key>secure-one</key>
                                <value>secure-value</value>
                              </property>
                              <property>
                                <key>secure-two</key>
                                <encryptedValue>%s</encryptedValue>
                              </property>
                          </configuration>
                        </package>
                      </packages>
                    </repository>
                  </repositories>
                <pipelines group="group_name">
                  <pipeline name="new_name">
                    <materials>
                      <package ref='package-id' />
                    </materials>
                    <stage name="stage_name">
                      <jobs>
                        <job name="job_name">
                            <tasks>
                              <exec command="echo">
                                <runif status="passed" />
                              </exec>
                            </tasks>
                          </job>
                      </jobs>
                    </stage>
                  </pipeline>
                </pipelines></cruise>""").formatted(CONFIG_SCHEMA_VERSION, encryptedValue, encryptedValue);

        //meta data of package
        PackageConfigurations packageConfigurations = new PackageConfigurations();
        packageConfigurations.addConfiguration(new PackageConfiguration("plain"));
        packageConfigurations.addConfiguration(new PackageConfiguration("secure-one").with(PackageConfiguration.SECURE, true));
        packageConfigurations.addConfiguration(new PackageConfiguration("secure-two").with(PackageConfiguration.SECURE, true));
        PackageMetadataStore.getInstance().addMetadataFor("plugin-id", packageConfigurations);
        RepositoryMetadataStore.getInstance().addMetadataFor("plugin-id", packageConfigurations);

        GoConfigHolder goConfigHolder = xmlLoader.loadConfigHolder(xml);
        PackageDefinition packageDefinition = goConfigHolder.config.getPackageRepositories().first().getPackages().first();
        PipelineConfig pipelineConfig = goConfigHolder.config.pipelineConfigByName(new CaseInsensitiveString("new_name"));
        PackageMaterialConfig packageMaterialConfig = (PackageMaterialConfig) pipelineConfig.materialConfigs().get(0);
        assertThat(packageMaterialConfig.getPackageDefinition()).isEqualTo(packageDefinition);
        Configuration repoConfig = packageMaterialConfig.getPackageDefinition().getRepository().getConfiguration();
        assertThat(repoConfig.get(0).getConfigurationValue().getValue()).isEqualTo("value");
        assertThat(repoConfig.get(1).getEncryptedValue()).startsWith("AES:");
        assertThat(repoConfig.get(2).getEncryptedValue()).startsWith("AES:");
        Configuration packageConfig = packageMaterialConfig.getPackageDefinition().getConfiguration();
        assertThat(packageConfig.get(0).getConfigurationValue().getValue()).isEqualTo("value");
        assertThat(packageConfig.get(1).getEncryptedValue()).startsWith("AES:");
        assertThat(packageConfig.get(2).getEncryptedValue()).startsWith("AES:");
    }

    @Test
    void shouldResolvePackageRepoReferenceElementForAPackageInConfig() throws Exception {
        String xml = ("""
                <cruise schemaVersion='%d'>
                <repositories>
                    <repository id='repo-id' name='name'>
                    <pluginConfiguration id='plugin-id' version='1.0'/>
                      <configuration>
                        <property>
                          <key>url</key>
                          <value>https://go</value>
                        </property>
                      </configuration>
                      <packages>
                        <package id='package-id' name='name'>
                          <configuration>
                            <property>
                              <key>name</key>
                              <value>go-agent</value>
                            </property>
                          </configuration>
                        </package>
                      </packages>
                    </repository>
                  </repositories></cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        GoConfigHolder goConfigHolder = xmlLoader.loadConfigHolder(xml);
        PackageRepository packageRepository = goConfigHolder.config.getPackageRepositories().first();
        PackageDefinition packageDefinition = packageRepository.getPackages().first();
        assertThat(packageDefinition.getRepository()).isEqualTo(packageRepository);
    }

    @Test
    void shouldFailValidationIfPackageDefinitionWithDuplicateFingerprintExists() {
        com.thoughtworks.go.plugin.api.material.packagerepository.PackageConfiguration packageConfiguration = new com.thoughtworks.go.plugin.api.material.packagerepository.PackageConfiguration();
        packageConfiguration.add(new PackageMaterialProperty("PKG-KEY1"));
        RepositoryConfiguration repositoryConfiguration = new RepositoryConfiguration();
        repositoryConfiguration.add(new PackageMaterialProperty("REPO-KEY1"));
        repositoryConfiguration.add(new PackageMaterialProperty("REPO-KEY2").with(REQUIRED, false).with(PART_OF_IDENTITY, false));
        repositoryConfiguration.add(new PackageMaterialProperty("REPO-KEY3").with(REQUIRED, false).with(PART_OF_IDENTITY, false).with(SECURE, true));
        PackageMetadataStore.getInstance().addMetadataFor("plugin-1", new PackageConfigurations(packageConfiguration));
        RepositoryMetadataStore.getInstance().addMetadataFor("plugin-1", new PackageConfigurations(repositoryConfiguration));

        String xml = ("""
                <cruise schemaVersion='%d'>
                <repositories>
                    <repository id='repo-id-1' name='name-1'>
                    <pluginConfiguration id='plugin-1' version='1.0'/>
                      <configuration>
                        <property>
                          <key>REPO-KEY1</key>
                          <value>repo-key1</value>
                        </property>
                        <property>
                          <key>REPO-KEY2</key>
                          <value>repo-key2</value>
                        </property>
                        <property>
                          <key>REPO-KEY3</key>
                          <value>repo-key3</value>
                        </property>
                      </configuration>
                      <packages>
                        <package id='package-id-1' name='name-1'>
                          <configuration>
                            <property>
                              <key>PKG-KEY1</key>
                              <value>pkg-key1</value>
                            </property>
                          </configuration>
                        </package>
                      </packages>
                    </repository>
                    <repository id='repo-id-2' name='name-2'>
                    <pluginConfiguration id='plugin-1' version='1.0'/>
                      <configuration>
                        <property>
                          <key>REPO-KEY1</key>
                          <value>repo-key1</value>
                        </property>
                        <property>
                          <key>REPO-KEY2</key>
                          <value>another-repo-key2</value>
                        </property>
                        <property>
                          <key>REPO-KEY3</key>
                          <value>another-repo-key3</value>
                        </property>
                      </configuration>
                      <packages>
                        <package id='package-id-2' name='name-2'>
                          <configuration>
                            <property>
                              <key>PKG-KEY1</key>
                              <value>pkg-key1</value>
                            </property>
                          </configuration>
                        </package>
                      </packages>
                    </repository>
                  </repositories>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        assertFailureDuringLoad(xml,
                GoConfigInvalidException.class, "Cannot save package or repo, found duplicate packages. [Repo Name: 'name-1', Package Name: 'name-1'], [Repo Name: 'name-2', Package Name: 'name-2']"
        );
    }

    private final static String REPO = " <repository id='repo-id' name='name1'><pluginConfiguration id='id' version='1.0'/><configuration><property><key>url</key><value>https://go</value></property></configuration>%s</repository>";
    private final static String REPO_WITH_NAME = " <repository id='%s' name='%s'><pluginConfiguration id='id' version='1.0'/><configuration><property><key>url</key><value>https://go</value></property></configuration>%s</repository>";
    private final static String REPO_WITH_MISSING_ID = " <repository name='name1'><pluginConfiguration id='id' version='1.0'/><configuration><property><key>url</key><value>https://go</value></property></configuration><packages>%s</packages></repository>";
    private final static String REPO_WITH_INVALID_ID = " <repository id='id with space' name='name1'><pluginConfiguration id='id' version='1.0'/><configuration><property><key>url</key><value>https://go</value></property></configuration>%s</repository>";
    private final static String REPO_WITH_EMPTY_ID = " <repository id='' name='name1'><pluginConfiguration id='id' version='1.0'/><configuration><property><key>url</key><value>https://go</value></property></configuration>%s</repository>";
    private final static String REPO_WITH_MISSING_NAME = " <repository id='id' ><pluginConfiguration id='id' version='1.0'/><configuration><property><key>url</key><value>https://go</value></property></configuration>%s</repository>";
    private final static String REPO_WITH_INVALID_NAME = " <repository id='id' name='name with space'><pluginConfiguration id='id' version='1.0'/><configuration><property><key>url</key><value>https://go</value></property></configuration>%s</repository>";
    private final static String REPO_WITH_EMPTY_NAME = " <repository id='id' name=''><pluginConfiguration id='id' version='1.0'/><configuration><property><key>url</key><value>https://go</value></property></configuration>%s</repository>";

    private final static String PACKAGE = "<package id='package-id' name='name'><configuration><property><key>name</key><value>go-agent</value></property></configuration></package>";
    private final static String PACKAGE_WITH_MISSING_ID = "<package name='name'><configuration><property><key>name</key><value>go-agent</value></property></configuration></package>";
    private final static String PACKAGE_WITH_INVALID_ID = "<package id='id with space' name='name'><configuration><property><key>name</key><value>go-agent</value></property></configuration></package>";
    private final static String PACKAGE_WITH_EMPTY_ID = "<package id='' name='name'><configuration><property><key>name</key><value>go-agent</value></property></configuration></package>";
    private final static String PACKAGE_WITH_MISSING_NAME = "<package id='id'><configuration><property><key>name</key><value>go-agent</value></property></configuration></package>";
    private final static String PACKAGE_WITH_INVALID_NAME = "<package id='id' name='name with space'><configuration><property><key>name</key><value>go-agent</value></property></configuration></package>";
    private final static String PACKAGE_WITH_EMPTY_NAME = "<package id='id' name=''><configuration><property><key>name</key><value>go-agent</value></property></configuration></package>";

    private String withPackages(String repo, String packages) {
        return format(repo, packages);
    }

    @Test
    void shouldThrowXsdValidationWhenPackageRepositoryIdsAreDuplicate() {
        String xml = "<cruise schemaVersion='" + CONFIG_SCHEMA_VERSION + "'><repositories>\n" + withPackages(REPO, "") + withPackages(REPO, "") + " </repositories></cruise>";
        assertXsdFailureDuringLoad(xml,
                "Duplicate unique value [repo-id] declared for identity constraint of element \"repositories\".",
                "Duplicate unique value [repo-id] declared for identity constraint \"uniqueRepositoryId\" of element \"repositories\"."
        );
    }

    @Test
    void shouldThrowXsdValidationWhenPackageRepositoryNamesAreDuplicate() {
        String xml = "<cruise schemaVersion='" + CONFIG_SCHEMA_VERSION + "'><repositories>\n" + format(REPO_WITH_NAME, "1", "repo", "") + format(REPO_WITH_NAME, "2", "repo", "") + " </repositories></cruise>";
        assertXsdFailureDuringLoad(xml,
                "Duplicate unique value [repo] declared for identity constraint of element \"repositories\".",
                "Duplicate unique value [repo] declared for identity constraint \"uniqueRepositoryName\" of element \"repositories\"."
        );
    }

    @Test
    void shouldThrowXsdValidationWhenPackageIdsAreDuplicate() {
        String xml = "<cruise schemaVersion='" + CONFIG_SCHEMA_VERSION + "'><repositories>\n" + withPackages(REPO, format("<packages>%s%s</packages>",
                PACKAGE, PACKAGE)) + " </repositories></cruise>";

        assertXsdFailureDuringLoad(xml,
                "Duplicate unique value [package-id] declared for identity constraint of element \"cruise\".",
                "Duplicate unique value [package-id] declared for identity constraint \"uniquePackageId\" of element \"cruise\"."
        );
    }

    @Test
    void shouldThrowXsdValidationWhenPackageRepositoryIdIsEmpty() {
        String xml = "<cruise schemaVersion='" + CONFIG_SCHEMA_VERSION + "'><repositories>\n" + withPackages(REPO_WITH_EMPTY_ID, "") + " </repositories></cruise>";
        assertXsdFailureDuringLoad(xml, "Repo id is invalid. \"\" should conform to the pattern - [a-zA-Z0-9_\\-]{1}[a-zA-Z0-9_\\-.]*");
    }

    @Test
    void shouldThrowXsdValidationWhenPackageRepositoryIdIsInvalid() {
        String xml = "<cruise schemaVersion='" + CONFIG_SCHEMA_VERSION + "'><repositories>\n" + withPackages(REPO_WITH_INVALID_ID, "") + " </repositories></cruise>";
        assertXsdFailureDuringLoad(xml, "Repo id is invalid. \"id with space\" should conform to the pattern - [a-zA-Z0-9_\\-]{1}[a-zA-Z0-9_\\-.]*");
    }

    @Test
    void shouldThrowXsdValidationWhenPackageRepositoryNameIsMissing() {
        String xml = "<cruise schemaVersion='" + CONFIG_SCHEMA_VERSION + "'><repositories>\n" + withPackages(REPO_WITH_MISSING_NAME, "") + " </repositories></cruise>";
        assertXsdFailureDuringLoad(xml, "\"Name\" is required for Repository");
    }

    @Test
    void shouldThrowXsdValidationWhenPackageRepositoryNameIsEmpty() {
        String xml = "<cruise schemaVersion='" + CONFIG_SCHEMA_VERSION + "'><repositories>\n" + withPackages(REPO_WITH_EMPTY_NAME, "") + " </repositories></cruise>";
        assertXsdFailureDuringLoad(xml, "Name is invalid. \"\" should conform to the pattern - [a-zA-Z0-9_\\-]{1}[a-zA-Z0-9_\\-.]*");
    }

    @Test
    void shouldThrowXsdValidationWhenPackageRepositoryNameIsInvalid() {
        String xml = "<cruise schemaVersion='" + CONFIG_SCHEMA_VERSION + "'><repositories>\n" + withPackages(REPO_WITH_INVALID_NAME, "") + " </repositories></cruise>";
        assertXsdFailureDuringLoad(xml, "Name is invalid. \"name with space\" should conform to the pattern - [a-zA-Z0-9_\\-]{1}[a-zA-Z0-9_\\-.]*");
    }

    @Test
    void shouldGenerateRepoAndPkgIdWhenMissing() throws Exception {
        String xml = "<cruise schemaVersion='" + CONFIG_SCHEMA_VERSION + "'><repositories>\n" + withPackages(REPO_WITH_MISSING_ID,
                PACKAGE_WITH_MISSING_ID) + " </repositories></cruise>";
        GoConfigHolder configHolder = xmlLoader.loadConfigHolder(xml);
        assertThat(configHolder.config.getPackageRepositories().get(0).getId()).isNotNull();
        assertThat(configHolder.config.getPackageRepositories().get(0).getPackages().get(0).getId()).isNotNull();
    }

    @Test
    void shouldThrowXsdValidationWhenPackageIdIsEmpty() {
        String xml = "<cruise schemaVersion='" + CONFIG_SCHEMA_VERSION + "'><repositories>\n" + withPackages(REPO_WITH_EMPTY_ID, PACKAGE_WITH_EMPTY_ID) + " </repositories></cruise>";
        assertXsdFailureDuringLoad(xml, "Repo id is invalid. \"\" should conform to the pattern - [a-zA-Z0-9_\\-]{1}[a-zA-Z0-9_\\-.]*");
    }

    @Test
    void shouldThrowXsdValidationWhenPackageIdIsInvalid() {
        String xml = "<cruise schemaVersion='" + CONFIG_SCHEMA_VERSION + "'><repositories>\n" + withPackages(REPO_WITH_INVALID_ID,
                PACKAGE_WITH_INVALID_ID) + " </repositories></cruise>";
        assertXsdFailureDuringLoad(xml, "Repo id is invalid. \"id with space\" should conform to the pattern - [a-zA-Z0-9_\\-]{1}[a-zA-Z0-9_\\-.]*");
    }

    @Test
    void shouldThrowXsdValidationWhenPackageNameIsMissing() {
        String xml = "<cruise schemaVersion='" + CONFIG_SCHEMA_VERSION + "'><repositories>\n" + withPackages(REPO_WITH_MISSING_NAME,
                PACKAGE_WITH_MISSING_NAME) + " </repositories></cruise>";
        assertXsdFailureDuringLoad(xml, "\"Name\" is required for Repository");
    }

    @Test
    void shouldThrowXsdValidationWhenPackageNameIsEmpty() {
        String xml = "<cruise schemaVersion='" + CONFIG_SCHEMA_VERSION + "'><repositories>\n" + withPackages(REPO_WITH_EMPTY_NAME,
                PACKAGE_WITH_EMPTY_NAME) + " </repositories></cruise>";
        assertXsdFailureDuringLoad(xml, "Name is invalid. \"\" should conform to the pattern - [a-zA-Z0-9_\\-]{1}[a-zA-Z0-9_\\-.]*");
    }

    @Test
    void shouldThrowXsdValidationWhenPackageNameIsInvalid() {
        String xml = "<cruise schemaVersion='" + CONFIG_SCHEMA_VERSION + "'><repositories>\n" + withPackages(REPO_WITH_INVALID_NAME,
                PACKAGE_WITH_INVALID_NAME) + " </repositories></cruise>";
        assertXsdFailureDuringLoad(xml, "Name is invalid. \"name with space\" should conform to the pattern - [a-zA-Z0-9_\\-]{1}[a-zA-Z0-9_\\-.]*");
    }

    @Test
    void shouldLoadAutoUpdateValueForPackageWhenLoadedFromConfigFile() throws Exception {
        String configTemplate = ("""
                <cruise schemaVersion='%d'>
                <repositories>
                  <repository id='2ef830d7-dd66-42d6-b393-64a84646e557' name='GoYumRepo'>
                    <pluginConfiguration id='yum' version='1' />
                       <configuration>
                           <property>
                               <key>REPO_URL</key>
                               <value>https://fake-yum-repo/go/yum/no-arch</value>
                               </property>
                       </configuration>
                      <packages>
                           <package id='88a3beca-cbe2-4c4d-9744-aa0cda3f371c' name='1' autoUpdate='%%s'>
                               <configuration>
                                   <property>
                                       <key>REPO_URL</key>
                                       <value>https://fake-yum-repo/go/yum/no-arch</value>
                                   </property>
                               </configuration>
                           </package>
                       </packages>
                   </repository>
                </repositories>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);
        String configContent = String.format(configTemplate, false);
        GoConfigHolder holder = xmlLoader.loadConfigHolder(configContent);
        PackageRepository packageRepository = holder.config.getPackageRepositories().find("2ef830d7-dd66-42d6-b393-64a84646e557");
        PackageDefinition aPackage = packageRepository.findPackage("88a3beca-cbe2-4c4d-9744-aa0cda3f371c");
        assertThat(aPackage.isAutoUpdate()).isFalse();

        configContent = String.format(configTemplate, true);
        holder = xmlLoader.loadConfigHolder(configContent);
        packageRepository = holder.config.getPackageRepositories().find("2ef830d7-dd66-42d6-b393-64a84646e557");
        aPackage = packageRepository.findPackage("88a3beca-cbe2-4c4d-9744-aa0cda3f371c");
        assertThat(aPackage.isAutoUpdate()).isTrue();
    }

    @Test
    void shouldAllowColonsInPipelineLabelTemplate() {
        String xml = ("""
                <cruise schemaVersion='%d'>
                <repositories>
                    <repository id='repo-id' name='repo_name'>
                    <pluginConfiguration id='plugin-id' version='1.0'/>
                      <configuration>
                        <property>
                          <key>url</key>
                          <value>https://go</value>
                        </property>
                      </configuration>
                      <packages>
                        <package id='package-id' name='pkg_name'>
                          <configuration>
                            <property>
                              <key>name</key>
                              <value>go-agent</value>
                            </property>
                          </configuration>
                        </package>
                      </packages>
                    </repository>
                  </repositories>
                <pipelines group="group_name">
                  <pipeline name="new_name" labeltemplate="${COUNT}:${repo_name_pkg_name}">
                    <materials>
                      <package ref='package-id' />
                    </materials>
                    <stage name="stage_name">
                      <jobs>
                        <job name="job_name">
                         <tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                        </job>
                      </jobs>
                    </stage>
                  </pipeline>
                </pipelines></cruise>""").formatted(CONFIG_SCHEMA_VERSION);
        GoConfigHolder holder = ConfigMigrator.loadWithMigration(xml);
        assertThat(holder.config.getAllPipelineConfigs().get(0).materialConfigs().get(0).getName().toString()).isEqualTo("repo_name_pkg_name");
    }

    @Test
    void shouldAllowEmptyAuthorizationTagUnderEachTemplateWhileLoading() {
        String configString =
                ("""
                        <cruise schemaVersion='%d'>
                           <templates>
                               <pipeline name='template-name'>
                                   <authorization>
                                       <admins>
                                       </admins>
                                   </authorization>
                                   <stage name='stage-name'>
                                       <jobs>
                                           <job name='job-name'><tasks><exec command='echo'><runif status='passed' /></exec></tasks></job>
                                       </jobs>
                                   </stage>
                               </pipeline>
                           </templates>
                        </cruise>""").formatted(CONFIG_SCHEMA_VERSION);
        CruiseConfig configForEdit = ConfigMigrator.loadWithMigration(configString).configForEdit;
        PipelineTemplateConfig template = configForEdit.getTemplateByName(new CaseInsensitiveString("template-name"));
        Authorization authorization = template.getAuthorization();
        assertThat(authorization).isNotNull();
        assertThat(authorization.getAdminsConfig().getUsers()).isEmpty();
        assertThat(authorization.getAdminsConfig().getRoles()).isEmpty();
    }

    @Test
    void shouldAllowPluggableTaskConfiguration() {
        String configString =
                ("""
                        <cruise schemaVersion='%d'>
                         <pipelines>
                        <pipeline name='pipeline1'>
                            <materials>
                              <svn url='svnurl' username='admin' password='%%s'/>
                            </materials>
                          <stage name='mingle'>
                            <jobs>
                              <job name='do-something'><tasks>
                                <task>
                                  <pluginConfiguration id='plugin-id-1' version='1.0'/>
                                  <configuration>
                                    <property><key>url</key><value>https://fake-go-server</value></property>
                                    <property><key>username</key><value>godev</value></property>
                                    <property><key>password</key><value>password</value></property>
                                  </configuration>
                                </task> </tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline></pipelines>
                        </cruise>""").formatted(CONFIG_SCHEMA_VERSION);
        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(configString).configForEdit;

        PipelineConfig pipelineConfig = cruiseConfig.getAllPipelineConfigs().get(0);
        JobConfig jobConfig = pipelineConfig.getFirstStageConfig().getJobs().get(0);
        Tasks tasks = jobConfig.getTasks();
        assertThat(tasks.size()).isEqualTo(1);
        assertThat(tasks.get(0) instanceof PluggableTask).isTrue();
        PluggableTask task = (PluggableTask) tasks.get(0);
        assertThat(task.getTaskType()).isEqualTo("pluggable_task_plugin_id_1");
        assertThat(task.getTypeForDisplay()).isEqualTo("Pluggable Task");
        final Configuration configuration = task.getConfiguration();
        assertThat(configuration.listOfConfigKeys().size()).isEqualTo(3);
        assertThat(configuration.listOfConfigKeys()).isEqualTo(List.of("url", "username", "password"));
        Stream<String> values = configuration.listOfConfigKeys().stream().map(o -> {
            ConfigurationProperty property = configuration.getProperty(o);
            return property.getConfigurationValue().getValue();
        });
        assertThat(values).containsExactly("https://fake-go-server", "godev", "password");
    }

    @Test
    void shouldBeAbleToResolveSecureConfigPropertiesForPluggableTasks() throws Exception {
        String configString =
                ("""
                        <cruise schemaVersion='%d'>
                         <pipelines>
                        <pipeline name='pipeline1'>
                            <materials>
                              <svn url='svnurl' username='admin' password='%%s'/>
                            </materials>
                          <stage name='mingle'>
                            <jobs>
                              <job name='do-something'><tasks>
                                <task>
                                  <pluginConfiguration id='plugin-id-1' version='1.0'/>
                                  <configuration>
                                    <property><key>username</key><value>godev</value></property>
                                    <property><key>password</key><value>password</value></property>
                                  </configuration>
                                </task> </tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline></pipelines>
                        </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        //meta data of package
        PluggableTaskConfigStore.store().setPreferenceFor("plugin-id-1", new TaskPreference(new com.thoughtworks.go.plugin.api.task.Task() {
            @Override
            public TaskConfig config() {
                TaskConfig taskConfig = new TaskConfig();
                taskConfig.addProperty("username").with(Property.SECURE, false);
                taskConfig.addProperty("password").with(Property.SECURE, true);
                return taskConfig;
            }

            @Override
            public TaskExecutor executor() {
                return null;
            }

            @Override
            public TaskView view() {
                return null;
            }

            @Override
            public ValidationResult validate(TaskConfig configuration) {
                return null;
            }
        }));

        GoConfigHolder goConfigHolder = xmlLoader.loadConfigHolder(configString);

        PipelineConfig pipelineConfig = goConfigHolder.config.pipelineConfigByName(new CaseInsensitiveString("pipeline1"));
        PluggableTask task = (PluggableTask) pipelineConfig.getStage("mingle").getJobs().getJob(new CaseInsensitiveString("do-something")).getTasks().first();

        assertThat(task.getConfiguration().getProperty("username").isSecure()).isFalse();
        assertThat(task.getConfiguration().getProperty("password").isSecure()).isTrue();
    }

    @Test
    void shouldAllowTemplateViewConfigToBeSpecified() {
        String configXml = ("""
                <?xml version="1.0" encoding="utf-8"?>
                <cruise xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"      xsi:noNamespaceSchemaLocation="cruise-config.xsd" schemaVersion='%d'>
                <server>
                    <artifacts>
                      <artifactsDir>artifactsDir</artifactsDir>
                    </artifacts>
                     <security>
                         <roles>
                             <role name='role1'>
                                 <users>
                                     <user>jyoti</user>
                                     <user>duck</user>
                                 </users>
                             </role>
                         </roles>
                     </security>
                 </server>
                 <templates>
                   <pipeline name='template1'>
                     <authorization>
                       <view>
                         <user>foo</user>
                         <role>role1</role>
                       </view>
                     </authorization>
                  <stage name='build'>
                    <jobs>
                      <job name='test1'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                      </job>
                    </jobs>
                  </stage>
                   </pipeline>
                  </templates>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(configXml).config;
        ViewConfig expectedViewConfig = new ViewConfig(new AdminUser(new CaseInsensitiveString("foo")), new AdminRole(new RoleConfig(new CaseInsensitiveString("role1"), new RoleUser("duck"), new RoleUser("jyoti"))));

        assertThat(cruiseConfig.getTemplateByName(new CaseInsensitiveString("template1")).getAuthorization().getViewConfig()).isEqualTo(expectedViewConfig);
    }

    @Test
    void shouldAllowPipelineGroupAdminsToViewTemplateByDefault() {
        String configXml = ("""
                <?xml version="1.0" encoding="utf-8"?>
                <cruise xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"      xsi:noNamespaceSchemaLocation="cruise-config.xsd" schemaVersion='%d'>
                <server>
                     <security>
                         <roles>
                             <role name='role1'>
                                 <users>
                                     <user>jyoti</user>
                                     <user>duck</user>
                                 </users>
                             </role>
                         </roles>
                     </security>
                     <artifacts>
                         <artifactsDir>artifactsDir</artifactsDir>
                     </artifacts>
                 </server>
                 <templates>
                   <pipeline name='template1'>
                     <authorization>
                       <admins>
                         <user>foo</user>
                         <role>role1</role>
                       </admins>
                     </authorization>
                  <stage name='build'>
                    <jobs>
                        <job name="test1">
                            <tasks>
                              <exec command="echo">
                                <runif status="passed" />
                              </exec>
                            </tasks>
                          </job>
                    </jobs>
                  </stage>
                   </pipeline>
                  </templates>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(configXml).config;

        assertThat(cruiseConfig.getTemplateByName(new CaseInsensitiveString("template1")).getAuthorization().isAllowGroupAdmins()).isTrue();
    }

    @Test
    void shouldNotAllowGroupAdminsToViewTemplateIfTheOptionIsDisabled() {
        String configXml = ("""
                <?xml version="1.0" encoding="utf-8"?>
                <cruise xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"      xsi:noNamespaceSchemaLocation="cruise-config.xsd" schemaVersion='%d'>
                <server>
                    <artifacts>
                      <artifactsDir>artifactsDir</artifactsDir>
                    </artifacts>
                    <security>
                        <roles>
                            <role name='role1'>
                                <users>
                                     <user>jyoti</user>
                                     <user>duck</user>
                                </users>
                            </role>
                        </roles>
                     </security>
                 </server>
                 <templates>
                   <pipeline name='template1'>
                     <authorization allGroupAdminsAreViewers='false'>
                       <admins>
                         <user>foo</user>
                         <role>role1</role>
                       </admins>
                     </authorization>
                  <stage name='build'>
                    <jobs>
                        <job name="test1">
                            <tasks>
                              <exec command="echo">
                                <runif status="passed" />
                              </exec>
                            </tasks>
                          </job>
                    </jobs>
                  </stage>
                   </pipeline>
                  </templates>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(configXml).config;

        assertThat(cruiseConfig.getTemplateByName(new CaseInsensitiveString("template1")).getAuthorization().isAllowGroupAdmins()).isFalse();
    }

    @Test
    void shouldSerializeJobElasticProfileId() {
        String configWithJobElasticProfileId =
                """
                        <cruise schemaVersion='119'>
                          <elastic jobStarvationTimeout="10">
                            <profiles>
                              <profile clusterProfileId='blah' id='unit-test' pluginId='aws'>
                                <property>
                                  <key>instance-type</key>
                                  <value>m1.small</value>
                                </property>
                              </profile>
                            </profiles>
                            <clusterProfiles>
                              <clusterProfile id="blah" pluginId="aws"/>
                            </clusterProfiles>
                          </elastic>
                        <pipelines group="first">
                        <pipeline name="pipeline">
                          <materials>
                            <hg url="/hgrepo"/>
                          </materials>
                          <stage name="mingle">
                            <jobs>
                              <job name="functional" elasticProfileId="unit-test">
                                    <tasks>
                                      <exec command="echo">
                                        <runif status="passed" />
                                      </exec>
                                    </tasks>
                                  </job>
                            </jobs>
                          </stage>
                        </pipeline>
                        </pipelines>
                        </cruise>
                        """;

        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(configWithJobElasticProfileId).configForEdit;

        String elasticProfileId = cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("pipeline")).getStage("mingle").jobConfigByConfigName("functional").getElasticProfileId();

        assertThat(elasticProfileId).isEqualTo("unit-test");
    }

    @Test
    void shouldSerializeElasticAgentProfiles() {
        String configWithElasticProfile =
                """
                        <cruise schemaVersion='119'>
                          <elastic jobStarvationTimeout="2">
                            <profiles>
                              <profile clusterProfileId='blah' id="foo" pluginId="docker">
                                  <property>
                                   <key>USERNAME</key>
                                   <value>bob</value>
                                  </property>
                              </profile>
                            </profiles>
                            <clusterProfiles>
                              <clusterProfile id="blah" pluginId="docker"/>
                            </clusterProfiles>
                          </elastic>
                        </cruise>
                        """;

        CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(configWithElasticProfile).configForEdit;

        assertThat(cruiseConfig.getElasticConfig().getJobStarvationTimeout()).isEqualTo(120000L);
        assertThat(cruiseConfig.getElasticConfig().getProfiles().size()).isEqualTo(1);

        ElasticProfile elasticProfile = cruiseConfig.getElasticConfig().getProfiles().find("foo");
        assertThat(elasticProfile).isNotNull();
        assertThat(elasticProfile.size()).isEqualTo(1);
        assertThat(elasticProfile.getProperty("USERNAME").getValue()).isEqualTo("bob");
    }

    @Test
    void shouldNotAllowJobElasticProfileIdAndResourcesTogether() {
        String configWithJobElasticProfile =
                ("""
                        <cruise schemaVersion='%d'>
                        <pipelines group="first">
                        <pipeline name="pipeline">
                          <materials>
                            <hg url="/hgrepo"/>
                          </materials>
                          <stage name="mingle">
                            <jobs>
                              <job name="functional" elasticProfileId="docker.unit-test">
                                <resources>
                                  <resource>foo</resource>
                                </resources>
                                <tasks>
                                  <exec command="echo">
                                    <runif status="passed" />
                                  </exec>
                                </tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>
                        </pipelines>
                        </cruise>
                        """).formatted(CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(configWithJobElasticProfile))
            .hasMessage("Job cannot have both `resource` and `elasticProfileId`, No profile defined corresponding to profile_id 'docker.unit-test', Job cannot have both `resource` and `elasticProfileId`");
    }

    @Test
    void shouldGetConfigRepoPreprocessor() {
        MagicalGoConfigXmlLoader loader = new MagicalGoConfigXmlLoader(null, null);
        assertThat(loader.getPreprocessorOfType(ConfigRepoPartialPreprocessor.class) instanceof ConfigRepoPartialPreprocessor).isTrue();
        assertThat(loader.getPreprocessorOfType(ConfigParamPreprocessor.class) instanceof ConfigParamPreprocessor).isTrue();
    }

    @Test
    void shouldMigrateEncryptedEnvironmentVariablesWithNewlineAndSpaces_XslMigrationFrom88To90(ResetCipher resetCipher) throws Exception {
        resetCipher.setupDESCipherFile();

        String plainText = "user-password!";
        // "user-password!" encrypted using the above key
        String encryptedValue = "mvcX9yrQsM4iPgm1tDxN1A==";
        String encryptedValueWithWhitespaceAndNewline = new StringBuilder(encryptedValue).insert(2, "\r\n" +
                "                        ").toString();

        String content = configWithPipeline(
                ("""
                        <pipeline name='some_pipeline'>
                        <environmentvariables>
                                <variable name="var_name" secure="true"><encryptedValue>%s</encryptedValue></variable>
                              </environmentvariables>
                            <materials>
                              <svn url='svnurl'/>
                            </materials>
                          <stage name='some_stage'>
                            <jobs>
                              <job name='some_job'>
                                    <tasks>
                                      <exec command="echo">
                                        <runif status="passed" />
                                      </exec>
                                    </tasks>
                                  </job>
                            </jobs>
                          </stage>
                        </pipeline>""").formatted(encryptedValueWithWhitespaceAndNewline), 88);

        CruiseConfig config = ConfigMigrator.loadWithMigration(content).config;
        assertThat(config.allPipelines().get(0).getVariables().get(0).getValue()).isEqualTo(plainText);
        assertThat(config.allPipelines().get(0).getVariables().get(0).getEncryptedValue()).startsWith("AES:");
    }

    @Test
    void shouldMigrateEncryptedPluginPropertyValueWithNewlineAndSpaces_XslMigrationFrom88To90(ResetCipher resetCipher) throws Exception {
        resetCipher.setupDESCipherFile();

        String plainText = "user-password!";
        // "user-password!" encrypted using the above key
        String encryptedValue = "mvcX9yrQsM4iPgm1tDxN1A==";
        String encryptedValueWithWhitespaceAndNewline = new StringBuilder(encryptedValue).insert(2, "\r\n" +
                "                        ").toString();

        String content = configWithPluggableScm(
                ("""
                        <scm id="f7c309f5-ea4d-41c5-9c43-95d79fa9ec7b" name="gocd-private">
                              <pluginConfiguration id="github.pr" version="1" />
                              <configuration>
                                <property>
                                  <key>plainTextKey</key>
                                  <value>https://url/some_path</value>
                                </property>
                                <property>
                                  <key>secureKey</key>
                                  <encryptedValue>%s</encryptedValue>
                                </property>
                              </configuration>
                            </scm>""").formatted(encryptedValueWithWhitespaceAndNewline), 88);

        CruiseConfig config = ConfigMigrator.loadWithMigration(content).config;
        assertThat(config.getSCMs().get(0).getConfiguration().getProperty("secureKey").getValue()).isEqualTo(plainText);
        assertThat(config.getSCMs().get(0).getConfiguration().getProperty("secureKey").getEncryptedValue()).startsWith("AES:");
        assertThat(config.getSCMs().get(0).getConfiguration().getProperty("plainTextKey").getValue()).isEqualTo("https://url/some_path");
    }

    @Test
    void shouldMigrateEncryptedMaterialPasswordWithNewlineAndSpaces_XslMigrationFrom88To90(ResetCipher resetCipher) throws Exception {
        resetCipher.setupDESCipherFile();

        String plainText = "user-password!";
        // "user-password!" encrypted using the above key
        String encryptedValue = "mvcX9yrQsM4iPgm1tDxN1A==";
        String encryptedValueWithWhitespaceAndNewline = new StringBuilder(encryptedValue).insert(2, "\r\n" +
                "                        ").toString();

        String content = configWithPipeline(
                ("""
                        <pipeline name='some_pipeline'>
                            <materials>
                              <svn url='asdsa' username='user' encryptedPassword='%s' dest='svn'>
                        <filter>
                                    <ignore pattern='**/*' />
                                  </filter>
                        </svn>
                        <tfs url='tfsurl' username='user' domain='domain' encryptedPassword='%s' projectPath='path' dest='tfs' />
                        <p4 port='host:9999' username='user' encryptedPassword='%s' dest='perforce'>
                                  <view><![CDATA[view]]></view>
                                </p4>
                            </materials>
                          <stage name='some_stage'>
                            <jobs>
                                <job name="some_job">
                                    <tasks>
                                      <exec command="echo">
                                        <runif status="passed" />
                                      </exec>
                                    </tasks>
                                  </job>
                            </jobs>
                          </stage>
                        </pipeline>""").formatted(encryptedValueWithWhitespaceAndNewline, encryptedValueWithWhitespaceAndNewline, encryptedValueWithWhitespaceAndNewline), 88);

        CruiseConfig config = ConfigMigrator.loadWithMigration(content).config;
        MaterialConfigs materialConfigs = config.allPipelines().get(0).materialConfigs();
        SvnMaterialConfig svnMaterialConfig = (SvnMaterialConfig) materialConfigs.get(0);
        assertThat(svnMaterialConfig.getPassword()).isEqualTo(plainText);
        assertThat(svnMaterialConfig.getEncryptedPassword()).startsWith("AES:");
        assertThat(svnMaterialConfig.getFilterAsString()).isEqualTo("**/*");
        TfsMaterialConfig tfs = (TfsMaterialConfig) materialConfigs.get(1);
        assertThat(tfs.getPassword()).isEqualTo(plainText);
        assertThat(tfs.getEncryptedPassword()).startsWith("AES:");
        assertThat(tfs.getUrl()).isEqualTo("tfsurl");
        P4MaterialConfig p4 = (P4MaterialConfig) materialConfigs.get(2);
        assertThat(p4.getPassword()).isEqualTo(plainText);
        assertThat(p4.getEncryptedPassword()).startsWith("AES:");
        assertThat(p4.getServerAndPort()).isEqualTo("host:9999");
    }

    @Test
    void shouldMigrateServerMailHostEncryptedPasswordWithNewlineAndSpaces_XslMigrationFrom88To90(ResetCipher resetCipher) throws Exception {
        resetCipher.setupDESCipherFile();

        String plainText = "user-password!";
        // "user-password!" encrypted using the above key
        String encryptedValue = "mvcX9yrQsM4iPgm1tDxN1A==";
        String encryptedValueWithWhitespaceAndNewline = new StringBuilder(encryptedValue).insert(2, "\r\n" +
                "                        ").toString();

        String content = config(
                ("""
                        <server>
                            <mailhost hostname='host' port='25' username='user' encryptedPassword='%s' tls='false' from='user@domain.com' admin='admin@domain.com' />
                          </server>""").formatted(encryptedValueWithWhitespaceAndNewline), 88);

        CruiseConfig config = ConfigMigrator.loadWithMigration(content).config;
        assertThat(config.server().mailHost().getPassword()).isEqualTo(plainText);
        assertThat(config.server().mailHost().getEncryptedPassword()).startsWith("AES:");
        assertThat(config.server().mailHost().getHostName()).isEqualTo("host");
    }

    @Test
    void shouldFailValidationForPipelineWithDuplicateStageNames() {
        assertFailureDuringLoad(PIPELINES_WITH_DUPLICATE_STAGE_NAME,
                RuntimeException.class, "You have defined multiple stages called 'mingle'. Stage names are case-insensitive and must be unique."
        );
    }

    @Test
    void shouldThrowExceptionIfBuildPlansExistWithTheSameNameWithinAPipeline() {
        assertXsdFailureDuringLoad(JOBS_WITH_SAME_NAME,
                "Duplicate unique value [unit] declared for identity constraint of element \"jobs\".",
                "Duplicate unique value [unit] declared for identity constraint \"uniqueJob\" of element \"jobs\"."
        );
    }

    @Test
    void shouldThrowExceptionIfPipelineDoesNotContainAnyBuildPlans() {
        assertXsdFailureDuringLoad(STAGE_WITH_NO_JOBS,
                "The content of element 'jobs' is not complete. One of '{job}' is expected.");
    }

    @Test
    void shouldAllowOnlyThreeValuesForLockBehavior() throws Exception {
        xmlLoader.loadConfigHolder(pipelineWithAttributes("name=\"p1\" lockBehavior=\"" + LOCK_VALUE_LOCK_ON_FAILURE + "\"", CONFIG_SCHEMA_VERSION));
        xmlLoader.loadConfigHolder(pipelineWithAttributes("name=\"p2\" lockBehavior=\"" + LOCK_VALUE_UNLOCK_WHEN_FINISHED + "\"", CONFIG_SCHEMA_VERSION));
        xmlLoader.loadConfigHolder(pipelineWithAttributes("name=\"p3\" lockBehavior=\"" + LOCK_VALUE_NONE + "\"", CONFIG_SCHEMA_VERSION));
        xmlLoader.loadConfigHolder(pipelineWithAttributes("name=\"pipelineWithNoLockBehaviorDefined\"", CONFIG_SCHEMA_VERSION));

        assertXsdFailureDuringLoad(pipelineWithAttributes("name=\"pipelineWithWrongLockBehavior\" lockBehavior=\"some-random-value\"", CONFIG_SCHEMA_VERSION),
                "Value 'some-random-value' is not facet-valid with respect to enumeration '[lockOnFailure, unlockWhenFinished, none]'. It must be a value from the enumeration.");
    }

    @Test
    void shouldDisallowModificationOfTokenGenerationKeyWhileTheServerIsOnline() throws Exception {
        xmlLoader.loadConfigHolder(configWithTokenGenerationKey("something"));

        systemEnvironment.setProperty("go.enforce.server.immutability", "Y");
        assertFailureDuringLoad(configWithTokenGenerationKey("something-else"), RuntimeException.class, "The value of 'tokenGenerationKey' cannot be modified while the server is online. If you really want to make this change, you may do so while the server is offline. Please note: updating 'tokenGenerationKey' will invalidate all registration tokens issued to the agents so far.");
    }

    private String configWithTokenGenerationKey(final String key) {
        final ServerIdImmutabilityValidator serverIdImmutabilityValidator = (ServerIdImmutabilityValidator) MagicalGoConfigXmlLoader.VALIDATORS.stream().filter(goConfigValidator -> goConfigValidator instanceof ServerIdImmutabilityValidator).findFirst().orElseThrow();
        return ("""
                <?xml version="1.0" encoding="UTF-8"?><cruise schemaVersion="%d">
                <server serverId="%s" tokenGenerationKey="%s"/>
                <pipelines>
                </pipelines>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION, serverIdImmutabilityValidator.getInitialServerId(), key);
    }

    @Test
    void shouldDeserializeArtifactStores() {
        String configXml = ("""
                <cruise schemaVersion='%d'>
                <artifactStores>
                    <artifactStore pluginId="foo" id="bar">
                        <property>
                            <key>ACCESS_KEY</key>
                            <value>dasdas</value>
                        </property>
                    </artifactStore>
                    <artifactStore pluginId="bar" id="foo">
                        <property>
                            <key>SECRET_ACCESS_KEY</key>
                            <value>$rrhsdhjf</value>
                        </property>
                    </artifactStore>
                </artifactStores>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        final CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(configXml).configForEdit;
        assertThat(cruiseConfig.getArtifactStores()).hasSize(2);
        assertThat(cruiseConfig.getArtifactStores()).containsExactly(new ArtifactStore("bar", "foo", create("ACCESS_KEY", false, "dasdas")), new ArtifactStore("foo", "bar", create("SECRET_ACCESS_KEY", false, "$rrhsdhjf")));
    }

    @Test
    void shouldNotDeserializeArtifactStoreWhenIdIsNotDefined() {
        String configXml = ("""
                <cruise schemaVersion='%d'>
                <artifactStores>
                    <artifactStore pluginId="foo">
                        <property>
                            <key>ACCESS_KEY</key>
                            <value>dasdas</value>
                        </property>
                    </artifactStore>
                </artifactStores>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(configXml))
            .hasMessage("\"Id\" is required for ArtifactStore");
    }

    @Test
    void shouldNotDeserializeArtifactStoreWhenPluginIdIsNotDefined() {
        String configXml = ("""
                <cruise schemaVersion='%d'>
                <artifactStores>
                    <artifactStore id="foo">
                        <property>
                            <key>ACCESS_KEY</key>
                            <value>dasdas</value>
                        </property>
                    </artifactStore>
                </artifactStores>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(configXml))
            .hasMessage("\"Plugin id\" is required for ArtifactStore");
    }

    @Test
    void shouldDeserializePluggableArtifactConfig() {
        String configXml = ("""
                <cruise schemaVersion='%d'>
                <artifactStores>
                    <artifactStore pluginId="cd.go.s3" id="s3">
                        <property>
                            <key>ACCESS_KEY</key>
                            <value>dasdas</value>
                        </property>
                    </artifactStore>
                    <artifactStore pluginId="bar" id="foo">
                        <property>
                            <key>SECRET_ACCESS_KEY</key>
                            <value>$rrhsdhjf</value>
                        </property>
                    </artifactStore>
                </artifactStores>
                <pipelines group="first">
                    <pipeline name="up42">
                      <materials>
                        <git url="test-repo" />
                      </materials>
                      <stage name="up42_stage">
                        <jobs>
                          <job name="up42_job">
                            <tasks>
                              <exec command="ls">
                                <runif status="passed" />
                              </exec>
                            </tasks>
                            <artifacts>
                              <artifact id="installer" storeId="s3" type="external">
                               <configuration>
                                <property>
                                  <key>filename</key>
                                  <value>foo.xml</value>
                                </property>
                               </configuration>
                              </artifact>
                            </artifacts>
                          </job>
                        </jobs>
                      </stage>
                    </pipeline>
                  </pipelines>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        final CruiseConfig cruiseConfig = ConfigMigrator.loadWithMigration(configXml).configForEdit;
        final ArtifactTypeConfigs artifactTypeConfigs = cruiseConfig.pipelineConfigByName(
                        new CaseInsensitiveString("up42")).getStage("up42_stage")
                .getJobs().getJob(new CaseInsensitiveString("up42_job")).artifactTypeConfigs();

        assertThat(artifactTypeConfigs).hasSize(1);
        assertThat(artifactTypeConfigs).containsExactly(new PluggableArtifactConfig("installer", "s3", create("filename", false, "foo.xml")));
    }

    @Test
    void shouldNotDeserializePluggableArtifactConfigWhenIdIsNotDefined() {
        String configXml = ("""
                <cruise schemaVersion='%d'>
                <artifactStores>
                    <artifactStore pluginId="cd.go.s3" id="s3">
                        <property>
                            <key>ACCESS_KEY</key>
                            <value>dasdas</value>
                        </property>
                    </artifactStore>
                    <artifactStore pluginId="bar" id="foo">
                        <property>
                            <key>SECRET_ACCESS_KEY</key>
                            <value>$rrhsdhjf</value>
                        </property>
                    </artifactStore>
                </artifactStores>
                <pipelines group="first">
                    <pipeline name="up42">
                      <materials>
                        <git url="test-repo" />
                      </materials>
                      <stage name="up42_stage">
                        <jobs>
                          <job name="up42_job">
                            <tasks>
                              <exec command="ls">
                                <runif status="passed" />
                              </exec>
                            </tasks>
                            <artifacts>
                              <artifact type="external" storeId="s3">
                               <configuration>
                                <property>
                                  <key>filename</key>
                                  <value>foo.xml</value>
                                </property>
                               </configuration>
                              </artifact>
                            </artifacts>
                          </job>
                        </jobs>
                      </stage>
                    </pipeline>
                  </pipelines>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);


        assertThatThrownBy(() -> ConfigMigrator.loadWithMigration(configXml))
            .hasMessageContaining("\"Id\" is required for PluggableArtifact");
    }

    @Test
    void shouldNotDeserializePluggableArtifactConfigWhenStoreIdIsNotDefined() {
        String configXml = ("""
                <cruise schemaVersion='%d'>
                <artifactStores>
                    <artifactStore pluginId="cd.go.s3" id="s3">
                        <property>
                            <key>ACCESS_KEY</key>
                            <value>dasdas</value>
                        </property>
                    </artifactStore>
                    <artifactStore pluginId="bar" id="foo">
                        <property>
                            <key>SECRET_ACCESS_KEY</key>
                            <value>$rrhsdhjf</value>
                        </property>
                    </artifactStore>
                </artifactStores>
                <pipelines group="first">
                    <pipeline name="up42">
                      <materials>
                        <git url="test-repo" />
                      </materials>
                      <stage name="up42_stage">
                        <jobs>
                          <job name="up42_job">
                            <tasks>
                              <exec command="ls">
                                <runif status="passed" />
                              </exec>
                            </tasks>
                            <artifacts>
                              <artifact type="external" id="installer">
                               <configuration>
                                <property>
                                  <key>filename</key>
                                  <value>foo.xml</value>
                                </property>
                               </configuration>
                              </artifact>
                            </artifacts>
                          </job>
                        </jobs>
                      </stage>
                    </pipeline>
                  </pipelines>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> ConfigMigrator.loadWithMigration(configXml))
            .hasMessageContaining("\"Store id\" is required for PluggableArtifact");
    }

    @Test
    void shouldNotDeserializePluggableArtifactConfigWhenStoreWithIdNotFound() {
        String configXml = ("""
                <cruise schemaVersion='%d'>
                <pipelines group="first">
                    <pipeline name="up42">
                      <materials>
                        <git url="test-repo" />
                      </materials>
                      <stage name="up42_stage">
                        <jobs>
                          <job name="up42_job">
                            <tasks>
                              <exec command="ls">
                                <runif status="passed" />
                              </exec>
                            </tasks>
                            <artifacts>
                              <artifact type="external" id="installer" storeId="s3">
                               <configuration>
                                <property>
                                  <key>filename</key>
                                  <value>foo.xml</value>
                                </property>
                               </configuration>
                              </artifact>
                            </artifacts>
                          </job>
                        </jobs>
                      </stage>
                    </pipeline>
                  </pipelines>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);


        assertThatThrownBy(() -> ConfigMigrator.loadWithMigration(configXml))
            .hasMessageContaining("Artifact store with id `s3` does not exist");
    }

    @Test
    void shouldMigrateDESEncryptedEnvironmentVariables_XslMigrationFrom108To109(ResetCipher resetCipher) throws Exception {
        resetCipher.setupDESCipherFile();

        String clearText = "user-password!";
        // "user-password!" encrypted using the above key
        String desEncryptedPassword = "mvcX9yrQsM4iPgm1tDxN1A==";

        String content = configWithPipeline(
                ("""
                        <pipeline name='some_pipeline'>
                          <environmentvariables>
                            <variable name='var_name' secure='true'>
                              <encryptedValue>%s</encryptedValue>
                            </variable>
                           </environmentvariables>
                            <materials>
                              <svn url='svnurl'/>
                            </materials>
                          <stage name='some_stage'>
                            <jobs>
                              <job name='some_job'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""").formatted(desEncryptedPassword), 108);

        CruiseConfig config = ConfigMigrator.loadWithMigration(content).config;
        assertThat(config.allPipelines().get(0).getVariables().get(0).getValue()).isEqualTo(clearText);
        String encryptedValue = config.allPipelines().get(0).getVariables().get(0).getEncryptedValue();
        assertThat(encryptedValue).startsWith("AES:");
        assertThat(new AESEncrypter(new AESCipherProvider(systemEnvironment)).decrypt(encryptedValue)).isEqualTo("user-password!");
    }

    @Test
    void shouldRemoveEmptySCMPasswordAndEncryptedPasswordAttributes_XslMigrationFrom109To110(ResetCipher resetCipher) throws Exception {
        resetCipher.setupDESCipherFile();

        String content = configWithPipeline(
                """
                        <pipeline name='some_pipeline'>
                            <materials>
                              <svn url='svn1' username='bob' encryptedPassword='' dest='svn1'/>
                              <svn url='svn2' username='bob' password='' dest='svn2'/>
                              <tfs url='tfsurl1' username='user' domain='domain' encryptedPassword='' projectPath='path' dest='tfs1' />
                              <tfs url='tfsurl2' username='user' domain='domain' password='' projectPath='path' dest='tfs2' />
                              <p4 port='host:9999' username='user' encryptedPassword='' dest='perforce1'>
                                  <view><![CDATA[view]]></view>
                                </p4>
                              <p4 port='host:9999' username='user' password='' dest='perforce2'>
                                  <view><![CDATA[view]]></view>
                                </p4>
                            </materials>
                          <stage name='some_stage'>
                            <jobs>
                              <job name='some_job'>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""", 109);

        assertThat(XpathUtils.nodeExists(content, "//*[@password='']")).isTrue();
        assertThat(XpathUtils.nodeExists(content, "//*[@encryptedPassword='']")).isTrue();


        CruiseConfig config = ConfigMigrator.loadWithMigration(content).config;

        String xmlPartial = xmlWriter.toXmlPartial(config);

        assertThat(XpathUtils.nodeExists(xmlPartial, "//@password")).isFalse();
        assertThat(XpathUtils.nodeExists(xmlPartial, "//@encryptedPassword")).isFalse();
    }

    @Test
    void shouldMigrateDESEncryptedPluginPropertyValue_XslMigrationFrom108To109(ResetCipher resetCipher) throws Exception {
        resetCipher.setupDESCipherFile();

        String clearText = "user-password!";
        // "user-password!" encrypted using the above key
        String desEncryptedPassword = "mvcX9yrQsM4iPgm1tDxN1A==";

        String content = configWithPluggableScm(
                ("""
                          <scm id='f7c309f5-ea4d-41c5-9c43-95d79fa9ec7b' name='gocd-private'>
                              <pluginConfiguration id='github.pr' version='1' />
                              <configuration>
                                <property>
                                  <key>plainTextKey</key>
                                  <value>https://url/some_path</value>
                                </property>
                                <property>
                                  <key>secureKey</key>
                                  <encryptedValue>%s</encryptedValue>
                                </property>
                              </configuration>
                            </scm>""").formatted(desEncryptedPassword), 108);

        CruiseConfig config = ConfigMigrator.loadWithMigration(content).config;
        assertThat(config.getSCMs().get(0).getConfiguration().getProperty("secureKey").getValue()).isEqualTo(clearText);
        String encryptedValue = config.getSCMs().get(0).getConfiguration().getProperty("secureKey").getEncryptedValue();

        assertThat(encryptedValue).startsWith("AES:");
        assertThat(new AESEncrypter(new AESCipherProvider(systemEnvironment)).decrypt(encryptedValue)).isEqualTo("user-password!");

        assertThat(config.getSCMs().get(0).getConfiguration().getProperty("plainTextKey").getValue()).isEqualTo("https://url/some_path");
    }

    @Test
    void shouldMigrateDESEncryptedMaterialPassword_XslMigrationFrom108To109(ResetCipher resetCipher) throws Exception {
        resetCipher.setupDESCipherFile();

        String clearText = "user-password!";
        // "user-password!" encrypted using the above key
        String desEncryptedPassword = "mvcX9yrQsM4iPgm1tDxN1A==";

        String content = configWithPipeline(
                ("""
                        <pipeline name='some_pipeline'>
                            <materials>
                              <svn url='asdsa' username='user' encryptedPassword='%s' dest='svn'/>
                              <tfs url='tfsurl' username='user' domain='domain' encryptedPassword='%s' projectPath='path' dest='tfs' />
                              <p4 port='host:9999' username='user' encryptedPassword='%s' dest='perforce'>
                                  <view><![CDATA[view]]></view>
                                </p4>
                            </materials>
                          <stage name='some_stage'>
                            <jobs>
                              <job name='some_job'><tasks><exec command='echo'><runif status='passed' /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>""").formatted(desEncryptedPassword, desEncryptedPassword, desEncryptedPassword), 108);

        CruiseConfig config = ConfigMigrator.loadWithMigration(content).config;
        MaterialConfigs materialConfigs = config.allPipelines().get(0).materialConfigs();
        SvnMaterialConfig svnMaterialConfig = (SvnMaterialConfig) materialConfigs.get(0);
        assertThat(svnMaterialConfig.getPassword()).isEqualTo(clearText);
        assertThat(svnMaterialConfig.getEncryptedPassword()).startsWith("AES:");
        TfsMaterialConfig tfs = (TfsMaterialConfig) materialConfigs.get(1);
        assertThat(tfs.getPassword()).isEqualTo(clearText);
        assertThat(tfs.getEncryptedPassword()).startsWith("AES:");
        assertThat(tfs.getUrl()).isEqualTo("tfsurl");
        P4MaterialConfig p4 = (P4MaterialConfig) materialConfigs.get(2);
        assertThat(p4.getPassword()).isEqualTo(clearText);
        assertThat(p4.getEncryptedPassword()).startsWith("AES:");
        assertThat(p4.getServerAndPort()).isEqualTo("host:9999");
    }

    @Test
    void shouldMigrateDESServerMailHostEncryptedPassword_XslMigrationFrom108To109(ResetCipher resetCipher) throws Exception {
        resetCipher.setupDESCipherFile();

        String clearText = "user-password!";
        // "user-password!" encrypted using the above key
        String desEncryptedPassword = "mvcX9yrQsM4iPgm1tDxN1A==";

        String content = config(
                ("""
                        <server>
                            <mailhost hostname='host' port='25' username='user' encryptedPassword='%s' tls='false' from='user@domain.com' admin='admin@domain.com' />
                          </server>""").formatted(desEncryptedPassword), 108);

        CruiseConfig config = ConfigMigrator.loadWithMigration(content).config;
        assertThat(config.server().mailHost().getPassword()).isEqualTo(clearText);
        assertThat(config.server().mailHost().getEncryptedPassword()).startsWith("AES:");
        assertThat(config.server().mailHost().getHostName()).isEqualTo("host");
    }

    @Test
    void shouldEncryptPluggablePublishArtifactProperties() throws Exception {
        PluginDescriptor pluginDescriptor = GoPluginDescriptor.builder().id("cd.go.artifact.docker.registry").version("1.0").build();
        ArtifactPluginInfo artifactPluginInfo = buildArtifactPluginInfo(pluginDescriptor);
        ArtifactMetadataStore.instance().setPluginInfo(artifactPluginInfo);

        String content = goConfigMigration.upgradeIfNecessary(TestFileUtil.resourceToString("/data/pluggable_artifacts_with_params.xml"));

        CruiseConfig config = xmlLoader.loadConfigHolder(content).configForEdit;
        PipelineConfig ancestor = config.pipelineConfigByName(new CaseInsensitiveString("ancestor"));
        PipelineConfig parent = config.pipelineConfigByName(new CaseInsensitiveString("parent"));
        PipelineConfig child = config.pipelineConfigByName(new CaseInsensitiveString("child"));

        Configuration ancestorPublishArtifactConfig = ancestor.get(0).getJobs().first().artifactTypeConfigs().getPluggableArtifactConfigs().get(0).getConfiguration();
        Configuration parentPublishArtifactConfig = parent.get(0).getJobs().first().artifactTypeConfigs().getPluggableArtifactConfigs().get(0).getConfiguration();
        Configuration childFetchArtifactFromAncestorConfig = ((FetchPluggableArtifactTask) child.get(0).getJobs().first().tasks().get(0)).getConfiguration();
        Configuration childFetchArtifactFromParentConfig = ((FetchPluggableArtifactTask) child.get(0).getJobs().first().tasks().get(1)).getConfiguration();
        ArtifactStore dockerhubStore = config.getArtifactStores().first();

        assertConfigProperty(ancestorPublishArtifactConfig, "Image", "IMAGE_SECRET", true);
        assertConfigProperty(ancestorPublishArtifactConfig, "Tag", "ancestor_tag_${GO_PIPELINE_COUNTER}", false);

        assertConfigProperty(parentPublishArtifactConfig, "Image", "IMAGE_SECRET", true);
        assertConfigProperty(parentPublishArtifactConfig, "Tag", "parent_tag_${GO_PIPELINE_COUNTER}", false);


        assertConfigProperty(childFetchArtifactFromAncestorConfig, "FetchProperty", "SECRET", true);
        assertConfigProperty(childFetchArtifactFromAncestorConfig, "Tag", "ancestor_tag", false);

        assertConfigProperty(childFetchArtifactFromParentConfig, "FetchProperty", "SECRET", true);
        assertConfigProperty(childFetchArtifactFromParentConfig, "Tag", "parent_tag", false);

        assertConfigProperty(dockerhubStore, "RegistryURL", "https://index.docker.io/v1/", false);
        assertConfigProperty(dockerhubStore, "Username", "docker-user", false);
        assertConfigProperty(dockerhubStore, "Password", "SECRET", true);
    }

    private static ArtifactPluginInfo buildArtifactPluginInfo(PluginDescriptor pluginDescriptor) {
        PluginConfiguration buildFile = new PluginConfiguration("BuildFile", new Metadata(false, false));
        PluginConfiguration image = new PluginConfiguration("Image", new Metadata(false, true));
        PluginConfiguration tag = new PluginConfiguration("Tag", new Metadata(false, false));
        PluginConfiguration fetchProperty = new PluginConfiguration("FetchProperty", new Metadata(false, true));
        PluginConfiguration fetchTag = new PluginConfiguration("Tag", new Metadata(false, false));
        PluginConfiguration registryUrl = new PluginConfiguration("RegistryURL", new Metadata(true, false));
        PluginConfiguration username = new PluginConfiguration("Username", new Metadata(false, false));
        PluginConfiguration password = new PluginConfiguration("Password", new Metadata(false, true));
        PluggableInstanceSettings storeConfigSettings = new PluggableInstanceSettings(List.of(registryUrl, username, password));
        PluggableInstanceSettings publishArtifactSettings = new PluggableInstanceSettings(List.of(buildFile, image, tag));
        PluggableInstanceSettings fetchArtifactSettings = new PluggableInstanceSettings(List.of(fetchProperty, fetchTag));
        return new ArtifactPluginInfo(pluginDescriptor, storeConfigSettings, publishArtifactSettings, fetchArtifactSettings, null, new Capabilities());
    }

    @Test
    void shouldLoadSecretConfigs() {
        String content = config(
                """
                        <secretConfigs>
                        <secretConfig id="my_secret" pluginId="gocd_file_based_plugin">
                            <description>All secrets for env1</description>
                            <configuration>
                               <property>
                                   <key>PasswordFilePath</key>
                                   <value>/godata/config/password.properties</value>
                               </property>
                            </configuration>
                            <rules>
                                <deny action="refer" type="pipeline_group">my_group</deny>
                                <allow action="refer" type="pipeline_group">other_group</allow> \s
                            </rules>
                        </secretConfig>
                        </secretConfigs>""", 116);

        CruiseConfig config = ConfigMigrator.load(content);
        SecretConfigs secretConfigs = config.getSecretConfigs();
        assertThat(secretConfigs.size()).isEqualTo(1);

        SecretConfig secretConfig = secretConfigs.first();
        assertThat(secretConfig.getId()).isEqualTo("my_secret");
        assertThat(secretConfig.getPluginId()).isEqualTo("gocd_file_based_plugin");
        assertThat(secretConfig.getDescription()).isEqualTo("All secrets for env1");

        Configuration configuration = secretConfig.getConfiguration();
        assertThat(configuration.size()).isEqualTo(1);
        assertThat(configuration.getProperty("PasswordFilePath").getValue()).isEqualTo("/godata/config/password.properties");

        Rules rules = secretConfig.getRules();
        assertThat(rules.size()).isEqualTo(2);
        assertThat(rules).containsExactly(new Deny("refer", "pipeline_group", "my_group"), new Allow("refer", "pipeline_group", "other_group"));

    }

    @Test
    void shouldNotAllowMoreThanOneOnCancelTaskWhenDefined() {
        String xml = ("""
                <cruise schemaVersion='%d'>
                <server>
                    <artifacts>
                      <artifactsDir>artifactsDir</artifactsDir>
                    </artifacts>
                </server>
                <pipelines>
                <pipeline name='pipeline1' template='abc'>
                    <materials>
                      <svn url ='svnurl' username='foo' password='password'/>
                    </materials>
                </pipeline>
                </pipelines>
                <templates>
                  <pipeline name='abc'>
                    <stage name='stage1'>
                      <jobs>
                        <job name='job1'>
                         <tasks>
                             <exec command="rake">
                                 <arg>all_test</arg>
                                 <oncancel>
                                     <ant target='kill' />
                                     <ant target='kill' />
                                 </oncancel>
                             </exec>
                         </tasks>
                        </job>
                      </jobs>
                    </stage>
                  </pipeline>
                </templates>
                </cruise>""").formatted(CONFIG_SCHEMA_VERSION);

        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(xml))
                .hasMessage("Invalid content was found starting with element 'ant'. No child element is expected at this point.");
    }

    @Test
    void shouldLoadHgConfigWithBranchAttributePostSchemaVersion123() throws Exception {
        String content = config(
                """
                        <config-repos>
                            <config-repo id="Test" pluginId="cd.go.json">
                                <hg url="https://domain.com" branch="feature" />
                             </config-repo>
                        </config-repos>
                        <pipelines group="first">
                            <pipeline name="Test" template="test_template">
                              <materials>
                                  <hg url="https://domain.com" branch="feature" />
                              </materials>
                             </pipeline>
                        </pipelines>
                        <templates>
                            <pipeline name="test_template">
                              <stage name="Functional">
                                <jobs>
                                  <job name="Functional">
                                    <tasks>
                                      <exec command="echo" args="Hello World!!!" />
                                    </tasks>
                                   </job>
                                </jobs>
                              </stage>
                            </pipeline>
                        </templates>""", CONFIG_SCHEMA_VERSION);

        CruiseConfig config = xmlLoader.loadConfigHolder(content).config;

        PipelineConfig pipelineConfig = config.getPipelineConfigByName(new CaseInsensitiveString("Test"));
        assertThat(pipelineConfig.materialConfigs()).hasSize(1);
        assertThat(((HgMaterialConfig) pipelineConfig.materialConfigs().get(0)).getBranch()).isEqualTo("feature");

        assertThat(config.getConfigRepos()).hasSize(1);
        assertThat(((HgMaterialConfig) config.getConfigRepos().get(0).getRepo()).getBranch()).isEqualTo("feature");

    }

    @Test
    void shouldLoadRulesConfigWhereActionAndTypeHasWildcardForSchemaVersion124() throws Exception {
        String content = config(
                """
                        <secretConfigs>
                         <secretConfig id="example" pluginId="vault_based_plugin">
                          <description>All secrets for env1</description>
                          <configuration>
                           <property>
                              <key>path</key>
                             <value>secret/dev/teamA</value>
                           </property>
                          </configuration>
                          <rules>
                           <deny action="*" type="environment">up42</deny> \s
                           <deny action="refer" type="*">up43</deny> \s
                          </rules>
                         </secretConfig>
                        </secretConfigs>""", CONFIG_SCHEMA_VERSION);

        CruiseConfig config = xmlLoader.loadConfigHolder(content).config;

        SecretConfig secretConfig = config.getSecretConfigs().find("example");

        assertThat(secretConfig.getRules().first().action()).isEqualTo("*");
        assertThat(secretConfig.getRules().get(1).type()).isEqualTo("*");
    }

    @Test
    void shouldLoadAllowOnlySuccessOnManualApprovalType() throws Exception {
        Approval approval = xmlLoader.fromXmlPartial("<approval type=\"manual\" allowOnlyOnSuccess=\"true\" />", Approval.class);

        assertThat(approval.getType()).isEqualTo("manual");
        assertThat(approval.isAllowOnlyOnSuccess()).isEqualTo(true);
    }

    @Test
    void shouldLoadAllowOnlySuccessOnSuccessApprovalType() throws Exception {
        String content = config(
                """
                        <pipelines group="first">
                        <pipeline name="pipeline">
                          <materials>
                            <hg url="/hgrepo"/>
                          </materials>
                          <stage name="mingle">
                            <approval type="success" allowOnlyOnSuccess="true" />
                            <jobs>
                                <job name="functional">
                                    <tasks>
                                      <exec command="echo">
                                        <runif status="passed" />
                                      </exec>
                                    </tasks>
                                </job>
                            </jobs>
                          </stage>
                        </pipeline>
                        </pipelines>""", CONFIG_SCHEMA_VERSION);

        CruiseConfig config = xmlLoader.loadConfigHolder(goConfigMigration.upgradeIfNecessary(content)).config;

        Approval approval = config
                .getPipelineConfigByName(new CaseInsensitiveString("pipeline"))
                .getStage("mingle")
                .getApproval();

        assertThat(approval.getType()).isEqualTo("success");
        assertThat(approval.isAllowOnlyOnSuccess()).isEqualTo(true);
    }

    @Test
    void shouldLoadInvertFilterForScmMaterial() throws Exception {
        String content = config(
                """
                        <scms>
                        <scm id="abcd" name="scm_name">
                          <pluginConfiguration id="GitPathMaterial" version="1" />
                          <configuration>
                            <property>
                              <key>url</key>
                              <value>git@github.com:gocd/gocd.git</value>
                            </property>
                          </configuration>
                        </scm>
                        </scms>
                        <pipelines group="first">
                        <pipeline name="pipeline">
                          <materials>
                            <scm ref="abcd" invertFilter="true"/>
                          </materials>
                          <stage name="stage">
                            <jobs>
                                <job name="functional">
                                    <tasks>
                                      <exec command="echo">
                                        <runif status="passed" />
                                      </exec>
                                    </tasks>
                                </job>
                            </jobs>
                          </stage>
                        </pipeline>
                        </pipelines>""", CONFIG_SCHEMA_VERSION);

        CruiseConfig config = xmlLoader.loadConfigHolder(goConfigMigration.upgradeIfNecessary(content)).config;

        MaterialConfig materialConfig = config
                .getPipelineConfigByName(new CaseInsensitiveString("pipeline"))
                .materialConfigs().get(0);

        assertThat(materialConfig).isInstanceOf(PluggableSCMMaterialConfig.class);
        assertThat(materialConfig.isInvertFilter()).isTrue();
    }

    private void assertConfigProperty(Configuration configuration, String name, String plainTextValue, boolean shouldBeEncrypted) {
        assertThat(configuration.getProperty(name).getValue()).isEqualTo(plainTextValue);
        if (shouldBeEncrypted) {
            assertThat(configuration.getProperty(name).getEncryptedValue()).startsWith("AES");

        } else {
            assertThat(configuration.getProperty(name).getEncryptedValue()).isNull();
        }
    }

    private void assertXsdFailureDuringLoad(String configXML, String... expectedMessages) {
        assertFailureDuringLoad(configXML, XsdValidationException.class, expectedMessages);
    }

    private void assertFailureDuringLoad(String configXML,
                                         Class<?> expectedExceptionClass,
                                         String... expectedMessage) {

        assertThatThrownBy(() -> xmlLoader.loadConfigHolder(configXML))
            .as("Should have failed with an exception of type: " + expectedExceptionClass.getSimpleName())
            .isInstanceOf(expectedExceptionClass)
            .satisfies(t -> assertThat(t.getMessage()).containsAnyOf(expectedMessage));
    }

    private StageConfig stageWithJobResource(String resourceName) {
        StageConfig stage = StageConfigMother.custom("stage", "job");
        JobConfigs configs = stage.allBuildPlans();
        ResourceConfig resourceConfig = new ResourceConfig();
        resourceConfig.setName(resourceName);
        configs.get(0).resourceConfigs().add(resourceConfig);
        return stage;
    }
}
