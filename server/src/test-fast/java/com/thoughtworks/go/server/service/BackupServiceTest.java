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
import com.thoughtworks.go.server.database.Database;
import com.thoughtworks.go.server.domain.BackupStatus;
import com.thoughtworks.go.server.domain.ServerBackup;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.messaging.ServerBackupQueue;
import com.thoughtworks.go.server.messaging.StartServerBackupMessage;
import com.thoughtworks.go.server.persistence.ServerBackupRepository;
import com.thoughtworks.go.service.ConfigRepository;
import com.thoughtworks.go.util.Dates;
import com.thoughtworks.go.util.SystemEnvironment;
import com.thoughtworks.go.util.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Optional;

import static com.thoughtworks.go.server.service.BackupService.ABORTED_BACKUPS_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BackupServiceTest {

    @Mock
    private SystemEnvironment systemEnvironment;

    @TempDir
    File configDir;

    @Mock
    private ConfigRepository configRepo;

    @Mock
    private Database databaseStrategy;

    @Mock
    private TimeProvider timeProvider;

    @Mock
    private ServerBackupQueue backupQueue;

    @Mock
    private ServerBackupRepository serverBackupRepository;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ArtifactsDirHolder artifactsDirHolder;

    @BeforeEach
    public void setUp() {
        lenient().when(systemEnvironment.getConfigDir()).thenReturn(configDir.getAbsolutePath());
        lenient().when(configRepo.doLocked(any())).thenCallRealMethod();
    }

    @Test
    public void shouldGetServerBackupLocation() {
        ArtifactsDirHolder artifactsDirHolder = mock(ArtifactsDirHolder.class);
        String location = "/var/go-server-backups";
        when(artifactsDirHolder.getBackupsDir()).thenReturn(new File(location));

        BackupService backupService = new BackupService(artifactsDirHolder, mock(GoConfigService.class), null, null, systemEnvironment, configRepo, databaseStrategy, null);
        assertThat(backupService.backupLocation()).isEqualTo(new File(location).getAbsolutePath());
    }

    @Test
    public void shouldReturnTheLatestBackupTime() {
        ServerBackupRepository repo = mock(ServerBackupRepository.class);
        Date serverBackupTime = new Date();
        when(repo.lastSuccessfulBackup()).thenReturn(Optional.of(new ServerBackup("file_path", serverBackupTime, "user", "")));
        BackupService backupService = new BackupService(null, mock(GoConfigService.class), null, repo, systemEnvironment, configRepo, databaseStrategy, null);

        Optional<Date> date = backupService.lastBackupTime();
        assertThat(date.get()).isEqualTo(serverBackupTime);
    }

    @Test
    public void shouldReturnEmptyWhenTheLatestBackupTimeIsNotAvailable() {
        ServerBackupRepository repo = mock(ServerBackupRepository.class);
        when(repo.lastSuccessfulBackup()).thenReturn(Optional.empty());
        BackupService backupService = new BackupService(null, mock(GoConfigService.class), null, repo, systemEnvironment, configRepo, databaseStrategy, null);

        assertThat(backupService.lastBackupTime().isPresent()).isFalse();
    }
    @Test
    public void shouldReturnTheUserThatTriggeredTheLastBackup() {
        ServerBackupRepository repo = mock(ServerBackupRepository.class);
        when(repo.lastSuccessfulBackup()).thenReturn(Optional.of(new ServerBackup("file_path", new Date(), "loser", "")));
        BackupService backupService = new BackupService(null, mock(GoConfigService.class), null, repo, systemEnvironment, configRepo, databaseStrategy, null);

        Optional<String> username = backupService.lastBackupUser();
        assertThat(username.get()).isEqualTo("loser");
    }

    @Test
    public void shouldReturnEmptyWhenTheLatestBackupUserIsNotAvailable() {
        ServerBackupRepository repo = mock(ServerBackupRepository.class);
        when(repo.lastSuccessfulBackup()).thenReturn(Optional.empty());
        BackupService backupService = new BackupService(null, mock(GoConfigService.class), null, repo, systemEnvironment, configRepo, databaseStrategy, null);

        assertThat(backupService.lastBackupUser().isPresent()).isFalse();
    }

    @Test
    public void shouldReturnAvailableDiskSpaceOnArtifactsDirectory() {
        ArtifactsDirHolder artifactsDirHolder = mock(ArtifactsDirHolder.class);
        File artifactDirectory = mock(File.class);
        when(artifactsDirHolder.getArtifactsDir()).thenReturn(artifactDirectory);
        when(artifactDirectory.getUsableSpace()).thenReturn(42424242L);
        BackupService backupService = new BackupService(artifactsDirHolder, mock(GoConfigService.class), null, null, systemEnvironment, configRepo, databaseStrategy, null);

        assertThat(backupService.availableDiskSpace()).isEqualTo("40 MB");

    }

    @Test
    public void shouldScheduleBackupAsynchronously() {
        Username username = mock(Username.class);
        CaseInsensitiveString user = new CaseInsensitiveString("admin");
        LocalDateTime backupTime = LocalDateTime.of(2019, 2, 19, 0, 0, 0, 0);
        ArgumentCaptor<StartServerBackupMessage> captor = ArgumentCaptor.forClass(StartServerBackupMessage.class);
        String expectedBackupPath = new File("backup_path/backup_20190219-000000").getAbsolutePath();
        ServerBackup expectedBackup = new ServerBackup(expectedBackupPath, Dates.from(backupTime), user.toString(), "Backup scheduled");
        ServerBackup backupWithId = new ServerBackup(expectedBackupPath, Dates.from(backupTime), user.toString(), BackupStatus.IN_PROGRESS, "Backup scheduled", 99L);

        when(username.getUsername()).thenReturn(user);
        when(artifactsDirHolder.getBackupsDir().getAbsolutePath()).thenReturn("backup_path");
        when(timeProvider.currentLocalDateTime()).thenReturn(backupTime);
        when(serverBackupRepository.save(expectedBackup)).thenReturn(backupWithId);

        BackupService backupService = new BackupService(artifactsDirHolder, null, timeProvider, serverBackupRepository, systemEnvironment, null, databaseStrategy, backupQueue);
        backupService.scheduleBackup(username);

        verify(serverBackupRepository).save(expectedBackup);
        verify(backupQueue).post(captor.capture());

        StartServerBackupMessage serverBackupMessage = captor.getValue();
        assertThat(serverBackupMessage.getId()).isEqualTo(99L);
    }

    @Test
    public void shouldMarkIncompleteBackupsAsAbortedOnInitialize() {
        BackupService backupService = new BackupService(artifactsDirHolder, mock(GoConfigService.class), null, serverBackupRepository, systemEnvironment, configRepo, databaseStrategy, null);

        backupService.initialize();

        verify(serverBackupRepository).markInProgressBackupsAsAborted(ABORTED_BACKUPS_MESSAGE);
    }
}
