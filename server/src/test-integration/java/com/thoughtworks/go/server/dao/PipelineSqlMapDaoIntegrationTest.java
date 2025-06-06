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
package com.thoughtworks.go.server.dao;

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.GoConfigDao;
import com.thoughtworks.go.config.PipelineConfig;
import com.thoughtworks.go.config.StageConfig;
import com.thoughtworks.go.config.exceptions.RecordNotFoundException;
import com.thoughtworks.go.config.materials.MaterialConfigs;
import com.thoughtworks.go.config.materials.Materials;
import com.thoughtworks.go.config.materials.ScmMaterial;
import com.thoughtworks.go.config.materials.dependency.DependencyMaterial;
import com.thoughtworks.go.config.materials.git.GitMaterial;
import com.thoughtworks.go.config.materials.perforce.P4Material;
import com.thoughtworks.go.config.materials.svn.SvnMaterial;
import com.thoughtworks.go.domain.*;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.domain.materials.Material;
import com.thoughtworks.go.domain.materials.Modification;
import com.thoughtworks.go.domain.materials.ModifiedAction;
import com.thoughtworks.go.domain.materials.ModifiedFile;
import com.thoughtworks.go.domain.materials.dependency.DependencyMaterialRevision;
import com.thoughtworks.go.helper.*;
import com.thoughtworks.go.presentation.pipelinehistory.PipelineInstanceModel;
import com.thoughtworks.go.presentation.pipelinehistory.PipelineInstanceModels;
import com.thoughtworks.go.presentation.pipelinehistory.StageInstanceModel;
import com.thoughtworks.go.presentation.pipelinehistory.StageInstanceModels;
import com.thoughtworks.go.server.cache.GoCache;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.materials.DependencyMaterialUpdateNotifier;
import com.thoughtworks.go.server.persistence.MaterialRepository;
import com.thoughtworks.go.server.service.InstanceFactory;
import com.thoughtworks.go.server.service.PipelinePauseService;
import com.thoughtworks.go.server.service.ScheduleService;
import com.thoughtworks.go.server.service.ScheduleTestUtil;
import com.thoughtworks.go.server.transaction.TransactionTemplate;
import com.thoughtworks.go.util.GoConfigFileHelper;
import com.thoughtworks.go.util.GoConstants;
import com.thoughtworks.go.util.TestingClock;
import com.thoughtworks.go.util.TimeProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.thoughtworks.go.domain.PersistentObject.NOT_PERSISTED;
import static com.thoughtworks.go.helper.MaterialsMother.svnMaterial;
import static com.thoughtworks.go.helper.ModificationsMother.*;
import static com.thoughtworks.go.util.GoConstants.DEFAULT_APPROVED_BY;
import static com.thoughtworks.go.util.IBatisUtil.arguments;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;


@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml",
        "classpath:/spring-all-servlet.xml",
})
public class PipelineSqlMapDaoIntegrationTest {
    @Autowired
    private StageDao stageDao;
    @Autowired
    private PipelineSqlMapDao pipelineDao;
    @Autowired
    private JobInstanceDao jobInstanceDao;
    @Autowired
    private DatabaseAccessHelper dbHelper;
    @Autowired
    private MaterialRepository materialRepository;
    @Autowired
    private GoCache goCache;
    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PipelinePauseService pipelinePauseService;
    @Autowired
    private GoConfigDao goConfigDao;
    @Autowired
    private InstanceFactory instanceFactory;
    @Autowired
    private DependencyMaterialUpdateNotifier notifier;

    private String md5 = "md5-test";
    private ScheduleTestUtil u;
    private GoConfigFileHelper configHelper;

    @BeforeEach
    public void setup() throws Exception {
        dbHelper.onSetUp();
        goCache.clear();
        configHelper = new GoConfigFileHelper();
        configHelper.usingCruiseConfigDao(goConfigDao);
        u = new ScheduleTestUtil(transactionTemplate, materialRepository, dbHelper, configHelper);
        notifier.disableUpdates();
    }

    @AfterEach
    public void teardown() throws Exception {
        notifier.enableUpdates();
        dbHelper.onTearDown();

    }

    private Pipeline schedulePipelineWithStages(PipelineConfig pipelineConfig) {
        BuildCause buildCause = BuildCause.createWithModifications(modifyOneFile(pipelineConfig), "");
        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, buildCause, new DefaultSchedulingContext(DEFAULT_APPROVED_BY), "md5-test", new TimeProvider());
        assertNotInserted(pipeline.getId());
        savePipeline(pipeline);
        long pipelineId = pipeline.getId();
        assertIsInserted(pipelineId);
        return pipeline;
    }

    private void savePipeline(final Pipeline pipeline) {
        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                materialRepository.save(pipeline.getBuildCause().getMaterialRevisions());
                pipelineDao.saveWithStages(pipeline);
            }
        });
    }

    private void schedulePipelineWithoutCounter(PipelineConfig pipelineConfig) {
        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createWithModifications(modifyOneFile(pipelineConfig), ""),
                new DefaultSchedulingContext(
                        "anyone"), "md5-test", new TimeProvider());
        save(pipeline);
        for (Stage stage : pipeline.getStages()) {
            stageDao.saveWithJobs(pipeline, stage);
        }
    }

    private void save(final Pipeline pipeline) {
        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                materialRepository.save(pipeline.getBuildCause().getMaterialRevisions());
                pipelineDao.save(pipeline);
            }
        });
    }

    @Test
    public void shouldLoadNaturalOrder() {
        Pipeline pipeline = new Pipeline("Test", BuildCause.createManualForced(), new Stage("dev", new JobInstances(new JobInstance("unit")), "anonymous", null, "manual", new TimeProvider()));
        savePipeline(pipeline);
        dbHelper.updateNaturalOrder(pipeline.getId(), 2.5);
        PipelineInstanceModel loaded = pipelineDao.loadHistory("Test").get(0);
        PipelineInstanceModel loadedById = pipelineDao.loadHistory(pipeline.getId());
        assertThat(loadedById.getNaturalOrder()).isEqualTo(2.5);
        assertThat(loaded.getNaturalOrder()).isEqualTo(2.5);
        assertThat(loaded.isBisect()).isTrue();
    }

    @Test
    public void shouldLoadStageResult() {
        Stage stage = new Stage("dev", new JobInstances(new JobInstance("unit")), "anonymous", null, "manual", new TimeProvider());
        stage.building();
        Pipeline pipeline = new Pipeline("Test", BuildCause.createManualForced(), stage);
        savePipeline(pipeline);
        PipelineInstanceModel loaded = pipelineDao.loadHistory("Test").get(0);
        assertThat(loaded.getStageHistory().get(0).getResult()).isEqualTo(StageResult.Unknown);
        PipelineInstanceModel loadedById = pipelineDao.loadHistory(pipeline.getId());
        assertThat(loadedById.getStageHistory().get(0).getResult()).isEqualTo(StageResult.Unknown);
    }

    @Test
    public void shouldLoadStageIdentifier() {
        Stage stage = new Stage("dev", new JobInstances(new JobInstance("unit")), "anonymous", null, "manual", new TimeProvider());
        stage.building();
        Pipeline pipeline = new Pipeline("Test", BuildCause.createManualForced(), stage);
        savePipeline(pipeline);
        PipelineInstanceModel loaded = pipelineDao.loadHistory("Test").get(0);
        StageInstanceModel historicalStage = loaded.getStageHistory().get(0);
        assertThat(historicalStage.getIdentifier()).isEqualTo(new StageIdentifier("Test", loaded.getCounter(), loaded.getLabel(), "dev", historicalStage.getCounter()));
        PipelineInstanceModel loadedById = pipelineDao.loadHistory(pipeline.getId());
        StageInstanceModel historicalStageModelById = loadedById.getStageHistory().get(0);
        assertThat(historicalStageModelById.getIdentifier()).isEqualTo(new StageIdentifier("Test", loadedById.getCounter(), loadedById.getLabel(), "dev", historicalStage.getCounter()));
    }

    @Test
    public void shouldDefaultCounterToZero() {
        assertThat(pipelineDao.getCounterForPipeline("pipeline-with-no-history")).isEqualTo(0);
    }

    @Test
    public void shouldReserveModificationsOrder() {
        MaterialRevisions materialRevisions = ModificationsMother.multipleModifications();
        Pipeline pipeline = new Pipeline("Test", BuildCause.createWithModifications(materialRevisions, ""));
        save(pipeline);

        Pipeline loaded = pipelineDao.loadPipeline(pipeline.getId());
        ModificationsCollector mods = new ModificationsCollector();
        loaded.getBuildCause().getMaterialRevisions().accept(mods);
        assertEquals(ModificationsMother.TODAY_CHECKIN, mods.first().getModifiedTime());
    }

    @Test
    public void shouldLoadModificationsWithChangedFlagApplied() {
        MaterialRevisions materialRevisions = ModificationsMother.multipleModifications();
        materialRevisions.getMaterialRevision(0).markAsChanged();
        Pipeline pipeline = new Pipeline("Test", BuildCause.createWithModifications(materialRevisions, ""));
        save(pipeline);

        Pipeline loaded = pipelineDao.loadPipeline(pipeline.getId());
        assertThat(loaded.getBuildCause().getMaterialRevisions().getMaterialRevision(0).isChanged()).isTrue();
    }

    @Test
    public void shouldLoadModificationsWithNoModifiedFiles() {
        List<Modification> modifications = new ArrayList<>();
        Modification modification = ModificationsMother.oneModifiedFile(ModificationsMother.nextRevision());
        modifications.add(modification);
        modifications.add(new Modification(MOD_USER, MOD_COMMENT, "foo@bar.com", YESTERDAY_CHECKIN, ModificationsMother.nextRevision()));
        SvnMaterial svnMaterial = MaterialsMother.svnMaterial();
        MaterialRevisions revisions = new MaterialRevisions();
        revisions.addRevision(svnMaterial, modifications);

        Pipeline pipeline = new Pipeline("Test", BuildCause.createWithModifications(revisions, ""));
        savePipeline(pipeline);
        Pipeline loaded = pipelineDao.loadPipeline(pipeline.getId());
        ModificationsCollector summary = new ModificationsCollector();
        loaded.getBuildCause().getMaterialRevisions().accept(summary);
        assertThat(summary.numberOfModifications()).isEqualTo(2);
    }

    @Test
    public void shouldSupportModifiedFileWithVeryLongName() {
        Modification modification = ModificationsMother.withModifiedFileWhoseNameLengthIsOneK();

        Pipeline pipeline = new Pipeline("Test",
                BuildCause.createWithModifications(ModificationsMother.createSvnMaterialRevisions(modification), ""));
        savePipeline(pipeline);
        Pipeline loaded = pipelineDao.loadPipeline(pipeline.getId());
        Modification loadedModification = loaded.getBuildCause().getMaterialRevisions().getMaterialRevision(
                0).getModification(0);
        assertEquals(modification, loadedModification);
    }

    @Test
    public void shouldTruncateFileNameIfItIsTooLong() {
        Modification modification = ModificationsMother.withModifiedFileWhoseNameLengthIsMoreThanOneK();

        Pipeline pipeline = new Pipeline("Test",
                BuildCause.createWithModifications(ModificationsMother.createSvnMaterialRevisions(modification), ""));
        savePipeline(pipeline);
        Pipeline loaded = pipelineDao.loadPipeline(pipeline.getId());
        Modification loadedModification = loaded.getBuildCause().getMaterialRevisions().getMaterialRevision(
                0).getModification(0);
        assertThat(loadedModification.getModifiedFiles().get(0).getFileName().length()).isEqualTo(ModifiedFile.MAX_NAME_LENGTH);
    }

    @Test
    public void shouldPersistBuildCauseMessage() {
        MaterialRevisions materialRevisions = ModificationsMother.multipleModifications();
        BuildCause buildCause = BuildCause.createManualForced(materialRevisions, Username.ANONYMOUS);
        Pipeline pipeline = new Pipeline("Test", buildCause);
        save(pipeline);
        Pipeline loaded = pipelineDao.mostRecentPipeline("Test");
        assertThat(loaded.getBuildCauseMessage()).isEqualTo(buildCause.getBuildCauseMessage());
    }

    @Test
    public void shouldReturnNullWhileLoadingMostRecentPipelineForNoPipelineFound() {
        Pipeline loaded = pipelineDao.mostRecentPipeline("Test");
        assertThat(loaded).isInstanceOf(NullPipeline.class);
    }

    @Test
    public void shouldPersistBuildCauseEnvironmentVariables() {
        MaterialRevisions materialRevisions = ModificationsMother.multipleModifications();
        BuildCause buildCause = BuildCause.createManualForced(materialRevisions, Username.ANONYMOUS);
        EnvironmentVariables environmentVariables = new EnvironmentVariables();
        environmentVariables.add(new EnvironmentVariable("VAR1", "value one"));
        environmentVariables.add(new EnvironmentVariable("VAR2", "value two"));
        buildCause.addOverriddenVariables(environmentVariables);
        Pipeline pipeline = new Pipeline("Test", buildCause);
        save(pipeline);
        Pipeline loaded = pipelineDao.mostRecentPipeline("Test");
        EnvironmentVariables variables = new EnvironmentVariables();
        variables.add("VAR1", "value one");
        variables.add("VAR2", "value two");
        assertThat(loaded.getBuildCause().getVariables()).isEqualTo(variables);
        assertThat(loaded.scheduleTimeVariables()).isEqualTo(variables);
    }

    @Test
    public void shouldPersistMaterialsWithRealPassword() {
        MaterialRevisions materialRevisions = new MaterialRevisions();

        addRevision(materialRevisions, MaterialsMother.svnMaterial("http://username:password@localhost"));
        addRevision(materialRevisions, MaterialsMother.hgMaterial("http://username:password@localhost"));
        addRevision(materialRevisions, new GitMaterial("git://username:password@localhost"));
        addRevision(materialRevisions, new P4Material("localhost:1666", "view"));

        BuildCause buildCause = BuildCause.createManualForced(materialRevisions, Username.ANONYMOUS);
        Pipeline pipeline = new Pipeline("Test", buildCause);
        save(pipeline);
        Pipeline loaded = pipelineDao.mostRecentPipeline("Test");
        Materials materials = loaded.getMaterials();
        for (Material material : materials) {
            assertThat(((ScmMaterial) material).getUrl()).doesNotContain("******");
        }
    }

    private void addRevision(MaterialRevisions materialRevisions, Material material) {
        materialRevisions.addRevision(material, ModificationsMother.multipleModificationList());
    }


    @Test
    public void shouldSchedulePipelineWithModifications() {
        Pipeline pipeline = schedulePipelineWithStages(
                PipelineMother.withMaterials("mingle", "dev", BuildPlanMother.withBuildPlans("functional", "unit"))
        );
        assertModifications(pipeline);
    }

    //TODO FIXME sorted by Id is not good.
    // - Comment by Bobby: Sorted by Id is exactly what we want here. Please discuss.
    @Test
    public void shouldLoadMostRecentPipeline() {
        String stageName = "dev";
        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", stageName);
        Pipeline mingle = schedulePipelineWithStages(mingleConfig);

        Pipeline mostRecentPipeline = pipelineDao.mostRecentPipeline(mingle.getName());
        assertThat(mostRecentPipeline.getId()).isEqualTo(mingle.getId());
        assertThat(mostRecentPipeline.getBuildCause().getMaterialRevisions().totalNumberOfModifications()).isEqualTo(1);
    }

    @Test
    public void shouldLoadMostRecentPipelineWithSingleStage() {
        String stageName = "dev";
        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", stageName);
        Pipeline mingle = schedulePipelineWithStages(mingleConfig);

        Pipeline mostRecentPipeline = pipelineDao.mostRecentPipeline(mingle.getName());

        assertThat(mostRecentPipeline.getId()).isEqualTo(mingle.getId());
        assertThat(mostRecentPipeline.getStages().size()).isEqualTo(1);
        assertThat(mostRecentPipeline.getFirstStage().getName()).isEqualTo(stageName);
        assertThat(mostRecentPipeline.getBuildCause().getMaterialRevisions().totalNumberOfModifications()).isEqualTo(1);
    }

    @Test
    public void shouldLoadMostRecentPipelineWithMutipleSameStage() {
        String stageName = "dev";
        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", stageName);
        Pipeline mingle = schedulePipelineWithStages(mingleConfig);

        Stage newInstance = rescheduleStage(stageName, mingleConfig, mingle);

        Pipeline mostRecentPipeline = pipelineDao.mostRecentPipeline(mingle.getName());

        assertThat(mostRecentPipeline.getId()).isEqualTo(mingle.getId());
        assertThat(mostRecentPipeline.getStages().size()).isEqualTo(1);
        assertThat(mostRecentPipeline.getFirstStage().getId()).isEqualTo(newInstance.getId());
        assertThat(mostRecentPipeline.getBuildCause().getMaterialRevisions().totalNumberOfModifications()).isEqualTo(1);
    }

    private Stage rescheduleStage(String stageName, PipelineConfig mingleConfig, Pipeline pipeline) {
        Stage newInstance = instanceFactory.createStageInstance(mingleConfig.findBy(new CaseInsensitiveString(stageName)), new DefaultSchedulingContext("anyone"), md5, new TimeProvider());
        return stageDao.saveWithJobs(pipeline, newInstance);
    }

    @Test
    public void shouldLoadPipelineHistories() {
        String dev = "dev";
        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", dev);
        Pipeline mingle = schedulePipelineWithStages(mingleConfig);
        Stage firstStage = mingle.getFirstStage();
        Pipeline mingle2 = schedulePipelineWithStages(mingleConfig);

        JobInstance instance = firstStage.getJobInstances().first();
        jobInstanceDao.ignore(instance);

        PipelineInstanceModels pipelineHistories = pipelineDao.loadHistory(mingle.getName(), 10, 0);
        assertThat(pipelineHistories.size()).isEqualTo(2);
        StageInstanceModels stageHistories = pipelineHistories.first().getStageHistory();
        assertThat(stageHistories.size()).isEqualTo(1);

        StageInstanceModel history = stageHistories.first();
        assertThat(history.getName()).isEqualTo(dev);
        assertThat(history.getApprovalType()).isEqualTo(GoConstants.APPROVAL_SUCCESS);
        assertThat(history.getBuildHistory().size()).isEqualTo(2);

        assertThat(pipelineHistories.get(1).getName()).isEqualTo("mingle");
    }

    @Test
    public void shouldReturnEmptyListWhenThereIsNoPipelineHistory() {
        PipelineInstanceModels pipelineHistories = pipelineDao.loadHistory("something not exist", 10, 0);
        assertThat(pipelineHistories.size()).isEqualTo(0);
    }

    @Test
    public void shouldLoadPipelineHistoryOnlyForSuppliedPipeline() {
        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev");
        PipelineConfig otherConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("other", "dev");

        schedulePipelineWithStages(mingleConfig);
        schedulePipelineWithStages(otherConfig);
        schedulePipelineWithStages(mingleConfig);
        schedulePipelineWithStages(otherConfig);
        schedulePipelineWithStages(mingleConfig);

        PipelineInstanceModels pipelineHistories = pipelineDao.loadHistory("mingle", 10, 0);

        assertThat(pipelineHistories.get(0).getName()).isEqualTo("mingle");
        assertThat(pipelineHistories.get(1).getName()).isEqualTo("mingle");
        assertThat(pipelineHistories.get(2).getName()).isEqualTo("mingle");
        assertThat(pipelineHistories.size()).isEqualTo(3);
    }

    @Test
    public void shouldSupportPipelinesWithoutCounterWhenLoadHistory() {
        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev");
        schedulePipelineWithoutCounter(mingleConfig);

        PipelineInstanceModels models = pipelineDao.loadHistory(CaseInsensitiveString.str(mingleConfig.name()), 10, 0);
        assertThat(models.size()).isEqualTo(1);
    }

    @Test
    public void shouldLoadAllActivePipelines() {
        PipelineConfig twistConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("twist", "dev", "ft");
        Pipeline twistPipeline = dbHelper.newPipelineWithAllStagesPassed(twistConfig);
        List<CaseInsensitiveString> allPipelineNames = goConfigDao.load().getAllPipelineNames();
        if (!allPipelineNames.contains(new CaseInsensitiveString("twist"))) {
            goConfigDao.addPipeline(twistConfig, "pipelinesqlmapdaotest");
        }

        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev", "ft");
        if (!allPipelineNames.contains(new CaseInsensitiveString("mingle"))) {
            goConfigDao.addPipeline(mingleConfig, "pipelinesqlmapdaotest");
        }


        Pipeline firstPipeline = dbHelper.newPipelineWithAllStagesPassed(mingleConfig);
        Pipeline secondPipeline = dbHelper.newPipelineWithFirstStagePassed(mingleConfig);
        dbHelper.scheduleStage(secondPipeline, mingleConfig.get(1));
        Pipeline thirdPipeline = dbHelper.newPipelineWithFirstStageScheduled(mingleConfig);

        PipelineInstanceModels pipelineHistories = pipelineDao.loadActivePipelines();
        assertThat(pipelineHistories.size()).isEqualTo(3);
        assertThat(pipelineHistories.get(0).getId()).isEqualTo(thirdPipeline.getId());
        assertThat(pipelineHistories.get(1).getId()).isEqualTo(secondPipeline.getId());
        assertThat(pipelineHistories.get(2).getId()).isEqualTo(twistPipeline.getId());
        assertThat(pipelineHistories.get(0).getBuildCause().getMaterialRevisions().isEmpty()).isFalse();
    }

    @Test
    public void shouldLoadAllActivePipelinesPresentInConfigOnly() {
        PipelineConfig twistConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("twist", "dev", "ft");
        Pipeline twistPipeline = dbHelper.newPipelineWithAllStagesPassed(twistConfig);
        List<CaseInsensitiveString> allPipelineNames = goConfigDao.load().getAllPipelineNames();
        if (!allPipelineNames.contains(new CaseInsensitiveString("twist"))) {
            goConfigDao.addPipeline(twistConfig, "pipelinesqlmapdaotest");
        }
        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev", "ft");

        dbHelper.newPipelineWithAllStagesPassed(mingleConfig);
        Pipeline minglePipeline = dbHelper.newPipelineWithFirstStagePassed(mingleConfig);

        PipelineInstanceModels pipelineHistories = pipelineDao.loadActivePipelines();
        assertThat(pipelineHistories.size()).isEqualTo(1);
        assertThat(pipelineHistories.get(0).getId()).isEqualTo(twistPipeline.getId());
        assertThat(pipelineHistories.get(0).getBuildCause().getMaterialRevisions().isEmpty()).isFalse();

        if (!allPipelineNames.contains(new CaseInsensitiveString("mingle"))) {
            goConfigDao.addPipeline(mingleConfig, "pipelinesqlmapdaotest");
        }

        pipelineHistories = pipelineDao.loadActivePipelines();
        assertThat(pipelineHistories.size()).isEqualTo(2);
        assertThat(pipelineHistories.get(0).getId()).isEqualTo(minglePipeline.getId());
        assertThat(pipelineHistories.get(1).getId()).isEqualTo(twistPipeline.getId());
        assertThat(pipelineHistories.get(1).getBuildCause().getMaterialRevisions().isEmpty()).isFalse();
    }

    @Test
    public void loadAllActivePipelinesPresentInConfigOnlyShouldBeCaseInsensitive() {
        PipelineConfig twistConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("twist", "dev", "ft");
        Pipeline twistPipeline = dbHelper.newPipelineWithAllStagesPassed(twistConfig);
        List<CaseInsensitiveString> allPipelineNames = goConfigDao.load().getAllPipelineNames();
        if (!allPipelineNames.contains(new CaseInsensitiveString("twist"))) {
            PipelineConfig pipelineConfigWithDifferentCase = PipelineMother.createPipelineConfig("TWIST", twistConfig.materialConfigs(), "dev", "ft");
            goConfigDao.addPipeline(pipelineConfigWithDifferentCase, "pipelinesqlmapdaotest");
        }
        PipelineInstanceModels pipelineHistories = pipelineDao.loadActivePipelines();
        assertThat(pipelineHistories.size()).isEqualTo(1);
        assertThat(pipelineHistories.get(0).getId()).isEqualTo(twistPipeline.getId());
        assertThat(pipelineHistories.get(0).getBuildCause().getMaterialRevisions().isEmpty()).isFalse();

    }

    @Test
    public void shouldLoadAllActivePipelinesPresentInConfigAndAlsoTheScheduledStagesOfPipelinesNotInConfig() {
        PipelineConfig twistConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("twist", "dev", "ft");
        Pipeline twistPipeline = dbHelper.newPipelineWithAllStagesPassed(twistConfig);
        List<CaseInsensitiveString> allPipelineNames = goConfigDao.load().getAllPipelineNames();
        if (!allPipelineNames.contains(new CaseInsensitiveString("twist"))) {
            goConfigDao.addPipeline(twistConfig, "pipelinesqlmapdaotest");
        }
        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev", "ft");
        if (!allPipelineNames.contains(new CaseInsensitiveString("mingle"))) {
            goConfigDao.addPipeline(mingleConfig, "pipelinesqlmapdaotest");
        }
        dbHelper.newPipelineWithAllStagesPassed(mingleConfig);
        Pipeline secondPipeline = dbHelper.newPipelineWithFirstStagePassed(mingleConfig);
        dbHelper.scheduleStage(secondPipeline, mingleConfig.get(1));
        Pipeline thirdPipeline = dbHelper.newPipelineWithFirstStageScheduled(mingleConfig);

        PipelineInstanceModels pipelineHistories = pipelineDao.loadActivePipelines();
        assertThat(pipelineHistories.size()).isEqualTo(3);
        assertThat(pipelineHistories.get(0).getId()).isEqualTo(thirdPipeline.getId());
        assertThat(pipelineHistories.get(1).getId()).isEqualTo(secondPipeline.getId());
        assertThat(pipelineHistories.get(2).getId()).isEqualTo(twistPipeline.getId());
        assertThat(pipelineHistories.get(0).getBuildCause().getMaterialRevisions().isEmpty()).isFalse();
    }


    @Test
    public void shouldLoadAllActivePipelinesEvenWhenThereIsStageStatusChange() {
        PipelineConfig twistConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("twist", "dev", "ft");
        goConfigDao.addPipeline(twistConfig, "pipelinesqlmapdaotest");
        Pipeline twistPipeline = dbHelper.newPipelineWithAllStagesPassed(twistConfig);
        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev", "ft");
        goConfigDao.addPipeline(mingleConfig, "pipelinesqlmapdaotest");
        final Pipeline firstPipeline = dbHelper.newPipelineWithAllStagesPassed(mingleConfig);
        final Pipeline secondPipeline = dbHelper.newPipelineWithFirstStagePassed(mingleConfig);
        dbHelper.scheduleStage(secondPipeline, mingleConfig.get(1));
        Pipeline thirdPipeline = dbHelper.newPipelineWithFirstStageScheduled(mingleConfig);
        Thread stageStatusChanger = new Thread() {
            @Override
            public void run() {
                for (; ; ) {
                    pipelineDao.stageStatusChanged(secondPipeline.findStage("dev"));
                    if (super.isInterrupted()) {
                        break;
                    }
                }
            }
        };
        stageStatusChanger.setDaemon(true);
        stageStatusChanger.start();
        PipelineInstanceModels pipelineHistories = pipelineDao.loadActivePipelines();
        assertThat(pipelineHistories.size()).isEqualTo(3);
        assertThat(pipelineHistories.get(0).getId()).isEqualTo(thirdPipeline.getId());
        assertThat(pipelineHistories.get(1).getId()).isEqualTo(secondPipeline.getId());
        assertThat(pipelineHistories.get(2).getId()).isEqualTo(twistPipeline.getId());
        assertThat(pipelineHistories.get(0).getBuildCause().getMaterialRevisions().isEmpty()).isFalse();
        stageStatusChanger.interrupt();
    }

    @Test
    public void shouldLoadPipelineHistoriesWithMultipleSameStage() {
        String stageName = "dev";
        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", stageName);
        Pipeline mingle = schedulePipelineWithStages(mingleConfig);

        Stage newInstance = rescheduleStage(stageName, mingleConfig, mingle);

        PipelineInstanceModels pipelineHistories = pipelineDao.loadHistory(mingle.getName(), 10, 0);
        assertThat(pipelineHistories.size()).isEqualTo(1);
        StageInstanceModels stageHistories = pipelineHistories.first().getStageHistory();
        assertThat(stageHistories.size()).isEqualTo(1);
        StageInstanceModel history = stageHistories.first();
        assertThat(history.getName()).isEqualTo(stageName);
        assertThat(history.getId()).isEqualTo(newInstance.getId());
        assertThat(history.getBuildHistory().size()).isEqualTo(2);
    }

    @Test
    public void shouldSaveUserNameAsCausedBy() {
        PipelineConfig pipelineConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev");
        BuildCause cause = BuildCause.createManualForced(modifyOneFile(pipelineConfig), Username.ANONYMOUS);
        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, cause, new DefaultSchedulingContext(MOD_USER), md5, new TimeProvider());
        assertNotInserted(pipeline.getId());
        savePipeline(pipeline);
        Pipeline pipelineFromDB = pipelineDao.loadPipeline(pipeline.getId());
        BuildCause buildCause = pipelineFromDB.getBuildCause();
        //TODO: This is a known bug #3248 in the way that the pipeline user is stored. We should fix with the new UI.
        assertThat(buildCause.getBuildCauseMessage()).isEqualTo("Forced by " + Username.ANONYMOUS.getDisplayName());
    }

    @Test
    public void shouldReturnCorrectCount() {
        PipelineConfig mingle = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev");
        assertThat(pipelineDao.count(CaseInsensitiveString.str(mingle.name()))).isEqualTo(0);
        dbHelper.pass(schedulePipelineWithStages(mingle));
        assertThat(pipelineDao.count(CaseInsensitiveString.str(mingle.name()))).isEqualTo(1);
    }

    @Test
    public void shouldStoreAndRetrieveSvnMaterials() {
        SvnMaterial svnMaterial = svnMaterial("svnUrl", "folder");
        PipelineConfig pipelineConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev");
        pipelineConfig.setMaterialConfigs(new MaterialConfigs(svnMaterial.config()));

        MaterialRevisions materialRevisions = new MaterialRevisions();
        materialRevisions.addRevision(svnMaterial, ModificationsMother.multipleModificationList());

        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(materialRevisions,
                Username.ANONYMOUS), new DefaultSchedulingContext(
                DEFAULT_APPROVED_BY), md5, new TimeProvider());
        assertNotInserted(pipeline.getId());
        savePipeline(pipeline);
        Pipeline pipelineFromDB = pipelineDao.loadPipeline(pipeline.getId());
        final Materials materials = pipelineFromDB.getMaterials();

        assertThat(materials.get(0)).isEqualTo(svnMaterial);
    }

    @Test
    public void shouldRetrieveModificationsSortedBySavedOrder() {
        GitMaterial gitMaterial = new GitMaterial("url");
        PipelineConfig pipelineConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev");
        pipelineConfig.setMaterialConfigs(new MaterialConfigs(gitMaterial.config()));

        Modification firstModification = new Modification(new Date(), "1", "MOCK_LABEL-12", null);
        Modification secondModification = new Modification(new Date(), "2", "MOCK_LABEL-12", null);
        secondModification.setModifiedFiles(List.of(new ModifiedFile("filename", "foldername", ModifiedAction.modified)));

        MaterialRevisions materialRevisions = new MaterialRevisions();
        materialRevisions.addRevision(gitMaterial, firstModification, secondModification);

        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(materialRevisions,
                Username.ANONYMOUS), new DefaultSchedulingContext(
                DEFAULT_APPROVED_BY), md5, new TimeProvider());

        assertNotInserted(pipeline.getId());
        save(pipeline);

        Pipeline pipelineFromDB = pipelineDao.mostRecentPipeline(pipeline.getName());

        List<MaterialRevision> revisionsFromDB = pipelineFromDB.getMaterialRevisions().getRevisions();
        List<Modification> modificationsFromDB = revisionsFromDB.get(0).getModifications();
        assertThat(modificationsFromDB.size()).isEqualTo(2);
        assertThat(modificationsFromDB.get(0).getRevision()).isEqualTo("1");
        assertThat(modificationsFromDB.get(1).getRevision()).isEqualTo("2");
    }

    @Test
    public void shouldStoreAndRetrieveDependencyMaterials() {
        DependencyMaterial dependencyMaterial = new DependencyMaterial(new CaseInsensitiveString("pipeline-name"), new CaseInsensitiveString("stage-name"));

        PipelineConfig pipelineConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev");
        pipelineConfig.setMaterialConfigs(new MaterialConfigs(dependencyMaterial.config()));

        MaterialRevisions materialRevisions = new MaterialRevisions();
        materialRevisions.addRevision(DependencyMaterialRevision.create("pipeline-name", -12, "1234", "stage-name", 1).convert(dependencyMaterial, new Date()));


        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(materialRevisions,
                Username.ANONYMOUS), new DefaultSchedulingContext(
                DEFAULT_APPROVED_BY), md5, new TimeProvider());
        assertNotInserted(pipeline.getId());
        savePipeline(pipeline);
        Pipeline pipelineFromDB = pipelineDao.loadPipeline(pipeline.getId());

        final Materials materials = pipelineFromDB.getMaterials();

        assertThat(materials.get(0)).isEqualTo(dependencyMaterial);
    }

    @Test
    public void shouldStoreAndRetrieveDependencyMaterialsWithMaxAllowedRevision() {
        char[] name = new char[255];
        for (int i = 0; i < 255; i++) {
            name[i] = 'a';
        }
        final String s = new String(name);
        final String s1 = new String(name);
        DependencyMaterial dependencyMaterial = new DependencyMaterial(new CaseInsensitiveString(s), new CaseInsensitiveString(s1));
        PipelineConfig pipelineConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev");
        pipelineConfig.setMaterialConfigs(new MaterialConfigs(dependencyMaterial.config()));

        MaterialRevisions materialRevisions = new MaterialRevisions();
        DependencyMaterialRevision revision = DependencyMaterialRevision.create(new String(name), -10, new String(name), new String(name), Integer.MAX_VALUE);
        materialRevisions.addRevision(revision.convert(dependencyMaterial, new Date()));

        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(materialRevisions,
                Username.ANONYMOUS), new DefaultSchedulingContext(
                DEFAULT_APPROVED_BY), md5, new TimeProvider());
        assertNotInserted(pipeline.getId());
        savePipeline(pipeline);
        Pipeline pipelineFromDB = pipelineDao.loadPipeline(pipeline.getId());

        final Materials materials = pipelineFromDB.getMaterials();

        assertThat(materials.get(0)).isEqualTo(dependencyMaterial);
    }

    @Test
    public void shouldStoreAndRetrieveMultipleSvnMaterials() {
        SvnMaterial svnMaterial1 = svnMaterial("svnUrl1", "folder1");
        SvnMaterial svnMaterial2 = svnMaterial("svnUrl2", "folder2");
        PipelineConfig pipelineConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev");
        pipelineConfig.setMaterialConfigs(new MaterialConfigs(svnMaterial1.config(), svnMaterial2.config()));

        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(modifyOneFile(pipelineConfig),
                Username.ANONYMOUS),
                new DefaultSchedulingContext(
                        DEFAULT_APPROVED_BY), md5, new TimeProvider());
        assertNotInserted(pipeline.getId());
        savePipeline(pipeline);

        Pipeline pipelineFromDB = pipelineDao.loadPipeline(pipeline.getId());

        Materials materials = pipelineFromDB.getMaterials();
        assertThat(materials).contains(svnMaterial1);
        assertThat(materials).contains(svnMaterial2);
    }

    @Test
    public void shouldStoreAndRetrieveMaterialRevisions() {
        SvnMaterial svnMaterial1 = svnMaterial("svnUrl1", "folder1");
        SvnMaterial svnMaterial2 = svnMaterial("svnUrl2", "folder2");
        PipelineConfig pipelineConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev");
        pipelineConfig.setMaterialConfigs(new MaterialConfigs(svnMaterial1.config(), svnMaterial2.config()));

        MaterialRevisions revisions = new MaterialRevisions();
        revisions.addRevision(svnMaterial1, new Modification("user1", "comment1", null, new Date(), "1"));
        revisions.addRevision(svnMaterial2, new Modification("user2", "comment2", null, new Date(), "2"));

        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(revisions, Username.ANONYMOUS),
                new DefaultSchedulingContext(DEFAULT_APPROVED_BY), md5, new TimeProvider());
        assertNotInserted(pipeline.getId());

        save(pipeline);

        Pipeline pipelineFromDB = pipelineDao.loadPipeline(pipeline.getId());

        BuildCause buildCause = pipelineFromDB.getBuildCause();
        assertEquals(revisions, buildCause.getMaterialRevisions());
    }

    @Test
    public void shouldStoreAndRetrieveHgMaterialsFromDatabase() {
        Materials materials = MaterialsMother.hgMaterials("hgUrl", "hgdir");
        PipelineConfig pipelineConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev");
        pipelineConfig.setMaterialConfigs(materials.convertToConfigs());

        final MaterialRevisions originalMaterialRevision = multipleModificationsInHg(pipelineConfig);
        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(originalMaterialRevision, Username.ANONYMOUS), new DefaultSchedulingContext(
                DEFAULT_APPROVED_BY), md5, new TimeProvider());
        save(pipeline);

        Pipeline pipelineFromDB = pipelineDao.loadPipeline(pipeline.getId());
        final MaterialRevisions materialRevisions = pipelineFromDB.getBuildCause().getMaterialRevisions();

        assertEquals(originalMaterialRevision, materialRevisions);
        assertThat(materialRevisions.getMaterialRevision(0).getRevision().getRevision()).isEqualTo("9fdcf27f16eadc362733328dd481d8a2c29915e1");
        assertThat(pipelineFromDB.getMaterials()).isEqualTo(materials);
    }

    @Test
    public void shouldHaveUrlInGitMaterials() {
        Materials gitMaterials = MaterialsMother.gitMaterials("gitUrl");
        PipelineConfig pipelineConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev");
        pipelineConfig.setMaterialConfigs(gitMaterials.convertToConfigs());

        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig,
                BuildCause.createManualForced(modifyOneFile(pipelineConfig), Username.ANONYMOUS),
                new DefaultSchedulingContext(DEFAULT_APPROVED_BY),
                md5, new TimeProvider());
        assertNotInserted(pipeline.getId());
        savePipeline(pipeline);
        Pipeline pipelineFromDB = pipelineDao.loadPipeline(pipeline.getId());
        Materials materials = pipelineFromDB.getMaterials();
        GitMaterial gitMaterial = (GitMaterial) materials.get(0);
        assertThat(gitMaterial.getUrl()).isEqualTo("gitUrl");
    }

    @Test
    public void shouldSupportBranchInGitMaterials() {
        Materials branchedMaterials = MaterialsMother.gitMaterials("gitUrl", "foo");
        PipelineConfig pipelineConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev");
        pipelineConfig.setMaterialConfigs(branchedMaterials.convertToConfigs());

        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(modifyOneFile(pipelineConfig),
                Username.ANONYMOUS),
                new DefaultSchedulingContext(
                        DEFAULT_APPROVED_BY), md5, new TimeProvider());
        assertNotInserted(pipeline.getId());
        savePipeline(pipeline);
        Pipeline pipelineFromDB = pipelineDao.loadPipeline(pipeline.getId());
        Materials materials = pipelineFromDB.getMaterials();
        GitMaterial gitMaterial = (GitMaterial) materials.get(0);
        assertThat(gitMaterial.getBranch()).isEqualTo("foo");
    }

    @Test
    public void shouldSupportSubmoduleFolderInGitMaterials() {
        Materials materials = MaterialsMother.gitMaterials("gitUrl", "submoduleFolder", null);
        PipelineConfig pipelineConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev");
        pipelineConfig.setMaterialConfigs(materials.convertToConfigs());

        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(modifyOneFile(pipelineConfig),
                Username.ANONYMOUS), new DefaultSchedulingContext(DEFAULT_APPROVED_BY), md5, new TimeProvider());
        assertNotInserted(pipeline.getId());
        save(pipeline);
        Pipeline pipelineFromDB = pipelineDao.loadPipeline(pipeline.getId());

        Materials materialsFromDB = pipelineFromDB.getMaterials();
        GitMaterial gitMaterial = (GitMaterial) materialsFromDB.get(0);
        assertThat(gitMaterial.getSubmoduleFolder()).isEqualTo("submoduleFolder");
    }

    @Test
    public void shouldHaveServerAndPortAndViewAndUseTicketsInP4Materials() {
        String p4view = "//depot/... //localhost/...";
        Materials p4Materials = MaterialsMother.p4Materials(p4view);
        P4Material p4Material = (P4Material) p4Materials.first();
        p4Material.setUseTickets(true);
        PipelineConfig pipelineConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev");
        pipelineConfig.setMaterialConfigs(p4Materials.convertToConfigs());

        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(modifyOneFile(pipelineConfig),
                Username.ANONYMOUS),
                new DefaultSchedulingContext(
                        DEFAULT_APPROVED_BY), md5, new TimeProvider());
        assertNotInserted(pipeline.getId());
        savePipeline(pipeline);

        Pipeline pipelineFromDB = pipelineDao.loadPipeline(pipeline.getId());
        Materials materials = pipelineFromDB.getMaterials();
        assertThat(materials.get(0)).isEqualTo(p4Material);
    }

    @Test
    public void shouldSupportMultipleP4Materials() {
        String p4view1 = "//depot1/... //localhost1/...";
        String p4view2 = "//depot2/... //localhost2/...";
        Material p4Material1 = MaterialsMother.p4Materials(p4view1).get(0);
        Material p4Material2 = MaterialsMother.p4Materials(p4view2).get(0);
        Materials materials = new Materials(p4Material1, p4Material2);

        PipelineConfig pipelineConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("mingle", "dev");
        pipelineConfig.setMaterialConfigs(materials.convertToConfigs());

        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(modifyOneFile(pipelineConfig),
                Username.ANONYMOUS),
                new DefaultSchedulingContext(
                        DEFAULT_APPROVED_BY), md5, new TimeProvider());
        assertNotInserted(pipeline.getId());
        savePipeline(pipeline);
        Pipeline pipelineFromDB = pipelineDao.loadPipeline(pipeline.getId());
        final Materials loaded = pipelineFromDB.getMaterials();
        assertThat(loaded.get(0)).isEqualTo(p4Material1);
        assertThat(loaded.get(1)).isEqualTo(p4Material2);
    }

    @Test
    public void shouldFindPipelineByNameAndCounterCaseInsensitively() {
        Pipeline pipeline = new Pipeline("Test", BuildCause.createWithEmptyModifications());
        savePipeline(pipeline);
        Pipeline loadedId = pipelineDao.findPipelineByNameAndCounter("Test", pipeline.getCounter());
        assertThat(loadedId.getId()).isEqualTo(pipeline.getId());
        loadedId = pipelineDao.findPipelineByNameAndCounter("tEsT", pipeline.getCounter());
        assertThat(loadedId.getId()).isEqualTo(pipeline.getId());
    }

    @Test
    public void shouldFindTheRightPipelineWithUseOfTilde() {
        Pipeline correctPipeline = new Pipeline("Test", BuildCause.createWithEmptyModifications());
        savePipeline(correctPipeline);
        Pipeline incorrectPipeline = new Pipeline("Tests", BuildCause.createWithEmptyModifications());
        savePipeline(incorrectPipeline);
        Pipeline loadedId = pipelineDao.findPipelineByNameAndCounter(correctPipeline.getName(), correctPipeline.getCounter());
        assertThat(loadedId.getId()).isEqualTo(correctPipeline.getId());
        loadedId = pipelineDao.findPipelineByNameAndCounter(correctPipeline.getName().toLowerCase(), correctPipeline.getCounter());
        assertThat(loadedId.getId()).isEqualTo(correctPipeline.getId());
    }

    @Test
    public void shouldInvalidateSessionAndFetchNewPipelineByNameAndCounter_WhenPipelineIsPersisted() {
        Pipeline pipeline = new Pipeline("Test", BuildCause.createWithEmptyModifications());
        assertThat(pipelineDao.findPipelineByNameAndCounter("Test", 1)).isNull();

        savePipeline(pipeline);
        Pipeline loadedPipeline = pipelineDao.findPipelineByNameAndCounter("Test", pipeline.getCounter());
        assertThat(pipelineDao.findPipelineByNameAndCounter("Test", 1)).isNotNull();
        assertThat(loadedPipeline.getId()).isEqualTo(pipeline.getId());
    }

    @Test
    public void shouldInvalidateSessionAndFetchNewPipelineByNameAndLabel_WhenPipelineIsPersisted() {
        Pipeline pipeline = new Pipeline("Test", BuildCause.createWithEmptyModifications());
        assertThat(pipelineDao.findPipelineByNameAndLabel("Test", "1")).isNull();

        savePipeline(pipeline);
        Pipeline loadedPipeline = pipelineDao.findPipelineByNameAndLabel("Test", pipeline.getLabel());
        assertThat(loadedPipeline).isNotNull();
        assertThat(loadedPipeline.getId()).isEqualTo(pipeline.getId());
    }

    @Test
    public void shouldFindPipelineByNameAndLabel() {
        Pipeline pipeline = new Pipeline("Test", BuildCause.createWithEmptyModifications());
        savePipeline(pipeline);
        Pipeline loadedId = pipelineDao.findPipelineByNameAndLabel("Test", pipeline.getLabel());
        assertThat(loadedId.getId()).isEqualTo(pipeline.getId());
    }

    @Test
    public void findPipelineByNameAndLabelShouldReturnLatestWhenLabelRepeated() {
        Pipeline pipeline = new Pipeline("Test", BuildCause.createWithEmptyModifications());
        savePipeline(pipeline);
        Pipeline newPipeline = dbHelper.save(pipeline);
        Pipeline loadedId = pipelineDao.findPipelineByNameAndLabel("Test", newPipeline.getLabel());
        assertThat(loadedId.getId()).isEqualTo(newPipeline.getId());
    }

    @Test
    public void shouldSaveModificationWithChangedAsTrue() {
        Pipeline pipeline = new Pipeline("Test", BuildCause.createWithModifications(revisions(true), ""));
        save(pipeline);
        Pipeline loaded = pipelineDao.loadPipeline(pipeline.getId());
        assertThat(loaded.getMaterialRevisions().getMaterialRevision(0).isChanged()).isTrue();
    }

    @Test
    public void shouldSaveModificationWithChangedAsFalse() {
        Pipeline pipeline = new Pipeline("Test", BuildCause.createWithModifications(revisions(false), ""));
        save(pipeline);
        Pipeline loaded = pipelineDao.loadPipeline(pipeline.getId());
        assertThat(loaded.getMaterialRevisions().getMaterialRevision(0).isChanged()).isFalse();
    }

    @Test
    public void shouldSaveAndLoadPipeline() {
        Pipeline pipeline = new Pipeline("Test", BuildCause.createWithModifications(revisions(false), ""));
        pipeline.updateCounter(0);
        save(pipeline);
        Pipeline loaded = pipelineDao.loadPipeline(pipeline.getId());
        assertThat(loaded.getCounter()).isEqualTo(1);
    }

    @Test
    public void shouldSaveMixedLabel() {
        Pipeline pipeline = new Pipeline("Test", "mingle-${COUNT}-${mingle}", BuildCause.createWithModifications(revisions(false), ""), new EnvironmentVariables());
        pipeline.updateCounter(0);
        save(pipeline);
        Pipeline loaded = pipelineDao.loadPipeline(pipeline.getId());
        assertThat(loaded.getLabel()).isEqualTo("mingle-1-" + ModificationsMother.currentRevision());
    }

    @Test
    public void shouldSaveAndLoadMaterialsWithName() {
        BuildCause buildCause = BuildCause.createWithModifications(revisions(false), "");
        Pipeline pipeline = new Pipeline("Test", buildCause);
        save(pipeline);
        Pipeline loaded = pipelineDao.loadPipeline(pipeline.getId());
        assertEquals(buildCause, loaded.getBuildCause());
    }


    @Test
    public void shouldFindPipelineThatPassedForStage() {
        PipelineConfig config = PipelineMother.createPipelineConfig("pipeline", new MaterialConfigs(MaterialConfigsMother.hgMaterialConfig()), "firstStage", "secondStage");
        Pipeline pipeline0 = dbHelper.newPipelineWithAllStagesPassed(config);
        dbHelper.updateNaturalOrder(pipeline0.getId(), 4.0);

        Pipeline pipeline1 = dbHelper.newPipelineWithFirstStagePassed(config);
        Stage stage = dbHelper.scheduleStage(pipeline1, config.get(1));
        dbHelper.failStage(stage);
        stage = dbHelper.scheduleStage(pipeline1, config.get(1));
        dbHelper.passStage(stage);
        dbHelper.updateNaturalOrder(pipeline1.getId(), 5.0);

        Pipeline pipeline2 = dbHelper.newPipelineWithFirstStagePassed(config);
        stage = dbHelper.scheduleStage(pipeline2, config.get(1));
        dbHelper.failStage(stage);
        dbHelper.updateNaturalOrder(pipeline2.getId(), 6.0);

        Pipeline pipeline3 = dbHelper.newPipelineWithFirstStagePassed(config);
        dbHelper.updateNaturalOrder(pipeline3.getId(), 7.0);

        Pipeline pipeline4 = dbHelper.newPipelineWithFirstStagePassed(config);
        stage = dbHelper.scheduleStage(pipeline4, config.get(1));
        dbHelper.cancelStage(stage);
        dbHelper.updateNaturalOrder(pipeline4.getId(), 8.0);

        Pipeline pipeline5 = dbHelper.newPipelineWithFirstStagePassed(config);
        dbHelper.scheduleStage(pipeline5, config.get(1));
        dbHelper.updateNaturalOrder(pipeline5.getId(), 9.0);

        Pipeline pipeline6 = dbHelper.newPipelineWithFirstStagePassed(config);
        stage = dbHelper.scheduleStage(pipeline6, config.get(1));
        dbHelper.failStage(stage);
        dbHelper.updateNaturalOrder(pipeline6.getId(), 10.0);

        Pipeline pipeline = pipelineDao.findEarlierPipelineThatPassedForStage("pipeline", "secondStage", 10.0);
        assertThat(pipeline.getId()).isEqualTo(pipeline1.getId());
        assertThat(pipeline.getNaturalOrder()).isEqualTo(5.0);
    }

    @Test
    public void shouldFindPipelineThatPassedForStageAcrossStageRerunsHavingPassedStagesOtherThanLatest() {
        PipelineConfig config = PipelineMother.createPipelineConfig("pipeline", new MaterialConfigs(MaterialConfigsMother.hgMaterialConfig()), "firstStage", "secondStage");
        Pipeline pipeline0 = dbHelper.newPipelineWithAllStagesPassed(config);
        dbHelper.updateNaturalOrder(pipeline0.getId(), 4.0);

        Pipeline pipeline1 = dbHelper.newPipelineWithFirstStagePassed(config);
        Stage stage = dbHelper.scheduleStage(pipeline1, config.get(1));
        dbHelper.failStage(stage);
        stage = dbHelper.scheduleStage(pipeline1, config.get(1));
        dbHelper.passStage(stage);
        dbHelper.updateNaturalOrder(pipeline1.getId(), 5.0);


        Pipeline pipeline5 = dbHelper.newPipelineWithAllStagesPassed(config);
        dbHelper.updateNaturalOrder(pipeline5.getId(), 9.0);
        Stage failedRerun = StageMother.scheduledStage("pipeline", pipeline5.getCounter(), "secondStage", 2, "job");
        failedRerun = stageDao.saveWithJobs(pipeline5, failedRerun);
        dbHelper.failStage(failedRerun);

        Pipeline pipeline6 = dbHelper.newPipelineWithFirstStagePassed(config);
        stage = dbHelper.scheduleStage(pipeline6, config.get(1));
        dbHelper.failStage(stage);
        dbHelper.updateNaturalOrder(pipeline6.getId(), 10.0);

        Pipeline pipeline = pipelineDao.findEarlierPipelineThatPassedForStage("pipeline", "secondStage", 10.0);
        assertThat(pipeline.getNaturalOrder()).isEqualTo(5.0);
        assertThat(pipeline.getId()).isEqualTo(pipeline1.getId());
    }

    @Test
    public void shouldFindPipelineThatPassedForStageAcrossStageReruns() {
        PipelineConfig config = PipelineMother.createPipelineConfig("pipeline", new MaterialConfigs(MaterialConfigsMother.hgMaterialConfig()), "firstStage", "secondStage");
        Pipeline pipeline0 = dbHelper.newPipelineWithAllStagesPassed(config);
        dbHelper.updateNaturalOrder(pipeline0.getId(), 4.0);

        Pipeline pipeline1 = dbHelper.newPipelineWithFirstStagePassed(config);
        Stage stage = dbHelper.scheduleStage(pipeline1, config.get(1));
        dbHelper.failStage(stage);
        stage = dbHelper.scheduleStage(pipeline1, config.get(1));
        dbHelper.passStage(stage);
        dbHelper.updateNaturalOrder(pipeline1.getId(), 5.0);

        Stage passedStageRerun = StageMother.scheduledStage("pipeline", pipeline1.getCounter(), "secondStage", 2, "job");
        passedStageRerun = stageDao.saveWithJobs(pipeline1, passedStageRerun);
        dbHelper.passStage(passedStageRerun);

        Pipeline pipeline5 = dbHelper.newPipelineWithFirstStagePassed(config);
        stage = dbHelper.scheduleStage(pipeline5, config.get(1));
        dbHelper.failStage(stage);
        dbHelper.updateNaturalOrder(pipeline5.getId(), 9.0);

        Pipeline pipeline6 = dbHelper.newPipelineWithFirstStagePassed(config);
        stage = dbHelper.scheduleStage(pipeline6, config.get(1));
        dbHelper.failStage(stage);
        dbHelper.updateNaturalOrder(pipeline6.getId(), 10.0);

        Pipeline pipeline = pipelineDao.findEarlierPipelineThatPassedForStage("pipeline", "secondStage", 10.0);
        assertThat(pipeline.getId()).isEqualTo(pipeline1.getId());
        assertThat(pipeline.getNaturalOrder()).isEqualTo(5.0);
    }

    @Test
    public void shouldReturnTheEarliestFailedPipelineIfThereAreNoPassedStageEver() {
        PipelineConfig config = PipelineMother.createPipelineConfig("pipeline", new MaterialConfigs(MaterialConfigsMother.hgMaterialConfig()), "firstStage", "secondStage");

        Pipeline pipeline2 = dbHelper.newPipelineWithFirstStagePassed(config);
        Stage stage = dbHelper.scheduleStage(pipeline2, config.get(1));
        dbHelper.failStage(stage);
        dbHelper.updateNaturalOrder(pipeline2.getId(), 6.0);

        Pipeline pipeline3 = dbHelper.newPipelineWithFirstStagePassed(config);
        dbHelper.updateNaturalOrder(pipeline3.getId(), 7.0);

        Pipeline pipeline4 = dbHelper.newPipelineWithFirstStagePassed(config);
        stage = dbHelper.scheduleStage(pipeline4, config.get(1));
        dbHelper.cancelStage(stage);
        dbHelper.updateNaturalOrder(pipeline4.getId(), 8.0);

        Pipeline pipeline5 = dbHelper.newPipelineWithFirstStagePassed(config);
        dbHelper.scheduleStage(pipeline5, config.get(1));
        dbHelper.updateNaturalOrder(pipeline5.getId(), 9.0);

        Pipeline pipeline6 = dbHelper.newPipelineWithFirstStagePassed(config);
        stage = dbHelper.scheduleStage(pipeline6, config.get(1));
        dbHelper.failStage(stage);
        dbHelper.updateNaturalOrder(pipeline6.getId(), 10.0);

        assertThat(pipelineDao.findEarlierPipelineThatPassedForStage("pipeline", "secondStage", 10.0)).isNull();
    }

    @Test
    public void shouldReturnPageNumberOfThePageInWhichThePIMWouldBePresent() {
        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("some-pipeline", "dev");
        Pipeline pipeline1 = schedulePipelineWithStages(mingleConfig);
        Pipeline pipeline2 = schedulePipelineWithStages(mingleConfig);
        Pipeline pipeline3 = schedulePipelineWithStages(mingleConfig);
        Pipeline pipeline4 = schedulePipelineWithStages(mingleConfig);
        Pipeline pipeline5 = schedulePipelineWithStages(mingleConfig);

        assertThat(pipelineDao.getPageNumberForCounter("some-pipeline", pipeline4.getCounter(), 1)).isEqualTo(2);
        assertThat(pipelineDao.getPageNumberForCounter("some-pipeline", pipeline5.getCounter(), 1)).isEqualTo(1);
        assertThat(pipelineDao.getPageNumberForCounter("some-pipeline", pipeline1.getCounter(), 2)).isEqualTo(3);
        assertThat(pipelineDao.getPageNumberForCounter("some-pipeline", pipeline2.getCounter(), 3)).isEqualTo(2);
        assertThat(pipelineDao.getPageNumberForCounter("some-pipeline", pipeline3.getCounter(), 10)).isEqualTo(1);
    }

    @Test
    public void shouldPauseExistingPipeline() {
        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("some-pipeline", "dev");
        schedulePipelineWithStages(mingleConfig);

        pipelineDao.pause(mingleConfig.name().toString(), "cause", "by");

        PipelinePauseInfo actual = pipelineDao.pauseState(mingleConfig.name().toString());
        PipelinePauseInfo expected = new PipelinePauseInfo(true, "cause", "by");

        assertThat(actual.isPaused()).isEqualTo(expected.isPaused());
        assertThat(actual.getPauseCause()).isEqualTo(expected.getPauseCause());
        assertThat(actual.getPauseBy()).isEqualTo(expected.getPauseBy());
        assertNotNull(actual.getPausedAt());
    }

    @Test
    public void shouldPauseExistingPipelineCaseInsensitive() {
        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("some-pipeline", "dev");
        schedulePipelineWithStages(mingleConfig);

        pipelineDao.pause(mingleConfig.name().toString(), "cause", "by");

        PipelinePauseInfo actual = pipelineDao.pauseState(mingleConfig.name().toString().toUpperCase());
        PipelinePauseInfo expected = new PipelinePauseInfo(true, "cause", "by");

        assertThat(actual.isPaused()).isEqualTo(expected.isPaused());
        assertThat(actual.getPauseCause()).isEqualTo(expected.getPauseCause());
        assertThat(actual.getPauseBy()).isEqualTo(expected.getPauseBy());
        assertNotNull(actual.getPausedAt());
    }

    @Test
    public void shouldUnPauseAPausedPipeline() {
        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("some-pipeline", "dev");
        schedulePipelineWithStages(mingleConfig);
        pipelineDao.pause(mingleConfig.name().toString(), "cause", "by");

        pipelineDao.unpause(mingleConfig.name().toString());

        PipelinePauseInfo actual = pipelineDao.pauseState(mingleConfig.name().toString());
        PipelinePauseInfo expected = new PipelinePauseInfo(false, null, null);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    public void shouldUnPauseAPausedPipelineCaseInsensitive() {
        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("some-pipeline", "dev");
        schedulePipelineWithStages(mingleConfig);
        pipelineDao.pause(mingleConfig.name().toString(), "cause", "by");

        pipelineDao.unpause(mingleConfig.name().toString().toUpperCase());

        PipelinePauseInfo actual = pipelineDao.pauseState(mingleConfig.name().toString());
        PipelinePauseInfo expected = new PipelinePauseInfo(false, null, null);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    public void shouldPauseNewPipeline() {
        PipelineConfig newlyAddedPipelineConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("newly-added-pipeline-config", "dev");

        pipelineDao.pause(newlyAddedPipelineConfig.name().toString(), "cause", "by");

        PipelinePauseInfo actual = pipelineDao.pauseState(newlyAddedPipelineConfig.name().toString());
        PipelinePauseInfo expected = new PipelinePauseInfo(true, "cause", "by");

        assertThat(actual.isPaused()).isEqualTo(expected.isPaused());
        assertThat(actual.getPauseCause()).isEqualTo(expected.getPauseCause());
        assertThat(actual.getPauseBy()).isEqualTo(expected.getPauseBy());
        assertNotNull(actual.getPausedAt());
    }

    @Test
    public void shouldReturnCurrentPauseStateOfPipeline() {
        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("some-pipeline", "dev");
        schedulePipelineWithStages(mingleConfig);

        PipelinePauseInfo expected = new PipelinePauseInfo(false, null, null);
        PipelinePauseInfo actual = pipelineDao.pauseState(mingleConfig.name().toString());
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    public void shouldReturnCurrentPauseStateOfPipelineCaseInsensitive() {
        PipelineConfig mingleConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials("some-pipeline", "dev");
        schedulePipelineWithStages(mingleConfig);

        pipelineDao.pause(mingleConfig.name().toString(), "really good reason", "me");

        PipelinePauseInfo actual = pipelineDao.pauseState(mingleConfig.name().toString().toUpperCase());
        assertThat(actual.isPaused()).isTrue();
        assertThat(actual.getPauseCause()).isEqualTo("really good reason");
        assertThat(actual.getPauseBy()).isEqualTo("me");
        assertNotNull(actual.getPausedAt());
    }

    @Test
    public void shouldUpdateCounter_WhenPipelineRowIsPresentWhichWasInsertedByPauseAction() {
        String pipelineName = "some-pipeline";
        PipelineConfig pipelineConfig = PipelineConfigMother.pipelineConfig(pipelineName);
        Username userNameAdmin = new Username(new CaseInsensitiveString("admin"));
        pipelinePauseService.pause(pipelineName, "some-cause", userNameAdmin); // Pause and unpause so that an entry exists for that pipeline
        pipelinePauseService.unpause(pipelineName);

        Pipeline pipeline = dbHelper.schedulePipeline(pipelineConfig, new TimeProvider());

        assertThat(pipelineDao.getCounterForPipeline(pipeline.getName())).isEqualTo(1);
    }

    @Test
    public void shouldIncrementCounter_WhenPipelineRowIsPresentWhichWasInsertedByPauseAction() {
        String pipelineName = "some-pipeline";
        PipelineConfig pipelineConfig = PipelineConfigMother.pipelineConfig(pipelineName);

        Pipeline pipeline = dbHelper.newPipelineWithAllStagesPassed(pipelineConfig); // Counter is 1
        dbHelper.newPipelineWithAllStagesPassed(pipelineConfig); // Counter should be incremented

        assertThat(pipelineDao.getCounterForPipeline(pipeline.getName().toUpperCase())).isEqualTo(pipeline.getCounter() + 1);
    }

    @Test
    public void shouldInsertCounter_WhenPipelineRowIsNotPresent() {
        String pipelineName = "some-pipeline";
        PipelineConfig pipelineConfig = PipelineConfigMother.pipelineConfig(pipelineName);

        Pipeline pipeline = dbHelper.schedulePipeline(pipelineConfig, new TimeProvider());

        assertThat(pipelineDao.getCounterForPipeline(pipeline.getName())).isEqualTo(1);
    }

    @Test
    public void shouldReturnStageIdIfAStageOfPipelineIdPassed() {
        String pipelineName = "some-pipeline";
        PipelineConfig pipelineConfig = PipelineConfigMother.pipelineConfig(pipelineName);

        Pipeline pipeline = dbHelper.schedulePipelineWithAllStages(pipelineConfig, ModificationsMother.modifySomeFiles(pipelineConfig));
        dbHelper.pass(pipeline);
        String stage = pipelineConfig.get(0).name().toString();
        assertThat(pipelineDao.latestPassedStageIdentifier(pipeline.getId(), stage)).isEqualTo(new StageIdentifier(pipelineName, pipeline.getCounter(), pipeline.getLabel(), stage, "1"));
    }

    @Test
    public void shouldReturnNullStageIdIfStageOfPipelineIdNeverPassed() {
        String pipelineName = "some-pipeline";
        PipelineConfig pipelineConfig = PipelineConfigMother.pipelineConfig(pipelineName);
        Pipeline pipeline = dbHelper.schedulePipelineWithAllStages(pipelineConfig, ModificationsMother.modifySomeFiles(pipelineConfig));
        dbHelper.failStage(pipeline.getFirstStage());
        String stage = pipelineConfig.get(0).name().toString();
        assertThat(pipelineDao.latestPassedStageIdentifier(pipeline.getId(), stage)).isEqualTo(StageIdentifier.NULL);
    }

    @Test
    public void shouldReturnStageIdOfPassedRunIfThereWereMultipleRerunsOfAStageAndOneOfThemPassed() {
        String pipelineName = "some-pipeline";
        PipelineConfig pipelineConfig = PipelineConfigMother.pipelineConfig(pipelineName);

        Pipeline pipeline = dbHelper.schedulePipelineWithAllStages(pipelineConfig, ModificationsMother.modifySomeFiles(pipelineConfig));
        dbHelper.pass(pipeline);
        Stage stage = dbHelper.scheduleStage(pipeline, pipelineConfig.getFirstStageConfig());
        dbHelper.cancelStage(stage);
        String stageName = pipelineConfig.get(0).name().toString();
        assertThat(pipelineDao.latestPassedStageIdentifier(pipeline.getId(), stageName)).isEqualTo(new StageIdentifier(pipelineName, pipeline.getCounter(), pipeline.getLabel(), stageName, "1"));
    }

    @Test
    public void shouldReturnLatestPassedStageIdentifierIfMultipleRunsOfTheStageHadPassed() {
        String pipelineName = "some-pipeline";
        PipelineConfig pipelineConfig = PipelineConfigMother.pipelineConfig(pipelineName);
        Pipeline pipeline = dbHelper.schedulePipelineWithAllStages(pipelineConfig, ModificationsMother.modifySomeFiles(pipelineConfig));
        dbHelper.pass(pipeline);
        Stage stage = dbHelper.scheduleStage(pipeline, pipelineConfig.getFirstStageConfig());
        dbHelper.passStage(stage);
        stage = dbHelper.scheduleStage(pipeline, pipelineConfig.getFirstStageConfig());
        dbHelper.cancelStage(stage);

        String stageName = stage.getName();
        assertThat(pipelineDao.latestPassedStageIdentifier(pipeline.getId(), stageName)).isEqualTo(new StageIdentifier(pipelineName, pipeline.getCounter(), pipeline.getLabel(), stageName, "2"));
    }

    @Test
    public void shouldReturnPipelineWithBuildCauseForJobId() {
        String pipelineName = "P1";
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfigWithStages(pipelineName, "S1");
        String username = "username";
        BuildCause manualForced = BuildCause.createManualForced(modifyOneFile(pipelineConfig), new Username(new CaseInsensitiveString(username)));
        Pipeline pipeline = dbHelper.schedulePipeline(pipelineConfig, manualForced, username, new TimeProvider());
        dbHelper.pass(pipeline);
        long jobId = pipeline.getStages().get(0).getJobInstances().get(0).getId();
        Pipeline pipelineFromDB = pipelineDao.pipelineWithMaterialsAndModsByBuildId(jobId);
        assertThat(pipelineFromDB.getBuildCause().getApprover()).isEqualTo(username);
        assertThat(pipelineFromDB.getBuildCause().getBuildCauseMessage()).isEqualTo("Forced by username");
        assertThat(pipelineFromDB.getName()).isEqualTo(pipelineName);
    }

    @Test
    public void shouldThrowExceptionWhenBuildCauseIsAskedForANonExistentPipeline() {
        try {
            pipelineDao.findBuildCauseOfPipelineByNameAndCounter("foo", 1);
            fail("should have thrown RecordNotFoundException");
        } catch (Exception e) {
            assertThat(e instanceof RecordNotFoundException).isTrue();
            assertThat(e.getMessage()).isEqualTo("Pipeline foo with counter 1 was not found");
        }
    }

    @Test
    public void shouldThrowExceptionWhenBuildCauseIsAskedForAPipelineWithInvalidCounter() {
        String pipelineName = "P1";
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfigWithStages(pipelineName, "S1");
        String username = "username";
        BuildCause manualForced = BuildCause.createManualForced(modifyOneFile(pipelineConfig), new Username(new CaseInsensitiveString(username)));
        Pipeline pipeline = dbHelper.schedulePipeline(pipelineConfig, manualForced, username, new TimeProvider());
        dbHelper.pass(pipeline);
        BuildCause buildCause = pipelineDao.findBuildCauseOfPipelineByNameAndCounter(pipelineName, 1);
        assertThat(buildCause).isNotNull();
        try {
            pipelineDao.findBuildCauseOfPipelineByNameAndCounter(pipelineName, 10);
            fail("should have thrown RecordNotFoundException");
        } catch (Exception e) {
            assertThat(e instanceof RecordNotFoundException).isTrue();
            assertThat(e.getMessage()).isEqualTo("Pipeline P1 with counter 10 was not found");
        }
    }

    @Test
    public void shouldReturnListOfPipelineIdentifiersForDownstreamPipelinesBasedOnARunOfUpstreamPipeline() {
        GitMaterial g1 = u.wf(new GitMaterial("g1"), "folder3");
        u.checkinInOrder(g1, "g_1");

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("p1", u.m(g1));
        ScheduleTestUtil.AddedPipeline p2 = u.saveConfigWith("p2", u.m(p1));

        String p1_1 = u.runAndPass(p1, "g_1");
        String p2_1 = u.runAndPass(p2, p1_1);
        String p2_2 = u.runAndPass(p2, p1_1);

        List<PipelineIdentifier> pipelineIdentifiers = pipelineDao.getPipelineInstancesTriggeredWithDependencyMaterial(p2.config.name().toString(),
                new PipelineIdentifier(p1.config.name().toString(), 1));
        assertThat(pipelineIdentifiers).hasSize(2);
        assertThat(pipelineIdentifiers).contains(new PipelineIdentifier(p2.config.name().toString(), 2, "2"), new PipelineIdentifier(p2.config.name().toString(), 1, "1"));
    }

    @Test
    public void shouldReturnEmptyListOfPipelineIdentifiersForUnRunDownstreamPipelinesBasedOnARunOfUpstreamPipeline() {
        GitMaterial g1 = u.wf(new GitMaterial("g1"), "folder3");
        u.checkinInOrder(g1, "g_1");

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("p1", u.m(g1));
        ScheduleTestUtil.AddedPipeline p2 = u.saveConfigWith("p2", u.m(p1));

        String p1_1 = u.runAndPass(p1, "g_1");

        List<PipelineIdentifier> pipelineIdentifiers = pipelineDao.getPipelineInstancesTriggeredWithDependencyMaterial(p2.config.name().toString(),
                new PipelineIdentifier(p1.config.name().toString(), 1));
        assertThat(pipelineIdentifiers).isEmpty();
    }

    @Test
    public void shouldReturnListOfPipelineIdentifiersBasedOnAMaterialRevisionCorrectly() {
        GitMaterial g1 = u.wf(new GitMaterial("g1"), "folder3");
        u.checkinInOrder(g1, "g_1", "g_2", "g_3");

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("p1", u.m(g1));

        String p1_1 = u.runAndPass(p1, "g_2");
        String p1_2 = u.runAndPass(p1, "g_3");
        String p1_3 = u.runAndPass(p1, "g_2");

        MaterialInstance g1Instance = materialRepository.findMaterialInstance(g1);
        List<PipelineIdentifier> pipelineIdentifiers = pipelineDao.getPipelineInstancesTriggeredWithDependencyMaterial(p1.config.name().toString(), g1Instance, "g_2");

        assertThat(pipelineIdentifiers.size()).isEqualTo(2);
        assertThat(pipelineIdentifiers).contains(new PipelineIdentifier(p1.config.name().toString(), 3, "3"), new PipelineIdentifier(p1.config.name().toString(), 1, "1"));

        pipelineIdentifiers = pipelineDao.getPipelineInstancesTriggeredWithDependencyMaterial(p1.config.name().toString(), g1Instance, "g_1");

        assertThat(pipelineIdentifiers).isEmpty();
    }

    @Test
    /* THIS IS A BUG IN VSM. STAGE RERUNS ARE NOT SUPPORTED AND DOWNSTREAMS SHOW THE RUNS MADE OUT OF PREVIOUS STAGE RUN. CHANGE TEST EXPECTATION WHEN BUG IS FIXED POST 13.2 [Mingle #7385] (DUCK & SACHIN) */
    public void shouldReturnListOfDownstreamPipelineIdentifiersForARunOfUpstreamPipeline_AlthoughUpstreamHasHadAStageReRun() {
        GitMaterial g1 = u.wf(new GitMaterial("g1"), "folder3");
        u.checkinInOrder(g1, "g_1");

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("p1", u.m(g1));
        ScheduleTestUtil.AddedPipeline p2 = u.saveConfigWith("p2", u.m(p1));

        Pipeline p1_1_s_1 = u.runAndPassAndReturnPipelineInstance(p1, u.d(0), "g_1");
        Pipeline p2_1 = u.runAndPassAndReturnPipelineInstance(p2, u.d(1), p1_1_s_1.getStages().first().stageLocator());
        String p1_1_s_2 = u.rerunStageAndCancel(p1_1_s_1, p1.config.get(0));

        List<PipelineIdentifier> pipelineIdentifiers = pipelineDao.getPipelineInstancesTriggeredWithDependencyMaterial(p2.config.name().toString(),
                new PipelineIdentifier(p1.config.name().toString(), 1));
        assertThat(pipelineIdentifiers).hasSize(1);
        assertThat(pipelineIdentifiers).contains(new PipelineIdentifier(p2.config.name().toString(), 1, "1"));
    }

    @Test
    public void shouldInvalidateCachedPipelineHistoryViaNameAndCounterUponStageChangeCaseInsensitively() {
        GitMaterial g1 = u.wf(new GitMaterial("g1"), "folder3");
        u.checkinInOrder(g1, "g_1");

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("p1", "s1", u.m(g1));
        Pipeline p1_1 = dbHelper.schedulePipeline(p1.config, new TestingClock());
        dbHelper.pass(p1_1);
        PipelineInstanceModel pim1 = pipelineDao.findPipelineHistoryByNameAndCounter(p1.config.name().toUpper(), 1); //prime cache
        scheduleService.rerunStage(p1_1.getName(), p1_1.getCounter(), p1_1.getStages().get(0).getName());

        PipelineInstanceModel pim2 = pipelineDao.findPipelineHistoryByNameAndCounter(p1.config.name().toUpper(), 1);

        assertThat(pim2).isNotEqualTo(pim1);
        assertThat(pim2.getStageHistory().get(0).getIdentifier().getStageCounter()).isEqualTo("2");
    }

    @Test
    public void shouldLoadHistoryForDashboard() {
        /*
         *   P1 [S1 (J1), S2(J1), S3(J1, J2)]
         *   P2 [S1 (J1)]
         *
         * */
        String pipeline1 = "p1";
        String pipeline2 = "p2";
        PipelineConfig pipelineConfig1 = PipelineConfigMother.pipelineConfig(pipeline1);
        pipelineConfig1.getStages().clear();
        pipelineConfig1.add(StageConfigMother.oneBuildPlanWithResourcesAndMaterials("s1"));
        pipelineConfig1.add(StageConfigMother.oneBuildPlanWithResourcesAndMaterials("s2"));
        StageConfig s2Config = StageConfigMother.twoBuildPlansWithResourcesAndMaterials("s3");
        pipelineConfig1.add(s2Config);

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith(pipelineConfig1);
        ScheduleTestUtil.AddedPipeline p2 = u.saveConfigWith(PipelineConfigMother.createPipelineConfig(pipeline2, "p2s1", "j1"));

        // Pipeline 1 Counter 1: All stages green
        Pipeline p1_1 = dbHelper.schedulePipeline(p1.config, new TestingClock());
        dbHelper.pass(p1_1);

        // Pipeline 1 Counter 2: S1, S2 green, S3 running. J1 failed, J2 scheduled
        Pipeline p1_2 = dbHelper.schedulePipeline(p1.config, new TestingClock());
        dbHelper.passStage(p1_2.getFirstStage());
        Stage p1_2_s2_1 = dbHelper.scheduleStage(p1_2, pipelineConfig1.getStage("s2"));
        dbHelper.passStage(p1_2_s2_1);
        Stage p1_2_s3_1 = dbHelper.scheduleStage(p1_2, pipelineConfig1.getStage("s3"));
        dbHelper.failJob(p1_2_s3_1, p1_2_s3_1.findJob("WinBuild"));

        // Pipeline 1 Counter 3: S1 green, S2 running
        Pipeline p1_3 = dbHelper.schedulePipeline(p1.config, new TestingClock());
        dbHelper.passStage(p1_3.getFirstStage());
        Stage p1_3_s2_1 = dbHelper.scheduleStage(p1_3, pipelineConfig1.getStage("s2"));

        // Pipeline 1 Counter 4: S1 scheduled
        Pipeline p1_4 = dbHelper.schedulePipeline(p1.config, new TestingClock());

        // Pipeline 2 Counter 1: All stages green
        Pipeline p2_1 = dbHelper.schedulePipeline(p2.config, new TestingClock());
        dbHelper.pass(p2_1);

        PipelineInstanceModels pipelineInstanceModels = pipelineDao.loadHistoryForDashboard(List.of(pipeline1, pipeline2)); // Load for all pipelines
        assertThat(pipelineInstanceModels.size()).isEqualTo(4);
        PipelineInstanceModels allRunningInstancesOfPipeline1 = pipelineInstanceModels.findAll(pipeline1);
        assertThat(allRunningInstancesOfPipeline1.size()).isEqualTo(3);
        assertThat(allRunningInstancesOfPipeline1.get(0).getCounter()).isEqualTo(4);
        assertThat(allRunningInstancesOfPipeline1.get(1).getCounter()).isEqualTo(3);
        assertThat(allRunningInstancesOfPipeline1.get(2).getCounter()).isEqualTo(2);

        PipelineInstanceModels allRunningInstancesOfPipeline2 = pipelineInstanceModels.findAll(pipeline2);
        assertThat(allRunningInstancesOfPipeline2.size()).isEqualTo(1);
        assertThat(allRunningInstancesOfPipeline2.get(0).getCounter()).isEqualTo(1);

        PipelineInstanceModels pipelineInstanceModelsForPipeline1 = pipelineDao.loadHistoryForDashboard(List.of(pipeline1)); // Load for single pipeline
        assertThat(pipelineInstanceModelsForPipeline1.size()).isEqualTo(3);
        assertThat(pipelineInstanceModelsForPipeline1.get(0).getCounter()).isEqualTo(4);
        assertThat(pipelineInstanceModelsForPipeline1.get(1).getCounter()).isEqualTo(3);
        assertThat(pipelineInstanceModelsForPipeline1.get(2).getCounter()).isEqualTo(2);
    }

    @Test
    public void ensureActivePipelineCacheUsedByOldDashboardIsCaseInsensitiveWRTPipelineNames() {
        GitMaterial g1 = u.wf(new GitMaterial("g1"), "folder3");
        u.checkinInOrder(g1, "g_1");

        ScheduleTestUtil.AddedPipeline pipeline1 = u.saveConfigWith("pipeline1", u.m(g1));
        Pipeline pipeline1_1 = dbHelper.schedulePipeline(pipeline1.config, new TestingClock());
        pipelineDao.loadActivePipelines(); //to initialize cache

        dbHelper.pass(pipeline1_1);
        pipelineDao.stageStatusChanged(pipeline1_1.getFirstStage());
        assertThat(getActivePipelinesForPipelineName(pipeline1).count()).isEqualTo(1L);
        assertThat(getActivePipelinesForPipelineName(pipeline1).findFirst().get().getName()).isEqualTo(pipeline1.config.name().toString());

        configHelper.removePipeline(pipeline1.config.name().toString());
        ScheduleTestUtil.AddedPipeline p1ReincarnatedWithDifferentCase = u.saveConfigWith("PIPELINE1", u.m(g1));
        Pipeline pipelineReincarnatedWithDifferentCase_1 = dbHelper.schedulePipeline(p1ReincarnatedWithDifferentCase.config, new TestingClock());
        pipelineDao.loadActivePipelines(); //to initialize cache
        pipelineDao.stageStatusChanged(pipelineReincarnatedWithDifferentCase_1.getFirstStage());

        assertThat(getActivePipelinesForPipelineName(p1ReincarnatedWithDifferentCase).count()).isEqualTo(1L);
        assertThat(getActivePipelinesForPipelineName(p1ReincarnatedWithDifferentCase).findFirst().get().getName()).isEqualTo(p1ReincarnatedWithDifferentCase.config.name().toString());
    }

    @Test
    public void shouldRemoveDuplicateEntriesForPipelineCounterFromDbForAGivenPipelineName() {
        String pipelineName = "Pipeline-Name";
        configHelper.addPipeline(pipelineName, "stage-name");
        pipelineDao.getSqlMapClientTemplate().insert("insertPipelineLabelCounter", arguments("pipelineName", pipelineName.toLowerCase()).and("count", 10).asMap());
        pipelineDao.getSqlMapClientTemplate().insert("insertPipelineLabelCounter", arguments("pipelineName", pipelineName.toUpperCase()).and("count", 20).asMap());
        pipelineDao.getSqlMapClientTemplate().insert("insertPipelineLabelCounter", arguments("pipelineName", pipelineName).and("count", 30).asMap());
        assertThat(pipelineDao.getPipelineNamesWithMultipleEntriesForLabelCount().size()).isEqualTo(1);
        assertThat(pipelineDao.getPipelineNamesWithMultipleEntriesForLabelCount().get(0).equalsIgnoreCase(pipelineName)).isTrue();

        pipelineDao.deleteOldPipelineLabelCountForPipelineInConfig(pipelineName);
        assertThat(pipelineDao.getPipelineNamesWithMultipleEntriesForLabelCount().isEmpty()).isTrue();
        assertThat(pipelineDao.getCounterForPipeline(pipelineName)).isEqualTo(30);
        assertThat(pipelineDao.getCounterForPipeline(pipelineName.toLowerCase())).isEqualTo(30);
        assertThat(pipelineDao.getCounterForPipeline(pipelineName.toUpperCase())).isEqualTo(30);
    }

    private Stream<PipelineInstanceModel> getActivePipelinesForPipelineName(ScheduleTestUtil.AddedPipeline pipeline1) {
        return pipelineDao.loadActivePipelines().stream().filter(pipelineInstanceModel -> pipelineInstanceModel.getName().equalsIgnoreCase(pipeline1.config.name().toString()));
    }

    @Test
    public void shouldLoadMostRecentPipelineIdentifier() {
        GitMaterial g1 = u.wf(new GitMaterial("g1"), "folder3");
        u.checkinInOrder(g1, "g_1");

        String pipelineName = "p1";
        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith(pipelineName, "s1", u.m(g1));
        Pipeline p1_1 = dbHelper.schedulePipeline(p1.config, new TestingClock());
        dbHelper.pass(p1_1);
        Pipeline p2_1 = dbHelper.schedulePipeline(p1.config, new TestingClock());
        dbHelper.pass(p2_1);

        PipelineIdentifier pipelineIdentifier = pipelineDao.mostRecentPipelineIdentifier(pipelineName);
        assertThat(pipelineIdentifier.getLabel()).isEqualTo(p2_1.getLabel());
        assertThat(pipelineIdentifier.getName()).isEqualTo(p2_1.getName());
        assertThat(pipelineIdentifier.getCounter()).isEqualTo(p2_1.getCounter());
    }

    @Test
    public void shouldReturnLatestAndOldestPipelineRunID() {
        GitMaterial g1 = u.wf(new GitMaterial("g1"), "folder3");
        u.checkinInOrder(g1, "g_1");

        String pipelineName = "p1";
        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith(pipelineName, "s1", u.m(g1));
        Pipeline p1_1 = dbHelper.schedulePipeline(p1.config, new TestingClock());
        dbHelper.pass(p1_1);
        Pipeline p2_1 = dbHelper.schedulePipeline(p1.config, new TestingClock());
        dbHelper.pass(p2_1);
        Pipeline p3_1 = dbHelper.schedulePipeline(p1.config, new TestingClock());
        dbHelper.pass(p3_1);
        PipelineRunIdInfo oldestAndLatestPipelineId = pipelineDao.getOldestAndLatestPipelineId(pipelineName);

        assertThat(oldestAndLatestPipelineId.getLatestRunId()).isEqualTo(p3_1.getId());
        assertThat(oldestAndLatestPipelineId.getOldestRunId()).isEqualTo(p1_1.getId());
    }

    @Test
    public void shouldReturnLatestPipelineHistory() {
        String pipelineName = "some-pipeline";
        PipelineConfig pipelineConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials(pipelineName, "dev", "ft");

        Pipeline pipeline1 = dbHelper.newPipelineWithAllStagesPassed(pipelineConfig);
        Pipeline pipeline2 = dbHelper.newPipelineWithAllStagesPassed(pipelineConfig);
        Pipeline pipeline3 = dbHelper.newPipelineWithAllStagesPassed(pipelineConfig);
        Pipeline pipeline4 = dbHelper.newPipelineWithAllStagesPassed(pipelineConfig);
        Pipeline pipeline5 = dbHelper.newPipelineWithAllStagesPassed(pipelineConfig);
        List<CaseInsensitiveString> allPipelineNames = goConfigDao.load().getAllPipelineNames();
        if (!allPipelineNames.contains(new CaseInsensitiveString("twist"))) {
            goConfigDao.addPipeline(pipelineConfig, "pipelinesqlmapdaotest");
        }

        PipelineInstanceModels pipelineInstanceModels = pipelineDao.loadHistory(pipelineName, FeedModifier.Latest, 0, 3);

        assertThat(pipelineInstanceModels.size()).isEqualTo(3);
        assertThat(pipelineInstanceModels.get(0).getId()).isEqualTo(pipeline5.getId());
        assertThat(pipelineInstanceModels.get(1).getId()).isEqualTo(pipeline4.getId());
        assertThat(pipelineInstanceModels.get(2).getId()).isEqualTo(pipeline3.getId());
    }

    @Test
    public void shouldReturnPipelineHistoryAfterTheSuppliedCursor() {
        String pipelineName = "pipeline-name" + UUID.randomUUID().toString();
        PipelineConfig pipelineConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials(pipelineName, "dev", "ft");

        Pipeline pipeline1 = dbHelper.newPipelineWithAllStagesPassed(pipelineConfig);
        Pipeline pipeline2 = dbHelper.newPipelineWithAllStagesPassed(pipelineConfig);
        Pipeline pipeline3 = dbHelper.newPipelineWithAllStagesPassed(pipelineConfig);
        Pipeline pipeline4 = dbHelper.newPipelineWithAllStagesPassed(pipelineConfig);
        Pipeline pipeline5 = dbHelper.newPipelineWithAllStagesPassed(pipelineConfig);
        List<CaseInsensitiveString> allPipelineNames = goConfigDao.load().getAllPipelineNames();
        if (!allPipelineNames.contains(new CaseInsensitiveString("twist"))) {
            goConfigDao.addPipeline(pipelineConfig, "pipelinesqlmapdaotest");
        }

        PipelineInstanceModels pipelineInstanceModels = pipelineDao.loadHistory(pipelineName, FeedModifier.After, pipeline3.getId(), 3);

        assertThat(pipelineInstanceModels.size()).isEqualTo(2);
        assertThat(pipelineInstanceModels.get(0).getId()).isEqualTo(pipeline2.getId());
        assertThat(pipelineInstanceModels.get(1).getId()).isEqualTo(pipeline1.getId());
    }

    @Test
    public void shouldReturnPipelineHistoryBeforeTheSuppliedCursor() {
        String pipelineName = "pipeline-name" + UUID.randomUUID().toString();
        PipelineConfig pipelineConfig = PipelineMother.twoBuildPlansWithResourcesAndMaterials(pipelineName, "dev", "ft");

        Pipeline pipeline1 = dbHelper.newPipelineWithAllStagesPassed(pipelineConfig);
        Pipeline pipeline2 = dbHelper.newPipelineWithAllStagesPassed(pipelineConfig);
        Pipeline pipeline3 = dbHelper.newPipelineWithAllStagesPassed(pipelineConfig);
        Pipeline pipeline4 = dbHelper.newPipelineWithAllStagesPassed(pipelineConfig);
        Pipeline pipeline5 = dbHelper.newPipelineWithAllStagesPassed(pipelineConfig);
        List<CaseInsensitiveString> allPipelineNames = goConfigDao.load().getAllPipelineNames();
        if (!allPipelineNames.contains(new CaseInsensitiveString("twist"))) {
            goConfigDao.addPipeline(pipelineConfig, "pipelinesqlmapdaotest");
        }

        PipelineInstanceModels pipelineInstanceModels = pipelineDao.loadHistory(pipelineName, FeedModifier.Before, pipeline3.getId(), 3);

        assertThat(pipelineInstanceModels.size()).isEqualTo(2);
        assertThat(pipelineInstanceModels.get(0).getId()).isEqualTo(pipeline5.getId());
        assertThat(pipelineInstanceModels.get(1).getId()).isEqualTo(pipeline4.getId());
    }

    @Test
    public void shouldSetApproverInBuildCause_findPipelineHistory() {
        GitMaterial g1 = u.wf(new GitMaterial("g1"), "folder3");
        u.checkinInOrder(g1, "g_1");

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("p1", "s1", u.m(g1));
        Pipeline p1_1 = dbHelper.schedulePipeline(p1.config, new TestingClock());
        dbHelper.pass(p1_1);
        PipelineInstanceModel pim1 = pipelineDao.findPipelineHistoryByNameAndCounter(p1.config.name().toUpper(), 1);

        assertThat(pim1.getBuildCause().getApprover()).isEqualTo("changes");
        assertThat(pim1.getBuildCause().getBuildCauseMessage()).isEqualTo("modified by lgao");
    }

    public static MaterialRevisions revisions(boolean changed) {
        MaterialRevisions revisions = new MaterialRevisions();
        List<Modification> modifications = new ArrayList<>();
        modifications.add(ModificationsMother.oneModifiedFile(ModificationsMother.currentRevision()));
        SvnMaterial svnMaterial = MaterialsMother.svnMaterial("http://mingle.com");
        svnMaterial.setName(new CaseInsensitiveString("mingle"));
        MaterialRevision materialRevision = new MaterialRevision(svnMaterial, changed, modifications.toArray(new Modification[0]));
        revisions.addRevision(materialRevision);
        return revisions;
    }

    private void assertNotInserted(long instanceId) {
        assertThat(instanceId).isEqualTo(NOT_PERSISTED);
    }

    private void assertIsInserted(long instanceId) {
        assertThat(instanceId).isNotEqualTo(0L);
    }

    private void assertModifications(Pipeline pipeline) {
        assertThat(pipeline.getBuildCause().getMaterialRevisions().totalNumberOfModifications()).isEqualTo(1);
    }

    private static class ModificationsCollector extends ModificationVisitorAdapter {
        private List<Modification> mods = new ArrayList<>();

        @Override
        public void visit(Modification modification) {
            mods.add(modification);
        }

        public Modification first() {
            return mods.get(0);
        }

        public int numberOfModifications() {
            return mods.size();
        }
    }
}
