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
package com.thoughtworks.go.config;

import com.thoughtworks.go.config.materials.MaterialConfigs;
import com.thoughtworks.go.config.registry.ConfigElementImplementationRegistry;
import com.thoughtworks.go.util.ConfigElementImplementationRegistryMother;
import com.thoughtworks.go.util.GoConstants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

public class MagicalGoConfigXmlLoaderFixture {
    public static void assertNotValid(String message, String xmlMaterials) {
        try {
            toMaterials(xmlMaterials);
            fail("Should not be valid");
        } catch (Exception expected) {
            assertThat(expected.getMessage()).contains(message);
        }
    }

    public static void assertValid(String xmlMaterials) throws Exception {
        toMaterials(xmlMaterials);
    }

    public static MaterialConfigs toMaterials(String materials) throws Exception {

        ConfigElementImplementationRegistry registry = ConfigElementImplementationRegistryMother.withNoPlugins();

        MagicalGoConfigXmlLoader xmlLoader = new MagicalGoConfigXmlLoader(new ConfigCache(), registry);
        String pipelineXmlPartial =
                ("""
                        <?xml version="1.0" encoding="utf-8"?>
                        <cruise         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"         xsi:noNamespaceSchemaLocation="cruise-config.xsd"         schemaVersion="%d">
                        <server>
                             <artifacts>
                                   <artifactsDir>logs</artifactsDir>
                             </artifacts>
                        </server>
                          <pipelines>
                        <pipeline name="pipeline">
                          %s
                          <stage name="mingle">
                            <jobs>
                              <job name="functional">
                                <artifacts>
                                  <artifact type="build" src="artifact1.xml" dest="cruise-output" />
                                </artifacts>
                                <tasks><exec command="echo"><runif status="passed" /></exec></tasks>
                              </job>
                            </jobs>
                          </stage>
                        </pipeline>
                        </pipelines>
                        </cruise>
                        """).formatted(GoConstants.CONFIG_SCHEMA_VERSION, materials);
        CruiseConfig cruiseConfig = xmlLoader.loadConfigHolder(pipelineXmlPartial).config;
        return cruiseConfig.pipelineConfigByName(new CaseInsensitiveString("pipeline")).materialConfigs();
    }

}
