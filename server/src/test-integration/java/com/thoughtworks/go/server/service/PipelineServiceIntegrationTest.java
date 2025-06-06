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
import com.thoughtworks.go.config.PipelineConfig;
import com.thoughtworks.go.config.materials.Materials;
import com.thoughtworks.go.config.materials.mercurial.HgMaterial;
import com.thoughtworks.go.domain.*;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.domain.materials.Material;
import com.thoughtworks.go.domain.materials.ModifiedAction;
import com.thoughtworks.go.domain.materials.dependency.DependencyMaterialRevision;
import com.thoughtworks.go.helper.PipelineMother;
import com.thoughtworks.go.server.cache.GoCache;
import com.thoughtworks.go.server.dao.DatabaseAccessHelper;
import com.thoughtworks.go.server.dao.PipelineDao;
import com.thoughtworks.go.server.dao.PipelineSqlMapDao;
import com.thoughtworks.go.server.persistence.MaterialRepository;
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

import java.io.File;
import java.time.ZonedDateTime;
import java.util.List;

import static com.thoughtworks.go.helper.ModificationsMother.modifySomeFiles;
import static com.thoughtworks.go.util.IBatisUtil.arguments;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
    "classpath:/applicationContext-global.xml",
    "classpath:/applicationContext-dataLocalAccess.xml",
    "classpath:/testPropertyConfigurer.xml",
    "classpath:/spring-all-servlet.xml",
})
public class PipelineServiceIntegrationTest {
    @Autowired private DatabaseAccessHelper dbHelper;
    @Autowired private GoCache goCache;
    @Autowired private GoConfigDao goConfigDao;
    @Autowired private PipelineService pipelineService;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private PipelineSqlMapDao pipelineSqlMapDao;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private PipelineDao pipelineDao;
    @Autowired private GoConfigService goConfigService;
    @Autowired private InstanceFactory instanceFactory;

    private GoConfigFileHelper configHelper = new GoConfigFileHelper();
    private ScheduleTestUtil u;

    @BeforeEach
    public void setUp() throws Exception {
        goCache.clear();
        configHelper.usingCruiseConfigDao(goConfigDao);
        configHelper.onSetUp();

        dbHelper.onSetUp();
        u = new ScheduleTestUtil(transactionTemplate, materialRepository, dbHelper, configHelper);
    }

    @AfterEach
    public void teardown() throws Exception {
        dbHelper.onTearDown();
        configHelper.onTearDown();
    }

    @Test
    public void shouldFetchPipelinePointedToByGivenDMR() {
        Pipeline pipeline = PipelineMother.passedPipelineInstance("pipeline", "stage", "job");
        dbHelper.savePipelineWithStagesAndMaterials(pipeline);

        UpstreamPipelineResolver resolver = pipelineService;
        BuildCause loadedBC = resolver.buildCauseFor(
            DependencyMaterialRevision.create(pipeline.getStages().get(0).getIdentifier().getStageLocator(), pipeline.getLabel()).getPipelineName(),
            DependencyMaterialRevision.create(pipeline.getStages().get(0).getIdentifier().getStageLocator(), pipeline.getLabel()).getPipelineCounter());

        assertEquals(pipeline.getBuildCause(), loadedBC);
    }

    @Test
    public void shouldReturnCorrectNumberOfMaterialRevisionsAndMaterials() {
        File file1 = new File("file1");
        File file2 = new File("file2");
        File file3 = new File("file3");
        File file4 = new File("file4");
        Material hg = new HgMaterial("url", "Dest");
        String[] hgRevs = new String[]{"h1"};

        u.checkinFiles(hg, "h1", List.of(file1, file2, file3, file4), ModifiedAction.added);

        ScheduleTestUtil.AddedPipeline pair01 = u.saveConfigWith("pair01", "stageName", u.m(hg));

        u.runAndPass(pair01, hgRevs);

        Pipeline pipeline = pipelineService.mostRecentFullPipelineByName("pair01");
        MaterialRevisions materialRevisions = pipeline.getBuildCause().getMaterialRevisions();
        assertThat(materialRevisions.getMaterials().size()).isEqualTo(1);
    }

    @Test
    public void shouldReturnModificationsInCorrectOrder() {
        File file1 = new File("file1");
        File file2 = new File("file2");
        File file3 = new File("file3");
        File file4 = new File("file4");
        Material hg1 = new HgMaterial("url1", "Dest1");
        String[] hgRevs = new String[]{"hg1_2"};

        ZonedDateTime latestModification = ZonedDateTime.now();
        ZonedDateTime older = latestModification.minusDays(1);
        u.checkinFiles(hg1, "hg1_1", List.of(file1, file2, file3, file4), ModifiedAction.added, older.toInstant());
        u.checkinFiles(hg1, "hg1_2", List.of(file1, file2, file3, file4), ModifiedAction.modified, latestModification.toInstant());


        ScheduleTestUtil.AddedPipeline pair01 = u.saveConfigWith("pair01", "stageName", u.m(hg1));

        u.runAndPass(pair01, hgRevs);

        Pipeline pipeline = pipelineService.mostRecentFullPipelineByName("pair01");
        MaterialRevisions materialRevisions = pipeline.getBuildCause().getMaterialRevisions();
        assertThat(materialRevisions.getMaterials().size()).isEqualTo(1);
        assertThat(materialRevisions.getDateOfLatestModification().getTime()).isEqualTo(latestModification.toInstant().toEpochMilli());
    }

    @Test
    public void shouldReturnPMRsInCorrectOrder() {
        File file1 = new File("file1");
        File file2 = new File("file2");
        File file3 = new File("file3");
        File file4 = new File("file4");
        Material hg1 = new HgMaterial("url1", "Dest1");
        Material hg2 = new HgMaterial("url2", "Dest2");
        String[] hgRevs = new String[]{"h1", "h2"};

        ZonedDateTime latestModification = ZonedDateTime.now();
        u.checkinFiles(hg2, "h2", List.of(file1, file2, file3, file4), ModifiedAction.added, latestModification.minusDays(1).toInstant());
        u.checkinFiles(hg1, "h1", List.of(file1, file2, file3, file4), ModifiedAction.added, latestModification.toInstant());

        ScheduleTestUtil.AddedPipeline pair01 = u.saveConfigWith("pair01", "stageName", u.m(hg1), u.m(hg2));
        u.runAndPass(pair01, hgRevs);

        Pipeline pipeline = pipelineService.mostRecentFullPipelineByName("pair01");
        MaterialRevisions materialRevisions = pipeline.getBuildCause().getMaterialRevisions();
        Materials materials = materialRevisions.getMaterials();
        assertThat(materials.size()).isEqualTo(2);
        assertThat(materials.get(0)).isEqualTo(hg1);
        assertThat(materials.get(1)).isEqualTo(hg2);
    }

    @Test
    public void shouldUpdateTheCorrectPipelineCounterAfterDuplicatesHaveBeenDeleted() {
        String pipelineName = "Pipeline-Name";
        File file1 = new File("file1");
        Material hg = new HgMaterial("url", "Dest");
        u.checkinFiles(hg, "h1", List.of(file1), ModifiedAction.added);
        ScheduleTestUtil.AddedPipeline addedPipeline = u.saveConfigWith(pipelineName, "stageName", u.m(hg));
        pipelineSqlMapDao.getSqlMapClientTemplate().insert("insertPipelineLabelCounter", arguments("pipelineName", pipelineName.toLowerCase()).and("count", 10).asMap());
        pipelineSqlMapDao.getSqlMapClientTemplate().insert("insertPipelineLabelCounter", arguments("pipelineName", pipelineName.toUpperCase()).and("count", 20).asMap());
        pipelineSqlMapDao.getSqlMapClientTemplate().insert("insertPipelineLabelCounter", arguments("pipelineName", pipelineName).and("count", 30).asMap());

        pipelineSqlMapDao.deleteOldPipelineLabelCountForPipelineInConfig(pipelineName);

        MaterialRevisions materialRevisions = u.mrs(u.mr(u.m(hg).material, true, "h1"));
        BuildCause buildCause = BuildCause.createWithModifications(materialRevisions, "user");
        Pipeline pipelineInstance = instanceFactory.createPipelineInstance(addedPipeline.config, buildCause, new DefaultSchedulingContext(), null, new TestingClock());

        pipelineService.save(pipelineInstance);
        assertThat(pipelineInstance.getCounter()).isEqualTo(31);
    }

    @Test
    public void returnPipelineForBuildDetailViewShouldContainOnlyMods() {
        Pipeline pipeline = createPipelineWithStagesAndMods();
        JobInstance job = pipeline.getFirstStage().getJobInstances().first();

        Pipeline slimPipeline = pipelineService.wrapBuildDetails(job);
        assertThat(slimPipeline.getBuildCause().getMaterialRevisions().totalNumberOfModifications()).isEqualTo(1);
        assertThat(slimPipeline.getName()).isEqualTo(pipeline.getName());
        assertThat(slimPipeline.getFirstStage().getJobInstances().size()).isEqualTo(1);
    }

    @Test
    public void shouldApplyLabelFromPreviousPipeline() {
        String oldLabel = createNewPipeline().getLabel();
        String newLabel = createNewPipeline().getLabel();
        assertThat(newLabel).isGreaterThan(oldLabel);
    }

    private Pipeline createNewPipeline() {
        if (!goConfigService.hasPipelineNamed(new CaseInsensitiveString("Test"))) {
            configHelper.addPipeline("Test", "dev");
        }
        Pipeline pipeline = new Pipeline("Test", "testing-${COUNT}", BuildCause.createWithEmptyModifications(), new EnvironmentVariables());
        return pipelineService.save(pipeline);
    }

    @Test
    public void shouldIncreaseCounterFromPreviousPipeline() {
        Pipeline pipeline1 = createNewPipeline();
        Pipeline pipeline2 = createNewPipeline();
        assertThat(pipeline2.getCounter()).isEqualTo(pipeline1.getCounter() + 1);
    }

    @Test
    public void shouldFindPipelineByLabel() {
        Pipeline pipeline = createPipelineWhoseLabelIsNumberAndNotSameWithCounter();
        Pipeline actual = pipelineService.findPipelineByNameAndCounter("Test", 10);
        assertThat(actual.getId()).isEqualTo(pipeline.getId());
        assertThat(actual.getLabel()).isEqualTo(pipeline.getLabel());
        assertThat(actual.getCounter()).isEqualTo(pipeline.getCounter());
    }

    @Test
    public void shouldFindPipelineByCounter() {
        Pipeline pipeline = createNewPipeline();
        Pipeline actual = pipelineService.findPipelineByNameAndCounter("Test", pipeline.getCounter());
        assertThat(actual.getId()).isEqualTo(pipeline.getId());
        assertThat(actual.getLabel()).isEqualTo(pipeline.getLabel());
        assertThat(actual.getCounter()).isEqualTo(pipeline.getCounter());
    }

    @Test
    public void shouldReturnFullPipelineByCounter() {
        Pipeline pipeline = createPipelineWithStagesAndMods();
        Pipeline actual = pipelineService.fullPipelineByCounter(pipeline.getName(), pipeline.getCounter());
        assertThat(actual.getStages().size()).isNotEqualTo(0);
        assertThat(actual.getBuildCause().getMaterialRevisions().getRevisions().size()).isNotEqualTo(0);
    }

    private Pipeline createPipelineWhoseLabelIsNumberAndNotSameWithCounter() {
        Pipeline pipeline = new Pipeline("Test", "${COUNT}0", BuildCause.createWithEmptyModifications(), new EnvironmentVariables());
        pipeline.updateCounter(9);
        pipelineDao.save(pipeline);
        return pipeline;
    }

    private Pipeline createPipelineWithStagesAndMods() {
        PipelineConfig config = PipelineMother.twoBuildPlansWithResourcesAndMaterials("tester", "dev");
        configHelper.addPipeline(CaseInsensitiveString.str(config.name()), CaseInsensitiveString.str(config.first().name()));
        Pipeline pipeline = instanceFactory.createPipelineInstance(config, modifySomeFiles(config), new DefaultSchedulingContext(GoConstants.DEFAULT_APPROVED_BY), "md5-test", new TimeProvider());
        dbHelper.savePipelineWithStagesAndMaterials(pipeline);
        return pipeline;
    }

}
