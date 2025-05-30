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
package com.thoughtworks.go.server.persistence;

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.CruiseConfig;
import com.thoughtworks.go.config.GoConfigDao;
import com.thoughtworks.go.config.PipelineConfig;
import com.thoughtworks.go.config.materials.MaterialConfigs;
import com.thoughtworks.go.config.materials.git.GitMaterial;
import com.thoughtworks.go.config.materials.mercurial.HgMaterial;
import com.thoughtworks.go.config.materials.svn.SvnMaterial;
import com.thoughtworks.go.domain.*;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.domain.materials.MaterialConfig;
import com.thoughtworks.go.domain.materials.Modification;
import com.thoughtworks.go.helper.MaterialsMother;
import com.thoughtworks.go.server.dao.DatabaseAccessHelper;
import com.thoughtworks.go.server.dao.PipelineSqlMapDao;
import com.thoughtworks.go.server.dao.UserSqlMapDao;
import com.thoughtworks.go.server.domain.PipelineTimeline;
import com.thoughtworks.go.server.domain.user.ExcludesFilter;
import com.thoughtworks.go.server.domain.user.Filters;
import com.thoughtworks.go.server.domain.user.IncludesFilter;
import com.thoughtworks.go.server.domain.user.PipelineSelections;
import com.thoughtworks.go.server.service.InstanceFactory;
import com.thoughtworks.go.server.service.ScheduleTestUtil;
import com.thoughtworks.go.server.transaction.TransactionSynchronizationManager;
import com.thoughtworks.go.server.transaction.TransactionTemplate;
import com.thoughtworks.go.util.Dates;
import com.thoughtworks.go.util.GoConfigFileHelper;
import com.thoughtworks.go.util.TimeProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.ZonedDateTime;
import java.util.*;

import static com.thoughtworks.go.helper.ModificationsMother.oneModifiedFile;
import static com.thoughtworks.go.helper.PipelineConfigMother.createPipelineConfig;
import static com.thoughtworks.go.server.domain.user.DashboardFilter.DEFAULT_NAME;
import static java.time.ZoneOffset.UTC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml",
        "classpath:/spring-all-servlet.xml",
})
public class PipelineRepositoryIntegrationTest {

    @Autowired
    PipelineSqlMapDao pipelineSqlMapDao;
    @Autowired
    DatabaseAccessHelper dbHelper;
    @Autowired
    PipelineRepository pipelineRepository;
    @Autowired
    MaterialRepository materialRepository;
    @Autowired
    UserSqlMapDao userSqlMapDao;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private TransactionSynchronizationManager transactionSynchronizationManager;
    @Autowired
    private GoConfigDao goConfigDao;
    @Autowired
    private InstanceFactory instanceFactory;

    private GoConfigFileHelper configHelper = new GoConfigFileHelper();
    private static final String PIPELINE_NAME = "pipeline";

    @BeforeEach
    public void setup() throws Exception {
        dbHelper.onSetUp();
        configHelper.usingCruiseConfigDao(goConfigDao);
        configHelper.onSetUp();
    }

    @AfterEach
    public void teardown() throws Exception {
        configHelper.onTearDown();
        dbHelper.onTearDown();
    }

    @Test
    public void shouldConsider_firstRevision_forAFlyweight_asInDb_whilePickingFromMultipleDeclarations() {
        ScheduleTestUtil u = new ScheduleTestUtil(transactionTemplate, materialRepository, dbHelper, configHelper);
        int i = 1;

        GitMaterial git1 = u.wf(new GitMaterial("git"), "folder1");
        u.checkinInOrder(git1, "g1");

        GitMaterial git2 = u.wf(new GitMaterial("git"), "folder2");

        ScheduleTestUtil.AddedPipeline p = u.saveConfigWith("P", u.m(git1), u.m(git2));
        CruiseConfig cruiseConfig = goConfigDao.load();

        u.checkinInOrder(git1, u.d(i++), "g2");

        u.runAndPass(p, "g1", "g2");

        u.runAndPass(p, "g2", "g1");

        PipelineTimeline timeline = new PipelineTimeline(pipelineRepository, transactionTemplate, transactionSynchronizationManager);
        timeline.updateTimelineOnInit();

        List<PipelineTimelineEntry> timelineEntries = new ArrayList<>(timeline.getEntriesFor("P"));
        assertThat(timelineEntries.get(0).getPipelineLocator().getCounter()).isEqualTo(1);
        assertThat(timelineEntries.get(0).naturalOrder()).isEqualTo(1.0);
        List<PipelineTimelineEntry.Revision> flyweightsRevs = new ArrayList<>(timelineEntries.get(0).revisions().values()).get(0);
        assertThat(flyweightsRevs.get(0).revision).isEqualTo("g1");
        assertThat(flyweightsRevs.get(1).revision).isEqualTo("g2");
        assertThat(timelineEntries.get(1).getPipelineLocator().getCounter()).isEqualTo(2);
        assertThat(timelineEntries.get(1).naturalOrder()).isEqualTo(2.0);
        flyweightsRevs = new ArrayList<>(timelineEntries.get(1).revisions().values()).get(0);
        assertThat(flyweightsRevs.get(0).revision).isEqualTo("g2");
        assertThat(flyweightsRevs.get(1).revision).isEqualTo("g1");

        MaterialConfigs materials = configHelper.deepClone(p.config.materialConfigs());
        Collections.reverse(materials);
        configHelper.setMaterialConfigForPipeline("P", materials.toArray(new MaterialConfig[0]));

        goConfigDao.load();

        timeline = new PipelineTimeline(pipelineRepository, transactionTemplate, transactionSynchronizationManager);
        timeline.updateTimelineOnInit();

        timelineEntries = new ArrayList<>(timeline.getEntriesFor("P"));
        assertThat(timelineEntries.get(0).getPipelineLocator().getCounter()).isEqualTo(1);
        assertThat(timelineEntries.get(0).naturalOrder()).isEqualTo(1.0);
        flyweightsRevs = new ArrayList<>(timelineEntries.get(0).revisions().values()).get(0);
        assertThat(flyweightsRevs.get(0).revision).isEqualTo("g1");
        assertThat(flyweightsRevs.get(1).revision).isEqualTo("g2");
        assertThat(timelineEntries.get(1).getPipelineLocator().getCounter()).isEqualTo(2);
        assertThat(timelineEntries.get(1).naturalOrder()).isEqualTo(2.0);
        flyweightsRevs = new ArrayList<>(timelineEntries.get(1).revisions().values()).get(0);
        assertThat(flyweightsRevs.get(0).revision).isEqualTo("g2");
        assertThat(flyweightsRevs.get(1).revision).isEqualTo("g1");
    }

    @Test
    public void shouldReturnEarliestPMRFor1Material() {
        HgMaterial hgmaterial = MaterialsMother.hgMaterial("first");

        PipelineConfig pipelineConfig = createPipelineConfig(PIPELINE_NAME, "stage", "job");
        pipelineConfig.setMaterialConfigs(new MaterialConfigs(hgmaterial.config()));
        ZonedDateTime date = ZonedDateTime.of(1984, 12, 23, 0, 0, 0, 0, UTC);
        long firstId = createPipeline(hgmaterial, pipelineConfig, 1,
                oneModifiedFile("3", date.plusDays(2)),
                oneModifiedFile("2", date.plusDays(2)),
                oneModifiedFile("1", date.plusDays(3)));


        long secondId = createPipeline(hgmaterial, pipelineConfig, 2,
                oneModifiedFile("5", date.plusDays(1)),
                oneModifiedFile("4", date));


        PipelineTimeline pipelineTimeline = new PipelineTimeline(pipelineRepository, transactionTemplate, transactionSynchronizationManager);

        List<PipelineTimelineEntry> entries = new ArrayList<>();
        pipelineRepository.updatePipelineTimeline(pipelineTimeline, entries);

        assertThat(pipelineTimeline.getEntriesFor(PIPELINE_NAME).size()).isEqualTo(2);
        assertThat(entries.size()).isEqualTo(2);
        assertThat(entries).contains(expected(firstId,
                Map.of(hgmaterial.getFingerprint(), List.of(new PipelineTimelineEntry.Revision(Dates.from(date.plusDays(2)), "123", hgmaterial.getFingerprint(), 10))), 1));
        assertThat(entries).contains(expected(secondId,
                Map.of(hgmaterial.getFingerprint(), List.of(new PipelineTimelineEntry.Revision(Dates.from(date.plusDays(1)), "12", hgmaterial.getFingerprint(), 8))), 2));

        assertThat(pipelineTimeline.getEntriesFor(PIPELINE_NAME)).contains(expected(firstId,
                Map.of(hgmaterial.getFingerprint(), List.of(new PipelineTimelineEntry.Revision(Dates.from(date.plusDays(2)), "123", hgmaterial.getFingerprint(), 10))), 1));
        assertThat(pipelineTimeline.getEntriesFor(PIPELINE_NAME)).contains(expected(secondId,
                Map.of(hgmaterial.getFingerprint(), List.of(new PipelineTimelineEntry.Revision(Dates.from(date.plusDays(1)), "12", hgmaterial.getFingerprint(), 8))), 2));
        assertThat(pipelineTimeline.maximumId()).isEqualTo(secondId);

        long thirdId = createPipeline(hgmaterial, pipelineConfig, 3, oneModifiedFile("30", date.plusDays(10)));

        pipelineRepository.updatePipelineTimeline(pipelineTimeline, new ArrayList<>());

        assertThat(pipelineTimeline.getEntriesFor(PIPELINE_NAME).size()).isEqualTo(3);
        assertThat(pipelineTimeline.getEntriesFor(PIPELINE_NAME)).contains(expected(thirdId,
                Map.of(hgmaterial.getFingerprint(), List.of(new PipelineTimelineEntry.Revision(Dates.from(date.plusDays(10)), "1234", hgmaterial.getFingerprint(), 12))), 3));
        assertThat(pipelineTimeline.maximumId()).isEqualTo(thirdId);

        assertThat(pipelineSqlMapDao.pipelineByIdWithMods(firstId).getNaturalOrder()).isEqualTo(1.0);
        assertThat(pipelineSqlMapDao.pipelineByIdWithMods(secondId).getNaturalOrder()).isEqualTo(0.5);
        assertThat(pipelineSqlMapDao.pipelineByIdWithMods(thirdId).getNaturalOrder()).isEqualTo(2.0);

        PipelineTimeline pipelineTimeline2 = new PipelineTimeline(pipelineRepository, transactionTemplate, transactionSynchronizationManager);
        pipelineRepository.updatePipelineTimeline(pipelineTimeline2, new ArrayList<>());
    }

    @Test
    public void shouldAddExistingPipelinesToTimelineForNewTimeline() {
        HgMaterial hgmaterial = MaterialsMother.hgMaterial(UUID.randomUUID().toString());

        PipelineConfig pipelineConfig = createPipelineConfig(PIPELINE_NAME, "stage", "job");
        pipelineConfig.setMaterialConfigs(new MaterialConfigs(hgmaterial.config()));
        ZonedDateTime date = ZonedDateTime.of(1984, 12, 23, 0, 0, 0, 0, UTC);
        long firstId = createPipeline(hgmaterial, pipelineConfig, 1,
                oneModifiedFile("3", date.plusDays(2)),
                oneModifiedFile("2", date.plusDays(2)),
                oneModifiedFile("1", date.plusDays(3)));


        long secondId = createPipeline(hgmaterial, pipelineConfig, 2,
                oneModifiedFile("5", date.plusDays(1)),
                oneModifiedFile("4", date));


        PipelineTimeline mods = new PipelineTimeline(pipelineRepository, transactionTemplate, transactionSynchronizationManager);
        mods.update();

        assertThat(pipelineSqlMapDao.pipelineByIdWithMods(firstId).getNaturalOrder()).isEqualTo(1.0);
        assertThat(pipelineSqlMapDao.pipelineByIdWithMods(secondId).getNaturalOrder()).isEqualTo(0.5);

        PipelineTimeline modsAfterReboot = new PipelineTimeline(pipelineRepository, transactionTemplate, transactionSynchronizationManager);
        modsAfterReboot.update();
    }

    @Test
    public void shouldReturnEarliestPMRForMultipleMaterial() {
        final HgMaterial hgmaterial = MaterialsMother.hgMaterial("first");
        final SvnMaterial svnMaterial = MaterialsMother.svnMaterial();

        PipelineConfig pipelineConfig = createPipelineConfig(PIPELINE_NAME, "stage", "job");
        pipelineConfig.setMaterialConfigs(new MaterialConfigs(hgmaterial.config(), svnMaterial.config()));
        ZonedDateTime date = ZonedDateTime.of(1984, 12, 23, 0, 0, 0, 0, UTC);
        long first = save(pipelineConfig, 1, 1.0,
                new MaterialRevision(hgmaterial,
                        oneModifiedFile("13", date.plusDays(2)),
                        oneModifiedFile("12", date.plusDays(2)),
                        oneModifiedFile("11", date.plusDays(3))),
                new MaterialRevision(svnMaterial,
                        oneModifiedFile("23", date.plusDays(6)),
                        oneModifiedFile("22", date.plusDays(2)),
                        oneModifiedFile("21", date.plusDays(2)))
        );

        long second = save(pipelineConfig, 2, 0.0,
                new MaterialRevision(hgmaterial,
                        oneModifiedFile("15", date.plusDays(3)),
                        oneModifiedFile("14", date.plusDays(2))),
                new MaterialRevision(svnMaterial,
                        oneModifiedFile("25", date.plusDays(5))));

        PipelineTimeline pipelineTimeline = new PipelineTimeline(pipelineRepository, transactionTemplate, transactionSynchronizationManager);
        pipelineRepository.updatePipelineTimeline(pipelineTimeline, new ArrayList<>());

        Collection<PipelineTimelineEntry> modifications = pipelineTimeline.getEntriesFor(PIPELINE_NAME);
        assertThat(modifications.size()).isEqualTo(2);

        assertThat(modifications).contains(expected(first, Map.of(
            hgmaterial.getFingerprint(), List.of(new PipelineTimelineEntry.Revision(Dates.from(date.plusDays(2)), "123", hgmaterial.getFingerprint(), 8)),
            svnMaterial.getFingerprint(), List.of(new PipelineTimelineEntry.Revision(Dates.from(date.plusDays(6)), "456", svnMaterial.getFingerprint(), 12))
        ), 1));
        assertThat(modifications).contains(expected(second, Map.of(
            hgmaterial.getFingerprint(), List.of(new PipelineTimelineEntry.Revision(Dates.from(date.plusDays(3)), "234", hgmaterial.getFingerprint(), 9)),
            svnMaterial.getFingerprint(), List.of(new PipelineTimelineEntry.Revision(Dates.from(date.plusDays(5)), "345", svnMaterial.getFingerprint(), 10))
        ), 2));
    }

    private PipelineTimelineEntry expected(long first, Map<String, List<PipelineTimelineEntry.Revision>> map, int counter) {
        return new PipelineTimelineEntry(PIPELINE_NAME, first, counter, map);
    }

    private long createPipeline(HgMaterial hgmaterial, PipelineConfig pipelineConfig, int counter, Modification... modifications) {
        return save(pipelineConfig, counter, new MaterialRevision(hgmaterial, modifications));
    }

    private long save(PipelineConfig pipelineConfig, int counter, MaterialRevision... materialRevisions) {
        return save(pipelineConfig, counter, 0.0, materialRevisions);
    }

    private long save(final PipelineConfig pipelineConfig, final int counter, final double naturalOrder, final MaterialRevision... materialRevisions) {
        return transactionTemplate.execute(status -> {
            MaterialRevisions revisions = new MaterialRevisions(materialRevisions);
            materialRepository.save(revisions);

            Pipeline instance = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createWithModifications(revisions, "me"), new DefaultSchedulingContext(), "md5-test", new TimeProvider());
            instance.setCounter(counter);
            instance.setNaturalOrder(naturalOrder);
            return pipelineSqlMapDao.save(instance).getId();
        });
    }

    @Test
    public void shouldReturnNullForInvalidIds() {
        assertThat(pipelineRepository.findPipelineSelectionsById(null)).isNull();
        assertThat(pipelineRepository.findPipelineSelectionsById("")).isNull();
        assertThat(pipelineRepository.findPipelineSelectionsById("123")).isNull();
        try {
            pipelineRepository.findPipelineSelectionsById("foo");
            fail("should throw error");
        } catch (NumberFormatException ignored) {

        }
    }

    @Test
    public void shouldSaveSelectedPipelinesWithUserId() {
        User user = createUser();

        List<String> unSelected = List.of("pipeline1", "pipeline2");
        long id = pipelineRepository.saveSelectedPipelines(excludes(unSelected, user.getId()));
        assertThat(pipelineRepository.findPipelineSelectionsById(id).userId()).isEqualTo(user.getId());
    }

    @Test
    public void shouldSaveSelectedPipelinesWithBlacklistPreferenceFalse() {
        User user = createUser();

        List<String> selected = List.of("pipeline1", "pipeline2");
        final PipelineSelections included = includes(selected, user.getId());
        long id = pipelineRepository.saveSelectedPipelines(included);
        assertEquals(included, pipelineRepository.findPipelineSelectionsById(id));
    }

    @Test
    public void shouldSaveSelectedPipelinesWithBlacklistPreferenceTrue() {
        User user = createUser();

        List<String> unSelected = List.of("pipeline1", "pipeline2");
        final PipelineSelections excluded = excludes(unSelected, user.getId());
        long id = pipelineRepository.saveSelectedPipelines(excluded);
        assertEquals(excluded, pipelineRepository.findPipelineSelectionsById(id));
    }

    @Test
    public void shouldFindSelectedPipelinesByUserId() {
        User user = createUser();

        List<String> unSelected = List.of("pipeline1", "pipeline2");
        long id = pipelineRepository.saveSelectedPipelines(excludes(unSelected, user.getId()));
        assertThat(pipelineRepository.findPipelineSelectionsByUserId(user.getId()).getId()).isEqualTo(id);
    }

    @Test
    public void shouldReturnNullAsPipelineSelectionsIfUserIdIsNull() {
        assertThat(pipelineRepository.findPipelineSelectionsByUserId(null)).isNull();
    }

    @Test
    public void shouldReturnNullAsPipelineSelectionsIfSelectionsExistForUser() {
        assertThat(pipelineRepository.findPipelineSelectionsByUserId(10L)).isNull();
    }

    private User createUser() {
        userSqlMapDao.saveOrUpdate(new User("loser"));
        return userSqlMapDao.findUser("loser");
    }

    private PipelineSelections excludes(List<String> pipelines, Long userId) {
        final List<CaseInsensitiveString> pipelineNames = CaseInsensitiveString.list(pipelines);
        Filters filters = new Filters(List.of(new ExcludesFilter(DEFAULT_NAME, pipelineNames, new HashSet<>())));
        return new PipelineSelections(filters, new Date(), userId);
    }

    private PipelineSelections includes(List<String> pipelines, Long userId) {
        final List<CaseInsensitiveString> pipelineNames = CaseInsensitiveString.list(pipelines);
        Filters filters = new Filters(List.of(new IncludesFilter(DEFAULT_NAME, pipelineNames, new HashSet<>())));
        return new PipelineSelections(filters, new Date(), userId);
    }
}
