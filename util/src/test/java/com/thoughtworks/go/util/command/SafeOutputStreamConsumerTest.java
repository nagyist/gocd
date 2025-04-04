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
package com.thoughtworks.go.util.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SafeOutputStreamConsumerTest {

    @Test
    public void shouldReplaceSecretsOnTheOutput() {
        InMemoryStreamConsumer actualConsumer = ProcessOutputStreamConsumer.inMemoryConsumer();
        SafeOutputStreamConsumer streamConsumer = new SafeOutputStreamConsumer(actualConsumer);
        streamConsumer.addArgument(commandArgumentWhichHasASecret("secret"));

        streamConsumer.stdOutput("This line has a secret and another secret in it.");

        assertThat(actualConsumer.getAllOutput()).doesNotContain("secret");
    }

    private CommandArgument commandArgumentWhichHasASecret(final String secret) {
        return new CommandArgument() {
            @Override
            public String originalArgument() {
                return secret;
            }

            @Override
            public String forDisplay() {
                return "******";
            }

            @Override
            public String forCommandLine() {
                return originalArgument();
            }
        };
    }
}
