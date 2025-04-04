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
package com.thoughtworks.go.config.materials;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class FilterTest {
    @Test
    public void shouldReturnEmptyTextToDisplayWhenFilterIsEmpty() {
        assertThat(new Filter().getStringForDisplay()).isEqualTo("");
    }

    @Test
    public void shouldConcatenateIgnoredFilesWithCommaWhenDisplaying() {
        Filter filter = new Filter(new IgnoredFiles("/foo/**.*"), new IgnoredFiles("/another/**.*"), new IgnoredFiles("bar"));
        assertThat(filter.getStringForDisplay()).isEqualTo("/foo/**.*,/another/**.*,bar");
    }

    @Test
    public void shouldInitializeFromDisplayString() {
        assertThat(Filter.fromDisplayString("/foo/**.*,/another/**.*,bar")).isEqualTo(new Filter(new IgnoredFiles("/foo/**.*"), new IgnoredFiles("/another/**.*"), new IgnoredFiles("bar")));
        assertThat(Filter.fromDisplayString("/foo/**.* , /another/**.*,     bar     ")).isEqualTo(new Filter(new IgnoredFiles("/foo/**.*"), new IgnoredFiles("/another/**.*"), new IgnoredFiles("bar")));
    }

    @Test
    public void shouldAddErrorToItsErrorCollection() {
        IgnoredFiles ignore = new IgnoredFiles("helper/*.*");
        Filter filter = new Filter(ignore);
        filter.addError("key", "some error");
        assertThat(filter.errors().on("key")).isEqualTo("some error");
    }
}
