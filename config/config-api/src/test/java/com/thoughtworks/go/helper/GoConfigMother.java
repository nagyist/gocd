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
package com.thoughtworks.go.helper;

import com.rits.cloning.Cloner;
import com.thoughtworks.go.config.*;
import com.thoughtworks.go.config.materials.Filter;
import com.thoughtworks.go.config.materials.MaterialConfigs;
import com.thoughtworks.go.config.materials.ScmMaterialConfig;
import com.thoughtworks.go.config.materials.dependency.DependencyMaterialConfig;
import com.thoughtworks.go.config.remote.ConfigRepoConfig;
import com.thoughtworks.go.config.remote.ConfigReposConfig;
import com.thoughtworks.go.config.remote.FileConfigOrigin;
import com.thoughtworks.go.domain.label.PipelineLabel;
import com.thoughtworks.go.domain.materials.MaterialConfig;
import com.thoughtworks.go.util.ClonerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static com.thoughtworks.go.config.PipelineConfigs.DEFAULT_GROUP;
import static com.thoughtworks.go.helper.MaterialConfigsMother.git;
import static com.thoughtworks.go.helper.MaterialConfigsMother.svn;

public class GoConfigMother {
    public static final Cloner CLONER = cloner();

    private static Cloner cloner() {
        // Recreated here, because we deliberately not have access to the `GoConfigCloner` from the API
        Cloner instance = ClonerFactory.instance();
        instance.nullInsteadOfClone(BasicCruiseConfig.DO_NOT_CLONE_CLASSES);
        return instance;
    }

    public static <T> T deepClone(T config) {
        return CLONER.deepClone(config);
    }

    public Role createRole(String roleName, String... users) {
        return new RoleConfig(new CaseInsensitiveString(roleName), toRoleUsers(users));
    }

    private RoleUser[] toRoleUsers(String[] users) {
        RoleUser[] roleUsers = new RoleUser[users.length];
        for (int i = 0; i < users.length; i++) {
            roleUsers[i] = new RoleUser(new CaseInsensitiveString(users[i]));
        }
        return roleUsers;
    }

    public void addRole(CruiseConfig cruiseConfig, Role role) {
        cruiseConfig.server().security().addRole(role);
    }

    public void addAdminUserForPipelineGroup(CruiseConfig cruiseConfig, String user, String groupName) {
        PipelineConfigs group = cruiseConfig.getGroups().findGroup(groupName);
        group.getAuthorization().getAdminsConfig().add(new AdminUser(new CaseInsensitiveString(user)));
    }

    public void addAdminRoleForPipelineGroup(CruiseConfig config, String roleName, String groupName) {
        PipelineConfigs group = config.getGroups().findGroup(groupName);
        group.getAuthorization().getAdminsConfig().add(new AdminRole(new CaseInsensitiveString(roleName)));
    }

    public void addRoleAsSuperAdmin(CruiseConfig cruiseConfig, String rolename) {
        AdminsConfig adminsConfig = cruiseConfig.server().security().adminsConfig();
        adminsConfig.addRole(new AdminRole(new CaseInsensitiveString(rolename)));
    }

    public static void enableSecurityWithPasswordFilePlugin(CruiseConfig cruiseConfig) {
        cruiseConfig.server().security().securityAuthConfigs().add(new SecurityAuthConfig("file", "cd.go.authentication.passwordfile"));
    }

    public static CruiseConfig addUserAsSuperAdmin(CruiseConfig config, String adminName) {
        config.server().security().adminsConfig().add(new AdminUser(new CaseInsensitiveString(adminName)));
        return config;
    }

    public void addUserAsViewerOfPipelineGroup(CruiseConfig cruiseConfig, String userName, String groupName) {
        PipelineConfigs group = cruiseConfig.getGroups().findGroup(groupName);
        group.getAuthorization().getViewConfig().add(new AdminUser(new CaseInsensitiveString(userName)));
    }

    public void addRoleAsViewerOfPipelineGroup(CruiseConfig cruiseConfig, String roleName, String groupName) {
        PipelineConfigs group = cruiseConfig.getGroups().findGroup(groupName);
        group.getAuthorization().getViewConfig().add(new AdminRole(new CaseInsensitiveString(roleName)));
    }

    public void addUserAsOperatorOfPipelineGroup(CruiseConfig cruiseConfig, String userName, String groupName) {
        PipelineConfigs group = cruiseConfig.getGroups().findGroup(groupName);
        group.getAuthorization().getOperationConfig().add(new AdminUser(new CaseInsensitiveString(userName)));
    }

    public void addRoleAsOperatorOfPipelineGroup(CruiseConfig cruiseConfig, String roleName, String groupName) {
        PipelineConfigs group = cruiseConfig.getGroups().findGroup(groupName);
        group.getAuthorization().getOperationConfig().add(new AdminRole(new CaseInsensitiveString(roleName)));
    }

    public PipelineConfig addPipeline(CruiseConfig cruiseConfig,
                                      String pipelineName, String stageName, String... buildNames) {
        return addPipelineWithGroup(cruiseConfig, DEFAULT_GROUP, pipelineName, stageName, buildNames);
    }

    public PipelineConfig addPipelineWithGroup(CruiseConfig cruiseConfig, String groupName, String pipelineName, String stageName, String... buildNames) {
        return addPipelineWithGroup(cruiseConfig, groupName, pipelineName,
                new MaterialConfigs(MaterialConfigsMother.mockMaterialConfigs("file:///tmp/foo")),
                stageName, buildNames);
    }

    public PipelineConfig addPipelineWithGroup(CruiseConfig cruiseConfig, String groupName, String pipelineName, MaterialConfigs materialConfigs, String stageName, String... buildNames) {
        return addPipelineWithGroupAndTimer(cruiseConfig, groupName, pipelineName, materialConfigs, stageName, null, buildNames);
    }

    public void addEnvironmentConfig(CruiseConfig config, String envName, String... pipelineNames) {
        BasicEnvironmentConfig env = EnvironmentConfigMother.environment(envName, pipelineNames);
        config.addEnvironment(env);
    }

    /*
        Used in rspecs
     */
    public CruiseConfig cruiseConfigWithPipelineUsingTwoMaterials() {
        final CruiseConfig config = defaultCruiseConfig();
        addPipelineWithGroup(config, "group1", "pipeline1", MaterialConfigsMother.multipleMaterialConfigs(), "stage", "job");
        return config;
    }

    /*
        Used in rspecs
     */
    public PipelineConfig addPipelineWithTemplate(CruiseConfig cruiseConfig, String pipelineName, String templateName,
                                                  String stageName, String... buildNames) {
        PipelineTemplateConfig templateConfig = new PipelineTemplateConfig(new CaseInsensitiveString(templateName), StageConfigMother.custom(stageName, defaultBuildPlans(buildNames)));
        PipelineConfig pipelineConfig = new PipelineConfig(new CaseInsensitiveString(pipelineName), MaterialConfigsMother.mockMaterialConfigs("file:///tmp/foo"));
        pipelineConfig.setTemplateName(new CaseInsensitiveString(templateName));
        cruiseConfig.addTemplate(templateConfig);
        cruiseConfig.addPipeline(DEFAULT_GROUP, pipelineConfig);
        return pipelineConfig;
    }

    public PipelineConfig addPipelineWithGroupAndTimer(CruiseConfig cruiseConfig, String groupName, String pipelineName, MaterialConfigs materialConfigs, String stageName, TimerConfig timer,
                                                       String... buildNames) {
        String cronSpec = timer == null ? null : timer.getTimerSpec();
        boolean shouldTriggerOnlyOnMaterialChanges = timer != null && timer.shouldTriggerOnlyOnChanges();

        StageConfig stageConfig = StageConfigMother.custom(stageName, defaultBuildPlans(buildNames));
        PipelineConfig pipelineConfig = new PipelineConfig(new CaseInsensitiveString(pipelineName), PipelineLabel.COUNT_TEMPLATE, cronSpec, shouldTriggerOnlyOnMaterialChanges, materialConfigs,
                List.of(stageConfig));
        pipelineConfig.setOrigin(new FileConfigOrigin());
        cruiseConfig.addPipeline(groupName, pipelineConfig);
        return pipelineConfig;
    }

    public PipelineConfig addPipeline(CruiseConfig cruiseConfig, String pipelineName, String stageName, MaterialConfigs materialConfigs, String... buildNames) {
        StageConfig stageConfig = StageConfigMother.custom(stageName, defaultBuildPlans(buildNames));
        PipelineConfig pipelineConfig = new PipelineConfig(new CaseInsensitiveString(pipelineName), materialConfigs, stageConfig);
        pipelineConfig.setOrigin(new FileConfigOrigin());
        cruiseConfig.addPipeline(DEFAULT_GROUP, pipelineConfig);
        return pipelineConfig;
    }

    public PipelineConfig addStageToPipeline(CruiseConfig cruiseConfig, String pipelineName, String stageName, String... buildNames) {
        StageConfig stageConfig = StageConfigMother.custom(stageName, defaultBuildPlans(buildNames));
        PipelineConfig pipelineConfig = cruiseConfig.pipelineConfigByName(new CaseInsensitiveString(pipelineName));
        pipelineConfig.add(stageConfig);
        return pipelineConfig;
    }

    public PipelineConfig addStageToPipeline(CruiseConfig cruiseConfig, String pipelineName, String stageName,
                                             int stageindex, String... buildNames) {
        StageConfig stageConfig = StageConfigMother.custom(stageName, defaultBuildPlans(buildNames));
        PipelineConfig pipelineConfig = cruiseConfig.pipelineConfigByName(new CaseInsensitiveString(pipelineName));
        pipelineConfig.add(stageindex, stageConfig);
        return pipelineConfig;
    }

    public void setDependencyOn(CruiseConfig cruiseConfig, PipelineConfig pipelineConfig, String dependsOnPipeline,
                                String dependsOnStage) {
        PipelineConfig config = cruiseConfig.pipelineConfigByName(pipelineConfig.name());
        config.addMaterialConfig(new DependencyMaterialConfig(new CaseInsensitiveString(dependsOnPipeline), new CaseInsensitiveString(dependsOnStage)));
    }

    public BasicCruiseConfig cruiseConfigWithTwoPipelineGroups() {
        final BasicCruiseConfig config = cruiseConfigWithOnePipelineGroup();
        addPipelineWithGroup(config, "group2", "pipeline2", "stage", "job");
        return config;
    }

    public BasicCruiseConfig cruiseConfigWithOnePipelineGroup() {
        final BasicCruiseConfig config = defaultCruiseConfig();
        config.initializeServer();
        addPipelineWithGroup(config, "group1", "pipeline1", "stage", "job");
        return config;
    }

    public static BasicCruiseConfig defaultCruiseConfig() {
        try {
            BasicCruiseConfig cruiseConfig = new BasicCruiseConfig();
            ServerConfig serverConfig = new ServerConfig("artifactsDir", new SecurityConfig());
            serverConfig.ensureTokenGenerationKeyExists();
            cruiseConfig.setServerConfig(serverConfig);
            return cruiseConfig;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static BasicCruiseConfig configWithAutoRegisterKey(String autoregisterKey) {
        try {
            BasicCruiseConfig cruiseConfig = new BasicCruiseConfig();
            ServerConfig serverConfig = new ServerConfig(null, null, 0, 0, null, autoregisterKey);
            cruiseConfig.setServerConfig(serverConfig);
            return cruiseConfig;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    private static JobConfigs defaultBuildPlans(String... planNames) {
        JobConfigs plans = new JobConfigs();
        for (String name : planNames) {
            plans.add(defaultBuildPlan(name));
        }
        return plans;
    }

    private static JobConfig defaultBuildPlan(String name) {
        JobConfig jobConfig = new JobConfig(new CaseInsensitiveString(name), new ResourceConfigs(), new ArtifactTypeConfigs());
        jobConfig.addTask(new AntTask());
        return jobConfig;
    }

    public static BasicCruiseConfig cruiseConfigWithMailHost(MailHost mailHost) {
        final BasicCruiseConfig config = new BasicCruiseConfig();
        ServerConfig serverConfig = new ServerConfig();
        serverConfig.setMailHost(mailHost);
        config.setServerConfig(serverConfig);
        return config;
    }

    public static BasicCruiseConfig configWithPipelines(String... names) {
        final BasicCruiseConfig config = new BasicCruiseConfig();
        config.initializeServer();
        GoConfigMother mother = new GoConfigMother();
        for (String name : names) {
            mother.addPipeline(config, name, "stage", "job");
        }
        return config;
    }

    public static PipelineConfig createPipelineConfigWithMaterialConfig(MaterialConfig... materialConfigs) {
        return createPipelineConfigWithMaterialConfig("pipeline", materialConfigs);
    }

    public static PipelineConfig createPipelineConfigWithMaterialConfig(String pipelineName, MaterialConfig... materialConfigs) {
        CruiseConfig config = new BasicCruiseConfig();
        MaterialConfigs toAdd = new MaterialConfigs();
        toAdd.addAll(List.of(materialConfigs));
        return new GoConfigMother().addPipeline(config, pipelineName, "stage", toAdd, "job");
    }

    public static PipelineConfig createPipelineConfig(Filter filter, ScmMaterialConfig... materialConfigs) {
        for (ScmMaterialConfig scmMaterialConfig : materialConfigs) {
            scmMaterialConfig.setFilter(filter);
        }
        return createPipelineConfigWithMaterialConfig(materialConfigs);
    }

    public static CruiseConfig pipelineHavingJob(String pipelineName, String stageName, String jobPlanName, String filePath, String directoryPath) {
        CruiseConfig config = new BasicCruiseConfig();
        config.server().setArtifactsDir("logs");
        JobConfig job = new JobConfig(jobPlanName);
        String workingDir = new File("testdata/" + CruiseConfig.WORKING_BASE_DIR + stageName).getPath();
        AntTask task = new AntTask();
        task.setWorkingDirectory(workingDir);
        job.addTask(task);

        final BuildArtifactConfig artifactFile = new BuildArtifactConfig();
        artifactFile.setSource(filePath);
        job.artifactTypeConfigs().add(artifactFile);

        BuildArtifactConfig artifactDir = new BuildArtifactConfig();
        artifactFile.setSource(directoryPath);
        job.artifactTypeConfigs().add(artifactDir);

        PipelineConfig pipelineConfig = new PipelineConfig(new CaseInsensitiveString(pipelineName), new MaterialConfigs(svn("file:///foo", null, null, false)), new StageConfig(
                new CaseInsensitiveString(stageName), new JobConfigs(job)));
        config.addPipeline(BasicPipelineConfigs.DEFAULT_GROUP, pipelineConfig);
        return config;
    }

    public static CruiseConfig simpleDiamond() {
        CruiseConfig cruiseConfig = new BasicCruiseConfig();
        PipelineConfig pipeline1 = PipelineConfigMother.pipelineConfig("p1", new MaterialConfigs(MaterialConfigsMother.gitMaterialConfig("g1")));
        PipelineConfig pipeline2 = PipelineConfigMother.pipelineConfig("p2", new MaterialConfigs(MaterialConfigsMother.gitMaterialConfig("g1")));
        PipelineConfig pipeline3 = PipelineConfigMother.pipelineConfig("p3",
                new MaterialConfigs(MaterialConfigsMother.dependencyMaterialConfig("p1", "stage-1-1"), MaterialConfigsMother.dependencyMaterialConfig("p2", "stage-1-1")));

        cruiseConfig.addPipeline("group-1", pipeline1);
        cruiseConfig.addPipeline("group-1", pipeline2);
        cruiseConfig.addPipeline("group-1", pipeline3);
        return cruiseConfig;
    }

    public static CruiseConfig configWithConfigRepo() {
        CruiseConfig cruiseConfig = new BasicCruiseConfig();
        cruiseConfig.setConfigRepos(new ConfigReposConfig(ConfigRepoConfig.createConfigRepoConfig(
                git("https://github.com/tomzo/gocd-indep-config-part.git"), "myplugin", "id2"
        )));
        return cruiseConfig;
    }

    public static CruiseConfig configWithSecretConfig(SecretConfig... SecretConfigs) {
        CruiseConfig cruiseConfig = new BasicCruiseConfig();
        cruiseConfig.setSecretConfigs(new SecretConfigs());
        Arrays.stream(SecretConfigs)
                .forEach(secretConfig -> cruiseConfig.getSecretConfigs().add(secretConfig));
        return cruiseConfig;
    }
}
