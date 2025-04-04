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
package com.thoughtworks.go.domain.builder;

import com.thoughtworks.go.domain.RunIfConfigs;
import com.thoughtworks.go.util.command.CommandLine;

import java.io.File;

public class CommandBuilder extends BaseCommandBuilder {
    protected String args;

    public CommandBuilder(String command, String args, File workingDir, RunIfConfigs conditions, Builder cancelBuilder,
                          String description,
                          String errorString) {
        this(command, args, workingDir, conditions, cancelBuilder, description);
        this.errorString = errorString;
    }

    public CommandBuilder(String command, String args, File workingDir, RunIfConfigs conditions, Builder cancelBuilder,
                          String description) {
        super(conditions, cancelBuilder, description, command, workingDir);
        this.args = args;
    }

    @Override
    protected String[] argList() {
        return CommandLine.translateCommandLine(args);
    }

    public File getWorkingDir() {
        return workingDir;
    }

    public String getArgs() {
        return args;
    }

    public String getCommand() {
        return command;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        CommandBuilder that = (CommandBuilder) o;

        if (args != null ? !args.equals(that.args) : that.args != null) {
            return false;
        }
        if (command != null ? !command.equals(that.command) : that.command != null) {
            return false;
        }
        if (errorString != null ? !errorString.equals(that.errorString) : that.errorString != null) {
            return false;
        }
        if (workingDir != null ? !workingDir.equals(that.workingDir) : that.workingDir != null) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (command != null ? command.hashCode() : 0);
        result = 31 * result + (args != null ? args.hashCode() : 0);
        result = 31 * result + (workingDir != null ? workingDir.hashCode() : 0);
        result = 31 * result + (errorString != null ? errorString.hashCode() : 0);
        return result;
    }

    @Override public String toString() {
        return "CommandBuilder{" +
                "args='" + args + '\'' +
                "} " + super.toString();
    }

}
