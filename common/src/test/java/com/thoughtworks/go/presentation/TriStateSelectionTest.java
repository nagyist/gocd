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
package com.thoughtworks.go.presentation;

import com.thoughtworks.go.config.Agent;
import com.thoughtworks.go.config.Agents;
import com.thoughtworks.go.config.ResourceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

public class TriStateSelectionTest {
    private Set<ResourceConfig> resourceConfigs;
    private Agents agents;

    @BeforeEach
    public void before() {
        resourceConfigs = new HashSet<>();
        resourceConfigs.add(new ResourceConfig("one"));
        resourceConfigs.add(new ResourceConfig("two"));

        agents = new Agents();
    }

    @Test
    public void shouldHaveActionRemoveIfThereAreNoAgents() {
        List<TriStateSelection> selections = TriStateSelection.forAgentsResources(resourceConfigs, agents);
        assertThat(selections).contains(new TriStateSelection("one", TriStateSelection.Action.remove));
        assertThat(selections).contains(new TriStateSelection("two", TriStateSelection.Action.remove));
        assertThat(selections.size()).isEqualTo(2);
    }

    @Test
    public void shouldHaveActionAddIfAllAgentsHaveThatResource() {
        resourceConfigs.add(new ResourceConfig("all"));
        agents.add(new Agent("uuid1", "host1", "127.0.0.1", List.of("all")));
        agents.add(new Agent("uuid2", "host2", "127.0.0.2", List.of("all")));

        List<TriStateSelection> selections = TriStateSelection.forAgentsResources(resourceConfigs, agents);
        assertThat(selections).contains(new TriStateSelection("all", TriStateSelection.Action.add));
    }

    @Test
    public void shouldBeNoChangeIfAllAgentsHaveThatResource() {
        resourceConfigs.add(new ResourceConfig("some"));
        agents.add(new Agent("uuid1", "host1", "127.0.0.1", List.of("some")));
        agents.add(new Agent("uuid2", "host2", "127.0.0.2", emptyList()));

        List<TriStateSelection> selections = TriStateSelection.forAgentsResources(resourceConfigs, agents);
        assertThat(selections).contains(new TriStateSelection("some", TriStateSelection.Action.nochange));
    }

    @Test
    public void shouldHaveActionRemoveIfNoAgentsHaveResource() {
        resourceConfigs.add(new ResourceConfig("none"));
        agents.add(new Agent("uuid1", "host1", "127.0.0.1", List.of("one")));
        agents.add(new Agent("uuid2", "host2", "127.0.0.2", List.of("two")));

        List<TriStateSelection> selections = TriStateSelection.forAgentsResources(resourceConfigs, agents);
        assertThat(selections).contains(new TriStateSelection("none", TriStateSelection.Action.remove));
    }

    @Test
    public void shouldListResourceSelectionInAlhpaOrder() {
        HashSet<ResourceConfig> resourceConfigs = new HashSet<>();
        resourceConfigs.add(new ResourceConfig("B02"));
        resourceConfigs.add(new ResourceConfig("b01"));
        resourceConfigs.add(new ResourceConfig("a01"));
        List<TriStateSelection> selections = TriStateSelection.forAgentsResources(resourceConfigs, agents);

        assertThat(selections.get(0)).isEqualTo((new TriStateSelection("a01", TriStateSelection.Action.remove)));
        assertThat(selections.get(1)).isEqualTo((new TriStateSelection("b01", TriStateSelection.Action.remove)));
        assertThat(selections.get(2)).isEqualTo((new TriStateSelection("B02", TriStateSelection.Action.remove)));
    }

    @Test
    public void shouldDisableWhenDisableVoted() {
        final boolean[] associate = new boolean[1];

        final TriStateSelection.Assigner<String, String> disableWhenEql = new TriStateSelection.Assigner<>() {
            @Override
            public boolean shouldAssociate(String a, String b) {
                return associate[0];
            }

            @Override
            public String identifier(String s) {
                return s;
            }

            @Override
            public boolean shouldEnable(String a, String b) {
                return !a.equals(b);
            }
        };

        final Set<String> assignables = Set.of("quux", "baz");

        associate[0] = true;
        List<TriStateSelection> selections = TriStateSelection.convert(assignables, List.of("foo", "bar"), disableWhenEql);
        assertThat(selections).contains(new TriStateSelection("quux", TriStateSelection.Action.add));
        assertThat(selections).contains(new TriStateSelection("baz", TriStateSelection.Action.add));

        associate[0] = false;
        selections = TriStateSelection.convert(assignables, List.of("foo", "bar"), disableWhenEql);
        assertThat(selections).contains(new TriStateSelection("quux", TriStateSelection.Action.remove));
        assertThat(selections).contains(new TriStateSelection("baz", TriStateSelection.Action.remove));

        associate[0] = true;
        selections = TriStateSelection.convert(assignables, List.of("quux", "bar"), disableWhenEql);
        assertThat(selections).contains(new TriStateSelection("quux", TriStateSelection.Action.add, false));
        assertThat(selections).contains(new TriStateSelection("baz", TriStateSelection.Action.add, true));

        associate[0] = false;
        selections = TriStateSelection.convert(assignables, List.of("bar", "baz"), disableWhenEql);
        assertThat(selections).contains(new TriStateSelection("quux", TriStateSelection.Action.remove, true));
        assertThat(selections).contains(new TriStateSelection("baz", TriStateSelection.Action.remove, false));
    }
}
