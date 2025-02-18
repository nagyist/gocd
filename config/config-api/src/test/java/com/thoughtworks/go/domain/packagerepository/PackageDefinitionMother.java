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
package com.thoughtworks.go.domain.packagerepository;

import com.thoughtworks.go.domain.config.Configuration;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class PackageDefinitionMother {
    public static PackageDefinition create(String id, PackageRepository repository) {
        return create(id, "package-" + id, new Configuration(), repository);
    }

    public static PackageDefinition create(String id) {
        return create(id, null);
    }

    public static PackageDefinition create(final String id, final String name, final Configuration configuration, PackageRepository packageRepository) {
        PackageDefinition packageDefinition = new PackageDefinition(id, name, configuration);
        packageDefinition.setRepository(packageRepository);
        return packageDefinition;
    }

    public static Map<String, Object> paramsForPackageMaterialCreation(String repoId, String pkgName) {
        Map<String, Object> config = new HashMap<>();
        config.put("0", paramsForPackageMaterialConfig("key1", "value1"));
        config.put("1", paramsForPackageMaterialConfig("key2", "value2"));

        Map<String, Object> packageDef = new HashMap<>();
        packageDef.put("repositoryId", repoId);
        packageDef.put("name", pkgName);
        packageDef.put("configuration", config);

        Map<String, Object> params = new HashMap<>();
        params.put("package_definition", packageDef);
        return params;
    }

    public static Map<String, Object> paramsForPackageMaterialAssociation(String repoId, String pkgId) {
        Map<String, Object> packageDef = new HashMap<>();
        packageDef.put("repositoryId", repoId);

        Map<String, Object> params = new HashMap<>();
        params.put("package_definition", packageDef);
        params.put("packageId", pkgId);
        return params;
    }

    public static Map<String, Object> paramsForPackageMaterialConfig(String key, String value) {
        Map<String, Object> property = new HashMap<>();
        Map<String, String> valueMap = new HashMap<>();
        Map<String, Serializable> keyMap = new HashMap<>();
        keyMap.put("name", key);
        valueMap.put("value", value);
        property.put("configurationKey", keyMap);
        property.put("configurationValue", valueMap);
        return property;
    }
}
