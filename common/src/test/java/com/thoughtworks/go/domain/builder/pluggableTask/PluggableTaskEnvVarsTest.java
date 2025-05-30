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
package com.thoughtworks.go.domain.builder.pluggableTask;

import com.thoughtworks.go.plugin.api.task.Console;
import com.thoughtworks.go.util.command.EnvironmentVariableContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class PluggableTaskEnvVarsTest {

    private EnvironmentVariableContext context;
    private PluggableTaskEnvVars envVars;
    private List<String> keys = List.of("Social Net 1", "Social Net 2", "Social Net 3");
    private List<String> values = List.of("Twitter", "Facebook", "Mega Upload");

    @BeforeEach
    public void setUp() {
        context = new EnvironmentVariableContext();
        for (int i = 0; i < keys.size(); i++) {
            context.setProperty(keys.get(i), values.get(i), i % 2 != 0);
        }
        envVars = new PluggableTaskEnvVars(context);
    }

    @Test
    public void shouldReturnEnvVarsMap() {
        Map<String, String> envMap = envVars.asMap();
        assertThat(envMap.keySet().containsAll(keys)).isTrue();
        assertThat(envMap.values().containsAll(values)).isTrue();
        for (int i = 0; i < keys.size(); i++) {
            assertThat(envMap.get(keys.get(i))).isEqualTo(values.get(i));
        }
    }

    @Test
    public void testSecureEnvSpecifier() {
        Console.SecureEnvVarSpecifier secureEnvVarSpecifier = envVars.secureEnvSpecifier();
        for (int i = 0; i < keys.size(); i++) {
            assertThat(secureEnvVarSpecifier.isSecure(keys.get(i))).isEqualTo(i % 2 != 0);
        }
    }

    @Test
    public void shouldPrintToConsole() {
        Console console = mock(Console.class);
        envVars.writeTo(console);
        verify(console).printEnvironment(envVars.asMap(), envVars.secureEnvSpecifier());
    }
}
