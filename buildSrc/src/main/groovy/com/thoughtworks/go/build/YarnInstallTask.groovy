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

package com.thoughtworks.go.build

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.os.OperatingSystem
import org.gradle.process.ExecOperations

import javax.inject.Inject

class YarnInstallTask extends DefaultTask {

  private ExecOperations execOperations

  private File workingDir
  private File nodeModules

  @Inject
  YarnInstallTask(ExecOperations execOperations) {
    this.execOperations = execOperations
    inputs.property('os', OperatingSystem.current().toString())
    project.afterEvaluate({
      inputs.file(project.file("${getWorkingDir()}/package.json"))
      inputs.file(project.file("${getWorkingDir()}/yarn.lock"))
      inputs.file(project.file("${getWorkingDir()}/.yarnrc.yml"))
      inputs.dir(project.file("${getWorkingDir()}/.yarn/patches"))
      inputs.dir(project.file("${getWorkingDir()}/.yarn/plugins"))
      inputs.dir(project.file("${getWorkingDir()}/.yarn/releases"))
    })
  }

  @Input // not an @InputFile/InputDirectory, because we don't care about the contents of the workingDir itself
  String getWorkingDir() {
    return workingDir.toString()
  }

  @OutputDirectory
  File getNodeModules() {
    return nodeModules == null ? project.file("${getWorkingDir()}/node_modules") : nodeModules
  }

  void setWorkingDir(File workingDir) {
    this.workingDir = workingDir
  }

  @TaskAction
  def install() {
    execOperations.exec { execTask ->
      execTask.environment("FORCE_COLOR", "true")
      execTask.standardOutput = System.out
      execTask.errorOutput = System.err
      execTask.workingDir = this.getWorkingDir()
      execTask.commandLine = OperatingSystem.current().isWindows() ? ["yarn.cmd", "install", "--no-immutable"] : ["yarn", "install", "--immutable"]
    }  
  }
}
