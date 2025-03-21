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

import com.thoughtworks.go.config.*;
import com.thoughtworks.go.config.materials.MaterialConfigs;
import com.thoughtworks.go.config.materials.ScmMaterialConfig;
import com.thoughtworks.go.config.materials.dependency.DependencyMaterial;
import com.thoughtworks.go.config.materials.svn.SvnMaterialConfig;
import com.thoughtworks.go.domain.*;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.domain.materials.Material;
import com.thoughtworks.go.domain.materials.Modification;
import com.thoughtworks.go.domain.materials.dependency.DependencyMaterialRevision;
import com.thoughtworks.go.domain.materials.svn.SubversionRevision;
import com.thoughtworks.go.fixture.ArtifactsDiskIsFull;
import com.thoughtworks.go.fixture.PipelineWithMultipleStages;
import com.thoughtworks.go.fixture.PipelineWithTwoStages;
import com.thoughtworks.go.helper.ModificationsMother;
import com.thoughtworks.go.helper.PipelineConfigMother;
import com.thoughtworks.go.helper.StageConfigMother;
import com.thoughtworks.go.presentation.pipelinehistory.*;
import com.thoughtworks.go.server.cache.GoCache;
import com.thoughtworks.go.server.dao.DatabaseAccessHelper;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.materials.DependencyMaterialUpdateNotifier;
import com.thoughtworks.go.server.persistence.MaterialRepository;
import com.thoughtworks.go.server.scheduling.TriggerMonitor;
import com.thoughtworks.go.server.service.result.HttpLocalizedOperationResult;
import com.thoughtworks.go.server.service.result.HttpOperationResult;
import com.thoughtworks.go.server.transaction.TransactionTemplate;
import com.thoughtworks.go.server.util.Pagination;
import com.thoughtworks.go.util.GoConfigFileHelper;
import com.thoughtworks.go.util.TempDirUtils;
import com.thoughtworks.go.util.TimeProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;

import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static com.thoughtworks.go.helper.MaterialConfigsMother.svn;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml",
        "classpath:/spring-all-servlet.xml",
})
public class PipelineHistoryServiceIntegrationTest {
    @Autowired private GoConfigDao goConfigDao;
    @Autowired private PipelineHistoryService pipelineHistoryService;
    @Autowired private DatabaseAccessHelper dbHelper;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private GoConfigService goConfigService;
    @Autowired private TriggerMonitor triggerMonitor;
    @Autowired private GoCache goCache;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private DependencyMaterialUpdateNotifier notifier;

    private final GoConfigFileHelper configHelper = new GoConfigFileHelper();
    private PipelineWithMultipleStages pipelineOne;
    private PipelineWithTwoStages pipelineTwo;


    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        goCache.clear();

        pipelineOne = new PipelineWithMultipleStages(3, materialRepository, transactionTemplate, tempDir);
        pipelineOne.setGroupName("group1");
        pipelineTwo = new PipelineWithTwoStages(materialRepository, transactionTemplate, tempDir);
        pipelineTwo.setGroupName("group2");

        configHelper.usingCruiseConfigDao(goConfigDao);
        configHelper.onSetUp();

        dbHelper.onSetUp();

        pipelineOne.usingConfigHelper(configHelper).usingDbHelper(dbHelper).onSetUp();
        pipelineTwo.usingConfigHelper(configHelper).usingDbHelper(dbHelper).addToSetup();

        pipelineOne.configStageAsManualApprovalWithApprovedUsers(pipelineOne.stageName(2), "jez");

        configHelper.addSecurityWithAdminConfig();
        configHelper.setOperatePermissionForGroup("group1", "jez");

        notifier.disableUpdates();
    }

    @AfterEach
    public void tearDown() throws Exception {
        notifier.enableUpdates();
        dbHelper.onTearDown();
        pipelineOne.onTearDown();
        configHelper.onTearDown();
    }

    @Test
    public void shouldLoadPipelineHistory() {
        pipelineOne.createdPipelineWithAllStagesPassed();
        PipelineInstanceModels history = pipelineHistoryService.load(pipelineOne.pipelineName,
                Pagination.pageStartingAt(0, 1, 10),
                "jez", true);
        assertThat(history.size()).isEqualTo(1);
        StageInstanceModels stageHistory = history.first().getStageHistory();
        assertThat(stageHistory.size()).isEqualTo(3);
        for (StageInstanceModel stageHistoryItem : stageHistory) {
            assertThat(stageHistoryItem.isScheduled()).isTrue();
            assertThat(stageHistoryItem.getCanRun()).isTrue();
        }
    }

    @Test
    public void shouldReturnActiveInstanceOfAPipeline() {
        pipelineOne.createdPipelineWithAllStagesPassed();
        configHelper.setViewPermissionForGroup("group1", "jez");

        String pipelineName = pipelineOne.pipelineName;
        List<PipelineGroupModel> groupModels = pipelineHistoryService.getActivePipelineInstance(new Username(new CaseInsensitiveString("jez")), pipelineName.toUpperCase());

        assertThat(groupModels.size()).isEqualTo(1);
        List<PipelineModel> pipelineOneModels = groupModels.get(0).getPipelineModels();
        assertThat(pipelineOneModels.size()).isEqualTo(1);
        assertThat(pipelineOneModels.get(0).getActivePipelineInstances().get(0).getName()).isEqualTo(pipelineName);
    }

    @Test
    public void shouldLoadPipelineHistoryWithEmptyDefaultIfNone() {
        configHelper.setViewPermissionForGroup("group1", "jez");
        PipelineInstanceModels history = pipelineHistoryService.loadWithEmptyAsDefault(pipelineOne.pipelineName, Pagination.pageStartingAt(0, 1, 1), "jez");
        assertThat(history.size()).isEqualTo(1);
        StageInstanceModels stageHistory = history.first().getStageHistory();
        assertThat(stageHistory.size()).isEqualTo(3);
        assertStageHistory(stageHistory.get(0), false, true);
        assertStageHistory(stageHistory.get(1), false, false);
        assertStageHistory(stageHistory.get(2), false, false);
    }

    private void assertStageHistory(StageInstanceModel stageHistoryItem, boolean isScheduled, boolean canRun) {
        assertThat(stageHistoryItem.isScheduled()).isEqualTo(isScheduled);
        assertThat(stageHistoryItem.getCanRun()).isEqualTo(canRun);
    }

    @Test
    public void shouldNotLoadPipelinesThatTheUserDoesNotHavePermissionToSee() {
        configHelper.setViewPermissionForGroup("group1", "foo");

        PipelineInstanceModels history = pipelineHistoryService.loadWithEmptyAsDefault(pipelineOne.pipelineName, Pagination.pageStartingAt(0, 1, 1), "non-admin-user");
        assertThat(history.size()).isEqualTo(0);
    }

    @Test
    public void shouldLoadPipelineHistoryWithPlaceholderStagesPopulated() {
        pipelineOne.createPipelineWithFirstStagePassedAndSecondStageHasNotStarted();
        PipelineInstanceModels history = pipelineHistoryService.load(pipelineOne.pipelineName,
                Pagination.pageStartingAt(0, 1, 10),
                "jez", true);
        StageInstanceModels stageHistory = history.first().getStageHistory();
        assertThat(stageHistory.size()).isEqualTo(3);
        assertThat(stageHistory.first().isScheduled()).isTrue();
        assertThat(stageHistory.first().isAutoApproved()).isTrue();
        assertThat(stageHistory.first().getCanRun()).isTrue();
        assertThat(stageHistory.get(1).isScheduled()).isFalse();
        assertThat(stageHistory.get(1).isAutoApproved()).isFalse();
        assertThat(stageHistory.get(1).getCanRun()).isTrue();
        assertThat(stageHistory.get(2).isScheduled()).isFalse();
        assertThat(stageHistory.get(2).isAutoApproved()).isTrue();
        assertThat(stageHistory.get(2).getCanRun()).isFalse();
    }

    @Test
    @ExtendWith(ArtifactsDiskIsFull.class)
    public void shouldMakePipelineInstanceCanRunFalseWhenDiskSpaceIsEmpty(@TempDir Path tempDir) throws Exception {
        configHelper.updateArtifactRoot(TempDirUtils.createTempDirectoryIn(tempDir, "serverlogs").toAbsolutePath().toString());
        pipelineOne.createdPipelineWithAllStagesPassed();
        PipelineInstanceModels history = pipelineHistoryService.load(pipelineOne.pipelineName,
                Pagination.pageStartingAt(0, 1, 10),
                "jez", true);
        assertThat(history.size()).isEqualTo(1);
        assertThat(history.first().getCanRun()).isFalse();
        StageInstanceModels stageHistory = history.first().getStageHistory();
        assertThat(stageHistory.size()).isEqualTo(3);
        for (StageInstanceModel stageHistoryItem : stageHistory) {
            assertThat(stageHistoryItem.isScheduled()).isTrue();
            assertThat(stageHistoryItem.getCanRun()).isFalse();
        }
    }

    @Test
    public void shouldNotLoadDuplicatePlaceholderStages() {
        goConfigService.addPipeline(PipelineConfigMother.createPipelineConfig("pipeline", "stage", "job"), "pipeline-group");

        PipelineInstanceModels history = pipelineHistoryService.load("pipeline", Pagination.pageStartingAt(0, 1, 10), "anyone", true);
        PipelineInstanceModel instanceModel = history.first();

        assertThat(instanceModel instanceof EmptyPipelineInstanceModel).isTrue();
        StageInstanceModels stageHistory = instanceModel.getStageHistory();
        assertThat(stageHistory.size()).isEqualTo(1);
        assertThat(stageHistory.first() instanceof NullStageHistoryItem).isTrue();
    }

    @Test
    public void shouldContainLatestRevisionForEachPipeline() {
        Pipeline pipeline = pipelineOne.createPipelineWithFirstStageScheduled();
        MaterialRevision materialRevision = new MaterialRevision(pipeline.getMaterialRevisions().getMaterialRevision(0).getMaterial(), new Modification(new Date(), "2", "MOCK_LABEL-12", null));
        saveRev(materialRevision);
        configHelper.setViewPermissionForGroup("group1", "username");

        PipelineInstanceModels latest = pipelineHistoryService.loadWithEmptyAsDefault(pipelineOne.pipelineName, Pagination.ONE_ITEM, "username");
        MaterialRevisions latestRevision = latest.get(0).getLatestRevisions();
        assertThat(latestRevision.getMaterialRevision(0).getRevision()).isEqualTo(new SubversionRevision("2"));
    }

    private void saveRev(final MaterialRevision materialRevision) {
        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                materialRepository.saveMaterialRevision(materialRevision);
            }
        });
    }

    @Test
    public void shouldReturnLatestPipelineIntance() {
        Pipeline pipeline = pipelineOne.createPipelineWithFirstStageScheduled();
        saveRev(new MaterialRevision(pipeline.getMaterialRevisions().getMaterialRevision(0).getMaterial(), new Modification(new Date(), "2", "MOCK_LABEL-12", null)));
        configHelper.setViewPermissionForGroup("group1", "username");

        PipelineInstanceModel latest = pipelineHistoryService.latest(pipeline.getName(), new Username(new CaseInsensitiveString("username")));
        assertThat(latest.getLatestRevisions().getMaterialRevision(0).getRevision()).isEqualTo(new SubversionRevision("2"));
    }

    @Test
    public void shouldContainNoRevisionsForNewMaterialsThatHAveNotBeenUpdated() {
        pipelineOne.createPipelineWithFirstStageScheduled();
        SvnMaterialConfig svnMaterialConfig = svn("new-material", null, null, false);
        svnMaterialConfig.setConfigAttributes(Map.of(ScmMaterialConfig.FOLDER, "new-material"));
        configHelper.addMaterialToPipeline(pipelineOne.pipelineName, svnMaterialConfig);
        configHelper.setViewPermissionForGroup("group1", "username");
        PipelineInstanceModels latest = pipelineHistoryService.loadWithEmptyAsDefault(pipelineOne.pipelineName, Pagination.ONE_ITEM, "username");
        MaterialRevisions latestRevision = latest.get(0).getLatestRevisions();
        assertThat(latestRevision.getRevisions().size()).isEqualTo(1);
    }

    @Test
    public void shouldCreateEmptyPipelineIfThePipelineHasNeverBeenRun() {
        SvnMaterialConfig svnMaterial = svn("https://some-url", "new-user", "new-pass", false);
        configHelper.addPipeline("new-pipeline", "new-stage", svnMaterial, "first-job");
        configHelper.addAuthorizedUserForPipelineGroup("username", BasicPipelineConfigs.DEFAULT_GROUP);

        PipelineInstanceModels instanceModels = pipelineHistoryService.loadWithEmptyAsDefault("new-pipeline", Pagination.ONE_ITEM, "username");
        PipelineInstanceModel instanceModel = instanceModels.get(0);
        assertThat(instanceModel.getMaterials()).isEqualTo(new MaterialConfigs(svnMaterial));
        assertThat(instanceModel.getCurrentRevision(svnMaterial).getRevision()).isEqualTo("No historical data");
        assertThat(instanceModel.getLatestRevision(svnMaterial).getRevision()).isEqualTo("No historical data");
        assertThat(instanceModel.getStageHistory().size()).isEqualTo(1);
        assertThat(instanceModel.getStageHistory().get(0).getName()).isEqualTo("new-stage");
    }

    @Test
    public void shouldUnderstandIfMaterialHasNewModifications() {
        Pipeline pipeline = pipelineOne.createPipelineWithFirstStageScheduled();
        Material material = pipeline.getMaterialRevisions().getMaterialRevision(0).getMaterial();
        saveRev(new MaterialRevision(material, new Modification(new Date(), "2", "MOCK_LABEL-12", null)));
        configHelper.setViewPermissionForGroup("group1", "username");
        PipelineInstanceModels latest = pipelineHistoryService.loadWithEmptyAsDefault(pipelineOne.pipelineName, Pagination.ONE_ITEM, "username");
        PipelineInstanceModel model = latest.get(0);
        assertThat(model.hasNewRevisions(material.config())).isTrue();
    }

    @Test
    public void shouldUnderstandIfMaterialHasNoNewModifications() {
        Pipeline pipeline = pipelineOne.createPipelineWithFirstStageScheduled();
        Material material = pipeline.getMaterialRevisions().getMaterialRevision(0).getMaterial();
        configHelper.setViewPermissionForGroup("group1", "username");
        PipelineInstanceModels latest = pipelineHistoryService.loadWithEmptyAsDefault(pipelineOne.pipelineName, Pagination.ONE_ITEM, "username");
        PipelineInstanceModel model = latest.get(0);
        assertThat(model.hasNewRevisions(material.config())).isFalse();
    }

    @Test
    public void shouldReturnNullForLatestWhenPipelineNotViewable() {
        configHelper.addPipelineWithGroup("admin_only", "admin_pipeline", "stage", "deploy");
        configHelper.addRole(new RoleConfig(new CaseInsensitiveString("deployers"), new RoleUser(new CaseInsensitiveString("root"))));
        configHelper.blockPipelineGroupExceptFor("admin_only", "deployers");
        assertThat(pipelineHistoryService.latest("admin_pipeline", new Username(new CaseInsensitiveString("root")))).isNotNull();
        assertThat(pipelineHistoryService.latest("admin_pipeline", new Username(new CaseInsensitiveString("someone_else")))).isNull();
    }

    @Test
    public void shouldLoadPipelineInstanceModelGivenAnId() {
        Pipeline pipeline = pipelineOne.createdPipelineWithAllStagesPassed();
        configHelper.setViewPermissionForGroup("group1", "foo");
        PipelineInstanceModel pipelineInstance = pipelineHistoryService.load(pipeline.getId(), new Username(new CaseInsensitiveString("foo")), new HttpOperationResult());
        assertThat(pipelineInstance).isNotNull();
        assertThat(pipelineInstance.getMaterials().size()).isGreaterThan(0);
        assertThat(pipelineInstance.getLatestRevisions().numberOfRevisions()).isGreaterThan(0);
        assertThat(pipelineInstance.getBuildCause().getMaterialRevisions().numberOfRevisions()).isGreaterThan(0);
    }

    @Test
    public void shouldLoadPipelineInstanceWithMultipleRevisions() {
        Pipeline pipeline = pipelineOne.createPipelineWithFirstStageScheduled(ModificationsMother.multipleModifications(pipelineOne.pipelineConfig()));
        configHelper.setViewPermissionForGroup("group1", "foo");
        PipelineInstanceModel pipelineInstance = pipelineHistoryService.load(pipeline.getId(), new Username(new CaseInsensitiveString("foo")), new HttpOperationResult());
        assertThat(pipelineInstance).isNotNull();
        assertThat(pipelineInstance.hasNewRevisions()).isFalse();
    }

    @Test
    public void shouldPopulateResultAsNotFoundWhenPipelineNotFound() {
        HttpOperationResult result = new HttpOperationResult();
        PipelineInstanceModel pipelineInstance = pipelineHistoryService.load(-1, new Username(new CaseInsensitiveString("foo")), result);
        assertThat(pipelineInstance).isNull();
        assertThat(result.httpCode()).isEqualTo(404);
    }

    @Test
    public void shouldPopulateResultAsUnauthorizedWhenUserNotAllowedToViewPipeline() {
        Pipeline pipeline = pipelineOne.createdPipelineWithAllStagesPassed();
        String groupName = configHelper.currentConfig().getGroups().findGroupNameByPipeline(new CaseInsensitiveString(pipeline.getName()));
        configHelper.setViewPermissionForGroup(groupName, "admin");

        HttpOperationResult result = new HttpOperationResult();
        PipelineInstanceModel pipelineInstance = pipelineHistoryService.load(pipeline.getId(), new Username(new CaseInsensitiveString("foo")), result);
        assertThat(pipelineInstance).isNull();
        assertThat(result.httpCode()).isEqualTo(403);

        result = new HttpOperationResult();
        pipelineInstance = pipelineHistoryService.load(pipeline.getId(), new Username(new CaseInsensitiveString("admin")), result);
        assertThat(pipelineInstance).isNotNull();
    }

	@Test
	public void shouldLoadPipelineHistoryWithPlaceholderStagesPopulated_loadMinimalData() {
		pipelineOne.createPipelineWithFirstStagePassedAndSecondStageHasNotStarted();

		HttpOperationResult result = new HttpOperationResult();
        PipelineInstanceModels pipelineInstanceModels = pipelineHistoryService.loadMinimalData(pipelineOne.pipelineName,
                Pagination.pageStartingAt(0, 1, 10), new Username(new CaseInsensitiveString("admin1")), result);

		StageInstanceModels stageHistory = pipelineInstanceModels.first().getStageHistory();
		assertThat(stageHistory.size()).isEqualTo(3);
		assertThat(stageHistory.first().isScheduled()).isTrue();
		assertThat(stageHistory.first().isAutoApproved()).isTrue();
		assertThat(stageHistory.first().getCanRun()).isTrue();
		assertThat(stageHistory.get(1).isScheduled()).isFalse();
		assertThat(stageHistory.get(1).isAutoApproved()).isFalse();
		assertThat(stageHistory.get(1).getCanRun()).isTrue();
		assertThat(stageHistory.get(2).isScheduled()).isFalse();
		assertThat(stageHistory.get(2).isAutoApproved()).isTrue();
		assertThat(stageHistory.get(2).getCanRun()).isFalse();
	}

    @Test
    public void shouldLoadLatestOrEmptyInstanceForAllConfiguredPipelines() {
        configHelper.removePipeline(pipelineTwo.pipelineName);
        Pipeline pipeline = pipelineOne.createdPipelineWithAllStagesPassed();
        configHelper.setViewPermissionForGroup("group1", "foo");
        PipelineInstanceModels pipelines = pipelineHistoryService.latestInstancesForConfiguredPipelines(new Username(new CaseInsensitiveString("foo")));
        assertThat(pipelines.size()).isEqualTo(1);
        assertThat(pipelines.first().getId()).isEqualTo(pipeline.getId());
    }

    @Test
    public void shouldNotLoadLatestOrEmptyInstanceForAllConfiguredPipelinesWhenNotViewable() {
        configHelper.removePipeline(pipelineTwo.pipelineName);
        Pipeline pipeline = pipelineOne.createdPipelineWithAllStagesPassed();
        String groupName = configHelper.currentConfig().getGroups().findGroupNameByPipeline(new CaseInsensitiveString(pipeline.getName()));
        configHelper.setViewPermissionForGroup(groupName, "admin");
        PipelineInstanceModels pipelines = pipelineHistoryService.latestInstancesForConfiguredPipelines(new Username(new CaseInsensitiveString("foo")));
        assertThat(pipelines.size()).isEqualTo(0);
        pipelines = pipelineHistoryService.latestInstancesForConfiguredPipelines(new Username(new CaseInsensitiveString("admin")));
        assertThat(pipelines.size()).isEqualTo(1);
        assertThat(pipelines.first().getId()).isEqualTo(pipeline.getId());
    }

    @Test
    public void shouldFindAllPipelineInstancesForGivenPipelineName() {
        Pipeline pipeline = pipelineOne.createdPipelineWithAllStagesPassed();
        configHelper.setViewPermissionForGroup("group1", "foo");
        PipelineInstanceModels pipelineInstances = pipelineHistoryService.findAllPipelineInstances(pipeline.getName(), new Username(new CaseInsensitiveString("foo")), new HttpOperationResult());
        assertThat(pipelineInstances.size()).isEqualTo(1);
        assertThat(pipelineInstances.first().getName()).isEqualTo(pipeline.getName());
    }

    @Test
    public void shouldNotFindPipelineInstancesForGivenPipelineNameWhenNonViewableForUser() {
        Pipeline pipeline = pipelineOne.createdPipelineWithAllStagesPassed();
        String groupName = configHelper.currentConfig().getGroups().findGroupNameByPipeline(new CaseInsensitiveString(pipeline.getName()));
        configHelper.setViewPermissionForGroup(groupName, "admin");

        HttpOperationResult result = new HttpOperationResult();
        PipelineInstanceModels pipelineInstances = pipelineHistoryService.findAllPipelineInstances(pipeline.getName(), new Username(new CaseInsensitiveString("foo")), result);
        assertThat(pipelineInstances).isNull();
        assertThat(result.httpCode()).isEqualTo(403);
        assertThat(result.message()).isEqualTo("Not authorized to view pipeline");
        pipelineInstances = pipelineHistoryService.findAllPipelineInstances(pipeline.getName(), new Username(new CaseInsensitiveString("admin")), new HttpOperationResult());
        assertThat(pipelineInstances.size()).isEqualTo(1);
        assertThat(pipelineInstances.first().getName()).isEqualTo(pipeline.getName());
    }

    @Test
    public void shouldSet404InOperationResultWhenPipelineUnknown() {
        HttpOperationResult result = new HttpOperationResult();
        PipelineInstanceModels pipelineInstances = pipelineHistoryService.findAllPipelineInstances("pipeline_that_does_not_exist", new Username(new CaseInsensitiveString("foo")), result);
        assertThat(result.httpCode()).isEqualTo(404);
        assertThat(result.message()).isEqualTo("Pipeline named [pipeline_that_does_not_exist] is not known.");
        assertThat(pipelineInstances).isNull();
    }

    @Test
    public void shouldHaveAPipelineInstanceForAPipelineThatIsPreparingToSchedule() {

        configHelper.addPipeline("pipeline-name", "stage-1");
        configHelper.addAuthorizedUserForPipelineGroup("admin", BasicPipelineConfigs.DEFAULT_GROUP);

        triggerMonitor.markPipelineAsAlreadyTriggered(new CaseInsensitiveString("pipeline-name"));

        PipelineInstanceModel instance = pipelineHistoryService.latest("pipeline-name", new Username(new CaseInsensitiveString("admin")));
        assertThat(instance.getName()).isEqualTo("pipeline-name");
        assertThat(instance.getStageHistory().size()).isEqualTo(1);
        assertThat(instance.getStageHistory().get(0).isScheduled()).isFalse();
        assertThat(instance.isPreparingToSchedule()).isTrue();

    }

    @Test
    public void shouldLoadPipelineInstanceForGivenNameAndCounter() {
        PipelineConfig mingleConfig = PipelineConfigMother.createPipelineConfig("mingle", "stage", "job");
        goConfigService.addPipeline(mingleConfig, "pipeline-group");
        Pipeline instance1 = dbHelper.schedulePipeline(mingleConfig, new TimeProvider());
        dbHelper.cancelStage(instance1.getStages().get(0));
        Pipeline instance2 = dbHelper.schedulePipeline(mingleConfig, new TimeProvider());
        dbHelper.passStage(instance2.getStages().get(0));
        configHelper.addAuthorizedUserForPipelineGroup("user1", "pipeline-group");

        HttpOperationResult operationResult = new HttpOperationResult();
        assertPipeline(pipelineHistoryService.findPipelineInstance("mingle", 1, new Username(new CaseInsensitiveString("user1")), operationResult), instance1, operationResult);
        assertPipeline(pipelineHistoryService.findPipelineInstance("mingle", 2, new Username(new CaseInsensitiveString("user1")), operationResult), instance2, operationResult);
    }

    @Test
    public void shouldNotThrowUpWhenPipelineCounterIs0AndShouldReturnAnEmptyPIM() {
        PipelineConfig mingleConfig = PipelineConfigMother.createPipelineConfig("mingle", "stage", "job");
        goConfigService.addPipeline(mingleConfig, "pipeline-group");
        configHelper.addAuthorizedUserForPipelineGroup("user1", "pipeline-group");

        PipelineInstanceModel pim = pipelineHistoryService.findPipelineInstance("mingle", 0, new Username(new CaseInsensitiveString("user1")), new HttpOperationResult());
        assertThat(pim).isInstanceOf(EmptyPipelineInstanceModel.class);
    }

    @Test
    public void findPipelineInstanceShouldNotFindPipelineInstancesNotViewableByUser() {
        Pipeline pipeline = pipelineOne.createdPipelineWithAllStagesPassed();
        String groupName = configHelper.currentConfig().getGroups().findGroupNameByPipeline(new CaseInsensitiveString(pipeline.getName()));
        configHelper.setViewPermissionForGroup(groupName, "admin");

        HttpOperationResult result = new HttpOperationResult();
        PipelineInstanceModel pipelineInstance = pipelineHistoryService.findPipelineInstance(pipeline.getName(), pipeline.getCounter(), new Username(new CaseInsensitiveString("foo")), result);
        assertThat(pipelineInstance).isNull();
        assertThat(result.httpCode()).isEqualTo(403);
        assertThat(result.message()).isEqualTo("Not authorized to view pipeline");
        pipelineInstance = pipelineHistoryService.findPipelineInstance(pipeline.getName(), pipeline.getCounter(), new Username(new CaseInsensitiveString("admin")), new HttpOperationResult());
        assertThat(pipelineInstance).isNotNull();
    }

    @Test
    public void findPipelineInstanceShouldPopulateResultAsNotFoundWhenPipelineNotFound() {
        HttpOperationResult result = new HttpOperationResult();
        PipelineInstanceModel pipelineInstance = pipelineHistoryService.findPipelineInstance("blahName", 1, new Username(new CaseInsensitiveString("foo")), result);
        assertThat(pipelineInstance).isNull();
        assertThat(result.httpCode()).isEqualTo(404);
    }

    @Test
    public void shouldReturnListOfPipelineInstancesByPageNumber() {
        final int limit = 1;
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("mingle", "stage", "job");
        goConfigService.addPipeline(pipelineConfig, "pipeline-group");
        Pipeline instance1 = dbHelper.schedulePipeline(pipelineConfig, new TimeProvider());
        dbHelper.cancelStage(instance1.getStages().get(0));
        Pipeline instance2 = dbHelper.schedulePipeline(pipelineConfig, new TimeProvider());
        dbHelper.passStage(instance2.getStages().get(0));
        Pipeline instance3 = dbHelper.schedulePipeline(pipelineConfig, new TimeProvider());
        dbHelper.passStage(instance3.getStages().get(0));

        PipelineInstanceModels actual = pipelineHistoryService.findPipelineInstancesByPageNumber(instance2.getName(), 2, limit, "admin");

        assertThat(actual.size()).isEqualTo(1);
        assertThat(actual.get(0).getCounter()).isEqualTo(2);
        assertThat(actual.getPagination()).isEqualTo(Pagination.pageStartingAt(1, 3, limit));
    }

    @Test
    public void findMatchingPipelineInstances_shouldMatchLabels() {
        final int limit = 10;
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("pipeline_name", "stage", "job");
        goConfigService.addPipeline(pipelineConfig, "pipeline-group");
        configHelper.addAuthorizedUserForPipelineGroup("user", "pipeline-group");

        pipelineConfig.setLabelTemplate("${COUNT}-blah-1");
        Pipeline instance1 = dbHelper.schedulePipeline(pipelineConfig, new TimeProvider());
        dbHelper.cancelStage(instance1.getStages().get(0));

        pipelineConfig.setLabelTemplate("${COUNT}-blah-2");
        Pipeline instance2 = dbHelper.schedulePipeline(pipelineConfig, new TimeProvider());
        dbHelper.passStage(instance2.getStages().get(0));

        pipelineConfig.setLabelTemplate("${COUNT}-blah-1-1");
        Pipeline instance3 = dbHelper.schedulePipeline(pipelineConfig, new TimeProvider());
        dbHelper.passStage(instance3.getStages().get(0));

        pipelineConfig.setLabelTemplate("${COUNT}-blaH-1-2");
        Pipeline instance4 = dbHelper.schedulePipeline(pipelineConfig, new TimeProvider());
        dbHelper.passStage(instance4.getStages().get(0));

        PipelineInstanceModels actual = pipelineHistoryService.findMatchingPipelineInstances("pipeline_name", "h-1", limit, new Username(new CaseInsensitiveString("user")), new HttpLocalizedOperationResult());
        assertThat(actual.size()).isEqualTo(3);
        assertThat(actual.get(0).getCounter()).isEqualTo(4);
        assertThat(actual.get(1).getCounter()).isEqualTo(3);
        assertThat(actual.get(2).getCounter()).isEqualTo(1);
    }

    @Test
    public void findMatchingPipelineInstances_shouldEscapeUnderscoreAndPercentageSymbols() {
        final int limit = 10;
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("pipeline_name", "stage", "job");
        goConfigService.addPipeline(pipelineConfig, "pipeline-group");
        configHelper.addAuthorizedUserForPipelineGroup("user", "pipeline-group");

        pipelineConfig.setLabelTemplate("${COUNT}-blah-1");
        Pipeline instance1 = dbHelper.schedulePipeline(pipelineConfig, new TimeProvider());
        dbHelper.cancelStage(instance1.getStages().get(0));

        pipelineConfig.setLabelTemplate("${COUNT}-blah-2");
        Pipeline instance2 = dbHelper.schedulePipeline(pipelineConfig, new TimeProvider());
        dbHelper.passStage(instance2.getStages().get(0));

        pipelineConfig.setLabelTemplate("${COUNT}-blah-1-1");
        Pipeline instance3 = dbHelper.schedulePipeline(pipelineConfig, new TimeProvider());
        dbHelper.passStage(instance3.getStages().get(0));

        pipelineConfig.setLabelTemplate("${COUNT}-blaH-1-2");
        Pipeline instance4 = dbHelper.schedulePipeline(pipelineConfig, new TimeProvider());
        dbHelper.passStage(instance4.getStages().get(0));

        PipelineInstanceModels actual = pipelineHistoryService.findMatchingPipelineInstances("pipeline_name", "h-1", limit, new Username(new CaseInsensitiveString("user")), new HttpLocalizedOperationResult());
        assertThat(actual.size()).isEqualTo(3);

        actual = pipelineHistoryService.findMatchingPipelineInstances("pipeline_name", "h%-%1_5", limit, new Username(new CaseInsensitiveString("user")), new HttpLocalizedOperationResult());
        assertThat(actual.size()).isEqualTo(0);
    }

    @Test
    public void findMatchingPipelineInstances_shouldTrimThePatternBeforeSearch() {
        final int limit = 10;
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("pipeline_name", "stage", "job");
        goConfigService.addPipeline(pipelineConfig, "pipeline-group");
        configHelper.addAuthorizedUserForPipelineGroup("user", "pipeline-group");

        pipelineConfig.setLabelTemplate("${COUNT}-blah-1");
        Pipeline instance1 = dbHelper.schedulePipeline(pipelineConfig, new TimeProvider());
        dbHelper.cancelStage(instance1.getStages().get(0));

        pipelineConfig.setLabelTemplate("${COUNT}-blah-2");
        Pipeline instance2 = dbHelper.schedulePipeline(pipelineConfig, new TimeProvider());
        dbHelper.passStage(instance2.getStages().get(0));

        pipelineConfig.setLabelTemplate("${COUNT}-blah-1-1");
        Pipeline instance3 = dbHelper.schedulePipeline(pipelineConfig, new TimeProvider());
        dbHelper.passStage(instance3.getStages().get(0));

        pipelineConfig.setLabelTemplate("${COUNT}-blaH-1-2");
        Pipeline instance4 = dbHelper.schedulePipeline(pipelineConfig, new TimeProvider());
        dbHelper.passStage(instance4.getStages().get(0));

        PipelineInstanceModels actual = pipelineHistoryService.findMatchingPipelineInstances("pipeline_name", " h-1   ", limit, new Username(new CaseInsensitiveString("user")), new HttpLocalizedOperationResult());
        assertThat(actual.size()).isEqualTo(3);
        assertThat(actual.get(0).getCounter()).isEqualTo(4);
        assertThat(actual.get(1).getCounter()).isEqualTo(3);
        assertThat(actual.get(2).getCounter()).isEqualTo(1);
    }

    @Test
    public void findMatchingPipelineInstances_shouldPopulatePipelineFieldsIncludingPlaceholderStages() {
        configHelper.setViewPermissionForGroup("group1", "jez");
        Pipeline pipeline = pipelineOne.createPipelineWithFirstStagePassedAndSecondStageHasNotStarted();
        PipelineInstanceModels actual = pipelineHistoryService.findMatchingPipelineInstances(pipeline.getName(), pipeline.getBuildCause().getMaterialRevisions().getMaterialRevision(0).getLatestComment(), 2, new Username(new CaseInsensitiveString(
                "jez")), new HttpLocalizedOperationResult());
        assertThat(actual.size()).isEqualTo(1);
        PipelineInstanceModel actualPipeline = actual.get(0);
        assertThat(actualPipeline.getCounter()).isEqualTo(1);
        assertThat(actualPipeline.getStageHistory().size()).isEqualTo(3);
        assertThat(actualPipeline.getStageHistory().get(1).getState()).isEqualTo(StageState.Unknown);
        assertThat(actualPipeline.getStageHistory().get(2).getState()).isEqualTo(StageState.Unknown);
    }

    @Test
    public void findMatchingPipelineInstances_shouldMatchModificationsFields() {
        final int limit = 3;
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("pipeline_name", "stage", "job");
        goConfigService.addPipeline(pipelineConfig, "pipeline-group");
        configHelper.addAuthorizedUserForPipelineGroup("user", "pipeline-group");

        pipelineConfig.setLabelTemplate("${COUNT}-blah-1");
        Pipeline shouldMatch1 = dbHelper.schedulePipeline(pipelineConfig, ModificationsMother.buildCauseForOneModifiedFile(pipelineConfig, "abc1234", "hello world  -THIS SHOULD MATCH", "dev"),
                new TimeProvider());
        dbHelper.cancelStage(shouldMatch1.getStages().get(0));

        Pipeline shouldMatch2 = dbHelper.schedulePipeline(pipelineConfig, ModificationsMother.buildCauseForOneModifiedFile(pipelineConfig, "abc12345", "some MONKEY", "THIS SHOULD ALSO MATCH - YELLOW"),
                new TimeProvider());
        dbHelper.cancelStage(shouldMatch2.getStages().get(0));

        Pipeline shouldMatch3 = dbHelper.schedulePipeline(pipelineConfig, ModificationsMother.buildCauseForOneModifiedFile(pipelineConfig, "revision-HELLO-there-SHOULD_MATCH", "some monkey", "foo"),
                new TimeProvider());
        dbHelper.cancelStage(shouldMatch3.getStages().get(0));

        Pipeline shouldNotMatch = dbHelper.schedulePipeline(pipelineConfig, ModificationsMother.buildCauseForOneModifiedFile(pipelineConfig, "revision-there-2", "some monkey", "foo"),
                new TimeProvider());
        dbHelper.cancelStage(shouldNotMatch.getStages().get(0));

        PipelineInstanceModels actual = pipelineHistoryService.findMatchingPipelineInstances("pipeline_name", "ello", limit, new Username(new CaseInsensitiveString("user")), new HttpLocalizedOperationResult());
        assertThat(actual.size()).isEqualTo(3);
        assertThat(actual.get(0).getCounter()).isEqualTo(3);
        assertThat(actual.get(1).getCounter()).isEqualTo(2);
        assertThat(actual.get(2).getCounter()).isEqualTo(1);
    }

    @Test
    public void findMatchingPipelineInstances_shouldShowExactMatchesOnLabelBeforePartialMatches() {
        final int limit = 3;
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("pipeline_name", "stage", "job");
        goConfigService.addPipeline(pipelineConfig, "pipeline-group");
        configHelper.addAuthorizedUserForPipelineGroup("user", "pipeline-group");

        pipelineConfig.setLabelTemplate("${COUNT}-ABC");
        Pipeline shouldMatch1 = dbHelper.schedulePipeline(pipelineConfig, ModificationsMother.buildCauseForOneModifiedFile(pipelineConfig, "revision", "comment", "committer"), new TimeProvider());
        dbHelper.cancelStage(shouldMatch1.getStages().get(0));

        Pipeline shouldMatch2 = dbHelper.schedulePipeline(pipelineConfig, ModificationsMother.buildCauseForOneModifiedFile(pipelineConfig, String.format("revision-%s-abc-should-match", shouldMatch1.getCounter()), "another comment", "committer"),
                new TimeProvider());
        dbHelper.cancelStage(shouldMatch2.getStages().get(0));

        PipelineInstanceModels actual = pipelineHistoryService.findMatchingPipelineInstances("pipeline_name", String.format("%s-ABC", shouldMatch1.getCounter()), limit, new Username(new CaseInsensitiveString(
                "user")), new HttpLocalizedOperationResult());
        assertThat(actual.size()).isEqualTo(2);
        assertThat(actual.get(0).getCounter()).isEqualTo(shouldMatch1.getCounter());
        assertThat(actual.get(1).getCounter()).isEqualTo(shouldMatch2.getCounter());
    }

    @Test
    public void findMatchingPipelineInstances_shouldShowExpectedNumberOfMatchesWhenThePipelineHasMultipleStagesAndJobs() {
        final int limit = 2;
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfigWithStages("pipeline_name");
        pipelineConfig.add(StageConfigMother.custom("stage-1", "job-1", "job-2", "job-3", "job-4"));
        goConfigService.addPipeline(pipelineConfig, "pipeline-group");
        configHelper.addAuthorizedUserForPipelineGroup("user", "pipeline-group");

        pipelineConfig.setLabelTemplate("${COUNT}");
        Pipeline shouldMatch1 = dbHelper.schedulePipeline(pipelineConfig, ModificationsMother.buildCauseForOneModifiedFile(pipelineConfig, "abc-revision-should-match", "comment", "committer"),
                new TimeProvider());
        dbHelper.cancelStage(shouldMatch1.getStages().get(0));

        Pipeline shouldMatch2 = dbHelper.schedulePipeline(pipelineConfig, ModificationsMother.buildCauseForOneModifiedFile(pipelineConfig, "this-abc-revision-should-match-too", "another comment", "committer"),
                new TimeProvider());
        dbHelper.cancelStage(shouldMatch2.getStages().get(0));

        PipelineInstanceModels actual = pipelineHistoryService.findMatchingPipelineInstances("pipeline_name", "abc", limit, new Username(new CaseInsensitiveString("user")), new HttpLocalizedOperationResult());
        assertThat(actual.size()).isEqualTo(2);
        assertThat(actual.get(0).getCounter()).isEqualTo(shouldMatch2.getCounter());
        assertThat(actual.get(1).getCounter()).isEqualTo(shouldMatch1.getCounter());
    }

    @Test
    public void findMatchingPipelineInstances_shouldMatchBuildCauseMessage() {
        final int limit = 3;
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("pipeline_name", "stage", "job");
        goConfigService.addPipeline(pipelineConfig, "pipeline-group");
        configHelper.addAuthorizedUserForPipelineGroup("user", "pipeline-group");

        pipelineConfig.setLabelTemplate("${COUNT}-ABC");
        BuildCause buildCause = ModificationsMother.buildCauseForOneModifiedFile(pipelineConfig, "revision", "comment", "committer");
        buildCause.setMessage("Triggered by USER some million years ago.. when dinosaurs ruled the world");
        Pipeline shouldMatch1 = dbHelper.schedulePipeline(pipelineConfig, buildCause, new TimeProvider());
        dbHelper.cancelStage(shouldMatch1.getStages().get(0));

        PipelineInstanceModels actual = pipelineHistoryService.findMatchingPipelineInstances("pipeline_name", "user", limit, new Username(new CaseInsensitiveString("user")), new HttpLocalizedOperationResult());
        assertThat( actual.size()).isEqualTo(1);
        assertThat(actual.get(0).getCounter()).isEqualTo(shouldMatch1.getCounter());
    }

    @Test
    public void findMatchingPipelineInstances_shouldMatchUpStreamPipelineLabel() {
        final int limit = 3;
        PipelineConfig upstreamConfig = PipelineConfigMother.createPipelineConfig("upstream", "stage", "job");
        upstreamConfig.setLabelTemplate("${COUNT}-hello-world-${COUNT}");
        PipelineConfig downstreamConfig = PipelineConfigMother.createPipelineConfig("downstream", "stage", "job");
        downstreamConfig.materialConfigs().clear();
        DependencyMaterial dependencyMaterial = new DependencyMaterial(new CaseInsensitiveString("upstream"), new CaseInsensitiveString("stage"));
        downstreamConfig.addMaterialConfig(dependencyMaterial.config());
        goConfigService.addPipeline(upstreamConfig, "pipeline-group");
        goConfigService.addPipeline(downstreamConfig, "pipeline-group");
        configHelper.addAuthorizedUserForPipelineGroup("user", "pipeline-group");

        Pipeline upstreamPipeline = dbHelper.schedulePipeline(upstreamConfig, new TimeProvider());
        dbHelper.passStage(upstreamPipeline.getStages().get(0));
        Modification modification = new Modification(new Date(), DependencyMaterialRevision.create("upstream", 1, "1", "stage", 1).getRevision(), "1", upstreamPipeline.getId());
        MaterialRevisions materialRevisions = new MaterialRevisions(new MaterialRevision(dependencyMaterial, modification));
        Pipeline downstreamPipeline = dbHelper.schedulePipeline(downstreamConfig, BuildCause.createWithModifications(materialRevisions, "cruise"), new TimeProvider());
        dbHelper.passStage(downstreamPipeline.getStages().get(0));

        PipelineInstanceModels actual = pipelineHistoryService.findMatchingPipelineInstances("downstream", "hello-world", limit, new Username(new CaseInsensitiveString("user")), new HttpLocalizedOperationResult());
        assertThat(actual.size()).isEqualTo(1);
        assertThat(actual.get(0).getCounter()).isEqualTo(downstreamPipeline.getCounter());
    }

    @Test
    public void findMatchingPipelineInstances_shouldReturnUnauthorizedWhenUserDoesNotHavePermission() {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("pipeline_name", "stage", "job");
        goConfigService.addPipeline(pipelineConfig, "pipeline-group");
        configHelper.addAuthorizedUserForPipelineGroup("valid-user");

        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();
        PipelineInstanceModels pipelineInstances = pipelineHistoryService.findMatchingPipelineInstances("pipeline_name", "ABCD", 1000, new Username(new CaseInsensitiveString("fraud-user")), result);
        assertThat(pipelineInstances.size()).isEqualTo(0);
        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.httpCode()).isEqualTo(403);
    }

    @Test
    public void shouldReturnTheLatestAndOldestPipelineRunId() {
        Pipeline pipeline1 = pipelineTwo.createdPipelineWithAllStagesPassed();
        Pipeline pipeline2 = pipelineTwo.createdPipelineWithAllStagesPassed();
        Pipeline pipeline3 = pipelineTwo.createdPipelineWithAllStagesPassed();

        PipelineRunIdInfo oldestAndLatestPipelineId = pipelineHistoryService.getOldestAndLatestPipelineId(pipelineTwo.pipelineName, new Username(new CaseInsensitiveString("admin1")));

        assertThat(oldestAndLatestPipelineId.getLatestRunId()).isEqualTo(pipeline3.getId());
        assertThat(oldestAndLatestPipelineId.getOldestRunId()).isEqualTo(pipeline1.getId());
    }

    @Test
    public void shouldReturnLatestPipelineHistory() {
        List<Pipeline> pipelineList = IntStream.range(0, 5)
                .mapToObj(i -> pipelineTwo.createdPipelineWithAllStagesPassed())
                .toList();

        PipelineInstanceModels pipelineInstanceModels = pipelineHistoryService.loadPipelineHistoryData(new Username(new CaseInsensitiveString("admin1")), pipelineTwo.pipelineName, 0, 0, 10);

        assertThat(pipelineInstanceModels.size()).isEqualTo(5);
        assertThat(pipelineInstanceModels.get(0).getId()).isEqualTo(pipelineList.get(4).getId());
        assertThat(pipelineInstanceModels.get(4).getId()).isEqualTo(pipelineList.get(0).getId());
    }

    @Test
    public void shouldReturnThePipelineHistoryAfterTheSpecifiedCursor() {
        List<Pipeline> pipelineList = IntStream.range(0, 5)
                .mapToObj(i -> pipelineTwo.createdPipelineWithAllStagesPassed())
                .toList();

        PipelineInstanceModels pipelineInstanceModels = pipelineHistoryService.loadPipelineHistoryData(new Username(new CaseInsensitiveString("admin1")), pipelineTwo.pipelineName, pipelineList.get(2).getId(), 0, 10);

        assertThat(pipelineInstanceModels.size()).isEqualTo(2);
        assertThat(pipelineInstanceModels.get(0).getId()).isEqualTo(pipelineList.get(1).getId());
        assertThat(pipelineInstanceModels.get(1).getId()).isEqualTo(pipelineList.get(0).getId());
    }

    @Test
    public void shouldReturnThePipelineHistoryBeforeTheSpecifiedCursor() {
        List<Pipeline> pipelineList = IntStream.range(0, 5)
                .mapToObj(i -> pipelineTwo.createdPipelineWithAllStagesPassed())
                .toList();

        PipelineInstanceModels pipelineInstanceModels = pipelineHistoryService.loadPipelineHistoryData(new Username(new CaseInsensitiveString("admin1")), pipelineTwo.pipelineName, 0, pipelineList.get(2).getId(), 10);

        assertThat(pipelineInstanceModels.size()).isEqualTo(2);
        assertThat(pipelineInstanceModels.get(0).getId()).isEqualTo(pipelineList.get(4).getId());
        assertThat(pipelineInstanceModels.get(1).getId()).isEqualTo(pipelineList.get(3).getId());
    }

    private void assertPipeline(PipelineInstanceModel pipelineInstance, Pipeline instance, HttpOperationResult operationResult) {
        assertThat(operationResult.canContinue()).describedAs(operationResult.detailedMessage()).isTrue();
        assertThat(pipelineInstance.getCounter()).isEqualTo(instance.getCounter());
        assertThat(pipelineInstance.getName()).isEqualTo(instance.getName());
    }
}
