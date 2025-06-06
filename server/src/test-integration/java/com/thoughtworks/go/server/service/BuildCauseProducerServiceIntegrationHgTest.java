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
import com.thoughtworks.go.config.materials.Filter;
import com.thoughtworks.go.config.materials.IgnoredFiles;
import com.thoughtworks.go.config.materials.SubprocessExecutionContext;
import com.thoughtworks.go.config.materials.mercurial.HgMaterial;
import com.thoughtworks.go.domain.MaterialRevisions;
import com.thoughtworks.go.domain.Pipeline;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.domain.materials.Modification;
import com.thoughtworks.go.helper.HgTestRepo;
import com.thoughtworks.go.helper.MaterialsMother;
import com.thoughtworks.go.helper.PipelineMother;
import com.thoughtworks.go.server.dao.DatabaseAccessHelper;
import com.thoughtworks.go.server.scheduling.ScheduleHelper;
import com.thoughtworks.go.util.GoConfigFileHelper;
import com.thoughtworks.go.util.TempDirUtils;
import com.thoughtworks.go.util.command.InMemoryStreamConsumer;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.thoughtworks.go.util.command.ProcessOutputStreamConsumer.inMemoryConsumer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml",
        "classpath:/spring-all-servlet.xml",
})
public class BuildCauseProducerServiceIntegrationHgTest {

    private static final String STAGE_NAME = "dev";

    @Autowired private GoConfigService goConfigService;
    @Autowired private GoConfigDao goConfigDao;
    @Autowired private PipelineScheduleQueue pipelineScheduleQueue;
    @Autowired private ScheduleHelper scheduleHelper;
    @Autowired private SubprocessExecutionContext subprocessExecutionContext;

    @Autowired private DatabaseAccessHelper dbHelper;
    private static GoConfigFileHelper configHelper = new GoConfigFileHelper();
    private Pipeline latestPipeline;
    private InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
    private HgTestRepo hgTestRepo;
    private HgMaterial hgMaterial;
    private File workingFolder;
    PipelineConfig mingleConfig;

    @BeforeEach
    public void setup(@TempDir Path tempDir) throws Exception {
        dbHelper.onSetUp();
        configHelper.usingCruiseConfigDao(goConfigDao);
        configHelper.onSetUp();
        hgTestRepo = new HgTestRepo("hgTestRepo1", tempDir);
        hgMaterial = MaterialsMother.hgMaterial(hgTestRepo.projectRepositoryUrl());
        hgMaterial.setFilter(new Filter(new IgnoredFiles("helper/**/*.*")));
        workingFolder = TempDirUtils.createTempDirectoryIn(tempDir, "workingFolder").toFile();
        outputStreamConsumer = inMemoryConsumer();
        mingleConfig = configHelper.addPipeline("cruise", STAGE_NAME, this.hgMaterial.config(), "unit", "functional");
    }

    @AfterEach
    public void teardown() throws Exception {
        dbHelper.onTearDown();
        FileUtils.deleteQuietly(goConfigService.artifactsDir());
        FileUtils.deleteQuietly(workingFolder);
        pipelineScheduleQueue.clear();
    }


    /**
     * How we handle SVN Material and other Material(Hg Git and etc) is different, which caused the bug
     * #2375
     */
    @Test
    public void shouldNotGetModificationWhenCheckInFilesInIgnoredList() throws Exception {
        prepareAPipelineWithHistory();

        checkInFiles("helper/resources/images/cruise/StageActivity.png",
                "helper/topics/upgrading_go.xml",
                "helper/topics/whats_new_in_go.xml");

        Map<CaseInsensitiveString, BuildCause> beforeLoad = pipelineScheduleQueue.toBeScheduled();

        scheduleHelper.autoSchedulePipelinesWithRealMaterials();

        Map<CaseInsensitiveString, BuildCause> afterLoad = pipelineScheduleQueue.toBeScheduled();
        assertThat(afterLoad.size()).isEqualTo(beforeLoad.size());

    }

    private void prepareAPipelineWithHistory() {
        MaterialRevisions materialRevisions = new MaterialRevisions();
        List<Modification> modifications = this.hgMaterial.latestModification(workingFolder, subprocessExecutionContext);
        materialRevisions.addRevision(this.hgMaterial, modifications);
        BuildCause buildCause = BuildCause.createWithModifications(materialRevisions, "");

        latestPipeline = PipelineMother.schedule(mingleConfig, buildCause);
        latestPipeline = dbHelper.savePipelineWithStagesAndMaterials(latestPipeline);
        dbHelper.passStage(latestPipeline.getStages().first());
    }

    private void checkInFiles(String... files) throws Exception {
        for (String fileName : files) {
            File file = new File(workingFolder, fileName);
            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), "bla", UTF_8);
            hgMaterial.add(workingFolder, outputStreamConsumer, file);
        }
        hgMaterial.commit(workingFolder, outputStreamConsumer, "comment ", "user");
        hgMaterial.push(workingFolder, outputStreamConsumer);
    }
}
