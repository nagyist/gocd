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
package com.thoughtworks.go.server.materials.postcommit.mercurial;

import com.thoughtworks.go.config.materials.git.GitMaterial;
import com.thoughtworks.go.config.materials.mercurial.HgMaterial;
import com.thoughtworks.go.domain.materials.Material;
import com.thoughtworks.go.util.command.UrlArgument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class MercurialPostCommitHookImplementerTest {

    private MercurialPostCommitHookImplementer implementer;

    @BeforeEach
    public void setUp() {
        implementer = new MercurialPostCommitHookImplementer();
    }

    @Test
    public void shouldPruneMercurialMaterialsWhichMatchIncomingURL() {
        HgMaterial material1 = mock(HgMaterial.class);
        when(material1.getUrlArgument()).thenReturn(new UrlArgument("http://repo1.something.local"));
        HgMaterial material2 = mock(HgMaterial.class);
        when(material2.getUrlArgument()).thenReturn(new UrlArgument("http://repo1.something.local"));
        HgMaterial material3 = mock(HgMaterial.class);
        when(material3.getUrlArgument()).thenReturn(new UrlArgument("ssh://repo1.something.local"));
        GitMaterial material4 = mock(GitMaterial.class);
        when(material4.getUrlArgument()).thenReturn(new UrlArgument("http://repo1.something.local"));
        Set<Material> materials = Set.of(material1, material2, material3, material4);
        Map<String, String> params = new HashMap<>();
        params.put(MercurialPostCommitHookImplementer.REPO_URL_PARAM_KEY, "http://repo1.something.local");

        Set<Material> actual = implementer.prune(materials, params);

        assertThat(actual.size()).isEqualTo(2);
        assertThat(actual).contains(material1);
        assertThat(actual).contains(material2);
        verify(material1).getUrlArgument();
        verify(material2).getUrlArgument();
        verify(material3).getUrlArgument();
        verify(material4, times(0)).getUrlArgument();
    }

    @Test
    public void shouldReturnEmptyListWhenURLIsSpecified() {
        HgMaterial material = mock(HgMaterial.class);
        Set<Material> materials = Set.of(material);

        Set<Material> actual = implementer.prune(materials, new HashMap<>());

        assertThat(actual.size()).isEqualTo(0);
        verify(material, times(0)).getUrlArgument();
    }

    @Test
    public void shouldReturnTrueWhenURLIsAnExactMatch() {
        boolean isEqual = implementer.isUrlEqual("http://repo-url", new HgMaterial("http://repo-url", "dest"));
        assertThat(isEqual).isTrue();
    }

    @Test
    public void shouldReturnTrueWhenBasicAuthIsProvidedInURL() {
        boolean isEqual = implementer.isUrlEqual("http://repo-url", new HgMaterial("http://user:passW)rD@repo-url", "dest"));
        assertThat(isEqual).isTrue();
    }

    @Test
    public void shouldReturnTrueWhenBasicAuthWithoutPasswordIsProvidedInURL() {
        boolean isEqual = implementer.isUrlEqual("http://repo-url", new HgMaterial("http://user:@repo-url", "dest"));
        assertThat(isEqual).isTrue();
    }

    @Test
    public void shouldReturnTrueWhenBasicAuthWithOnlyUsernameIsProvidedInURL() {
        boolean isEqual = implementer.isUrlEqual("http://repo-url", new HgMaterial("http://user@repo-url", "dest"));
        assertThat(isEqual).isTrue();
    }

    @Test
    public void shouldReturnFalseWhenProtocolIsDifferent() {
        boolean isEqual = implementer.isUrlEqual("http://repo-url", new HgMaterial("https://repo-url", "dest"));
        assertThat(isEqual).isFalse();
    }

    @Test
    public void shouldReturnFalseWhenNoValidatorCouldParseUrl() {
        boolean isEqual = implementer.isUrlEqual("http://repo-url", new HgMaterial("something.completely.random", "dest"));
        assertThat(isEqual).isFalse();
    }

    @Test
    public void shouldReturnFalseWhenNoProtocolIsGiven() {
        boolean isEqual = implementer.isUrlEqual("http://repo-url#foo", new HgMaterial("repo-url#foo", "dest"));
        assertThat(isEqual).isFalse();
    }

    @Test
    public void shouldReturnFalseForEmptyURLField() {
        boolean isEqual = implementer.isUrlEqual("http://repo-url", new HgMaterial("http://", "dest"));
        assertThat(isEqual).isFalse();
    }

    @Test
    public void shouldReturnFalseForEmptyURLFieldWithAuth() {
        boolean isEqual = implementer.isUrlEqual("http://repo-url", new HgMaterial("http://user:password@", "dest"));
        assertThat(isEqual).isFalse();
    }

    @Test
    public void shouldMatchFileBasedAccessWithoutAuth() {
        boolean isEqual = implementer.isUrlEqual("/tmp/foo/repo-git", new HgMaterial("/tmp/foo/repo-git", "dest"));
        assertThat(isEqual).isTrue();
    }

    @Test
    public void shouldReturnTrueWhenIncomingUrlDoesNotHaveAuthDetails() {
        boolean isEqual = implementer.isUrlEqual("http://foo.bar/#foo", new HgMaterial("http://user:password@foo.bar/#foo", "dest"));
        assertThat(isEqual).isTrue();
    }
}
