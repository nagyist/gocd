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
import com.thoughtworks.go.config.exceptions.GoConfigInvalidException;
import com.thoughtworks.go.config.update.AdminsConfigUpdateCommand;
import com.thoughtworks.go.helper.GoConfigMother;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.service.result.BulkUpdateAdminsResult;
import com.thoughtworks.go.server.service.result.HttpLocalizedOperationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AdminsConfigServiceTest {

    @Mock
    private GoConfigService goConfigService;

    private BasicCruiseConfig cruiseConfig;

    private AdminsConfigService adminsConfigService;

    @Mock
    private EntityHashingService entityHashingService;

    @BeforeEach
    public void setUp() {
        cruiseConfig = GoConfigMother.defaultCruiseConfig();
        lenient().when(goConfigService.cruiseConfig()).thenReturn(cruiseConfig);
        adminsConfigService = new AdminsConfigService(goConfigService, entityHashingService);
    }

    @Test
    public void update_shouldAddAdminsToConfig() {
        AdminsConfig config = new AdminsConfig();
        Username admin = new Username("admin");
        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();

        adminsConfigService.update(admin, config, "md5", result);

        verify(goConfigService).updateConfig(any(AdminsConfigUpdateCommand.class), eq(admin));
    }

    @Test
    public void shouldBulkUpdateToAddAdminsToConfig() {
        AdminsConfig config = new AdminsConfig(new AdminUser("existingAdminUser"), new AdminRole("existingAdminRole"));
        cruiseConfig = GoConfigMother.defaultCruiseConfig();
        cruiseConfig.server().security().setAdminsConfig(config);
        when(goConfigService.serverConfig()).thenReturn(cruiseConfig.server());

        Username user = new Username("user");

        adminsConfigService.bulkUpdate(user, List.of("newUser1"), emptyList(), List.of("newRole1"), emptyList(), "md5");

        ArgumentCaptor<AdminsConfigUpdateCommand> captor = ArgumentCaptor.forClass(AdminsConfigUpdateCommand.class);
        verify(goConfigService).updateConfig(captor.capture(), eq(user));
        AdminsConfigUpdateCommand updateCommand = captor.getValue();

        AdminsConfig adminsConfig = updateCommand.getPreprocessedEntityConfig();
        assertThat(adminsConfig.getUsers()).hasSize(2);
        assertThat(adminsConfig.getUsers()).contains(new AdminUser("existingAdminUser"), new AdminUser("newUser1"));
        assertThat(adminsConfig.getRoles()).hasSize(2);
        assertThat(adminsConfig.getRoles()).contains(new AdminRole("existingAdminRole"), new AdminRole("newRole1"));
    }

    @Test
    public void shouldBulkUpdateToRemoveAdminsFromConfig() {
        AdminsConfig config = new AdminsConfig(new AdminUser("adminUser1"), new AdminUser("adminUser2"),
                new AdminRole("adminRole1"), new AdminRole("adminRole2"));
        cruiseConfig = GoConfigMother.defaultCruiseConfig();
        cruiseConfig.server().security().setAdminsConfig(config);
        when(goConfigService.serverConfig()).thenReturn(cruiseConfig.server());

        Username user = new Username("user");

        adminsConfigService.bulkUpdate(user, emptyList(), List.of("adminUser1"), emptyList(), List.of("adminRole1"), "md5");

        ArgumentCaptor<AdminsConfigUpdateCommand> captor = ArgumentCaptor.forClass(AdminsConfigUpdateCommand.class);
        verify(goConfigService).updateConfig(captor.capture(), eq(user));
        AdminsConfigUpdateCommand updateCommand = captor.getValue();

        AdminsConfig adminsConfig = updateCommand.getPreprocessedEntityConfig();
        assertThat(adminsConfig.getUsers()).hasSize(1);
        assertThat(adminsConfig.getUsers()).contains(new AdminUser("adminUser2"));
        assertThat(adminsConfig.getRoles()).hasSize(1);
        assertThat(adminsConfig.getRoles()).contains(new AdminRole("adminRole2"));
    }

    @Test
    public void bulkUpdate_shouldValidateThatRoleIsAnAdminWhenTryingToRemove() {
        AdminsConfig config = new AdminsConfig(new AdminUser("adminUser1"), new AdminUser("adminUser2"),
                new AdminRole("adminRole1"), new AdminRole("adminRole2"));
        cruiseConfig = GoConfigMother.defaultCruiseConfig();
        cruiseConfig.server().security().setAdminsConfig(config);
        when(goConfigService.serverConfig()).thenReturn(cruiseConfig.server());

        Username user = new Username("user");

        BulkUpdateAdminsResult result = adminsConfigService.bulkUpdate(user, emptyList(), emptyList(), emptyList(),
                List.of("someOtherRole"), "md5");

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.httpCode()).isEqualTo(422);
        assertThat(result.message()).isEqualTo("Update failed because some users or roles do not exist under super admins.");
        assertThat(result.getNonExistentRoles()).hasSize(1);
        assertThat(result.getNonExistentRoles()).contains(new CaseInsensitiveString("someOtherRole"));
        assertThat(result.getNonExistentUsers()).hasSize(0);

        verify(goConfigService, times(0)).updateConfig(any(), any());
    }

    @Test
    public void bulkUpdate_shouldValidateThatUserIsAnAdminWhenTryingToRemove() {
        AdminsConfig config = new AdminsConfig(new AdminUser("adminUser1"), new AdminUser("adminUser2"),
                new AdminRole("adminRole1"), new AdminRole("adminRole2"));
        cruiseConfig = GoConfigMother.defaultCruiseConfig();
        cruiseConfig.server().security().setAdminsConfig(config);
        when(goConfigService.serverConfig()).thenReturn(cruiseConfig.server());

        Username user = new Username("user");

        BulkUpdateAdminsResult result = adminsConfigService.bulkUpdate(user, emptyList(), List.of("someOtherUser"),
                emptyList(), emptyList(), "md5");

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.httpCode()).isEqualTo(422);
        assertThat(result.getNonExistentUsers()).hasSize(1);
        assertThat(result.getNonExistentUsers()).contains(new CaseInsensitiveString("someOtherUser"));
        assertThat(result.getNonExistentRoles()).hasSize(0);

        verify(goConfigService, times(0)).updateConfig(any(), any());
    }

    @Test
    public void bulkUpdate_shouldAddErrorsWhenValidationFails() {
        Username user = new Username("user");
        AdminsConfig config = new AdminsConfig(new AdminUser("adminUser1"), new AdminUser("adminUser2"),
                new AdminRole("adminRole1"), new AdminRole("adminRole2"));
        config.errors().add("foo", "bar");
        config.errors().add("baz", "bar");
        cruiseConfig = GoConfigMother.defaultCruiseConfig();
        cruiseConfig.server().security().setAdminsConfig(config);
        when(goConfigService.serverConfig()).thenReturn(cruiseConfig.server());
        cruiseConfig = GoConfigMother.defaultCruiseConfig();
        cruiseConfig.server().security().setAdminsConfig(config);
        when(goConfigService.serverConfig()).thenReturn(cruiseConfig.server());

        doThrow(new GoConfigInvalidException(cruiseConfig, "Validation Failed."))
                .when(goConfigService).updateConfig(any(AdminsConfigUpdateCommand.class), eq(user));

        BulkUpdateAdminsResult result = adminsConfigService.bulkUpdate(user, emptyList(), emptyList(), List.of("roleToRemove"),
                emptyList(), "md5");

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.httpCode()).isEqualTo(422);
        assertThat(result.message()).isEqualTo("Validations failed for admins. Error(s): [bar]. Please correct and resubmit.");
    }
}
