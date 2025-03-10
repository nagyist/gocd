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
package com.thoughtworks.go.plugin.access.authorization.v2;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CapabilitiesDTOTest {

    @Test
    public void shouldDeserializeFromJSON() {
        String json = """
                {
                  "supported_auth_type": "web",
                  "can_search": true,
                  "can_authorize": true,
                  "can_get_user_roles": true
                }""";

        CapabilitiesDTO capabilities = CapabilitiesDTO.fromJSON(json);

        assertThat(capabilities.getSupportedAuthType()).isEqualTo(SupportedAuthTypeDTO.Web);
        assertTrue(capabilities.canSearch());
        assertTrue(capabilities.canAuthorize());
        assertTrue(capabilities.canFetchUserRoles());
    }
}