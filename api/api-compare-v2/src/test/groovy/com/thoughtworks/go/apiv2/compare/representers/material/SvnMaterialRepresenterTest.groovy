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
package com.thoughtworks.go.apiv2.compare.representers.material


import com.thoughtworks.go.config.BasicCruiseConfig
import com.thoughtworks.go.config.CaseInsensitiveString
import com.thoughtworks.go.config.PipelineConfig
import com.thoughtworks.go.config.PipelineConfigSaveValidationContext
import com.thoughtworks.go.config.materials.MaterialConfigs
import com.thoughtworks.go.helper.MaterialConfigsMother
import com.thoughtworks.go.security.GoCipher
import com.thoughtworks.go.util.command.UrlArgument

import static com.thoughtworks.go.helper.MaterialConfigsMother.svn

class SvnMaterialRepresenterTest implements MaterialRepresenterTrait {
  def existingMaterial() {
    MaterialConfigsMother.svnMaterialConfig()
  }

  def existingMaterialWithErrors() {
    def svnConfig = svn(new UrlArgument(''), '', '', true, new GoCipher(), true, null, false, '', new CaseInsensitiveString('!nV@l!d'))
    def materialConfigs = new MaterialConfigs(svnConfig)
    materialConfigs.validateTree(PipelineConfigSaveValidationContext.forChain(true, "group", new BasicCruiseConfig(), new PipelineConfig()))
    return materialConfigs.get(0)
  }

  def materialHash =
    [
      type      : 'svn',
      attributes: [
        url               : "url",
        destination       : "svnDir",
        filter            : [
          ignore: [
            "*.doc"
          ]
        ],
        invert_filter     : false,
        name              : "svn-material",
        auto_update       : false,
        check_externals   : true,
        username          : "user",
        encrypted_password: new GoCipher().encrypt("pass"),
        display_type      : "Subversion",
        description       : "URL: url, Username: user, CheckExternals: true",
      ]
    ]

  def expectedMaterialHashWithErrors =
    [
      type      : "svn",
      attributes: [
        url            : "",
        destination    : "",
        filter         : null,
        invert_filter  : false,
        name           : "!nV@l!d",
        auto_update    : true,
        check_externals: true,
        username       : "",
        display_type   : "Subversion",
        description    : "URL: , Username: , CheckExternals: true",
      ],
      errors    : [
        name: ["Invalid material name '!nV@l!d'. This must be alphanumeric and can contain underscores, hyphens and periods (however, it cannot start with a period). The maximum allowed length is 255 characters."],
        url : ["URL cannot be blank"]
      ]
    ]
}
