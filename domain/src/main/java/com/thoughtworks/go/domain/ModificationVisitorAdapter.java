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
package com.thoughtworks.go.domain;

import com.thoughtworks.go.domain.materials.Material;
import com.thoughtworks.go.domain.materials.Modification;
import com.thoughtworks.go.domain.materials.ModifiedFile;
import com.thoughtworks.go.domain.materials.Revision;

public class ModificationVisitorAdapter implements ModificationVisitor {
    @Override
    public void visit(Material material, Revision revision) {
    }

    @Override
    public void visit(Modification modification) {
    }

    @Override
    public void visit(MaterialRevision materialRevision) {
    }

    @Override
    public void visit(ModifiedFile file) {

    }
}
