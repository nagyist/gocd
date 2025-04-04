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
package com.thoughtworks.go.config.update;

import com.thoughtworks.go.config.*;
import com.thoughtworks.go.config.commands.EntityConfigUpdateCommand;
import com.thoughtworks.go.server.service.ExternalArtifactsService;
import com.thoughtworks.go.server.service.GoConfigService;

public abstract class PipelineConfigCommand implements EntityConfigUpdateCommand<PipelineConfig> {

    protected PipelineConfig pipelineConfig;
    protected GoConfigService goConfigService;
    private ExternalArtifactsService externalArtifactsService;
    PipelineConfig preprocessedPipelineConfig;

    PipelineConfigCommand(PipelineConfig pipelineConfig, GoConfigService goConfigService, ExternalArtifactsService externalArtifactsService) {
        this.pipelineConfig = pipelineConfig;
        this.goConfigService = goConfigService;
        this.externalArtifactsService = externalArtifactsService;
    }

    @Override
    public void clearErrors() {
        BasicCruiseConfig.clearErrors(pipelineConfig);
    }

    @Override
    public PipelineConfig getPreprocessedEntityConfig() {
        return preprocessedPipelineConfig;
    }

    @Override
    public void encrypt(CruiseConfig preprocessedConfig) {
        preprocessedPipelineConfig = preprocessedConfig.getPipelineConfigByName(pipelineConfig.name());
        pipelineConfig.encryptSecureProperties(preprocessedConfig, preprocessedPipelineConfig);
    }

    void validatePublishAndFetchExternalConfigs(PipelineConfig pipelineConfig, CruiseConfig preprocessedConfig) {
        for (PluggableArtifactConfig pluggableArtifactConfig : pipelineConfig.getExternalArtifactConfigs()) {
            externalArtifactsService.validateExternalArtifactConfig(pluggableArtifactConfig, goConfigService.artifactStores().find(pluggableArtifactConfig.getStoreId()), true);
        }

        for (FetchPluggableArtifactTask fetchPluggableArtifactTask : pipelineConfig.getFetchExternalArtifactTasks()) {
            externalArtifactsService.validateFetchExternalArtifactTask(fetchPluggableArtifactTask, pipelineConfig, preprocessedConfig);
        }
    }
}
