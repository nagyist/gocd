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
package com.thoughtworks.go.server.messaging;

import com.thoughtworks.go.server.scheduling.ScheduleCheckCompletedMessage;

import java.util.ArrayList;
import java.util.List;

public class StubScheduleCheckCompletedListener implements GoMessageListener<ScheduleCheckCompletedMessage> {
    public List<String> pipelines = new ArrayList<>();

    @Override
    public void onMessage(ScheduleCheckCompletedMessage message) {
        pipelines.add(message.getPipelineName());
    }

    public void reset() {
        pipelines.clear();
    }
}
