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

import com.thoughtworks.go.config.BasicPipelineConfigs;
import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.GoConfigDao;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.service.result.HttpLocalizedOperationResult;
import com.thoughtworks.go.util.GoConfigFileHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml",
        "classpath:/spring-all-servlet.xml",
})
public class PipelineStagesFeedServiceIntegrationTest {

    @Autowired private StageService stageService;
    @Autowired private SecurityService securityService;
    @Autowired private GoConfigDao goConfigDao;
    @Autowired private GoConfigService goConfigService;

    private GoConfigFileHelper configHelper = new GoConfigFileHelper();


    @BeforeEach
    public void setup() throws Exception {
        configHelper.usingCruiseConfigDao(goConfigDao);
        configHelper.onSetUp();
        configHelper.addPipeline("cruise", "stage", "unit", "functional");
        configHelper.enableSecurity();
        configHelper.addAdmins("super_hero");
        configHelper.setViewPermissionForGroup(BasicPipelineConfigs.DEFAULT_GROUP, "sindbad");

        goConfigService.forceNotifyListeners();
    }

    @AfterEach
    public void tearDown() {
        configHelper.onTearDown();
    }

    @Test
    public void shouldReturnTheCorrectLocalizedMessage() {
        FeedResolver feedResolver = new PipelineStagesFeedService(stageService, securityService).feedResolverFor("cruise");
        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();
        feedResolver.feed(new Username(new CaseInsensitiveString("evil_hacker")), result);
        assertThat(result.message()).isEqualTo("User 'evil_hacker' does not have view permission on pipeline 'cruise'");
    }
}
