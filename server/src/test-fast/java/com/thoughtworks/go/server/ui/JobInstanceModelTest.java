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
package com.thoughtworks.go.server.ui;

import com.thoughtworks.go.config.Agent;
import com.thoughtworks.go.domain.JobInstance;
import com.thoughtworks.go.helper.AgentInstanceMother;
import com.thoughtworks.go.helper.JobInstanceMother;
import com.thoughtworks.go.server.domain.JobDurationStrategy;
import com.thoughtworks.go.util.TestingClock;
import org.joda.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class JobInstanceModelTest {

    @Test
    public void job_status_should_be_passed_for_passed_job() {
        assertThat(new JobInstanceModel(JobInstanceMother.passed("cruise"), JobDurationStrategy.ALWAYS_ZERO).getStatus()).isEqualTo("Passed");
    }

    @Test
    public void job_status_should_be_failed_for_failed_job() {
        assertThat(new JobInstanceModel(JobInstanceMother.failed("cruise"), JobDurationStrategy.ALWAYS_ZERO).getStatus()).isEqualTo("Failed");
    }

    @Test
    public void job_status_should_be_failed_for_cancelled_job() {
        assertThat(new JobInstanceModel(JobInstanceMother.cancelled("cruise"), JobDurationStrategy.ALWAYS_ZERO).getStatus()).isEqualTo("Cancelled");

    }

    @Test
    public void should_return_false_for_unassign() {
        assertThat(new JobInstanceModel(JobInstanceMother.cancelled("cruise"), JobDurationStrategy.ALWAYS_ZERO).hasAgentInfo()).isFalse();
        assertThat(new JobInstanceModel(JobInstanceMother.cancelled("cruise"), JobDurationStrategy.ALWAYS_ZERO, AgentInstanceMother.building()).hasAgentInfo()).isTrue();
    }

    @Test
    public void job_status_should_be_active_for_scheduled_job() {
        assertThat(new JobInstanceModel(JobInstanceMother.scheduled("cruise"), JobDurationStrategy.ALWAYS_ZERO).getStatus()).isEqualTo("Active");
    }

    @Test
    public void job_status_should_be_active_for_assigned_job() {
        assertThat(new JobInstanceModel(JobInstanceMother.assigned("cruise"), JobDurationStrategy.ALWAYS_ZERO).getStatus()).isEqualTo("Active");
    }

    @Test
    public void job_status_should_be_active_for_building_job() {
        assertThat(new JobInstanceModel(JobInstanceMother.building("cruise"), JobDurationStrategy.ALWAYS_ZERO).getStatus()).isEqualTo("Active");
    }

    private JobInstanceModel job(int elapsedSeconds, int etaSeconds) {
        TestingClock clock = new TestingClock();
        JobInstance instance = JobInstanceMother.building("job", clock.currentTime());
        instance.setClock(clock);
        clock.addSeconds(elapsedSeconds);

        return new JobInstanceModel(instance, new JobDurationStrategy.ConstantJobDuration(etaSeconds * 1000));
    }

    @Test
    public void shouldTellIfInProgress() {
        assertThat(job(500, 1000).isInprogress()).isTrue();
        assertThat(job(1000, 1000).isInprogress()).isFalse();
        assertThat(job(0, 1000).isInprogress()).isFalse();
    }

    @Test
    public void shouldCalculatePercentageComplete() {
        assertThat(job(500, 1000).getPercentComplete()).isEqualTo(50);
        assertThat(job(500, 1020).getPercentComplete()).isEqualTo(49);
        assertThat(job(0, 1000).getPercentComplete()).isEqualTo(0);
    }

    @Test
    public void shouldBeZeroIfWeDontHaveAnEta() {
        assertThat(job(1000, 0).getPercentComplete()).isEqualTo(0);
    }

    @Test
    public void shouldBeIndeterminateIfHasTakenLongerThanTheEta() {
        assertThat(job(1000, 100).getPercentComplete()).isEqualTo(100);
    }

    @Test
    public void shouldShowElapsedTime() {
        assertThat(job(301, 0).getElapsedTime()).isEqualTo(new Duration(301 * 1000));
    }

    @Test
    public void shouldHaveLiveAgent() {
        JobInstanceModel instance = new JobInstanceModel(JobInstanceMother.building("cruise"), JobDurationStrategy.ALWAYS_ZERO, AgentInstanceMother.building());
        assertThat(instance.hasLiveAgent()).isTrue();
    }

    @Test
    public void shouldReturnFalseForLiveAgentIfAgentInfoIsNotProvided() {
        JobInstanceModel instance = new JobInstanceModel(JobInstanceMother.building("cruise"), JobDurationStrategy.ALWAYS_ZERO);
        assertThat(instance.hasLiveAgent()).isFalse();
    }

    @Test
    public void shouldReturnFalseForLiveAgentIfAgentInfoIsConstructedFromDb() {
        JobInstanceModel instance = new JobInstanceModel(JobInstanceMother.building("cruise"), JobDurationStrategy.ALWAYS_ZERO, new Agent("uuid", "hostname", "ip", "cookie"));
        assertThat(instance.hasLiveAgent()).isFalse();
    }
}
