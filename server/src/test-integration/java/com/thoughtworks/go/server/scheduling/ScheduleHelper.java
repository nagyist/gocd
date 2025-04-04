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
package com.thoughtworks.go.server.scheduling;

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.PipelineConfig;
import com.thoughtworks.go.config.materials.Materials;
import com.thoughtworks.go.domain.DefaultSchedulingContext;
import com.thoughtworks.go.domain.Pipeline;
import com.thoughtworks.go.domain.SchedulingContext;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.domain.materials.Material;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.materials.MaterialDatabaseUpdater;
import com.thoughtworks.go.server.messaging.StubScheduleCheckCompletedListener;
import com.thoughtworks.go.server.persistence.MaterialRepository;
import com.thoughtworks.go.server.service.*;
import com.thoughtworks.go.server.service.result.ServerHealthStateOperationResult;
import com.thoughtworks.go.server.transaction.TransactionTemplate;
import com.thoughtworks.go.serverhealth.ServerHealthState;
import com.thoughtworks.go.util.TimeProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.thoughtworks.go.util.Timeout.TEN_SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Component
public class ScheduleHelper {
    private StubScheduleCheckCompletedListener scheduleCompleteListener;
    private PipelineScheduler pipelineScheduler;
    private final GoConfigService goConfigService;
    private PipelineService pipelineService;
    private MaterialRepository materialRepository;
    private final PipelineScheduleQueue pipelineScheduleQueue;
    private final TransactionTemplate transactionTemplate;
    static private long waitTime;
    private static final long ONE_SECOND = 1000;
    private MaterialDatabaseUpdater materialDatabaseUpdater;
    private InstanceFactory instanceFactory;

    @Autowired
    public ScheduleHelper(ScheduleCheckCompletedTopic scheduleCheckCompletedTopic, PipelineScheduler pipelineScheduler,
                          GoConfigService goConfigService, MaterialDatabaseUpdater materialDatabaseUpdater,
                          PipelineService pipelineService, MaterialRepository materialRepository,
                          PipelineScheduleQueue pipelineScheduleQueue, TransactionTemplate transactionTemplate, InstanceFactory instanceFactory) {
        this.pipelineScheduler = pipelineScheduler;
        this.goConfigService = goConfigService;
        this.materialDatabaseUpdater = materialDatabaseUpdater;
        this.pipelineService = pipelineService;
        this.materialRepository = materialRepository;
        this.pipelineScheduleQueue = pipelineScheduleQueue;
        this.transactionTemplate = transactionTemplate;
        this.instanceFactory = instanceFactory;
        this.scheduleCompleteListener = new StubScheduleCheckCompletedListener();
        scheduleCheckCompletedTopic.addListener(scheduleCompleteListener);
    }

    public Pipeline schedule(final PipelineConfig pipelineConfig, final BuildCause buildCause, final String approvedBy) {
        return schedule(pipelineConfig, buildCause, approvedBy, new DefaultSchedulingContext(approvedBy));
    }

    public Pipeline schedule(final PipelineConfig pipelineConfig, final BuildCause buildCause, final String approvedBy, final SchedulingContext schedulingContext) {
        return transactionTemplate.execute(status -> {
            materialRepository.save(buildCause.getMaterialRevisions());
            Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, buildCause, schedulingContext, "md5-test", new TimeProvider());
            pipeline = pipelineService.save(pipeline);
            return pipeline;
        });
    }

    public ServerHealthState manuallySchedulePipelineWithRealMaterials(String pipeline, Username username) throws Exception {
        final Map<String, String> revisions = new HashMap<>();
        return manuallySchedulePipelineWithRealMaterials(pipeline, username, revisions);
    }

    public ServerHealthState manuallySchedulePipelineWithRealMaterials(String pipeline, Username username, Map<String, String> pegging) throws Exception {
        updateMaterials(pipeline);
        ServerHealthStateOperationResult result = new ServerHealthStateOperationResult();
        final Map<String, String> environmentVariables = new HashMap<>();
        Map<String, String> secureEnvironmentVariables = new HashMap<>();
        pipelineScheduler.manualProduceBuildCauseAndSave(pipeline, username, new ScheduleOptions(pegging, environmentVariables, secureEnvironmentVariables), result);
        if (result.canContinue()) {
            waitForAnyScheduled(30);
        }
        return result.getServerHealthState();
    }

    public Map<CaseInsensitiveString, BuildCause> waitForAnyScheduled(int seconds) {
        await()
            .atMost(seconds, TimeUnit.SECONDS)
            .alias("Never scheduled")
            .until(() -> !pipelineScheduleQueue.toBeScheduled().isEmpty());
       return pipelineScheduleQueue.toBeScheduled();
    }

    public void waitForNotScheduled(int seconds, String pipelineName) {
        await()
            .atMost(seconds, TimeUnit.SECONDS)
            .failFast(() -> pipelineScheduleQueue.toBeScheduled().containsKey(new CaseInsensitiveString(pipelineName)));
    }

    public void autoSchedulePipelinesWithRealMaterials(String... pipelines) throws Exception {
        updateMaterials(pipelines);
        long startTime = System.currentTimeMillis();
        pipelineScheduler.onTimer();
        if (pipelines.length == 0) {
            await().atLeast(waitTime(), TimeUnit.MILLISECONDS);
        } else {
            await()
                .atMost(20, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(scheduleCompleteListener.pipelines).containsAll(List.of(pipelines)));

            setWaitTime(System.currentTimeMillis() - startTime);
        }

        scheduleCompleteListener.reset();
    }

    private void updateMaterials(String... pipelines) throws Exception {
        for (String pipeline : pipelines) {
            Materials materials = new MaterialConfigConverter().toMaterials(goConfigService.getCurrentConfig().pipelineConfigByName(new CaseInsensitiveString(pipeline)).materialConfigs());
            for (Material material : materials) {
                materialDatabaseUpdater.updateMaterial(material);
            }
        }
    }

    private void setWaitTime(long time) {
        if (time > waitTime) {
            waitTime = time;
        }
    }

    private long waitTime() {
        long result;

        if (waitTime > 0) {
            result = Math.max(waitTime, ONE_SECOND) * 2;
        } else {
            result = TEN_SECONDS.inMillis();
        }
        return result;
    }
}
