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
package com.thoughtworks.go.server.materials;

import com.thoughtworks.go.domain.materials.Material;
import com.thoughtworks.go.server.messaging.GoMessage;

/**
 * Understands when a material update has completed
 */
public class MaterialUpdateCompletedMessage implements GoMessage {
    private final Material material;
    private long trackingId;

    public MaterialUpdateCompletedMessage(Material material, long trackingId) {
        this.material = material;
        this.trackingId = trackingId;
    }

    public Material getMaterial() {
        return material;
    }

    public long trackingId() {
        return trackingId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        MaterialUpdateCompletedMessage that = (MaterialUpdateCompletedMessage) o;

        if (material != null ? !material.equals(that.material) : that.material != null) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        return material != null ? material.hashCode() : 0;
    }
}
