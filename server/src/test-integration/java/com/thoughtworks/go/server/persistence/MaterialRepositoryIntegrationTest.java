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
import com.thoughtworks.go.config.PipelineConfig;
import com.thoughtworks.go.config.materials.MaterialConfigs;
import com.thoughtworks.go.config.materials.PackageMaterial;
import com.thoughtworks.go.config.materials.PluggableSCMMaterial;
import com.thoughtworks.go.config.materials.ScmMaterial;
import com.thoughtworks.go.config.materials.dependency.DependencyMaterial;
import com.thoughtworks.go.config.materials.dependency.DependencyMaterialConfig;
import com.thoughtworks.go.config.materials.git.GitMaterial;
import com.thoughtworks.go.config.materials.git.GitMaterialConfig;
import com.thoughtworks.go.config.materials.mercurial.HgMaterial;
import com.thoughtworks.go.config.materials.mercurial.HgMaterialConfig;
import com.thoughtworks.go.config.materials.perforce.P4Material;
import com.thoughtworks.go.config.materials.perforce.P4MaterialConfig;
import com.thoughtworks.go.config.materials.svn.SvnMaterial;
import com.thoughtworks.go.config.materials.svn.SvnMaterialConfig;
import com.thoughtworks.go.config.materials.tfs.TfsMaterial;
import com.thoughtworks.go.domain.*;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.domain.config.Configuration;
import com.thoughtworks.go.domain.config.ConfigurationProperty;
import com.thoughtworks.go.domain.materials.*;
import com.thoughtworks.go.domain.materials.mercurial.HgMaterialInstance;
import com.thoughtworks.go.domain.materials.packagematerial.PackageMaterialInstance;
import com.thoughtworks.go.domain.materials.perforce.P4MaterialInstance;
import com.thoughtworks.go.domain.materials.scm.PluggableSCMMaterialInstance;
import com.thoughtworks.go.domain.materials.svn.SvnMaterialInstance;
import com.thoughtworks.go.domain.materials.tfs.TfsMaterialInstance;
import com.thoughtworks.go.domain.packagerepository.ConfigurationPropertyMother;
import com.thoughtworks.go.domain.packagerepository.PackageDefinitionMother;
import com.thoughtworks.go.domain.packagerepository.PackageRepository;
import com.thoughtworks.go.domain.packagerepository.PackageRepositoryMother;
import com.thoughtworks.go.domain.scm.SCMMother;
import com.thoughtworks.go.helper.*;
import com.thoughtworks.go.server.cache.GoCache;
import com.thoughtworks.go.server.dao.DatabaseAccessHelper;
import com.thoughtworks.go.server.dao.FeedModifier;
import com.thoughtworks.go.server.dao.PipelineSqlMapDao;
import com.thoughtworks.go.server.database.Database;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.service.InstanceFactory;
import com.thoughtworks.go.server.service.MaterialConfigConverter;
import com.thoughtworks.go.server.service.MaterialExpansionService;
import com.thoughtworks.go.server.transaction.TransactionSynchronizationManager;
import com.thoughtworks.go.server.transaction.TransactionTemplate;
import com.thoughtworks.go.server.util.Pagination;
import com.thoughtworks.go.util.Dates;
import com.thoughtworks.go.util.SerializationTester;
import com.thoughtworks.go.util.TestUtils;
import com.thoughtworks.go.util.TimeProvider;
import com.thoughtworks.go.util.json.JsonHelper;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.DetachedCriteria;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.hibernate3.HibernateTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.thoughtworks.go.helper.ModificationsMother.EMAIL_ADDRESS;
import static com.thoughtworks.go.helper.ModificationsMother.MOD_USER;
import static com.thoughtworks.go.util.GoConstants.DEFAULT_APPROVED_BY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml",
        "classpath:/spring-all-servlet.xml",
})
public class MaterialRepositoryIntegrationTest {

    @Autowired
    MaterialRepository repo;
    @Autowired
    GoCache goCache;
    @Autowired
    PipelineSqlMapDao pipelineSqlMapDao;
    @Autowired
    DatabaseAccessHelper dbHelper;
    @Autowired
    SessionFactory sessionFactory;
    @Autowired
    TransactionSynchronizationManager transactionSynchronizationManager;
    @Autowired
    TransactionTemplate transactionTemplate;
    @Autowired
    private InstanceFactory instanceFactory;
    @Autowired
    private MaterialConfigConverter materialConfigConverter;
    @Autowired
    private MaterialExpansionService materialExpansionService;
    @Autowired
    private Database databaseStrategy;

    private HibernateTemplate originalTemplate;
    private final String md5 = "md5-test";

    @BeforeEach
    public void setUp() throws Exception {
        originalTemplate = repo.getHibernateTemplate();
        dbHelper.onSetUp();
        goCache.clear();
    }

    @AfterEach
    public void tearDown() throws Exception {
        goCache.clear();
        repo.setHibernateTemplate(originalTemplate);
        dbHelper.onTearDown();
    }

    @Test
    public void shouldBeAbleToPersistAMaterial() {
        MaterialInstance original = new SvnMaterialInstance("url", "username", UUID.randomUUID().toString(), true);
        repo.saveOrUpdate(original);

        MaterialInstance loaded = repo.find(original.getId());

        assertThat(loaded).isEqualTo(original);
    }

    @Test
    public void shouldBeAbleToPersistADependencyMaterial() {
        MaterialInstance materialInstance = new DependencyMaterial(new CaseInsensitiveString("name"), new CaseInsensitiveString("pipeline"), new CaseInsensitiveString("stage")).createMaterialInstance();
        repo.saveOrUpdate(materialInstance);

        MaterialInstance loaded = repo.find(materialInstance.getId());
        assertThat(loaded).isEqualTo(materialInstance);
    }

    @Test
    public void shouldCacheMaterialInstanceOnSaveAndUpdate() {
        SvnMaterial originalMaterial = MaterialsMother.svnMaterial();
        MaterialInstance materialInstance = originalMaterial.createMaterialInstance();
        repo.saveOrUpdate(materialInstance);

        assertThat(repo.find(materialInstance.getId())).isEqualTo(materialInstance);

        SvnMaterial changedMaterial = MaterialsMother.svnMaterial();
        changedMaterial.setPassword("SomethingElse");
        MaterialInstance changedInstance = changedMaterial.createMaterialInstance();
        changedInstance.setId(materialInstance.getId());
        repo.saveOrUpdate(changedInstance);

        assertThat(repo.find(materialInstance.getId())).isEqualTo(changedInstance);
    }

    @Test
    public void findModificationsFor_shouldCacheModifications() {
        HibernateTemplate mockTemplate = mock(HibernateTemplate.class);
        repo.setHibernateTemplate(mockTemplate);

        List<?> modifications = new ArrayList<>();
        doReturn(modifications).when(mockTemplate).find("FROM Modification WHERE materialId = ? AND id BETWEEN ? AND ? ORDER BY id DESC", 10L, -1L, -1L);
        MaterialInstance materialInstance = material().createMaterialInstance();
        materialInstance.setId(10);
        doReturn(List.of(materialInstance)).when(mockTemplate).findByCriteria(any(DetachedCriteria.class));

        PipelineMaterialRevision pmr = pipelineMaterialRevision();
        repo.findModificationsFor(pmr);
        List<Modification> actual = repo.findModificationsFor(pmr);

        assertSame(modifications, actual);

        verify(mockTemplate, times(1)).find("FROM Modification WHERE materialId = ? AND id BETWEEN ? AND ? ORDER BY id DESC", 10L, -1L, -1L);
    }

    @Test
    public void findPipelineMaterialRevisions_shouldCacheResults() {
        HibernateTemplate mockTemplate = mock(HibernateTemplate.class);
        repo.setHibernateTemplate(mockTemplate);

        repo.findPipelineMaterialRevisions(2);
        repo.findPipelineMaterialRevisions(2);

        verify(mockTemplate, times(1)).find("FROM PipelineMaterialRevision WHERE pipelineId = ? ORDER BY id", 2L);
    }

    @Test
    public void findModificationsSince_shouldNotCacheIfTheResultSetLarge() {
        SvnMaterial material = MaterialsMother.svnMaterial();
        MaterialRevision first = saveOneScmModification(material, "user1", "file1");
        MaterialRevision second = saveOneScmModification(material, "user2", "file2");

        goCache.clear();
        repo = new MaterialRepository(sessionFactory, goCache, 1, transactionSynchronizationManager, materialConfigConverter, materialExpansionService, databaseStrategy);

        repo.findModificationsSince(material, first);
        assertThat(repo.cachedModifications(repo.findMaterialInstance(material))).isNull();

        repo.findModificationsSince(material, second);
        assertThat(repo.cachedModifications(repo.findMaterialInstance(material))).isNotNull();
        repo.findModificationsSince(material, first);
        assertThat(repo.cachedModifications(repo.findMaterialInstance(material))).isNull();
    }

    @Test
    public void findModificationsSince_shouldHandleConcurrentModificationToCache() throws InterruptedException {
        final SvnMaterial svn = MaterialsMother.svnMaterial();
        final MaterialRevision first = saveOneScmModification(svn, "user1", "file1");
        final MaterialRevision second = saveOneScmModification(svn, "user2", "file2");
        final MaterialRevision third = saveOneScmModification(svn, "user2", "file3");

        repo = new MaterialRepository(sessionFactory, goCache = new GoCache(goCache) {
            @Override
            public <T> T get(String key) {
                T value = super.get(key);
                TestUtils.sleepQuietly(200); // sleep so we can have multiple threads enter the critical section
                return value;
            }
        }, 200, transactionSynchronizationManager, materialConfigConverter, materialExpansionService, databaseStrategy);

        Thread thread1 = new Thread(() -> repo.findModificationsSince(svn, first));
        thread1.start();
        TestUtils.sleepQuietly(50);

        Thread thread2 = new Thread(() -> repo.findModificationsSince(svn, second));
        thread2.start();

        thread1.join();
        thread2.join();

        assertThat(repo.cachedModifications(repo.findMaterialInstance(svn)).size()).isEqualTo(3);
    }

    @Test
    public void findModificationsSince_shouldCacheResults() {
        SvnMaterial material = MaterialsMother.svnMaterial();
        MaterialRevision zero = saveOneScmModification(material, "user1", "file1");
        MaterialRevision first = saveOneScmModification(material, "user1", "file1");
        MaterialRevision second = saveOneScmModification(material, "user2", "file2");
        MaterialRevision third = saveOneScmModification(material, "user2", "file2");

        repo.findModificationsSince(material, first);

        HibernateTemplate mockTemplate = mock(HibernateTemplate.class);
        repo.setHibernateTemplate(mockTemplate);
        List<Modification> modifications = repo.findModificationsSince(material, first);
        assertThat(modifications.size()).isEqualTo(2);
        assertEquals(third.getLatestModification(), modifications.get(0));
        assertEquals(second.getLatestModification(), modifications.get(1));
        verifyNoMoreInteractions(mockTemplate);
    }

    @Test
    public void findLatestModifications_shouldCacheResults() {
        SvnMaterial material = MaterialsMother.svnMaterial();
        MaterialInstance materialInstance = material.createMaterialInstance();
        repo.saveOrUpdate(materialInstance);

        Modification mod = ModificationsMother.oneModifiedFile("file3");
        mod.setId(8);

        HibernateTemplate mockTemplate = mock(HibernateTemplate.class);
        repo.setHibernateTemplate(mockTemplate);
        when(mockTemplate.execute(any())).thenReturn(mod);

        repo.findLatestModification(materialInstance);

        Modification modification = repo.findLatestModification(materialInstance);
        assertSame(mod, modification);

        verify(mockTemplate, times(1)).execute(any());

    }

    @Test
    public void findLatestModifications_shouldQueryIfNotEnoughElementsInCache() {
        SvnMaterial material = MaterialsMother.svnMaterial();
        MaterialRevision mod = saveOneScmModification(material, "user2", "file3");
        goCache.remove(repo.latestMaterialModificationsKey(repo.findMaterialInstance(material)));
        HibernateTemplate mockTemplate = mock(HibernateTemplate.class);
        repo.setHibernateTemplate(mockTemplate);
        when(mockTemplate.execute(any())).thenReturn(mod.getModification(0));
        Modification modification = repo.findLatestModification(repo.findMaterialInstance(material));

        assertThat(modification).isEqualTo(mod.getLatestModification());
        verify(mockTemplate).execute(any());
    }

    @Test
    public void findLatestModifications_shouldQueryIfNotEnoughElementsInCache_Integration() {
        SvnMaterial material = MaterialsMother.svnMaterial();
        MaterialRevision mod = saveOneScmModification(material, "user2", "file3");
        goCache.clear();
        Modification modification = repo.findLatestModification(repo.findMaterialInstance(material));
        assertEquals(mod.getLatestModification(), modification);
    }

    @Test
    public void shouldFindMaterialInstanceIfExists() {
        Material svn = MaterialsMother.svnMaterial();
        MaterialInstance material1 = repo.findOrCreateFrom(svn);
        MaterialInstance material2 = repo.findOrCreateFrom(svn);

        assertThat(material1.getId()).isEqualTo(material2.getId());
    }

    @Test
    public void materialShouldNotBeSameIfOneFieldIsNull() {
        Material svn1 = MaterialsMother.svnMaterial("url", null, "username", "password", false, null);
        MaterialInstance material1 = repo.findOrCreateFrom(svn1);

        Material svn2 = MaterialsMother.svnMaterial("url", null, null, null, false, null);
        MaterialInstance material2 = repo.findOrCreateFrom(svn2);

        assertThat(material1.getId()).isNotEqualTo(material2.getId());
    }

    @Test
    public void findOrCreateFrom_shouldEnsureOnlyOneThreadCanCreateAtATime() throws Exception {
        final Material svn = MaterialsMother.svnMaterial("url", null, "username", "password", false, null);

        HibernateTemplate mockTemplate = mock(HibernateTemplate.class);
        repo = new MaterialRepository(repo.getSessionFactory(), goCache, 200, transactionSynchronizationManager, materialConfigConverter, materialExpansionService, databaseStrategy) {
            @Override
            public MaterialInstance findMaterialInstance(Material material) {
                MaterialInstance result = super.findMaterialInstance(material);
                TestUtils.sleepQuietly(20); // force multiple threads to try to create the material
                return result;
            }

            @Override
            public void saveOrUpdate(MaterialInstance material) {
                material.setId(10);
                super.saveOrUpdate(material);
            }
        };

        repo.setHibernateTemplate(mockTemplate);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Thread thread = new Thread(() -> repo.findOrCreateFrom(svn), "thread-" + i);
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        verify(mockTemplate, times(1)).saveOrUpdate(Mockito.<MaterialInstance>any());
    }

    @Test
    public void findOrCreateFrom_shouldCacheMaterialInstanceOnCreate() {
        Material svn = MaterialsMother.svnMaterial("url", null, "username", "password", false, null);

        MaterialInstance instance = repo.findOrCreateFrom(svn);
        assertThat(instance).isNotNull();

        HibernateTemplate mockTemplate = mock(HibernateTemplate.class);
        repo.setHibernateTemplate(mockTemplate);

        MaterialInstance cachedInstance = repo.findMaterialInstance(svn);
        assertSame(instance, cachedInstance);

        verifyNoMoreInteractions(mockTemplate);
    }

    @Test
    public void findMaterialInstance_shouldCacheMaterialInstance() {
        Material svn1 = MaterialsMother.svnMaterial("url", null, "username", "password", false, null);
        repo.saveOrUpdate(svn1.createMaterialInstance());

        MaterialInstance instance = repo.findMaterialInstance(svn1);

        HibernateTemplate mockTemplate = mock(HibernateTemplate.class);
        repo.setHibernateTemplate(mockTemplate);

        MaterialInstance cachedInstance = repo.findMaterialInstance(svn1);
        assertSame(instance, cachedInstance);

        verifyNoMoreInteractions(mockTemplate);
    }

    @Test
    public void shouldMaterialCacheKeyShouldReturnTheSameInstance() {
        Material svn = MaterialsMother.svnMaterial("url", null, "username", "password", false, null);
        assertSame(repo.materialKey(svn), repo.materialKey(svn));
    }

    @Test
    public void shouldBeAbleToPersistAMaterialWithNullBooleans() {
        P4Material p4Material = new P4Material("serverAndPort", "view");

        MaterialInstance original = p4Material.createMaterialInstance();
        repo.saveOrUpdate(original);
        MaterialInstance loaded = repo.find(original.getId());

        Material restored = loaded.toOldMaterial(null, null, null);

        assertThat(restored).isEqualTo(p4Material);
    }

    @Test
    public void shouldPersistModificationsWithMaterials() {
        MaterialInstance original = new SvnMaterialInstance("url", "username", UUID.randomUUID().toString(), false);
        repo.saveOrUpdate(original);

        MaterialInstance loaded = repo.find(original.getId());
        assertThat(loaded).isEqualTo(original);
    }

    @Test
    public void shouldPersistModifiedFiles() {
        MaterialInstance original = new SvnMaterialInstance("url", "username", UUID.randomUUID().toString(), true);
        Modification modification = new Modification("user", "comment", "email", new Date(), ModificationsMother.nextRevision());
        modification.createModifiedFile("file1", "folder1", ModifiedAction.added);
        modification.createModifiedFile("file2", "folder2", ModifiedAction.deleted);
        repo.saveOrUpdate(original);

        MaterialInstance loaded = repo.find(original.getId());
        assertThat(loaded).isEqualTo(original);
    }

    @Test
    public void shouldBeAbleToFindModificationsSinceAPreviousChange() {
        SvnMaterial original = MaterialsMother.svnMaterial();

        MaterialRevision originalRevision = saveOneScmModification(original, "user1", "file1");

        MaterialRevision later = saveOneScmModification(original, "user2", "file2");

        List<Modification> modifications = repo.findModificationsSince(original, originalRevision);
        assertEquals(later.getLatestModification(), modifications.get(0));
    }

    @Test
    public void shouldFindNoModificationsSinceLatestChange() {
        SvnMaterial original = MaterialsMother.svnMaterial();

        MaterialRevision originalRevision = saveOneScmModification(original, "user", "file1");

        List<Modification> modifications = repo.findModificationsSince(original, originalRevision);
        assertThat(modifications.size()).isEqualTo(0);
    }


    @Test
    public void materialsShouldBeSerializable() throws Exception {
        SvnMaterial svnMaterial = MaterialsMother.svnMaterial();
        Modification modification = new Modification("user", "comment", "email", new Date(), "revision");
        modification.createModifiedFile("file1", "folder1", ModifiedAction.added);
        modification.createModifiedFile("file2", "folder2", ModifiedAction.deleted);

        final MaterialRevision materialRevision = new MaterialRevision(svnMaterial, modification);
        MaterialInstance materialInstance = transactionTemplate.execute(status -> repo.saveMaterialRevision(materialRevision));

        List<Modification> mods = repo.findMaterialRevisionsForMaterial(materialInstance.getId());

        List<Modification> deserialized = SerializationTester.objectSerializeAndDeserialize(mods);
        assertThat(deserialized).isEqualTo(mods);
    }

    @Test
    public void hasPipelineEverRunWith() {
        HgMaterial hgMaterial = MaterialsMother.hgMaterial("hgUrl", "dest");
        MaterialRevision materialRevision = saveOneScmModification(hgMaterial, "user", "file");
        PipelineConfig pipelineConfig = PipelineMother.createPipelineConfig("mingle", new MaterialConfigs(hgMaterial.config()), "dev");
        MaterialRevisions materialRevisions = new MaterialRevisions(materialRevision);
        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(materialRevisions, Username.ANONYMOUS),
                new DefaultSchedulingContext(DEFAULT_APPROVED_BY), md5,
                new TimeProvider());

        pipelineSqlMapDao.save(pipeline);

        MaterialRevisions revisions = new MaterialRevisions(new MaterialRevision(hgMaterial, materialRevision.getLatestModification()));
        assertThat(repo.hasPipelineEverRunWith("mingle", revisions)).isTrue();
    }

    @Test
    public void hasPipelineEverRunWithIsFalseWhenThereAreNewerModificationsThatHaveNotBeenBuilt() {
        HgMaterial hgMaterial = MaterialsMother.hgMaterial("hgUrl", "dest");
        MaterialRevision materialRevision = saveOneScmModification(hgMaterial, "user", "file");
        PipelineConfig pipelineConfig = PipelineMother.createPipelineConfig("mingle", new MaterialConfigs(hgMaterial.config()), "dev");
        MaterialRevisions materialRevisions = new MaterialRevisions(materialRevision);
        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(materialRevisions, Username.ANONYMOUS),
                new DefaultSchedulingContext(DEFAULT_APPROVED_BY), md5,
                new TimeProvider());

        pipelineSqlMapDao.save(pipeline);

        MaterialRevision notBuiltRevision = saveOneScmModification(hgMaterial, "user", "file2");

        MaterialRevisions revisions = new MaterialRevisions(new MaterialRevision(hgMaterial, notBuiltRevision.getLatestModification()));
        assertThat(repo.hasPipelineEverRunWith("mingle", revisions)).isFalse();
    }

    @Test
    public void hasPipelineEverRunWithMultipleMaterials() {
        HgMaterial hgMaterial = MaterialsMother.hgMaterial("hgUrl", "dest");
        MaterialRevision hgMaterialRevision = saveOneScmModification(hgMaterial, "user", "file");
        DependencyMaterial depMaterial = new DependencyMaterial(new CaseInsensitiveString("blahPipeline"), new CaseInsensitiveString("blahStage"));
        MaterialRevision depMaterialRevision = saveOneDependencyModification(depMaterial, "blahPipeline/1/blahStage/1");
        PipelineConfig pipelineConfig = PipelineMother.createPipelineConfig("mingle", new MaterialConfigs(hgMaterial.config(), depMaterial.config()), "dev");
        MaterialRevisions materialRevisions = new MaterialRevisions(hgMaterialRevision, depMaterialRevision);
        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(materialRevisions, Username.ANONYMOUS), new DefaultSchedulingContext(DEFAULT_APPROVED_BY), md5,
                new TimeProvider());

        pipelineSqlMapDao.save(pipeline);

        MaterialRevisions revisions = new MaterialRevisions(new MaterialRevision(depMaterial, depMaterialRevision.getLatestModification()),
                new MaterialRevision(hgMaterial, hgMaterialRevision.getLatestModification()));
        assertThat(repo.hasPipelineEverRunWith("mingle", revisions)).isTrue();
    }

    @Test
    public void hasPipelineEverRunWithMultipleMaterialsAndMultipleRuns() {
        HgMaterial hgMaterial1 = MaterialsMother.hgMaterial("hgUrl", "dest");
        MaterialRevision hgMaterialRevision1 = saveOneScmModification(hgMaterial1, "user", "file");
        DependencyMaterial depMaterial1 = new DependencyMaterial(new CaseInsensitiveString("blahPipeline"), new CaseInsensitiveString("blahStage"));
        MaterialRevision depMaterialRevision1 = saveOneDependencyModification(depMaterial1, "blahPipeline/1/blahStage/1");
        PipelineConfig pipelineConfig = PipelineMother.createPipelineConfig("mingle", new MaterialConfigs(hgMaterial1.config(), depMaterial1.config()), "dev");
        MaterialRevisions materialRevisions1 = new MaterialRevisions(hgMaterialRevision1, depMaterialRevision1);
        pipelineSqlMapDao.save(instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(materialRevisions1, Username.ANONYMOUS), new DefaultSchedulingContext(DEFAULT_APPROVED_BY), md5,
                new TimeProvider()));

        HgMaterial hgMaterial2 = MaterialsMother.hgMaterial("hgUrl", "dest");
        MaterialRevision hgMaterialRevision2 = saveOneScmModification(hgMaterial2, "user", "file");
        DependencyMaterial depMaterial2 = new DependencyMaterial(new CaseInsensitiveString("blahPipeline"), new CaseInsensitiveString("blahStage"));
        MaterialRevision depMaterialRevision2 = saveOneDependencyModification(depMaterial2, "blahPipeline/2/blahStage/1");
        PipelineConfig pipelineConfig2 = PipelineMother.createPipelineConfig("mingle", new MaterialConfigs(hgMaterial2.config(), depMaterial2.config()), "dev");
        MaterialRevisions materialRevisions2 = new MaterialRevisions(hgMaterialRevision2, depMaterialRevision2);

        savePipeline(instanceFactory.createPipelineInstance(pipelineConfig2, BuildCause.createManualForced(materialRevisions2, Username.ANONYMOUS), new DefaultSchedulingContext(DEFAULT_APPROVED_BY), md5,
                new TimeProvider()));

        MaterialRevisions revisions = new MaterialRevisions(new MaterialRevision(depMaterial1, depMaterialRevision1.getLatestModification()),
                new MaterialRevision(hgMaterial2, hgMaterialRevision2.getLatestModification()));
        assertThat(repo.hasPipelineEverRunWith("mingle", revisions)).isTrue();
    }

    private Pipeline savePipeline(Pipeline pipeline) {
        Integer lastCount = pipelineSqlMapDao.getCounterForPipeline(pipeline.getName());
        pipeline.updateCounter(lastCount);
        pipelineSqlMapDao.insertOrUpdatePipelineCounter(pipeline, lastCount, pipeline.getCounter());
        return pipelineSqlMapDao.save(pipeline);
    }

    @Test
    public void hasPipelineEverRunWithMultipleMaterialsAndNewChanges() {
        HgMaterial material = MaterialsMother.hgMaterial("hgUrl", "dest");
        MaterialRevision hgMaterialRevision = saveOneScmModification(material, "user", "file");

        DependencyMaterial depMaterial = new DependencyMaterial(new CaseInsensitiveString("blahPipeline"), new CaseInsensitiveString("blahStage"));
        MaterialRevision depMaterialRevision = saveOneDependencyModification(depMaterial, "blahPipeline/1/blahStage/1");

        PipelineConfig pipelineConfig = PipelineMother.createPipelineConfig("mingle", new MaterialConfigs(material.config(), depMaterial.config()), "dev");
        MaterialRevisions revisions = new MaterialRevisions(hgMaterialRevision, depMaterialRevision);
        pipelineSqlMapDao.save(instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(revisions, Username.ANONYMOUS), new DefaultSchedulingContext(DEFAULT_APPROVED_BY), md5,
                new TimeProvider()));

        MaterialRevision laterRevision = saveOneScmModification(material, "user", "file");

        MaterialRevisions newRevisions = new MaterialRevisions(depMaterialRevision, laterRevision);
        assertThat(repo.hasPipelineEverRunWith("mingle", newRevisions)).isFalse();
    }

    @Test
    public void hasPipelineEverRunWithMultipleMaterialsInPeggedRevisionsCase() {
        HgMaterial firstMaterial = MaterialsMother.hgMaterial("first", "dest");
        MaterialRevision first1 = saveOneScmModification("first1", firstMaterial, "user", "file", "comment");
        MaterialRevision first2 = saveOneScmModification("first2", firstMaterial, "user", "file", "comment");

        HgMaterial secondMaterial = MaterialsMother.hgMaterial("second", "dest");
        MaterialRevision second1 = saveOneScmModification("second1", secondMaterial, "user", "file", "comment");
        MaterialRevision second2 = saveOneScmModification("second2", secondMaterial, "user", "file", "comment");

        MaterialRevisions firstRun = new MaterialRevisions(first1, second2);
        MaterialRevisions secondRun = new MaterialRevisions(first2, second1);

        PipelineConfig config = PipelineMother.createPipelineConfig("mingle", new MaterialConfigs(firstMaterial.config(), secondMaterial.config()), "dev");
        savePipeline(instanceFactory.createPipelineInstance(config, BuildCause.createWithModifications(firstRun, "Pavan"), new DefaultSchedulingContext(DEFAULT_APPROVED_BY), md5, new TimeProvider()));
        savePipeline(instanceFactory.createPipelineInstance(config, BuildCause.createWithModifications(secondRun, "Shilpa-who-gets-along-well-with-her"), new DefaultSchedulingContext(DEFAULT_APPROVED_BY), md5,
                new TimeProvider()));

        assertThat(repo.hasPipelineEverRunWith("mingle", new MaterialRevisions(first2, second2))).isTrue();
    }

    @Test
    public void hasPipelineEverRunWith_shouldCacheResultsForPipelineNameMaterialIdAndModificationId() {
        HgMaterial hgMaterial = MaterialsMother.hgMaterial("hgUrl", "dest");
        MaterialRevision materialRevision = saveOneScmModification(hgMaterial, "user", "file");
        PipelineConfig pipelineConfig = PipelineMother.createPipelineConfig("mingle", new MaterialConfigs(hgMaterial.config()), "dev");
        MaterialRevisions materialRevisions = new MaterialRevisions(materialRevision);
        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(materialRevisions, Username.ANONYMOUS), new DefaultSchedulingContext(DEFAULT_APPROVED_BY), md5,
                new TimeProvider());

        GoCache spyGoCache = spy(goCache);
        when(spyGoCache.get(any(String.class))).thenCallRealMethod();
        doCallRealMethod().when(spyGoCache).put(any(String.class), any(Object.class));
        repo = new MaterialRepository(sessionFactory, spyGoCache, 2, transactionSynchronizationManager, materialConfigConverter, materialExpansionService, databaseStrategy);

        pipelineSqlMapDao.save(pipeline);

        MaterialRevisions revisions = new MaterialRevisions(new MaterialRevision(hgMaterial, materialRevision.getLatestModification()));

        assertThat(repo.hasPipelineEverRunWith("mingle", revisions)).isTrue();
        assertThat(repo.hasPipelineEverRunWith("mingle", revisions)).isTrue();

        verify(spyGoCache, times(1)).put(any(String.class), eq(Boolean.TRUE));
    }

    @Test
    public void shouldSavePipelineMaterialRevisions() {
        SvnMaterialConfig svnMaterialConfig = MaterialConfigsMother.svnMaterialConfig("gitUrl", "folder", "user", "pass", true, "*.doc");
        assertCanLoadAndSaveMaterialRevisionsFor(svnMaterialConfig);
    }

    @Test
    public void shouldSaveGitPipelineMaterialRevisions() {
        GitMaterialConfig gitMaterialConfig = MaterialConfigsMother.gitMaterialConfig("gitUrl", "submoduleFolder", "branch", false);
        assertCanLoadAndSaveMaterialRevisionsFor(gitMaterialConfig);
    }

    @Test
    public void shouldSaveHgPipelineMaterialRevisions() {
        HgMaterialConfig hgMaterialConfig = MaterialConfigsMother.hgMaterialConfig("hgUrl", "dest");
        assertCanLoadAndSaveMaterialRevisionsFor(hgMaterialConfig);
    }

    @Test
    public void shouldSaveP4PipelineMaterialRevisions() {
        P4MaterialConfig p4MaterialConfig = MaterialConfigsMother.p4MaterialConfig("serverAndPort", "user", "pwd", "view", true);
        assertCanLoadAndSaveMaterialRevisionsFor(p4MaterialConfig);
    }

    @Test
    public void shouldSaveDependencyPipelineMaterialRevisions() {
        DependencyMaterialConfig dependencyMaterialConfig = new DependencyMaterialConfig(new CaseInsensitiveString("pipeline"), new CaseInsensitiveString("stage"));
        assertCanLoadAndSaveMaterialRevisionsFor(dependencyMaterialConfig);
    }

    @Test
    public void shouldReturnModificationForASpecificRevision() {
        DependencyMaterial dependencyMaterial = new DependencyMaterial(new CaseInsensitiveString("blahPipeline"), new CaseInsensitiveString("blahStage"));
        MaterialRevision originalRevision = saveOneDependencyModification(dependencyMaterial, "blahPipeline/3/blahStage/1");

        Modification modification = repo.findModificationWithRevision(dependencyMaterial, "blahPipeline/3/blahStage/1");

        assertThat(modification.getRevision()).isEqualTo("blahPipeline/3/blahStage/1");
        assertEquals(originalRevision.getModification(0).getModifiedTime(), modification.getModifiedTime());
    }

    @Test
    public void shouldPickupTheRightFromAndToForMaterialRevisions() {
        HgMaterial material = new HgMaterial("sdg", null);
        MaterialRevision firstRevision = new MaterialRevision(material, new Modifications(modification("6")));
        saveMaterialRev(firstRevision);
        final MaterialRevision secondRevision = new MaterialRevision(material, new Modifications(modification("10"), modification("12"), modification("13")));
        saveMaterialRev(secondRevision);
        final Pipeline pipeline = createPipeline();
        savePMR(secondRevision, pipeline);

        List<Modification> modificationsSince = repo.findModificationsSince(material, firstRevision);

        assertThat(modificationsSince.get(0).getRevision()).isEqualTo("10");
        assertThat(modificationsSince.get(modificationsSince.size() - 1).getRevision()).isEqualTo("13");
    }

    @Test
    public void shouldUseToAndFromAsRangeForSCMMaterialRevisionWhileSavingAndUpdating() {
        HgMaterial material = new HgMaterial("sdg", null);
        final MaterialRevision firstRevision = new MaterialRevision(material, new Modifications(modification("10"), modification("9"), modification("8")));
        saveMaterialRev(firstRevision);
        final Pipeline firstPipeline = createPipeline();
        savePMR(firstRevision, firstPipeline);

        MaterialRevisions revisionsFor11 = repo.findMaterialRevisionsForPipeline(firstPipeline.getId());
        assertThat(revisionsFor11.getModifications(material).size()).isEqualTo(3);
        assertThat(revisionsFor11.getModifications(material).get(0).getRevision()).isEqualTo("10");
        assertThat(revisionsFor11.getModifications(material).get(1).getRevision()).isEqualTo("9");
        assertThat(revisionsFor11.getModifications(material).get(2).getRevision()).isEqualTo("8");

        MaterialRevision secondRevision = new MaterialRevision(material, new Modifications(modification("11"), modification("10.5")));
        saveMaterialRev(secondRevision);
        Pipeline secondPipeline = createPipeline();
        savePMR(secondRevision, secondPipeline);

        MaterialRevisions revisionsFor12 = repo.findMaterialRevisionsForPipeline(secondPipeline.getId());
        assertThat(revisionsFor12.getModifications(material).size()).isEqualTo(2);
        assertThat(revisionsFor12.getModifications(material).get(0).getRevision()).isEqualTo("11");
        assertThat(revisionsFor12.getModifications(material).get(1).getRevision()).isEqualTo("10.5");

        MaterialRevision thirdRevision = new MaterialRevision(material, new Modifications(modification("12")));
        saveMaterialRev(thirdRevision);
        Pipeline thirdPipeline = createPipeline();
        savePMR(thirdRevision, thirdPipeline);

        MaterialRevisions revisionsFor13 = repo.findMaterialRevisionsForPipeline(thirdPipeline.getId());
        assertThat(revisionsFor13.getModifications(material).size()).isEqualTo(1);
        assertThat(revisionsFor13.getModifications(material).get(0).getRevision()).isEqualTo("12");
    }

    @Test
    public void shouldFixToAsFromForDependencyMaterialRevisionWhileSavingAndUpdating() {
        Material material = new DependencyMaterial(new CaseInsensitiveString("pipeline_name"), new CaseInsensitiveString("stage_name"));
        MaterialRevision firstRevision = new MaterialRevision(material, new Modifications(modification("pipeline_name/10/stage_name/1"), modification("pipeline_name/9/stage_name/2"), modification("pipeline_name/8/stage_name/2")));
        saveMaterialRev(firstRevision);
        Pipeline firstPipeline = createPipeline();
        savePMR(firstRevision, firstPipeline);
        MaterialRevisions revisionsFor11 = repo.findMaterialRevisionsForPipeline(firstPipeline.getId());
        assertThat(revisionsFor11.getModifications(material).size()).isEqualTo(1);
        assertThat(revisionsFor11.getModifications(material).get(0).getRevision()).isEqualTo("pipeline_name/10/stage_name/1");

        MaterialRevision secondRevision = new MaterialRevision(material, new Modifications(modification("pipeline_name/11/stage_name/2"), modification("pipeline_name/11/stage_name/1")));
        saveMaterialRev(secondRevision);
        Pipeline secondPipeline = createPipeline();
        savePMR(secondRevision, secondPipeline);

        MaterialRevisions revisionsFor12 = repo.findMaterialRevisionsForPipeline(secondPipeline.getId());
        assertThat(revisionsFor12.getModifications(material).size()).isEqualTo(1);
        assertThat(revisionsFor12.getModifications(material).get(0).getRevision()).isEqualTo("pipeline_name/11/stage_name/2");

        MaterialRevision thirdRevision = new MaterialRevision(material, new Modifications(modification("pipeline_name/12/stage_name/1")));
        saveMaterialRev(thirdRevision);
        Pipeline thirdPipeline = createPipeline();
        savePMR(thirdRevision, thirdPipeline);

        savePMR(thirdRevision, thirdPipeline);

        MaterialRevisions revisionsFor13 = repo.findMaterialRevisionsForPipeline(thirdPipeline.getId());
        assertThat(revisionsFor13.getModifications(material).size()).isEqualTo(1);
        assertThat(revisionsFor13.getModifications(material).get(0).getRevision()).isEqualTo("pipeline_name/12/stage_name/1");
    }

    @Test
    public void shouldPersistActualFromRevisionSameAsFromForSCMMaterial() {
        HgMaterial material = new HgMaterial("sdg", null);
        MaterialRevision firstRevision = new MaterialRevision(material, new Modifications(modification("10"), modification("9"), modification("8")));
        saveMaterialRev(firstRevision);
        Pipeline firstPipeline = createPipeline();
        savePMR(firstRevision, firstPipeline);
        List<PipelineMaterialRevision> pmrs = repo.findPipelineMaterialRevisions(firstPipeline.getId());
        assertThat(pmrs.get(0).getActualFromRevisionId()).isEqualTo(pmrs.get(0).getFromModification().getId());
    }

    @Test
    public void shouldPersistActualFromRevisionUsingTheRealFromForDependencyMaterial() {
        Material material = new DependencyMaterial(new CaseInsensitiveString("pipeline_name"), new CaseInsensitiveString("stage_name"));
        Modification actualFrom = modification("pipeline_name/8/stage_name/2");
        Modification from = modification("pipeline_name/10/stage_name/1");
        MaterialRevision firstRevision = new MaterialRevision(material, new Modifications(from, modification("pipeline_name/9/stage_name/2"), actualFrom));
        saveMaterialRev(firstRevision);
        Pipeline firstPipeline = createPipeline();
        savePMR(firstRevision, firstPipeline);

        List<PipelineMaterialRevision> pmrs = repo.findPipelineMaterialRevisions(firstPipeline.getId());
        assertThat(pmrs.get(0).getActualFromRevisionId()).isEqualTo(actualFrom.getId());
        assertEquals(from, pmrs.get(0).getFromModification());
    }

    @Test
    public void shouldUseTheFromIdAsActualFromIdWhenThePipelineIsBeingBuiltForTheFirstTime() {
        Material material = new DependencyMaterial(new CaseInsensitiveString("pipeline_name"), new CaseInsensitiveString("stage_name"));
        Modification actualFrom = modification("pipeline_name/8/stage_name/2");
        MaterialRevision firstRevision = new MaterialRevision(material, new Modifications(modification("pipeline_name/9/stage_name/2"), actualFrom));
        saveMaterialRev(firstRevision);

        HgMaterial hgMaterial = new HgMaterial("sdg", null);
        MaterialRevision hgRevision = new MaterialRevision(hgMaterial, new Modifications(modification("10"), modification("9")));
        saveMaterialRev(hgRevision);

        Modification from = modification("pipeline_name/10/stage_name/1");
        firstRevision = new MaterialRevision(material, new Modifications(from));
        saveMaterialRev(firstRevision);

        Pipeline firstPipeline = createPipeline();
        savePMR(firstRevision, firstPipeline);

        List<PipelineMaterialRevision> pmrs = repo.findPipelineMaterialRevisions(firstPipeline.getId());

        assertThat(pmrs.get(0).getActualFromRevisionId()).isEqualTo(from.getId());
        assertEquals(from, pmrs.get(0).getFromModification());
    }

    @Test
    public void shouldPersistActualFromRevisionForSameRevisionOfDependencyMaterialModifications() {
        Material material = new DependencyMaterial(new CaseInsensitiveString("pipeline_name"), new CaseInsensitiveString("stage_name"));
        Modification actualFrom = modification("pipeline_name/8/stage_name/2");
        MaterialRevision firstRevision = new MaterialRevision(material, new Modifications(actualFrom));
        saveMaterialRev(firstRevision);
        Pipeline firstPipeline = createPipeline();
        savePMR(firstRevision, firstPipeline);

        firstPipeline = createPipeline();
        savePMR(firstRevision, firstPipeline);

        List<PipelineMaterialRevision> pmrs = repo.findPipelineMaterialRevisions(firstPipeline.getId());

        assertThat(pmrs.get(0).getActualFromRevisionId()).isEqualTo(actualFrom.getId());
        assertEquals(actualFrom, pmrs.get(0).getFromModification());
    }

    @Test
    public void shouldUpdatePipelineMaterialRevisions() {
        HgMaterial material = new HgMaterial("sdg", null);
        Modification first = modification("6");
        Modification second = modification("7");
        MaterialRevision firstRevision = new MaterialRevision(material, new Modifications(second, first));
        saveMaterialRev(firstRevision);

        material.setId(repo.findMaterialInstance(material).getId());
        MaterialRevision secondRevision = new MaterialRevision(material, new Modifications(first));
        Pipeline secondPipeline = createPipeline();
        savePMR(secondRevision, secondPipeline);

        MaterialRevisions materialRevisions = repo.findMaterialRevisionsForPipeline(secondPipeline.getId());
        assertEquals(secondRevision, materialRevisions.getMaterialRevision(0));

        List<PipelineMaterialRevision> pipelineMaterialRevisions = repo.findPipelineMaterialRevisions(secondPipeline.getId());
        assertThat(pipelineMaterialRevisions.get(0).getMaterialId()).isEqualTo(material.getId());
    }

    @Test
    public void shouldReturnMaterialRevisionsWithEmptyModificationsWhenNoModifications() {
        Material material = material();
        repo.saveOrUpdate(material.createMaterialInstance());
        MaterialRevisions materialRevisions = repo.findLatestRevisions(new MaterialConfigs(material.config()));
        assertThat(materialRevisions.numberOfRevisions()).isEqualTo(1);
        MaterialRevision materialRevision = materialRevisions.getMaterialRevision(0);
        assertThat(materialRevision.getMaterial()).isEqualTo(material);
        assertThat(materialRevision.getModifications().size()).isEqualTo(0);
    }

    @Test
    public void shouldReturnMatchedRevisionsForAGivenSearchString() {
        ScmMaterial material = material();
        repo.saveOrUpdate(material.createMaterialInstance());
        MaterialRevision materialRevision = saveOneScmModification("40c95a3c41f54b5fb3107982cf2acd08783f102a", material, "pavan", "meet_you_in_hell.txt", "comment");
        saveOneScmModification(material, "turn_her", "of_course_he_will_be_there_first.txt");

        List<MatchedRevision> revisions = repo.findRevisionsMatching(material.config(), "pavan");
        assertThat(revisions.size()).isEqualTo(1);
        assertMatchedRevision(revisions.get(0), materialRevision.getLatestShortRevision(), materialRevision.getLatestRevisionString(), "pavan", materialRevision.getDateOfLatestModification(), "comment");
    }

    @Test
    public void shouldMatchPipelineLabelForDependencyModifications() {
        DependencyMaterial material = new DependencyMaterial(new CaseInsensitiveString("pipeline-name"), new CaseInsensitiveString("stage-name"));
        repo.saveOrUpdate(material.createMaterialInstance());
        MaterialRevision first = saveOneDependencyModification(material, "pipeline-name/1/stage-name/3", "my-random-label-123");
        MaterialRevision second = saveOneDependencyModification(material, "pipeline-name/3/stage-name/1", "other-label-456");

        List<MatchedRevision> revisions = repo.findRevisionsMatching(material.config(), "my-random");
        assertThat(revisions.size()).isEqualTo(1);
        assertMatchedRevision(revisions.get(0), first.getLatestShortRevision(), first.getLatestRevisionString(), null, first.getDateOfLatestModification(), "my-random-label-123");

        revisions = repo.findRevisionsMatching(material.config(), "other-label");
        assertThat(revisions.size()).isEqualTo(1);
        assertMatchedRevision(revisions.get(0), second.getLatestShortRevision(), second.getLatestRevisionString(), null, second.getDateOfLatestModification(), "other-label-456");

        revisions = repo.findRevisionsMatching(material.config(), "something-else");
        assertThat(revisions.size()).isEqualTo(0);
    }

    @Test
    public void shouldMatchSearchStringAcrossColumn() {
        ScmMaterial material = material();
        repo.saveOrUpdate(material.createMaterialInstance());
        MaterialRevision first = saveOneScmModification("40c95a3c41f54b5fb3107982cf2acd08783f102a", material, "pavan", "meet_you_in_hell.txt", "comment");
        MaterialRevision second = saveOneScmModification("c30c471137f31a4bf735f653f888e799f6deec04", material, "turn_her", "of_course_he_will_be_there_first.txt", "comment");

        List<MatchedRevision> revisions = repo.findRevisionsMatching(material.config(), "pavan co");
        assertThat(revisions.size()).isEqualTo(1);
        assertMatchedRevision(revisions.get(0), first.getLatestShortRevision(), first.getLatestRevisionString(), "pavan", first.getDateOfLatestModification(), "comment");

        revisions = repo.findRevisionsMatching(material.config(), "her co");
        assertThat(revisions.size()).isEqualTo(1);
        assertMatchedRevision(revisions.get(0), second.getLatestShortRevision(), second.getLatestRevisionString(), "turn_her", second.getDateOfLatestModification(), "comment");

        revisions = repo.findRevisionsMatching(material.config(), "of_curs");
        assertThat(revisions.size()).isEqualTo(0);
    }

    @Test
    public void shouldMatchSearchStringInDecreasingOrder() {
        ScmMaterial material = material();
        repo.saveOrUpdate(material.createMaterialInstance());
        MaterialRevision first = saveOneScmModification("40c95a3c41f54b5fb3107982cf2acd08783f102a", material, "pavan", "meet_you_in_hell.txt", "comment");
        MaterialRevision second = saveOneScmModification("c30c471137f31a4bf735f653f888e799f6deec04", material, "turn_her", "of_course_he_will_be_there_first.txt", "comment");

        List<MatchedRevision> revisions = repo.findRevisionsMatching(material.config(), "");
        assertThat(revisions.size()).isEqualTo(2);
        assertMatchedRevision(revisions.get(0), second.getLatestShortRevision(), second.getLatestRevisionString(), "turn_her", second.getDateOfLatestModification(), "comment");
        assertMatchedRevision(revisions.get(1), first.getLatestShortRevision(), first.getLatestRevisionString(), "pavan", first.getDateOfLatestModification(), "comment");
    }

    @Test
    public void shouldConsiderFieldToBeEmptyWhenRevisionOrUsernameOrCommentIsNull() {
        ScmMaterial material = material();
        repo.saveOrUpdate(material.createMaterialInstance());
        MaterialRevision userIsNullRevision = saveOneScmModification("40c95a3c41f54b5fb3107982cf2acd08783f102a", material, null, "meet_you_in_hell.txt", "bring it on!");
        MaterialRevision commentIsNullRevision = saveOneScmModification("c30c471137f31a4bf735f653f888e799f6deec04", material, "turn_her", "lets_party_in_hell.txt", null);

        List<MatchedRevision> revisions = repo.findRevisionsMatching(material.config(), "bring");
        assertThat(revisions.size()).isEqualTo(1);
        assertMatchedRevision(revisions.get(0), userIsNullRevision.getLatestShortRevision(), userIsNullRevision.getLatestRevisionString(), null, userIsNullRevision.getDateOfLatestModification(), "bring it on!");

        revisions = repo.findRevisionsMatching(material.config(), "c04 turn");
        assertThat(revisions.size()).isEqualTo(1);
        assertMatchedRevision(revisions.get(0), commentIsNullRevision.getLatestShortRevision(), commentIsNullRevision.getLatestRevisionString(), "turn_her",
                commentIsNullRevision.getDateOfLatestModification(), null);

        revisions = repo.findRevisionsMatching(material.config(), "null");
        assertThat(revisions.size()).isEqualTo(0);
    }

    @Test
    public void shouldFindLatestRevision() {
        ScmMaterial material = material();
        repo.saveOrUpdate(material.createMaterialInstance());
        saveOneScmModification("40c95a3c41f54b5fb3107982cf2acd08783f102a", material, "pavan", "meet_you_in_hell.txt", "comment");
        MaterialRevision second = saveOneScmModification("c30c471137f31a4bf735f653f888e799f6deec04", material, "turn_her", "of_course_he_will_be_there_first.txt", "comment");

        MaterialRevisions materialRevisions = repo.findLatestModification(material);
        List<MaterialRevision> revisions = materialRevisions.getRevisions();
        assertThat(revisions.size()).isEqualTo(1);
        MaterialRevision materialRevision = revisions.get(0);
        assertThat(materialRevision.getLatestRevisionString()).isEqualTo(second.getLatestRevisionString());
    }

    @Test
    public void shouldCacheModificationCountsForMaterialCorrectly() {
        ScmMaterial material = material();
        MaterialInstance materialInstance = material.createMaterialInstance();
        repo.saveOrUpdate(materialInstance);
        saveOneScmModification("1", material, "user1", "1.txt", "comment1");
        saveOneScmModification("2", material, "user2", "2.txt", "comment2");
        saveOneScmModification("3", material, "user3", "3.txt", "comment3");
        saveOneScmModification("4", material, "user4", "4.txt", "comment4");
        saveOneScmModification("5", material, "user5", "5.txt", "comment5");

        Long totalCount = repo.getTotalModificationsFor(materialInstance);

        assertThat(totalCount).isEqualTo(5L);
    }

    @Test
    public void shouldCacheModificationsForMaterialCorrectly() {
        final ScmMaterial material = material();
        MaterialInstance materialInstance = material.createMaterialInstance();
        repo.saveOrUpdate(materialInstance);
        saveOneScmModification("1", material, "user1", "1.txt", "comment1");
        saveOneScmModification("2", material, "user2", "2.txt", "comment2");
        saveOneScmModification("3", material, "user3", "3.txt", "comment3");
        saveOneScmModification("4", material, "user4", "4.txt", "comment4");
        saveOneScmModification("5", material, "user5", "5.txt", "comment5");

        repo.getTotalModificationsFor(materialInstance);

        goCache.get(repo.materialModificationCountKey(materialInstance));

        final Modification modOne = new Modification("user", "comment", "email@gmail.com", new Date(), "123");
        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                MaterialInstance foo = repo.findOrCreateFrom(material);

                repo.saveModifications(foo, List.of(modOne));
            }
        });

        assertThat(goCache.<Long>get(repo.materialModificationCountKey(materialInstance))).isNull();
    }

    @Test
    public void shouldGetPaginatedModificationsForMaterialCorrectly() {
        ScmMaterial material = material();
        MaterialInstance materialInstance = material.createMaterialInstance();
        repo.saveOrUpdate(materialInstance);
        MaterialRevision first = saveOneScmModification("1", material, "user1", "1.txt", "comment1");
        MaterialRevision second = saveOneScmModification("2", material, "user2", "2.txt", "comment2");
        MaterialRevision third = saveOneScmModification("3", material, "user3", "3.txt", "comment3");
        MaterialRevision fourth = saveOneScmModification("4", material, "user4", "4.txt", "comment4");
        MaterialRevision fifth = saveOneScmModification("5", material, "user5", "5.txt", "comment5");

        Modifications modifications = repo.getModificationsFor(materialInstance, Pagination.pageStartingAt(0, 5, 3));

        assertThat(modifications.size()).isEqualTo(3);
        assertThat(modifications.get(0).getRevision()).isEqualTo(fifth.getLatestRevisionString());
        assertThat(modifications.get(1).getRevision()).isEqualTo(fourth.getLatestRevisionString());
        assertThat(modifications.get(2).getRevision()).isEqualTo(third.getLatestRevisionString());

        modifications = repo.getModificationsFor(materialInstance, Pagination.pageStartingAt(3, 5, 3));

        assertThat(modifications.size()).isEqualTo(2);
        assertThat(modifications.get(0).getRevision()).isEqualTo(second.getLatestRevisionString());
        assertThat(modifications.get(1).getRevision()).isEqualTo(first.getLatestRevisionString());
    }

    @Test
    public void shouldCachePaginatedModificationsForMaterialCorrectly() {
        final ScmMaterial material = material();
        MaterialInstance materialInstance = material.createMaterialInstance();
        repo.saveOrUpdate(materialInstance);
        MaterialRevision first = saveOneScmModification("1", material, "user1", "1.txt", "comment1");
        MaterialRevision second = saveOneScmModification("2", material, "user2", "2.txt", "comment2");
        MaterialRevision third = saveOneScmModification("3", material, "user3", "3.txt", "comment3");
        MaterialRevision fourth = saveOneScmModification("4", material, "user4", "4.txt", "comment4");
        MaterialRevision fifth = saveOneScmModification("5", material, "user5", "5.txt", "comment5");

        Pagination page = Pagination.pageStartingAt(0, 5, 3);
        repo.getModificationsFor(materialInstance, page);
        Modifications modificationsFromCache = (Modifications) goCache.get(repo.materialModificationsWithPaginationKey(materialInstance), repo.materialModificationsWithPaginationSubKey(page));

        assertThat(modificationsFromCache.size()).isEqualTo(3);
        assertThat(modificationsFromCache.get(0).getRevision()).isEqualTo(fifth.getLatestRevisionString());
        assertThat(modificationsFromCache.get(1).getRevision()).isEqualTo(fourth.getLatestRevisionString());
        assertThat(modificationsFromCache.get(2).getRevision()).isEqualTo(third.getLatestRevisionString());


        page = Pagination.pageStartingAt(1, 5, 3);
        repo.getModificationsFor(materialInstance, page);
        modificationsFromCache = (Modifications) goCache.get(repo.materialModificationsWithPaginationKey(materialInstance), repo.materialModificationsWithPaginationSubKey(page));

        assertThat(modificationsFromCache.size()).isEqualTo(3);
        assertThat(modificationsFromCache.get(0).getRevision()).isEqualTo(fourth.getLatestRevisionString());
        assertThat(modificationsFromCache.get(1).getRevision()).isEqualTo(third.getLatestRevisionString());
        assertThat(modificationsFromCache.get(2).getRevision()).isEqualTo(second.getLatestRevisionString());


        page = Pagination.pageStartingAt(3, 5, 3);
        repo.getModificationsFor(materialInstance, page);
        modificationsFromCache = (Modifications) goCache.get(repo.materialModificationsWithPaginationKey(materialInstance), repo.materialModificationsWithPaginationSubKey(page));

        assertThat(modificationsFromCache.size()).isEqualTo(2);
        assertThat(modificationsFromCache.get(0).getRevision()).isEqualTo(second.getLatestRevisionString());
        assertThat(modificationsFromCache.get(1).getRevision()).isEqualTo(first.getLatestRevisionString());

        final Modification modOne = new Modification("user", "comment", "email@gmail.com", new Date(), "123");
        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                MaterialInstance foo = repo.findOrCreateFrom(material);

                repo.saveModifications(foo, List.of(modOne));
            }
        });

        modificationsFromCache = (Modifications) goCache.get(repo.materialModificationsWithPaginationKey(materialInstance), repo.materialModificationsWithPaginationSubKey(Pagination.pageStartingAt(0, 5, 3)));

        assertThat(modificationsFromCache).isNull();

        modificationsFromCache = (Modifications) goCache.get(repo.materialModificationsWithPaginationKey(materialInstance), repo.materialModificationsWithPaginationSubKey(Pagination.pageStartingAt(3, 5, 3)));

        assertThat(modificationsFromCache).isNull();
    }

    @Test
    public void shouldFindLatestModificationRunByPipeline() {
        ScmMaterial material = material();
        repo.saveOrUpdate(material.createMaterialInstance());
        MaterialRevision first = saveOneScmModification("40c95a3c41f54b5fb3107982cf2acd08783f102a", material, "pavan", "meet_you_in_hell.txt", "comment");
        MaterialRevision second = saveOneScmModification("c30c471137f31a4bf735f653f888e799f6deec04", material, "turn_her", "of_course_he_will_be_there_first.txt", "comment");
        Pipeline pipeline = createPipeline();
        savePMR(first, pipeline);
        savePMR(second, pipeline);
        Long latestModId = repo.latestModificationRunByPipeline(new CaseInsensitiveString(pipeline.getName()), material);

        assertThat(latestModId).isEqualTo(second.getLatestModification().getId());
    }

    @Test
    public void shouldFindModificationsForAStageIdentifier() {
        DependencyMaterial material = new DependencyMaterial(new CaseInsensitiveString("P1"), new CaseInsensitiveString("S1"));
        repo.saveOrUpdate(material.createMaterialInstance());
        saveOneDependencyModification(material, "P1/1/S1/1");
        saveOneDependencyModification(material, "P1/2/S1/1");
        saveOneDependencyModification(material, "P1/1/S2/1");
        saveOneDependencyModification(material, "P2/1/S1/1");
        StageIdentifier stageIdentifier = new StageIdentifier("P1", 2, "2", "S1", "1");
        List<Modification> modifications = repo.modificationFor(stageIdentifier);
        assertThat(modifications.size()).isEqualTo(1);
        assertThat(modifications.get(0).getRevision()).isEqualTo("P1/2/S1/1");
        assertThat(goCache.<Object>get(repo.cacheKeyForModificationsForStageLocator(stageIdentifier))).isEqualTo(modifications);

        StageIdentifier p2_s1_stageId = new StageIdentifier("P2", 1, "S1", "1");
        List<Modification> mod_p2_s1 = repo.modificationFor(p2_s1_stageId);
        assertThat(goCache.<Object>get(repo.cacheKeyForModificationsForStageLocator(p2_s1_stageId))).isEqualTo(mod_p2_s1);
        StageIdentifier p2_s1_3 = new StageIdentifier("P2", 1, "S1", "3");
        assertThat(repo.modificationFor(p2_s1_3)).isEmpty();
        assertThat(goCache.<Object>get(repo.cacheKeyForModificationsForStageLocator(p2_s1_3))).isNull();
    }

    @Test
    public void shouldSavePackageMaterialInstance() {
        PackageMaterial material = new PackageMaterial();
        PackageRepository repository = PackageRepositoryMother.create("repo-id", "repo", "pluginid", "version", new Configuration(ConfigurationPropertyMother.create("k1", false, "v1")));
        material.setPackageDefinition(PackageDefinitionMother.create("p-id", "name", new Configuration(ConfigurationPropertyMother.create("k2", false, "v2")), repository));
        PackageMaterialInstance savedMaterialInstance = (PackageMaterialInstance) repo.findOrCreateFrom(material);
        assertThat(savedMaterialInstance.getId() > 0).isTrue();
        assertThat(savedMaterialInstance.getFingerprint()).isEqualTo(material.getFingerprint());
        assertThat(JsonHelper.fromJson(savedMaterialInstance.getConfiguration(), PackageMaterial.class).getPackageDefinition().getConfiguration()).isEqualTo(material.getPackageDefinition().getConfiguration());
        assertThat(JsonHelper.fromJson(savedMaterialInstance.getConfiguration(), PackageMaterial.class).getPackageDefinition().getRepository().getPluginConfiguration().getId()).isEqualTo(material.getPackageDefinition().getRepository().getPluginConfiguration().getId());
        assertThat(JsonHelper.fromJson(savedMaterialInstance.getConfiguration(), PackageMaterial.class).getPackageDefinition().getRepository().getConfiguration()).isEqualTo(material.getPackageDefinition().getRepository().getConfiguration());
    }

    @Test
    public void shouldSavePluggableSCMMaterialInstance() {
        PluggableSCMMaterial material = new PluggableSCMMaterial();
        ConfigurationProperty k1 = ConfigurationPropertyMother.create("k1", false, "v1");
        ConfigurationProperty k2 = ConfigurationPropertyMother.create("k2", true, "v2");
        material.setSCMConfig(SCMMother.create("scm-id", "scm-name", "plugin-id", "1.0", new Configuration(k1, k2)));

        PluggableSCMMaterialInstance savedMaterialInstance = (PluggableSCMMaterialInstance) repo.findOrCreateFrom(material);

        assertThat(savedMaterialInstance.getId() > 0).isTrue();
        assertThat(savedMaterialInstance.getFingerprint()).isEqualTo(material.getFingerprint());
        assertThat(JsonHelper.fromJson(savedMaterialInstance.getConfiguration(), PluggableSCMMaterial.class).getScmConfig().getConfiguration()).isEqualTo(material.getScmConfig().getConfiguration());
        assertThat(JsonHelper.fromJson(savedMaterialInstance.getConfiguration(), PluggableSCMMaterial.class).getScmConfig().getPluginConfiguration().getId()).isEqualTo(material.getScmConfig().getPluginConfiguration().getId());
    }

    @Test
    public void shouldRemoveDuplicatesBeforeInsertingModifications() {
        final MaterialInstance materialInstance = repo.findOrCreateFrom(new GitMaterial(UUID.randomUUID().toString(), "branch"));
        final List<Modification> firstSetOfModifications = getModifications(3);
        transactionTemplate.execute(status -> {
            repo.saveModifications(materialInstance, firstSetOfModifications);
            return null;
        });

        Modifications firstSetOfModificationsFromDb = repo.getModificationsFor(materialInstance, Pagination.pageByNumber(1, 10, 10));
        assertThat(firstSetOfModificationsFromDb.size()).isEqualTo(3);
        for (Modification modification : firstSetOfModifications) {
            assertThat(firstSetOfModificationsFromDb.containsRevisionFor(modification)).isTrue();
        }

        final List<Modification> secondSetOfModificationsContainingDuplicateRevisions = getModifications(4);

        transactionTemplate.execute(status -> {
            repo.saveModifications(materialInstance, secondSetOfModificationsContainingDuplicateRevisions);
            return null;
        });
        Modifications secondSetOfModificationsFromDb = repo.getModificationsFor(materialInstance, Pagination.pageByNumber(1, 10, 10));
        assertThat(secondSetOfModificationsFromDb.size()).isEqualTo(4);
        for (final Modification fromPreviousCycle : firstSetOfModificationsFromDb) {
            Modification modification = secondSetOfModificationsFromDb.stream().filter(item -> item.getId() == fromPreviousCycle.getId()).findFirst().orElse(null);
            assertThat(modification).isNotNull();
        }
        for (Modification modification : secondSetOfModificationsContainingDuplicateRevisions) {
            assertThat(secondSetOfModificationsFromDb.containsRevisionFor(modification)).isTrue();
        }
    }

    @Test
    public void shouldNotBlowUpReportErrorIfAnAttemptIsMadeToInsertOnlyDuplicateModificationsForAGivenMaterial() {
        final MaterialInstance materialInstance = repo.findOrCreateFrom(new GitMaterial(UUID.randomUUID().toString(), "branch"));
        final List<Modification> firstSetOfModifications = getModifications(3);
        transactionTemplate.execute(status -> {
            repo.saveModifications(materialInstance, firstSetOfModifications);
            return null;
        });
        Modifications firstSetOfModificationsFromDb = repo.getModificationsFor(materialInstance, Pagination.pageByNumber(1, 10, 10));
        assertThat(firstSetOfModificationsFromDb.size()).isEqualTo(3);
        for (Modification modification : firstSetOfModifications) {
            assertThat(firstSetOfModificationsFromDb.containsRevisionFor(modification)).isTrue();
        }

        final List<Modification> secondSetOfModificationsContainingAllDuplicateRevisions = getModifications(3);
        transactionTemplate.execute(status -> {
            repo.saveModifications(materialInstance, secondSetOfModificationsContainingAllDuplicateRevisions);
            return null;
        });
    }

    @Test
    public void shouldAllowSavingModificationsIfRevisionsAcrossDifferentMaterialsHappenToBeSame() {
        final MaterialInstance materialInstance1 = repo.findOrCreateFrom(new GitMaterial(UUID.randomUUID().toString(), "branch"));
        final MaterialInstance materialInstance2 = repo.findOrCreateFrom(new GitMaterial(UUID.randomUUID().toString(), "branch"));
        final List<Modification> modificationsForFirstMaterial = getModifications(3);
        transactionTemplate.execute(status -> {
            repo.saveModifications(materialInstance1, modificationsForFirstMaterial);
            return null;
        });

        assertThat(repo.getModificationsFor(materialInstance1, Pagination.pageByNumber(1, 10, 10)).size()).isEqualTo(3);

        final List<Modification> modificationsForSecondMaterial = getModifications(3);

        transactionTemplate.execute(status -> {
            repo.saveModifications(materialInstance2, modificationsForSecondMaterial);
            return null;
        });
        Modifications modificationsFromDb = repo.getModificationsFor(materialInstance2, Pagination.pageByNumber(1, 10, 10));
        assertThat(modificationsFromDb.size()).isEqualTo(3);
        for (Modification modification : modificationsForSecondMaterial) {
            assertThat(modificationsFromDb.containsRevisionFor(modification)).isTrue();
        }
    }

    @Test
    public void shouldNotSaveAndClearCacheWhenThereAreNoNewModifications() {
        final MaterialInstance materialInstance = repo.findOrCreateFrom(new GitMaterial(UUID.randomUUID().toString(), "branch"));
        String key = repo.materialModificationsWithPaginationKey(materialInstance);
        String subKey = repo.materialModificationsWithPaginationSubKey(Pagination.ONE_ITEM);
        goCache.put(key, subKey, new Modifications(new Modification()));
        transactionTemplate.execute(status -> {
            repo.saveModifications(materialInstance, new ArrayList<>());
            return null;
        });
        assertThat(goCache.get(key, subKey)).isNotNull();
    }

    //Slow test - takes ~1 min to run. Will remove if it causes issues. - Jyoti
    @Test
    public void shouldBeAbleToHandleLargeNumberOfModifications() {
        final MaterialInstance materialInstance = repo.findOrCreateFrom(new GitMaterial(UUID.randomUUID().toString(), "branch"));
        int count = 10000;
        final List<Modification> firstSetOfModifications = getModifications(count);
        transactionTemplate.execute(status -> {
            repo.saveModifications(materialInstance, firstSetOfModifications);
            return null;
        });

        assertThat(repo.getTotalModificationsFor(materialInstance)).isEqualTo(count);

        final List<Modification> secondSetOfModifications = getModifications(count + 1);
        transactionTemplate.execute(status -> {
            repo.saveModifications(materialInstance, secondSetOfModifications);
            return null;
        });

        assertThat(repo.getTotalModificationsFor(materialInstance)).isEqualTo((long)count + 1);
    }

    @Test
    public void shouldFetchModificationsWithMaterial() {
        SvnMaterial material = MaterialsMother.svnMaterial("http://username:password@localhost");
        MaterialRevisions materialRevisions = saveModifications(material, 1);
        Modifications modificationList = materialRevisions.getModifications(material);
        Modification expectedModification = modificationList.get(0);

        List<Modification> modifications = repo.getLatestModificationForEachMaterial();

        assertThat(modifications.size()).isEqualTo(1);
        Modification modification = modifications.get(0);

        assertModificationAreEqual(modification, expectedModification);

        MaterialInstance instance = modification.getMaterialInstance();

        assertThat(instance).isInstanceOf(SvnMaterialInstance.class);
        assertThat(instance.getFingerprint()).isEqualTo(material.getFingerprint());
        assertThat(instance.getUrl()).isEqualTo(material.getUrl());
        assertThat(instance.getUsername()).isEqualTo(material.getUserName());
        assertThat(instance.getBranch()).isNullOrEmpty();
        assertThat(instance.getCheckExternals()).isEqualTo(material.isCheckExternals());
    }

    @Test
    public void shouldFetchDetailsRelatedToHg() {
        HgMaterial material = MaterialsMother.hgMaterial("http://username:password@localhost");
        material.setBranch("master");
        MaterialRevisions materialRevisions = saveModifications(material, 1);
        Modifications modificationList = materialRevisions.getModifications(material);

        List<Modification> modifications = repo.getLatestModificationForEachMaterial();
        assertModificationAreEqual(modificationList.get(0), modifications.get(0));

        MaterialInstance instance = modifications.get(0).getMaterialInstance();

        assertThat(instance).isInstanceOf(HgMaterialInstance.class);
        assertThat(instance.getFingerprint()).isEqualTo(material.getFingerprint());
        assertThat(instance.getUrl()).isEqualTo(material.getUrl());
        assertThat(instance.getUsername()).isEqualTo(material.getUserName());
        assertThat(instance.getBranch()).isEqualTo(material.getBranch());
    }

    @Test
    public void shouldFetchDetailsRelatedToP4() {
        P4Material material = new P4Material("localhost:1666", "view");
        MaterialRevisions materialRevisions = saveModifications(material, 1);
        Modifications modificationList = materialRevisions.getModifications(material);

        List<Modification> modifications = repo.getLatestModificationForEachMaterial();

        assertThat(modifications.size()).isEqualTo(1);
        assertModificationAreEqual(modifications.get(0), modificationList.get(0));

        MaterialInstance instance = modifications.get(0).getMaterialInstance();

        assertThat(instance).isInstanceOf(P4MaterialInstance.class);
        assertThat(instance.getFingerprint()).isEqualTo(material.getFingerprint());
        assertThat(instance.getUrl()).isEqualTo(material.getUrl());
        assertThat(instance.getUsername()).isEqualTo(material.getUserName());
        assertThat(instance.getView()).isEqualTo(material.getView());
        assertThat(instance.getUseTickets()).isEqualTo(material.getUseTickets());
    }

    @Test
    public void shouldFetchDetailsRelatedToTfs() {
        TfsMaterial material = MaterialsMother.tfsMaterial("https://tfs.com");
        MaterialRevisions materialRevisions = saveModifications(material, 1);
        Modifications modificationList = materialRevisions.getModifications(material);

        List<Modification> modifications = repo.getLatestModificationForEachMaterial();

        assertThat(modifications.size()).isEqualTo(1);
        assertModificationAreEqual(modifications.get(0), modificationList.get(0));

        MaterialInstance instance = modifications.get(0).getMaterialInstance();

        assertThat(instance).isInstanceOf(TfsMaterialInstance.class);
        assertThat(instance.getFingerprint()).isEqualTo(material.getFingerprint());
        assertThat(instance.getUrl()).isEqualTo(material.getUrl());
        assertThat(instance.getUsername()).isEqualTo(material.getUserName());
        assertThat(instance.getProjectPath()).isEqualTo(material.getProjectPath());
        assertThat(instance.getDomain()).isEqualTo(material.getDomain());
    }

    @Test
    public void shouldFetchDetailsRelatedToPackage() {
        PackageMaterial material = MaterialsMother.packageMaterial();
        MaterialRevisions materialRevisions = saveModifications(material, 1);
        Modifications modificationList = materialRevisions.getModifications(material);

        List<Modification> modifications = repo.getLatestModificationForEachMaterial();

        assertThat(modifications.size()).isEqualTo(1);
        assertModificationAreEqual(modifications.get(0), modificationList.get(0));

        MaterialInstance instance = modifications.get(0).getMaterialInstance();

        assertThat(instance).isInstanceOf(PackageMaterialInstance.class);
        assertThat(instance.getFingerprint()).isEqualTo(material.getFingerprint());
        assertThat(instance.getAdditionalData()).isNullOrEmpty();
        PackageMaterial packageMaterial = JsonHelper.fromJson(instance.getConfiguration(), PackageMaterial.class);
        assertThat(packageMaterial).isEqualTo(material);
    }

    @Test
    public void shouldFetchDetailsRelatedToPluginMaterial() {
        PluggableSCMMaterial material = MaterialsMother.pluggableSCMMaterial();
        MaterialRevisions materialRevisions = saveModifications(material, 1);
        Modifications modificationList = materialRevisions.getModifications(material);

        List<Modification> modifications = repo.getLatestModificationForEachMaterial();

        assertThat(modifications.size()).isEqualTo(1);
        assertModificationAreEqual(modifications.get(0), modificationList.get(0));

        MaterialInstance instance = modifications.get(0).getMaterialInstance();

        assertThat(instance).isInstanceOf(PluggableSCMMaterialInstance.class);
        assertThat(instance.getFingerprint()).isEqualTo(material.getFingerprint());
        assertThat(instance.getAdditionalData()).isNullOrEmpty();
        PluggableSCMMaterial pluggableSCMMaterial = JsonHelper.fromJson(instance.getConfiguration(), PluggableSCMMaterial.class);
        assertThat(pluggableSCMMaterial).isEqualTo(material);
    }

    @Test
    public void listHistory_shouldFetchLatestHistoryForAGivenMaterial() {
        SvnMaterial material = MaterialsMother.svnMaterial("http://username:password@localhost");
        MaterialRevisions materialRevisions = saveModifications(material, 5);

        Modifications mods = materialRevisions.getModifications(material);
        //modifications gets updated with the material instance which contains the id
        long materialId = mods.get(0).getMaterialInstance().getId();

        List<Modification> modifications = repo.loadHistory(materialId, FeedModifier.Latest, 0, 3);

        assertThat(modifications.size()).isEqualTo(3);
        // this works because internally we reverse the list before saving in the DB(MaterialRepository#758)
        assertModificationAreEqualWithId(mods.get(0), modifications.get(0));
        assertModificationAreEqualWithId(mods.get(1), modifications.get(1));
        assertModificationAreEqualWithId(mods.get(2), modifications.get(2));
    }

    @Test
    public void listHistory_shouldFetchHistoryAfterTheSuppliedCursor() {
        SvnMaterial material = MaterialsMother.svnMaterial("http://username:password@localhost");
        MaterialRevisions materialRevisions = saveModifications(material, 5);

        Modifications mods = materialRevisions.getModifications(material);
        //modifications gets updated with the material instance which contains the id
        long materialId = mods.get(0).getMaterialInstance().getId();

        // Give me the older instances
        List<Modification> modifications = repo.loadHistory(materialId, FeedModifier.After, mods.get(2).getId(), 3);

        assertThat(modifications.size()).isEqualTo(2);
        assertModificationAreEqualWithId(mods.get(3), modifications.get(0));
        assertModificationAreEqualWithId(mods.get(4), modifications.get(1));
    }

    @Test
    public void listHistory_shouldFetchHistoryBeforeTheSuppliedCursor() {
        SvnMaterial material = MaterialsMother.svnMaterial("http://username:password@localhost");
        MaterialRevisions materialRevisions = saveModifications(material, 5);

        Modifications mods = materialRevisions.getModifications(material);
        //modifications gets updated with the material instance which contains the id
        long materialId = mods.get(0).getMaterialInstance().getId();

        // Give me the newer instances
        List<Modification> modifications = repo.loadHistory(materialId, FeedModifier.Before, mods.get(2).getId(), 3);

        assertThat(modifications.size()).isEqualTo(2);
        assertModificationAreEqualWithId(mods.get(0), modifications.get(0));
        assertModificationAreEqualWithId(mods.get(1), modifications.get(1));
    }

    @Test
    public void shouldReturnLatestAndOldestModificationID() {
        SvnMaterial material = MaterialsMother.svnMaterial("http://username:password@localhost");
        MaterialRevisions materialRevisions = saveModifications(material, 5);

        Modifications mods = materialRevisions.getModifications(material);
        //modifications gets updated with the material instance which contains the id
        long materialId = mods.get(0).getMaterialInstance().getId();

        PipelineRunIdInfo info = repo.getOldestAndLatestModificationId(materialId, "");

        assertThat(info).isNotNull();
        assertThat(info.getLatestRunId()).isEqualTo(mods.get(0).getId());
        assertThat(info.getOldestRunId()).isEqualTo(mods.get(4).getId());
    }

    @Test
    public void shouldReturnNullAsLatestAndOlderModIDIfPatternDoesNotMatch() {
        SvnMaterial material = MaterialsMother.svnMaterial("http://username:password@localhost");
        MaterialRevisions materialRevisions = saveModifications(material, 5);

        Modifications mods = materialRevisions.getModifications(material);
        //modifications gets updated with the material instance which contains the id
        long materialId = mods.get(0).getMaterialInstance().getId();

        PipelineRunIdInfo info = repo.getOldestAndLatestModificationId(materialId, "revision");

        assertThat(info).isNull();
    }

    @Test
    public void shouldReturnLatestAndOldestModificationIDBasedOnPattern() {
        GitMaterial material = MaterialsMother.gitMaterial("http://example.com/gocd");
        MaterialRevisions materialRevisions = new MaterialRevisions();
        List<Modification> mods = new ArrayList<>();
        mods.add(new Modification("user 1", "hello world", EMAIL_ADDRESS, Dates.from(ZonedDateTime.now().minusHours(1)), "Revisions-matches"));
        mods.add(new Modification("user 2", "this will match as well - yellow", EMAIL_ADDRESS, Dates.from(ZonedDateTime.now().minusHours(2)), "Revision-also-matches"));
        mods.add(new Modification("user 3", "this should match as well", EMAIL_ADDRESS, Dates.from(ZonedDateTime.now().minusHours(3)), "Revision-hello"));
        mods.add(new Modification("user 4", "some comment", EMAIL_ADDRESS, Dates.from(ZonedDateTime.now().minusHours(4)), "revisions-which-will-not-match"));

        materialRevisions.addRevision(material, mods);

        dbHelper.saveRevs(materialRevisions);
        //modifications gets updated with the material instance which contains the id
        long materialId = mods.get(0).getMaterialInstance().getId();

        PipelineRunIdInfo info = repo.getOldestAndLatestModificationId(materialId, "ello");

        assertThat(info).isNotNull();
        assertThat(info.getLatestRunId()).isEqualTo(mods.get(0).getId());
        assertThat(info.getOldestRunId()).isEqualTo(mods.get(2).getId());
    }

    @Test
    public void shouldReturnLatestMatchingMods() {
        GitMaterial material = MaterialsMother.gitMaterial("http://example.com/gocd");
        MaterialRevisions materialRevisions = new MaterialRevisions();
        List<Modification> mods = new ArrayList<>();
        mods.add(new Modification("user 1", "hello world", EMAIL_ADDRESS, Dates.from(ZonedDateTime.now().minusHours(1)), "Revisions-matches"));
        mods.add(new Modification("user 2", "this will match as well - yellow", EMAIL_ADDRESS, Dates.from(ZonedDateTime.now().minusHours(2)), "Revision-also-matches"));
        mods.add(new Modification("user 3", "this should match as well", EMAIL_ADDRESS, Dates.from(ZonedDateTime.now().minusHours(3)), "Revision-hello"));
        mods.add(new Modification("user 4", "some comment", EMAIL_ADDRESS, Dates.from(ZonedDateTime.now().minusHours(4)), "revisions-which-will-not-match"));

        materialRevisions.addRevision(material, mods);

        dbHelper.saveRevs(materialRevisions);
        //modifications gets updated with the material instance which contains the id
        long materialId = mods.get(0).getMaterialInstance().getId();

        List<Modification> matchingMods = repo.findMatchingModifications(materialId, "ello", FeedModifier.Latest, 0, 10);

        assertThat(matchingMods.size()).isEqualTo(3);
        assertModificationAreEqual(matchingMods.get(0), mods.get(0));
        assertModificationAreEqual(matchingMods.get(1), mods.get(1));
        assertModificationAreEqual(matchingMods.get(2), mods.get(2));
    }

    @Test
    public void shouldReturnMatchingModsIrrespectiveOfCase() {
        GitMaterial material = MaterialsMother.gitMaterial("http://example.com/gocd");
        MaterialRevisions materialRevisions = new MaterialRevisions();
        List<Modification> mods = new ArrayList<>();
        mods.add(new Modification("user 1", "this will match", EMAIL_ADDRESS, Dates.from(ZonedDateTime.now().minusHours(1)), "Revisions-matches"));
        mods.add(new Modification("user 2", "this wont", EMAIL_ADDRESS, Dates.from(ZonedDateTime.now().minusHours(2)), "Revision-not-matches"));
        mods.add(new Modification("user 3", "this also wont", EMAIL_ADDRESS, Dates.from(ZonedDateTime.now().minusHours(3)), "Revision-hello"));
        mods.add(new Modification("user 4", "this should match as well", EMAIL_ADDRESS, Dates.from(ZonedDateTime.now().minusHours(4)), "revisions-which-will-match"));

        materialRevisions.addRevision(material, mods);

        dbHelper.saveRevs(materialRevisions);
        //modifications gets updated with the material instance which contains the id
        long materialId = mods.get(0).getMaterialInstance().getId();

        List<Modification> matchingMods = repo.findMatchingModifications(materialId, "revisions", FeedModifier.Latest, 0, 10);

        assertThat(matchingMods.size()).isEqualTo(2);
        assertModificationAreEqual(matchingMods.get(0), mods.get(0));
        assertModificationAreEqual(matchingMods.get(1), mods.get(3));
    }

    @Test
    public void shouldReturnMatchingModsAfterSaidCursor() {
        GitMaterial material = MaterialsMother.gitMaterial("http://example.com/example_gocd");
        MaterialRevisions materialRevisions = saveModifications(material, 5);

        Modifications mods = materialRevisions.getModifications(material);
        //modifications gets updated with the material instance which contains the id
        long materialId = mods.get(0).getMaterialInstance().getId();

        List<Modification> matchingMods = repo.findMatchingModifications(materialId, "comment", FeedModifier.After, mods.get(2).getId(), 10);

        assertThat(matchingMods.size()).isEqualTo(2);
        assertModificationAreEqual(matchingMods.get(0), mods.get(3));
        assertModificationAreEqual(matchingMods.get(1), mods.get(4));
    }

    @Test
    public void shouldReturnMatchingModsBeforeSaidCursor() {
        GitMaterial material = MaterialsMother.gitMaterial("http://example.com/gocd_test");
        MaterialRevisions materialRevisions = saveModifications(material, 5);

        Modifications mods = materialRevisions.getModifications(material);
        //modifications gets updated with the material instance which contains the id
        long materialId = mods.get(0).getMaterialInstance().getId();

        List<Modification> matchingMods = repo.findMatchingModifications(materialId, "comment", FeedModifier.Before, mods.get(2).getId(), 10);

        assertThat(matchingMods.size()).isEqualTo(2);
        assertModificationAreEqual(matchingMods.get(0), mods.get(0));
        assertModificationAreEqual(matchingMods.get(1), mods.get(1));
    }

    private MaterialRevisions saveModifications(Material material, int count) {
        MaterialRevisions materialRevisions = new MaterialRevisions();
        List<Modification> mods = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Modification mod = new Modification(MOD_USER, "Dummy comment: " + i, EMAIL_ADDRESS, Dates.from(ZonedDateTime.now().minusHours(i)), "Rev: " + i);
            mods.add(mod);
        }

        materialRevisions.addRevision(material, mods);

        dbHelper.saveRevs(materialRevisions);
        return materialRevisions;
    }

    private void assertModificationAreEqual(Modification actual, Modification expected) {
        assertThat(actual.getRevision()).isEqualTo(expected.getRevision());
        assertThat(actual.getModifiedTime().getTime()).isEqualTo(expected.getModifiedTime().getTime());
        assertThat(actual.getComment()).isEqualTo(expected.getComment());
        assertThat(actual.getUserName()).isEqualTo(expected.getUserName());
        assertThat(actual.getEmailAddress()).isEqualTo(expected.getEmailAddress());
    }

    private void assertModificationAreEqualWithId(Modification actual, Modification expected) {
        assertThat(actual.getId()).isEqualTo(expected.getId());
        assertModificationAreEqual(actual, expected);
    }

    private List<Modification> getModifications(long count) {
        final List<Modification> modifications = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            modifications.add(new Modification("user", "comment", "email", new Date(), "r" + i));
        }
        return modifications;
    }

    private MaterialRevision saveOneDependencyModification(DependencyMaterial dependencyMaterial, String revision) {
        return saveOneDependencyModification(dependencyMaterial, revision, "MOCK_LABEL-12");
    }

    private MaterialRevision saveOneDependencyModification(final DependencyMaterial dependencyMaterial, final String revision, final String label) {
        return transactionTemplate.execute(status -> {
            Modification modification = new Modification(new Date(), revision, label, null);
            MaterialRevision originalRevision = new MaterialRevision(dependencyMaterial, modification);
            repo.save(new MaterialRevisions(originalRevision));
            return originalRevision;
        });
    }

    private void saveMaterialRev(final MaterialRevision rev) {
        transactionTemplate.execute(status -> repo.saveMaterialRevision(rev));
    }

    private Pipeline createPipeline() {
        Pipeline pipeline = PipelineMother.pipeline("pipeline", StageMother.custom("stage"));
        pipeline.getBuildCause().setMaterialRevisions(new MaterialRevisions());
        return savePipeline(pipeline);
    }

    private void savePMR(final MaterialRevision revision, final Pipeline pipeline) {
        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                repo.savePipelineMaterialRevision(pipeline, pipeline.getId(), revision);
            }
        });
    }

    private void assertMatchedRevision(MatchedRevision matchedRevision, String shortRevision, String longRevision, String user, Date checkinTime, String comment) {
        assertThat(matchedRevision.getShortRevision()).isEqualTo(shortRevision);
        assertThat(matchedRevision.getLongRevision()).isEqualTo(longRevision);
        assertThat(matchedRevision.getUser()).isEqualTo(user);
        assertEquals(checkinTime, matchedRevision.getCheckinTime(), "checkinTime");
        assertThat(matchedRevision.getComment()).isEqualTo(comment);
    }

    private Modification modification(String revision) {
        return new Modification("user1", "comment", "foo@bar", new Date(), revision);
    }

    private void assertCanLoadAndSaveMaterialRevisionsFor(MaterialConfig materialConfig) {
        final PipelineConfig pipelineConfig = PipelineMother.createPipelineConfig("mingle", new MaterialConfigs(materialConfig), "dev");

        final MaterialRevisions materialRevisions = ModificationsMother.modifyOneFile(pipelineConfig);
        MaterialRevision materialRevision = materialRevisions.getMaterialRevision(0);

        final Pipeline[] pipeline = new Pipeline[1];

        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                //assume that we have saved the materials
                repo.save(materialRevisions);

                PipelineConfig config = PipelineMother.withTwoStagesOneBuildEach("pipeline-name", "stage-1", "stage-2");
                config.setMaterialConfigs(materialRevisions.getMaterials().convertToConfigs());
                pipeline[0] = instanceFactory.createPipelineInstance(pipelineConfig, BuildCause.createManualForced(materialRevisions, Username.ANONYMOUS), new DefaultSchedulingContext(DEFAULT_APPROVED_BY), md5,
                        new TimeProvider());

                //this should persist the materials
                pipelineSqlMapDao.save(pipeline[0]);
            }
        });

        assertMaterialRevisions(materialRevision, pipeline[0]);
    }

    private void assertMaterialRevisions(MaterialRevision materialRevision, Pipeline pipeline) {
        assertThat(pipeline.getId()).isGreaterThan(0L);

        MaterialRevisions actual = repo.findMaterialRevisionsForPipeline(pipeline.getId());

        assertEquals(materialRevision.getMaterial(), actual.getMaterialRevision(0).getMaterial());
        assertEquals(materialRevision.getLatestModification(), actual.getMaterialRevision(0).getLatestModification());
    }

    private MaterialRevision saveOneScmModification(ScmMaterial original, String user, String filename) {
        return saveOneScmModification(ModificationsMother.nextRevision(), original, user, filename, "comment");
    }

    private MaterialRevision saveOneScmModification(final String revision, final Material original, final String user, final String filename, final String comment) {
        return transactionTemplate.execute(status -> {
            Modification modification = new Modification(user, comment, "email", new Date(), revision);
            modification.createModifiedFile(filename, "folder1", ModifiedAction.added);

            MaterialRevision originalRevision = new MaterialRevision(original, modification);

            repo.save(new MaterialRevisions(originalRevision));
            return originalRevision;
        });
    }

    private PipelineMaterialRevision pipelineMaterialRevision() {
        PipelineMaterialRevision pmr = mock(PipelineMaterialRevision.class);
        when(pmr.getMaterial()).thenReturn(material());
        when(pmr.getToModification()).thenReturn(new Modification(new Date(), "123", "MOCK_LABEL-12", null));
        when(pmr.getFromModification()).thenReturn(new Modification(new Date(), "125", "MOCK_LABEL-12", null));
        return pmr;
    }

    private HgMaterial material() {
        return new HgMaterial("url", null);
    }
}
