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
package com.thoughtworks.go.plugin.domain.common;

import java.util.ArrayList;
import java.util.List;

public class ValidationResult {
    private List<ValidationError> errors = new ArrayList<>();

    public ValidationResult() {
    }

    public void addError(ValidationError validationError) {
        errors.add(validationError);
    }

    public boolean isSuccessful() {
        return errors.isEmpty();
    }

    public List<ValidationError> getErrors() {
        return new ArrayList<>(errors);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ValidationResult that = (ValidationResult) o;

        return errors != null ? errors.equals(that.errors) : that.errors == null;

    }

    @Override
    public int hashCode() {
        return errors != null ? errors.hashCode() : 0;
    }
}
