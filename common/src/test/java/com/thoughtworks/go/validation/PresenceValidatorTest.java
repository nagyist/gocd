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
package com.thoughtworks.go.validation;

import com.thoughtworks.go.domain.materials.ValidationBean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class PresenceValidatorTest {
    @Test
    public void shouldNotRenderErrorWhenStringIsNotBlank() {
        ValidationBean validation = new PresenceValidator("String must be non-blank").validate("foo");
        assertThat(validation.isValid()).isTrue();
    }

    @Test
    public void shouldRenderErrorWhenStringIsBlank() {
        ValidationBean validation = new PresenceValidator("String must be non-blank").validate("");
        assertThat(validation.isValid()).isFalse();

        validation = new PresenceValidator("String must be non-blank").validate(null);
        assertThat(validation.isValid()).isFalse();

        validation = new PresenceValidator("String must be non-blank").validate("   ");
        assertThat(validation.isValid()).isFalse();

        validation = new PresenceValidator("String must be non-blank").validate(" \t\n  ");
        assertThat(validation.isValid()).isFalse();
    }
}
