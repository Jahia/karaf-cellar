package org.apache.karaf.cellar.config;

import org.apache.karaf.cellar.core.ClusterManager;
import org.apache.karaf.cellar.core.Group;
import org.apache.karaf.cellar.core.Node;
import org.apache.karaf.cellar.core.event.EventType;
import org.junit.Before;
import org.junit.Test;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Covers what pull() acts on when the cluster map changes underneath it.
 * <p>
 * The map is written by every node, so the value read before taking the monitor can be gone or replaced by the time
 * the monitor is held. pull() therefore has to act on a value it read inside the monitor, not on the one it sampled
 * to decide whether to enter it. The map below returns the live entry on its first read and a deletion marker on
 * every read after, which is that window made deterministic and without threads.
 */
public class ConfigurationSynchronizerPullTest {

    private static final String PID = "org.jahia.bundles.api.authorization~sam";
    private static final String FILENAME = "org.jahia.bundles.api.authorization-sam.yml";

    private MutatingMap clusterMap;
    private RecordingConfiguration local;
    private ConfigurationSynchronizer synchronizer;

    @Before
    public void setUp() {
        Properties live = new Properties();
        live.put("service.pid", PID);
        live.put("karaf.cellar.filename", FILENAME);
        live.put("healthcheck.grants[6].api", "graphql.GqlProbeStatus");

        Properties marker = new Properties();
        marker.put("service.pid", PID);
        marker.put("karaf.cellar.filename", FILENAME);
        marker.put("karaf.cellar.removed", true);

        clusterMap = new MutatingMap(PID, live, marker);

        Hashtable<String, Object> localProperties = new Hashtable<>();
        localProperties.put("service.pid", PID);
        localProperties.put("felix.fileinstall.filename", FILENAME);
        local = new RecordingConfiguration(PID, localProperties);

        synchronizer = new TestSynchronizer(clusterMap, local);
    }

    /**
     * The entry is live when pull() decides to act on it and carries a deletion marker by the time the monitor is
     * held. Acting on the first read updates the local configuration, which produces a CM_UPDATED that publishes a
     * live entry back over the marker, so the deletion is undone across the cluster.
     */
    @Test
    public void GIVEN_an_entry_deleted_while_pulling_WHEN_pulling_THEN_the_local_configuration_is_not_updated() {
        synchronizer.pull(new Group("default"));

        assertFalse("pull() updated the local configuration from an entry that was deleted while it ran",
                local.updated);
    }

    @Test
    public void GIVEN_an_entry_deleted_while_pulling_WHEN_pulling_THEN_the_entry_is_read_inside_the_monitor() {
        synchronizer.pull(new Group("default"));

        assertEquals("pull() read the entry once, so it acted on a value it sampled before taking the monitor",
                2, clusterMap.reads);
    }

    /** Answers the live value once, then the deletion marker, and counts its reads. */
    private static class MutatingMap extends HashMap<String, Properties> {

        private final String pid;
        private final Properties first;
        private final Properties rest;
        private transient int reads;

        private MutatingMap(String pid, Properties first, Properties rest) {
            this.pid = pid;
            this.first = first;
            this.rest = rest;
            super.put(pid, first);
        }

        @Override
        public Properties get(Object key) {
            if (!pid.equals(key)) {
                return super.get(key);
            }
            reads++;
            return reads == 1 ? first : rest;
        }
    }

    /** Records whether the configuration was updated. */
    private static class RecordingConfiguration extends StubConfiguration {

        private boolean updated;

        private RecordingConfiguration(String pid, Dictionary<String, Object> properties) {
            super(pid, properties);
        }

        @Override
        public void update(Dictionary<String, ?> properties) {
            updated = true;
        }
    }

    /** Answers pull()'s collaborators without a container. */
    private static class TestSynchronizer extends ConfigurationSynchronizer {

        private final Map<String, Properties> map;
        private final Configuration local;

        private TestSynchronizer(Map<String, Properties> map, Configuration local) {
            this.map = map;
            this.local = local;
            this.clusterManager = new StubClusterManager(map);
            this.configurationAdmin = new StubConfigurationAdmin(local);
            setStorage(new java.io.File(System.getProperty("java.io.tmpdir")));
        }

        @Override
        public Boolean isAllowed(Group group, String category, String event, EventType type) {
            return Boolean.TRUE;
        }

        @Override
        protected Map<String, Boolean> getSynchronizerMap() {
            return new HashMap<>();
        }
    }
}
