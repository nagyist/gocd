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
package com.thoughtworks.go.config.validation;

import com.thoughtworks.go.config.ExecTask;
import com.thoughtworks.go.config.pluggabletask.PluggableTask;
import com.thoughtworks.go.config.registry.ConfigElementImplementationRegistry;
import com.thoughtworks.go.domain.Task;
import com.thoughtworks.go.util.XmlUtils;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class UniqueOnCancelValidatorTest {
    @Test
    public void shouldNotFailWithExceptionWhenThereAreNoOnCancelTasksForABuiltInTask() throws Exception {
        ConfigElementImplementationRegistry registry = mock(ConfigElementImplementationRegistry.class);
        when(registry.implementersOf(Task.class)).thenReturn(tasks(ExecTask.class));

        String content =
                """
                        <cruise>
                          <pipeline>
                            <stage>
                              <jobs>
                                <job>
                                  <tasks>
                                    <exec command="install_addons.sh">
                                      <runif status="passed" />
                                    </exec>
                                  </tasks>
                                </job>
                              </jobs>
                            </stage>
                          </pipeline>
                        </cruise>
                        """;

        UniqueOnCancelValidator validator = new UniqueOnCancelValidator();
        validator.validate(elementFor(content), registry);
    }

    @Test
    public void shouldNotFailWithExceptionWhenThereIsOneOnCancelTaskForABuiltInTask() throws Exception {
        ConfigElementImplementationRegistry registry = mock(ConfigElementImplementationRegistry.class);
        when(registry.implementersOf(Task.class)).thenReturn(tasks(ExecTask.class));

        String content =
                """
                        <cruise>
                          <pipeline>
                            <stage>
                              <jobs>
                                <job>
                                  <tasks>
                                    <exec command="install_addons.sh">
                                      <runif status="passed" />
                                       <oncancel>
                                         <ant buildfile="build.xml" />
                                       </oncancel>
                                    </exec>
                                  </tasks>
                                </job>
                              </jobs>
                            </stage>
                          </pipeline>
                        </cruise>
                        """;

        UniqueOnCancelValidator validator = new UniqueOnCancelValidator();
        validator.validate(elementFor(content), registry);
    }

    @Test
    public void shouldFailWithExceptionWhenThereIsMoreThanOneOnCancelTasksForABuiltInTask() {
        ConfigElementImplementationRegistry registry = mock(ConfigElementImplementationRegistry.class);
        when(registry.implementersOf(Task.class)).thenReturn(tasks(ExecTask.class));

        String content =
                """
                        <cruise>
                          <pipeline>
                            <stage>
                              <jobs>
                                <job>
                                  <tasks>
                                    <exec command="install_addons.sh">
                                      <runif status="passed" />
                                       <oncancel>
                                         <ant buildfile="build1.xml" />
                                       </oncancel>
                                       <oncancel>
                                         <ant buildfile="build2.xml" />
                                       </oncancel>
                                    </exec>
                                  </tasks>
                                </job>
                              </jobs>
                            </stage>
                          </pipeline>
                        </cruise>
                        """;

        try {
            UniqueOnCancelValidator validator = new UniqueOnCancelValidator();
            validator.validate(elementFor(content), registry);
        } catch (Exception e) {
            assertThat(e.getMessage()).isEqualTo("Task [exec] should not contain more than 1 oncancel task");
        }
    }

    @Test
    public void shouldNotFailWithExceptionWhenThereAreNoOnCancelTasksForAPluginInTask() throws Exception {
        ConfigElementImplementationRegistry registry = mock(ConfigElementImplementationRegistry.class);
        when(registry.implementersOf(Task.class)).thenReturn(tasks(ExecTask.class));

        String content =
                """
                        <cruise>
                          <pipeline>
                            <stage>
                              <jobs>
                                <job>
                                  <tasks>
                                    <task name="">
                                      <pluginConfiguration id="curl.task.plugin" version="1" />
                                      <configuration>
                                        <property>
                                          <key>Url</key>
                                          <value>URL</value>
                                        </property>
                                      </configuration>
                                      <runif status="passed" />
                                    </task>
                                  </tasks>
                                </job>
                              </jobs>
                            </stage>
                          </pipeline>
                        </cruise>
                        """;

        UniqueOnCancelValidator validator = new UniqueOnCancelValidator();
        validator.validate(elementFor(content), registry);
    }

    @Test
    public void shouldNotFailWithExceptionWhenThereIsOneOnCancelTasksForAPluginInTask() throws Exception {
        ConfigElementImplementationRegistry registry = mock(ConfigElementImplementationRegistry.class);
        when(registry.implementersOf(Task.class)).thenReturn(tasks(ExecTask.class, PluggableTask.class));

        String content =
                """
                        <cruise>
                          <pipeline>
                            <stage>
                              <jobs>
                                <job>
                                  <tasks>
                                      <task name="">
                                        <pluginConfiguration id="curl.task.plugin" version="1" />
                                        <configuration>
                                          <property>
                                            <key>Url</key>
                                            <value>With_On_Cancel</value>
                                          </property>
                                        </configuration>
                                        <runif status="passed" />
                                        <oncancel>
                                          <ant buildfile="blah" target="blah" />
                                        </oncancel>
                                      </task>
                                  </tasks>
                                </job>
                              </jobs>
                            </stage>
                          </pipeline>
                        </cruise>
                        """;

        UniqueOnCancelValidator validator = new UniqueOnCancelValidator();
        validator.validate(elementFor(content), registry);
    }

    @Test
    public void shouldFailWithExceptionWhenThereIsMoreThanOneOnCancelTasksForAPluginInTask() {
        ConfigElementImplementationRegistry registry = mock(ConfigElementImplementationRegistry.class);
        when(registry.implementersOf(Task.class)).thenReturn(tasks(ExecTask.class, PluggableTask.class));

        String content =
                """
                        <cruise>
                          <pipeline>
                            <stage>
                              <jobs>
                                <job>
                                  <tasks>
                                      <task name="">
                                        <pluginConfiguration id="curl.task.plugin" version="1" />
                                        <configuration>
                                          <property>
                                            <key>Url</key>
                                            <value>With_On_Cancel</value>
                                          </property>
                                        </configuration>
                                        <runif status="passed" />
                                        <oncancel>
                                          <ant buildfile="blah" target="blah1" />
                                        </oncancel>
                                        <oncancel>
                                          <ant buildfile="blah" target="blah2" />
                                        </oncancel>
                                      </task>
                                  </tasks>
                                </job>
                              </jobs>
                            </stage>
                          </pipeline>
                        </cruise>
                        """;

        try {
            UniqueOnCancelValidator validator = new UniqueOnCancelValidator();
            validator.validate(elementFor(content), registry);
        } catch (Exception e) {
            assertThat(e.getMessage()).isEqualTo("Task [task] should not contain more than 1 oncancel task");
        }
    }

    @SafeVarargs @SuppressWarnings("varargs")
    private List<Class<? extends Task>> tasks(Class<? extends Task>... taskClasses) {
        List<Class<? extends Task>> tasks = new ArrayList<>();
        Collections.addAll(tasks, taskClasses);
        return tasks;
    }

    private Element elementFor(String content) throws JDOMException, IOException {
        return XmlUtils.buildXmlDocument(content).getRootElement();
    }
}
