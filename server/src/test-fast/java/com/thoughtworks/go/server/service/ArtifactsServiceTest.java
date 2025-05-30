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

import ch.qos.logback.classic.Level;
import com.thoughtworks.go.domain.JobIdentifier;
import com.thoughtworks.go.domain.LocatableEntity;
import com.thoughtworks.go.domain.Stage;
import com.thoughtworks.go.domain.exception.IllegalArtifactLocationException;
import com.thoughtworks.go.helper.JobIdentifierMother;
import com.thoughtworks.go.helper.StageMother;
import com.thoughtworks.go.server.dao.StageDao;
import com.thoughtworks.go.server.view.artifacts.ArtifactDirectoryChooser;
import com.thoughtworks.go.util.LogFixture;
import com.thoughtworks.go.util.ReflectionUtil;
import com.thoughtworks.go.util.TempDirUtils;
import com.thoughtworks.go.util.ZipUtil;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipInputStream;

import static com.thoughtworks.go.server.service.ArtifactsService.LOG_XML_NAME;
import static com.thoughtworks.go.util.GoConstants.PUBLISH_MAX_RETRIES;
import static com.thoughtworks.go.util.LogFixture.logFixtureFor;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class ArtifactsServiceTest {
    @TempDir
    Path tempDir;

    private ArtifactsDirHolder artifactsDirHolder;
    private ZipUtil zipUtil;
    private List<File> resourcesToBeCleanedOnTeardown = new ArrayList<>();
    private File fakeRoot;
    private JobResolverService resolverService;
    private StageDao stageService;

    @BeforeEach
    void setUp() throws IOException {
        artifactsDirHolder = mock(ArtifactsDirHolder.class);
        zipUtil = mock(ZipUtil.class);
        resolverService = mock(JobResolverService.class);
        stageService = mock(StageDao.class);

        fakeRoot = TempDirUtils.createTempDirectoryIn(tempDir, "ArtifactsServiceTest").toFile();
    }

    @AfterEach
    void tearDown() throws IOException {
        for (File resource : resourcesToBeCleanedOnTeardown) {
            FileUtils.deleteQuietly(resource);
        }
    }

    @Test
    void shouldUnzipWhenFileIsZip() throws Exception {
        final File logsDir = new File("logs");
        final ByteArrayInputStream stream = new ByteArrayInputStream("".getBytes());
        String buildInstanceId = "1";
        final File destFile = new File(logsDir, buildInstanceId + File.separator + LOG_XML_NAME);

        assumeArtifactsRoot(logsDir);
        ArtifactsService artifactsService = new ArtifactsService(resolverService, stageService, artifactsDirHolder, zipUtil);
        artifactsService.saveFile(destFile.getParentFile(), stream, true, 1);

        verify(zipUtil).unzip(any(ZipInputStream.class), eq(destFile.getParentFile()));
    }

    @Test
    void shouldNotSaveArtifactWhenItsAZipContainingDirectoryTraversalPath() throws IOException {
        final File logsDir = new File("logs");

        try (InputStream stream = Objects.requireNonNull(getClass().getResourceAsStream("/archive_traversal_attack.zip"))) {
            String buildInstanceId = "1";
            final File destFile = new File(logsDir, buildInstanceId + File.separator + LOG_XML_NAME);
            assumeArtifactsRoot(logsDir);
            ArtifactsService artifactsService = new ArtifactsService(resolverService, stageService, artifactsDirHolder, new ZipUtil());
            boolean saved = artifactsService.saveFile(destFile, stream, true, 1);
            assertThat(saved).isFalse();
        }
    }

    @Test
    void shouldWarnIfFailedToSaveFileWhenAttemptIsBelowMaxAttempts() throws IOException {
        final File logsDir = new File("logs");
        final ByteArrayInputStream stream = new ByteArrayInputStream("".getBytes());
        String buildInstanceId = "1";
        final File destFile = new File(logsDir,
                String.join(File.separator, buildInstanceId, "generated", LOG_XML_NAME));
        final IOException ioException = new IOException();

        assumeArtifactsRoot(logsDir);
        doThrow(ioException).when(zipUtil).unzip(any(ZipInputStream.class), any(File.class));

        try (LogFixture logFixture = logFixtureFor(ArtifactsService.class, Level.DEBUG)) {
            ArtifactsService artifactsService = new ArtifactsService(resolverService, stageService, artifactsDirHolder, zipUtil);
            artifactsService.saveFile(destFile, stream, true, 1);
            String result;
            synchronized (logFixture) {
                result = logFixture.getLog();
            }
            assertThat(result).contains("Failed to save the file to:");
        }
    }

    @Test
    void shouldLogErrorIfFailedToSaveFileWhenAttemptHitsMaxAttempts() throws IOException {
        final File logsDir = new File("logs");
        final ByteArrayInputStream stream = new ByteArrayInputStream("".getBytes());
        String buildInstanceId = "1";
        final File destFile = new File(logsDir,
                String.join(File.separator, buildInstanceId, "generated", LOG_XML_NAME));
        final IOException ioException = new IOException();

        doThrow(ioException).when(zipUtil).unzip(any(ZipInputStream.class), any(File.class));

        try (LogFixture logFixture = logFixtureFor(ArtifactsService.class, Level.DEBUG)) {
            ArtifactsService artifactsService = new ArtifactsService(resolverService, stageService, artifactsDirHolder, zipUtil);
            artifactsService.saveFile(destFile, stream, true, PUBLISH_MAX_RETRIES);
            String result;
            synchronized (logFixture) {
                result = logFixture.getLog();
            }
            assertThat(result).contains("Failed to save the file to:");
        }
    }

    @Test
    void shouldConvertArtifactPathToFileSystemLocation() throws Exception {
        File artifactsRoot = TempDirUtils.createRandomDirectoryIn(tempDir).toFile();
        assumeArtifactsRoot(artifactsRoot);
        ArtifactsService artifactsService = new ArtifactsService(resolverService, stageService, artifactsDirHolder, zipUtil);
        File location = artifactsService.getArtifactLocation("foo/bar/baz");
        assertThat(location).isEqualTo(new File(artifactsRoot + "/foo/bar/baz"));
    }

    @Test
    void shouldConvertArtifactPathToUrl() throws Exception {
        File artifactsRoot = TempDirUtils.createRandomDirectoryIn(tempDir).toFile();
        assumeArtifactsRoot(artifactsRoot);

        ArtifactsService artifactsService = new ArtifactsService(resolverService, stageService, artifactsDirHolder, zipUtil);
        JobIdentifier identifier = JobIdentifierMother.jobIdentifier("p", 1, "s", "2", "j");
        when(resolverService.actualJobIdentifier(identifier)).thenReturn(identifier);

        String url = artifactsService.findArtifactUrl(identifier);
        assertThat(url).isEqualTo("/files/p/1/s/2/j");
    }

    @Test
    void shouldConvertArtifactPathWithLocationToUrl() throws Exception {
        File artifactsRoot = TempDirUtils.createRandomDirectoryIn(tempDir).toFile();
        assumeArtifactsRoot(artifactsRoot);

        ArtifactsService artifactsService = new ArtifactsService(resolverService, stageService, artifactsDirHolder, zipUtil);
        JobIdentifier identifier = JobIdentifierMother.jobIdentifier("p", 1, "s", "2", "j");
        when(resolverService.actualJobIdentifier(identifier)).thenReturn(identifier);

        String url = artifactsService.findArtifactUrl(identifier, "console.log");
        assertThat(url).isEqualTo("/files/p/1/s/2/j/console.log");
    }

    @Test
    void shouldUsePipelineCounterAsFolderName() throws IllegalArtifactLocationException, IOException {
        File artifactsRoot = TempDirUtils.createRandomDirectoryIn(tempDir).toFile();
        assumeArtifactsRoot(artifactsRoot);

        ArtifactsService artifactsService = new ArtifactsService(resolverService, stageService, artifactsDirHolder, zipUtil);
        artifactsService.initialize();
        File artifact = artifactsService.findArtifact(
                new JobIdentifier("cruise", 1, "1.1", "dev", "2", "linux-firefox", null), "pkg.zip");
        assertThat(artifact).isEqualTo(new File(artifactsRoot + "/pipelines/cruise/1/dev/2/linux-firefox/pkg.zip"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void shouldProvideArtifactRootForAJobOnLinux() throws Exception {
        assumeArtifactsRoot(fakeRoot);
        ArtifactsService artifactsService = new ArtifactsService(resolverService, stageService, artifactsDirHolder, zipUtil);
        artifactsService.initialize();
        JobIdentifier oldId = new JobIdentifier("cruise", 1, "1.1", "dev", "2", "linux-firefox", null);
        when(resolverService.actualJobIdentifier(oldId)).thenReturn(new JobIdentifier("cruise", 2, "2.2", "functional", "3", "mac-safari"));
        String artifactRoot = artifactsService.findArtifactRoot(oldId);
        assertThat(artifactRoot).isEqualTo("pipelines/cruise/2/functional/3/mac-safari");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void shouldProvideArtifactRootForAJobOnWindows() throws Exception {
        assumeArtifactsRoot(fakeRoot);
        ArtifactsService artifactsService = new ArtifactsService(resolverService, stageService, artifactsDirHolder, zipUtil);
        artifactsService.initialize();
        JobIdentifier oldId = new JobIdentifier("cruise", 1, "1.1", "dev", "2", "linux-firefox", null);
        when(resolverService.actualJobIdentifier(oldId)).thenReturn(new JobIdentifier("cruise", 1, "1.1", "dev", "2", "linux-firefox", null));
        String artifactRoot = artifactsService.findArtifactRoot(oldId);
        assertThat(artifactRoot).isEqualTo("pipelines\\cruise\\1\\dev\\2\\linux-firefox");
    }

    @Test
    void shouldProvideArtifactUrlForAJob() {
        assumeArtifactsRoot(fakeRoot);
        ArtifactsService artifactsService = new ArtifactsService(resolverService, stageService, artifactsDirHolder, zipUtil);
        JobIdentifier oldId = new JobIdentifier("cruise", 1, "1.1", "dev", "2", "linux-firefox");
        when(resolverService.actualJobIdentifier(oldId)).thenReturn(new JobIdentifier("cruise", 2, "2.2", "functional", "3", "windows-ie"));
        String artifactUrl = artifactsService.findArtifactUrl(oldId);
        assertThat(artifactUrl).isEqualTo("/files/cruise/2/functional/3/windows-ie");
    }

    @Test
    void shouldUsePipelineLabelAsFolderNameIfNoCounter() throws IllegalArtifactLocationException, IOException {
        File artifactsRoot = TempDirUtils.createRandomDirectoryIn(tempDir).toFile();
        assumeArtifactsRoot(artifactsRoot);
        willCleanUp(artifactsRoot);
        ArtifactsService artifactsService = new ArtifactsService(resolverService, stageService, artifactsDirHolder, zipUtil);
        artifactsService.initialize();
        File artifact = artifactsService.findArtifact(new JobIdentifier("cruise", -2, "1.1", "dev", "2", "linux-firefox", null), "pkg.zip");
        assertThat(artifact).isEqualTo(new File(artifactsRoot, "pipelines/cruise/1.1/dev/2/linux-firefox/pkg.zip"));
    }

    @Test
    void shouldPurgeArtifactsExceptCruiseOutputForGivenStageAndMarkItCleaned() throws IOException {
        File artifactsRoot = TempDirUtils.createRandomDirectoryIn(tempDir).toFile();
        assumeArtifactsRoot(artifactsRoot);
        willCleanUp(artifactsRoot);
        File jobDir = new File(artifactsRoot, "pipelines/pipeline/10/stage/20/job");
        jobDir.mkdirs();
        File aFile = new File(jobDir, "foo");
        Files.writeString(aFile.toPath(), "hello world", UTF_8);
        File aDirectory = new File(jobDir, "bar");
        aDirectory.mkdir();
        File anotherFile = new File(aDirectory, "baz");
        Files.writeString(anotherFile.toPath(), "quux", UTF_8);

        File cruiseOutputDir = new File(jobDir, "cruise-output");
        cruiseOutputDir.mkdir();
        File consoleLog = new File(cruiseOutputDir, "console.log");
        Files.writeString(consoleLog.toPath(), "Build Logs", UTF_8);
        File checksumFile = new File(cruiseOutputDir, "md5.checksum");
        Files.writeString(checksumFile.toPath(), "foo:25463254625346", UTF_8);


        ArtifactsService artifactsService = new ArtifactsService(resolverService, stageService, artifactsDirHolder, zipUtil);
        artifactsService.initialize();
        Stage stage = StageMother.createPassedStage("pipeline", 10, "stage", 20, "job", Instant.now());
        artifactsService.purgeArtifactsForStage(stage);

        assertThat(jobDir).exists();
        assertThat(aFile).doesNotExist();
        assertThat(anotherFile).doesNotExist();
        assertThat(aDirectory).doesNotExist();

        assertThat(new File(artifactsRoot, "pipelines/pipeline/10/stage/20/job/cruise-output/console.log")).exists();
        assertThat(new File(artifactsRoot, "pipelines/pipeline/10/stage/20/job/cruise-output/md5.checksum")).exists();

        verify(stageService).markArtifactsDeletedFor(stage);
    }

    @Test
    void shouldPurgeArtifactsExceptPluggableArtifactMetadataFolderForGivenStageAndMarkItCleaned() throws IOException {
        File artifactsRoot = TempDirUtils.createRandomDirectoryIn(tempDir).toFile();
        assumeArtifactsRoot(artifactsRoot);
        willCleanUp(artifactsRoot);
        File jobDir = new File(artifactsRoot, "pipelines/pipeline/10/stage/20/job");
        jobDir.mkdirs();
        File aFile = new File(jobDir, "foo");
        Files.writeString(aFile.toPath(), "hello world", UTF_8);
        File aDirectory = new File(jobDir, "bar");
        aDirectory.mkdir();
        File anotherFile = new File(aDirectory, "baz");
        Files.writeString(anotherFile.toPath(), "quux", UTF_8);

        File pluggableArtifactMetadataDir = new File(jobDir, "pluggable-artifact-metadata");
        pluggableArtifactMetadataDir.mkdir();
        File metadataJson = new File(pluggableArtifactMetadataDir, "cd.go.artifact.docker.json");
        Files.writeString(metadataJson.toPath(), "{\"image\": \"alpine:foo\", \"digest\": \"sha\"}", UTF_8);

        ArtifactsService artifactsService = new ArtifactsService(resolverService, stageService, artifactsDirHolder, zipUtil);
        artifactsService.initialize();
        Stage stage = StageMother.createPassedStage("pipeline", 10, "stage", 20, "job", Instant.now());
        artifactsService.purgeArtifactsForStage(stage);

        assertThat(jobDir).exists();
        assertThat(aFile).doesNotExist();
        assertThat(anotherFile).doesNotExist();
        assertThat(aDirectory).doesNotExist();

        assertThat(new File(artifactsRoot, "pipelines/pipeline/10/stage/20/job/pluggable-artifact-metadata/cd.go.artifact.docker.json")).exists();

        verify(stageService).markArtifactsDeletedFor(stage);
    }

    @Test
    void shouldPurgeCachedArtifactsForGivenStageWhilePurgingArtifactsForAStage() throws IOException {
        File artifactsRoot = TempDirUtils.createRandomDirectoryIn(tempDir).toFile();
        assumeArtifactsRoot(artifactsRoot);
        willCleanUp(artifactsRoot);

        ArtifactsService artifactsService = new ArtifactsService(resolverService, stageService, artifactsDirHolder, zipUtil);
        artifactsService.initialize();
        Stage stage = StageMother.createPassedStage("pipeline", 10, "stage", 20, "job1", Instant.now());
        File job1Dir = createJobArtifactFolder(artifactsRoot + "/pipelines/pipeline/10/stage/20/job1");
        File job2Dir = createJobArtifactFolder(artifactsRoot + "/pipelines/pipeline/10/stage/20/job2");
        File job1DirFromADifferentStageRun = createJobArtifactFolder(artifactsRoot + "/pipelines/pipeline/10/stage/25/job2");
        File job1CacheDir = createJobArtifactFolder(artifactsRoot + "/cache/artifacts/pipelines/pipeline/10/stage/20/job1");
        File job2CacheDir = createJobArtifactFolder(artifactsRoot + "/cache/artifacts/pipelines/pipeline/10/stage/20/job2");
        File job1CacheDirFromADifferentStageRun = createJobArtifactFolder(artifactsRoot + "/cache/artifacts/pipelines/pipeline/10/stage/25/job2");

        artifactsService.purgeArtifactsForStage(stage);

        assertThat(job1Dir).exists();
        assertThat(job1Dir.listFiles().length).isEqualTo(0);
        assertThat(job2Dir).exists();
        assertThat(job2Dir.listFiles().length).isEqualTo(0);
        assertThat(job1DirFromADifferentStageRun).exists();
        assertThat(job1DirFromADifferentStageRun.listFiles().length).isEqualTo(1);
        assertThat(job1CacheDir).doesNotExist();
        assertThat(job2CacheDir).doesNotExist();
        assertThat(job1CacheDirFromADifferentStageRun).exists();
    }

    private File createJobArtifactFolder(final String path) throws IOException {
        File jobDir = new File(path);
        jobDir.mkdirs();
        File aFile = new File(jobDir, "foo");
        Files.writeString(aFile.toPath(), "hello world", UTF_8);
        return jobDir;
    }

    @Test
    void shouldLogAndIgnoreExceptionsWhenDeletingStageArtifacts() throws IllegalArtifactLocationException {
        ArtifactsService artifactsService = new ArtifactsService(resolverService, stageService, artifactsDirHolder, zipUtil);
        Stage stage = StageMother.createPassedStage("pipeline", 10, "stage", 20, "job", Instant.now());

        ArtifactDirectoryChooser chooser = mock(ArtifactDirectoryChooser.class);
        ReflectionUtil.setField(artifactsService, "chooser", chooser);

        when(chooser.findArtifact(any(LocatableEntity.class), eq(""))).thenThrow(new IllegalArtifactLocationException("holy cow!"));


        try (LogFixture logFixture = logFixtureFor(ArtifactsService.class, Level.DEBUG)) {
            artifactsService.purgeArtifactsForStage(stage);
            assertThat(logFixture.contains(Level.ERROR, "Error occurred while clearing artifacts for 'pipeline/10/stage/20'. Error: 'holy cow!'")).isTrue();
        }
        verify(stageService).markArtifactsDeletedFor(stage);
    }

    private void assumeArtifactsRoot(final File artifactsRoot) {
        when(artifactsDirHolder.getArtifactsDir()).thenReturn(artifactsRoot);
    }

    void willCleanUp(File file) {
        resourcesToBeCleanedOnTeardown.add(file);
    }
}

