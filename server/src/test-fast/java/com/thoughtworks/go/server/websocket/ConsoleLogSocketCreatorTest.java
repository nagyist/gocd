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
package com.thoughtworks.go.server.websocket;

import com.thoughtworks.go.server.service.RestfulService;
import com.thoughtworks.go.util.SystemEnvironment;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

public class ConsoleLogSocketCreatorTest {
    private RestfulService restfulService;
    private JettyServerUpgradeRequest request;
    private ConsoleLogSocketCreator creator;

    @BeforeEach
    public void setUp() {
        restfulService = mock(RestfulService.class);

        request = mock(JettyServerUpgradeRequest.class);
        creator = new ConsoleLogSocketCreator(mock(ConsoleLogSender.class), restfulService, new SocketHealthService(), new SystemEnvironment());
    }

    @Test
    public void createWebSocketParsesJobIdentifierFromURI() {
        when(request.getRequestPath()).thenReturn("/console-websocket/pipe/pipeLabel/stage/stageCount/job");
        creator.createWebSocket(request, mock(JettyServerUpgradeResponse.class));

        verify(restfulService).findJob("pipe", "pipeLabel", "stage", "stageCount", "job");
    }

}