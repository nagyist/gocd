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
package com.thoughtworks.go.apiv2.materials.representers.materials

import com.thoughtworks.go.config.BasicCruiseConfig
import com.thoughtworks.go.config.CaseInsensitiveString
import com.thoughtworks.go.config.PipelineConfig
import com.thoughtworks.go.config.PipelineConfigSaveValidationContext
import com.thoughtworks.go.config.materials.MaterialConfigs
import com.thoughtworks.go.config.materials.git.GitMaterialConfig
import com.thoughtworks.go.helper.MaterialConfigsMother
import com.thoughtworks.go.util.command.UrlArgument
import org.junit.jupiter.api.Test

import static com.thoughtworks.go.api.base.JsonUtils.toObjectString
import static com.thoughtworks.go.helper.MaterialConfigsMother.git
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson

class GitMaterialRepresenterTest implements MaterialRepresenterTrait<GitMaterialConfig> {

  GitMaterialConfig existingMaterial() {
    return MaterialConfigsMother.gitMaterialConfig()
  }

  GitMaterialConfig existingMaterialWithErrors() {
    def gitConfig = git(new UrlArgument(''), null, null, '', '', true, null, false, '', new CaseInsensitiveString('!nV@l!d'), false)
    def dupGitMaterial = git(new UrlArgument(''), null, null, '', '', true, null, false, '', new CaseInsensitiveString('!nV@l!d'), false)
    def materialConfigs = new MaterialConfigs(gitConfig)
    materialConfigs.add(dupGitMaterial)

    materialConfigs.validateTree(PipelineConfigSaveValidationContext.forChain(true, "group", new BasicCruiseConfig(), new PipelineConfig()))
    return materialConfigs.get(0) as GitMaterialConfig
  }

  @Test
  void "should serialize material without name"() {
    def gitMaterialConfig = git("http://funk.com/blank")
    def actualJson = toObjectString(MaterialsRepresenter.toJSON(gitMaterialConfig))

    assertThatJson(actualJson).isEqualTo([
      type       : 'git',
      fingerprint: gitMaterialConfig.fingerprint,
      attributes : [
        url             : "http://funk.com/blank",
        destination     : null,
        filter          : null,
        invert_filter   : false,
        name            : null,
        auto_update     : true,
        branch          : "master",
        submodule_folder: null,
        shallow_clone   : false
      ]
    ])
  }

  @Test
  void "should serialize material with blank branch"() {
    def gitMaterialConfig = git("http://funk.com/blank", "")
    def actualJson = toObjectString(MaterialsRepresenter.toJSON(gitMaterialConfig))

    assertThatJson(actualJson).isEqualTo([
      type       : 'git',
      fingerprint: gitMaterialConfig.fingerprint,
      attributes : [
        url             : "http://funk.com/blank",
        destination     : null,
        filter          : null,
        invert_filter   : false,
        name            : null,
        auto_update     : true,
        branch          : "master",
        submodule_folder: null,
        shallow_clone   : false
      ]
    ])
  }

  def expectedMaterialHashWithErrors() {
    [
      type       : "git",
      fingerprint: existingMaterialWithErrors().fingerprint,
      attributes : [
        url             : "",
        destination     : "",
        filter          : null,
        invert_filter   : false,
        name            : "!nV@l!d",
        auto_update     : true,
        branch          : "master",
        submodule_folder: "",
        shallow_clone   : false,
      ],
      errors     : [
        name       : ["You have defined multiple materials called '!nV@l!d'. Material names are case-insensitive and must be unique. Note that for dependency materials the default materialName is the name of the upstream pipeline. You can override this by setting the materialName explicitly for the upstream pipeline.", "Invalid material name '!nV@l!d'. This must be alphanumeric and can contain underscores, hyphens and periods (however, it cannot start with a period). The maximum allowed length is 255 characters."],
        destination: ["Destination directory is required when a pipeline has multiple SCM materials."],
        url        : ["URL cannot be blank"]
      ]
    ]
  }

  def materialHash() {
    [
      type       : 'git',
      fingerprint: existingMaterial().fingerprint,
      attributes : [
        url             : "http://user:password@funk.com/blank",
        destination     : "destination",
        filter          : [
          ignore: ['**/*.html', '**/foobar/']
        ],
        invert_filter   : false,
        branch          : 'branch',
        submodule_folder: 'sub_module_folder',
        shallow_clone   : true,
        name            : 'AwesomeGitMaterial',
        auto_update     : false,
      ]
    ]
  }

}
