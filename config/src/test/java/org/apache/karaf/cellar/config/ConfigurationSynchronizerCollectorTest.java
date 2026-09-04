/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.cellar.config;

import org.apache.karaf.cellar.core.Group;
import org.apache.karaf.cellar.core.Node;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers which entries of the cluster configuration map the synchronizer collects.
 */
public class ConfigurationSynchronizerCollectorTest {

    private static final String GROUP = "default";
    private static final String FILE = "org.jahia.bundles.api.authorization-sam.yml";
    private static final String OTHER_FILE = "org.jahia.bundles.api.authorization-default.yml";
    private static final String LOCAL_NODE = "10.0.0.1:7860";
    private static final String PEER_NODE = "10.0.0.2:7860";

    private Map<String, Properties> clusterConfigurations;
    private Map<String, String> declarations;
    private Set<Node> members;
    private TestSynchronizer synchronizer;

    /**
     * A synchronizer whose cluster group membership and held pids map are plain collections.
     */
    private static class TestSynchronizer extends ConfigurationSynchronizer {
        private final Map<String, String> declarations;
        private final Set<Node> members;

        TestSynchronizer(Map<String, String> declarations, Set<Node> members) {
            this.declarations = declarations;
            this.members = members;
        }

        @Override
        protected Map<String, String> getHeldPidsMap(String groupName) {
            return declarations;
        }

        @Override
        protected Set<Node> listGroupMembers(Group group) {
            return members;
        }

        @Override
        protected String getLocalNodeId() {
            return LOCAL_NODE;
        }
    }

    /** A node identified by its address, as HazelcastNode is. */
    private static class TestNode implements Node {
        private final String id;

        TestNode(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public String getAlias() {
            return null;
        }

        public String getHost() {
            return id.substring(0, id.indexOf(':'));
        }

        public int getPort() {
            return Integer.parseInt(id.substring(id.indexOf(':') + 1));
        }
    }

    @Before
    public void setUp() {
        clusterConfigurations = new LinkedHashMap<String, Properties>();
        declarations = new HashMap<String, String>();
        members = new LinkedHashSet<Node>();
        members.add(new TestNode(LOCAL_NODE));
        members.add(new TestNode(PEER_NODE));
        synchronizer = new TestSynchronizer(declarations, members);
    }

    private void entry(String pid, String filename, String content) {
        Properties properties = new Properties();
        properties.put(ConfigurationSupport.KARAF_CELLAR_FILENAME, filename);
        properties.put(ConfigurationSupport.KARAF_CELLAR_CONTENT, content);
        clusterConfigurations.put(pid, properties);
    }

    private void declares(String nodeId, String... pids) {
        StringBuilder declaration = new StringBuilder();
        for (String pid : pids) {
            if (declaration.length() > 0) {
                declaration.append("\n");
            }
            declaration.append(pid);
        }
        declarations.put(nodeId, declaration.toString());
    }

    private Set<String> collect() {
        return synchronizer.collectUnheldConfigurations(new Group(GROUP), clusterConfigurations);
    }

    @Test
    public void GIVEN_an_entry_no_node_declares_WHEN_collecting_THEN_it_is_removed() {
        entry("pid.local", FILE, "up to date");
        entry("pid.dead", FILE, "pre upgrade");
        declares(LOCAL_NODE, "pid.local");
        declares(PEER_NODE, "pid.local");

        collect();

        assertEquals(1, clusterConfigurations.size());
        assertTrue(clusterConfigurations.containsKey("pid.local"));
    }

    @Test
    public void GIVEN_two_unheld_entries_and_a_held_one_WHEN_collecting_THEN_only_the_held_one_remains() {
        entry("pid.fresh", FILE, "up to date");
        entry("pid.stale", FILE, "pre upgrade");
        entry("pid.current", FILE, "up to date");
        declares(LOCAL_NODE, "pid.current");
        declares(PEER_NODE, "pid.current");

        collect();

        assertEquals(1, clusterConfigurations.size());
        assertTrue(clusterConfigurations.containsKey("pid.current"));
        assertNull(clusterConfigurations.get("pid.stale"));
    }

    /**
     * Every entry of a file is stale and one has to stay, or cleanupResourcesNotPresentInCluster would delete the
     * local configuration of every node. Which one stays is the iteration order of the cluster map, so the survivor
     * must not be applied either: nothing vouches for what it carries, and the local file does.
     */
    @Test
    public void GIVEN_every_entry_of_a_file_is_unheld_WHEN_collecting_THEN_the_survivor_is_reported_as_unheld() {
        entry("pid.fresh", FILE, "up to date");
        entry("pid.stale", FILE, "pre upgrade");
        declares(LOCAL_NODE, "pid.current");
        declares(PEER_NODE, "pid.current");

        Set<String> unheld = collect();

        assertEquals(1, clusterConfigurations.size());
        String survivor = clusterConfigurations.keySet().iterator().next();
        assertTrue(unheld.contains(survivor));
    }

    @Test
    public void GIVEN_an_entry_a_peer_declares_WHEN_collecting_THEN_it_is_kept() {
        entry("pid.local", FILE, "up to date");
        entry("pid.peer", FILE, "up to date");
        declares(LOCAL_NODE, "pid.local");
        declares(PEER_NODE, "pid.peer");

        collect();

        assertEquals(2, clusterConfigurations.size());
    }

    @Test
    public void GIVEN_a_node_that_has_not_declared_WHEN_collecting_THEN_nothing_is_removed() {
        // the state of a cluster running mixed versions during a rolling upgrade
        entry("pid.local", FILE, "up to date");
        entry("pid.dead", FILE, "pre upgrade");
        declares(LOCAL_NODE, "pid.local");

        Set<String> unheld = collect();

        assertEquals(2, clusterConfigurations.size());
        assertTrue(unheld.isEmpty());
    }

    @Test
    public void GIVEN_the_only_entry_of_a_file_is_unheld_WHEN_collecting_THEN_it_is_kept() {
        // removing it would make cleanupResourcesNotPresentInCluster delete the local configuration of every node
        entry("pid.dead", FILE, "pre upgrade");
        declares(LOCAL_NODE);
        declares(PEER_NODE);

        Set<String> unheld = collect();

        assertEquals(1, clusterConfigurations.size());
        assertTrue(clusterConfigurations.containsKey("pid.dead"));
        assertTrue(unheld.contains("pid.dead"));
    }

    @Test
    public void GIVEN_a_deletion_marker_WHEN_collecting_THEN_it_is_kept() {
        entry("pid.local", FILE, "up to date");
        entry("pid.removed", FILE, "pre upgrade");
        clusterConfigurations.get("pid.removed").put(ConfigurationSupport.KARAF_CELLAR_REMOVED, true);
        declares(LOCAL_NODE, "pid.local");
        declares(PEER_NODE, "pid.local");

        collect();

        assertEquals(2, clusterConfigurations.size());
    }

    @Test
    public void GIVEN_an_entry_that_is_not_file_backed_WHEN_collecting_THEN_it_is_kept() {
        Properties properties = new Properties();
        clusterConfigurations.put("org.jahia.singleton", properties);
        declares(LOCAL_NODE);
        declares(PEER_NODE);

        collect();

        assertEquals(1, clusterConfigurations.size());
    }

    @Test
    public void GIVEN_entries_of_two_files_WHEN_collecting_THEN_each_file_is_arbitrated_on_its_own() {
        entry("pid.local.sam", FILE, "up to date");
        entry("pid.dead.sam", FILE, "pre upgrade");
        entry("pid.local.default", OTHER_FILE, "up to date");
        entry("pid.dead.default", OTHER_FILE, "pre upgrade");
        declares(LOCAL_NODE, "pid.local.sam", "pid.local.default");
        declares(PEER_NODE, "pid.local.sam", "pid.local.default");

        collect();

        assertEquals(2, clusterConfigurations.size());
        assertTrue(clusterConfigurations.containsKey("pid.local.sam"));
        assertTrue(clusterConfigurations.containsKey("pid.local.default"));
    }

    @Test
    public void GIVEN_a_stopped_node_WHEN_collecting_THEN_the_entries_it_declared_are_removed() {
        entry("pid.local", FILE, "up to date");
        entry("pid.stopped", FILE, "pre upgrade");
        declares(LOCAL_NODE, "pid.local");
        declares(PEER_NODE, "pid.local");
        declares("10.0.0.3:7860", "pid.stopped");

        collect();

        assertEquals(1, clusterConfigurations.size());
        assertTrue(clusterConfigurations.containsKey("pid.local"));
    }

    @Test
    public void GIVEN_a_declaration_WHEN_reading_it_back_THEN_it_names_the_same_pids() {
        Set<String> pids = new LinkedHashSet<String>(Arrays.asList("a.b~c", "d.e.0f4f8872-6406"));
        synchronizer.declareHeldPids(GROUP, pids);

        assertEquals(pids, synchronizer.readHeldPids(declarations.get(LOCAL_NODE)));
        assertTrue(synchronizer.readHeldPids(null).isEmpty());
        assertTrue(synchronizer.readHeldPids("").isEmpty());
        assertFalse(synchronizer.readHeldPids("a.b~c").isEmpty());
    }
}
