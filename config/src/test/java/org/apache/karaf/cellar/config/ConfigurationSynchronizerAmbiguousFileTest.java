package org.apache.karaf.cellar.config;

import org.apache.karaf.cellar.core.Group;
import org.apache.karaf.cellar.core.event.EventType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.osgi.service.cm.Configuration;

import java.io.File;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers what pull() does when several cluster map entries name one local configuration.
 * <p>
 * An entry names the file it was read from in karaf.cellar.filename, and a node that mints a generated pid for a
 * factory configuration publishes its own entry for a file every other node also publishes. pull() iterates over
 * pids, so it applies each of those entries to the same local configuration in turn and the last one read wins.
 * The map is a Hazelcast ReplicatedMap with no ordering contract on its entry set, which makes the winner a draw.
 * <p>
 * The two cases below are the whole rule. Entries whose content disagrees are a draw pull() must not take, and
 * entries whose content agrees carry no ambiguity and must still be applied. The second case is what holds the
 * change to the defective state: a map written before the naming fix carries one entry per node for every file,
 * and those entries usually agree.
 */
public class ConfigurationSynchronizerAmbiguousFileTest {

    private static final String FILENAME = "org.jahia.bundles.api.authorization-sam.yml";
    private static final String FACTORY_PID = "org.jahia.bundles.api.authorization";
    private static final String CANONICAL_PID = FACTORY_PID + "~sam";
    private static final String GENERATED_PID = FACTORY_PID + ".4d0d2f2a-6f0e-4a1e-9d4e-2b0d5a9c7f11";

    @Rule
    public TemporaryFolder storage = new TemporaryFolder();

    /**
     * Two entries for one file, one shipping six grants and the other seven. Whichever pid the map hands out last
     * decides the local content, which is the revert reported in the incident.
     */
    @Test
    public void GIVEN_two_entries_naming_one_file_that_disagree_WHEN_pulling_THEN_neither_is_applied() {
        Map<String, Properties> clusterMap = new LinkedHashMap<String, Properties>();
        clusterMap.put(CANONICAL_PID, entry(CANONICAL_PID, 7));
        clusterMap.put(GENERATED_PID, entry(GENERATED_PID, 6));

        RecordingConfiguration local = localReading(FILENAME, 7);
        new TestSynchronizer(clusterMap, local, storage.getRoot()).pull(new Group("default"));

        assertEquals("pull() chose between two entries that name one file and disagree on its content",
                0, local.updates);
    }

    /**
     * The same shape with the disagreement removed. Both entries ship seven grants and the local configuration
     * still holds six, so the pull has one unambiguous value to apply and applying it is the correct behaviour.
     */
    @Test
    public void GIVEN_two_entries_naming_one_file_that_agree_WHEN_pulling_THEN_the_local_configuration_is_updated() {
        Map<String, Properties> clusterMap = new LinkedHashMap<String, Properties>();
        clusterMap.put(CANONICAL_PID, entry(CANONICAL_PID, 7));
        clusterMap.put(GENERATED_PID, entry(GENERATED_PID, 7));

        RecordingConfiguration local = localReading(FILENAME, 6);
        new TestSynchronizer(clusterMap, local, storage.getRoot()).pull(new Group("default"));

        assertEquals("pull() applied an unambiguous value a number of times other than once", 1, local.updates);
    }

    /**
     * One live entry and one deletion marker for the same file. A marker is not a candidate, so the file carries
     * one candidate entry and the live value applies as it always did.
     * <p>
     * This is the case an implementation that counts entries rather than candidates would refuse, and refusing it
     * would stop deletions propagating for the whole time they are in flight. LocalConfigurationListener marks
     * the entries of a file one at a time, so a file with several entries passes through this exact state on its
     * way to being deleted everywhere.
     */
    @Test
    public void GIVEN_one_live_entry_and_a_deletion_marker_for_one_file_WHEN_pulling_THEN_the_live_entry_is_applied() {
        Properties marker = entry(GENERATED_PID, 6);
        marker.put("karaf.cellar.removed", true);

        Map<String, Properties> clusterMap = new LinkedHashMap<String, Properties>();
        clusterMap.put(CANONICAL_PID, entry(CANONICAL_PID, 7));
        clusterMap.put(GENERATED_PID, marker);

        RecordingConfiguration local = localReading(FILENAME, 6);
        new TestSynchronizer(clusterMap, local, storage.getRoot()).pull(new Group("default"));

        assertEquals("pull() counted a deletion marker as a candidate entry for its file", 1, local.updates);
    }

    /**
     * Two entries that name no file at all, with content that differs. A singleton configuration carries no
     * karaf.cellar.filename, and neither does a factory configuration no file feeds, so those entries have no
     * file to be ambiguous about and each one applies to its own local configuration.
     * <p>
     * The case is here because a map keyed on the filename accepts a null key. Indexing without a guard would
     * drop every file-less entry into one bucket, where they disagree by construction, and pull() would stop
     * applying the configurations that work today.
     */
    @Test
    public void GIVEN_two_entries_that_name_no_file_WHEN_pulling_THEN_the_local_configuration_is_updated() {
        Properties one = new Properties();
        one.put("service.pid", "org.cortex.singleton.one");
        one.put("threads", "4");

        Properties two = new Properties();
        two.put("service.pid", "org.cortex.singleton.two");
        two.put("threads", "8");

        Map<String, Properties> clusterMap = new LinkedHashMap<String, Properties>();
        clusterMap.put("org.cortex.singleton.one", one);
        clusterMap.put("org.cortex.singleton.two", two);

        Hashtable<String, Object> properties = new Hashtable<String, Object>();
        properties.put("service.pid", "org.cortex.singleton.one");
        properties.put("threads", "1");
        RecordingConfiguration local = new RecordingConfiguration("org.cortex.singleton.one", properties);

        new TestSynchronizer(clusterMap, local, storage.getRoot()).pull(new Group("default"));

        assertTrue("pull() treated entries that name no file as entries that disagree about one file",
                local.updates > 0);
    }

    /**
     * Two entries naming one file with content that disagrees, where this node blocks one of them
     * inbound. The blocked entry is never applied by the pull, so it cannot take part in a draw,
     * and the file has one candidate left.
     * <p>
     * Counting a blocked entry would refuse the entry that is allowed, and refuse it at every pull
     * rather than once, because nothing makes a blocked entry go away.
     */
    @Test
    public void GIVEN_one_of_two_entries_blocked_inbound_WHEN_pulling_THEN_the_allowed_one_is_applied() {
        Map<String, Properties> clusterMap = new LinkedHashMap<String, Properties>();
        clusterMap.put(CANONICAL_PID, entry(CANONICAL_PID, 7));
        clusterMap.put(GENERATED_PID, entry(GENERATED_PID, 6));

        RecordingConfiguration local = localReading(FILENAME, 6);
        TestSynchronizer synchronizer = new TestSynchronizer(clusterMap, local, storage.getRoot());
        synchronizer.blocked = GENERATED_PID;
        synchronizer.pull(new Group("default"));

        assertEquals("pull() let an entry it blocks inbound make its file ambiguous", 1, local.updates);
    }

    /** An entry read from FILENAME, shipping the grants a module version ships. */
    private static Properties entry(String pid, int grants) {
        Properties properties = new Properties();
        properties.put("service.pid", pid);
        properties.put("service.factoryPid", FACTORY_PID);
        properties.put("karaf.cellar.filename", FILENAME);
        for (int index = 0; index < grants; index++) {
            properties.put("healthcheck.grants[" + index + "].api", "graphql.GqlProbe" + index);
        }
        return properties;
    }

    /**
     * The local configuration every entry of that file resolves to. Configuration Admin answers one configuration
     * per file, so the filter findLocalConfiguration builds from either pid reaches this one.
     */
    private static RecordingConfiguration localReading(String filename, int grants) {
        Hashtable<String, Object> properties = new Hashtable<String, Object>();
        properties.put("service.pid", CANONICAL_PID);
        properties.put("service.factoryPid", FACTORY_PID);
        properties.put("felix.fileinstall.filename", filename);
        for (int index = 0; index < grants; index++) {
            properties.put("healthcheck.grants[" + index + "].api", "graphql.GqlProbe" + index);
        }
        return new RecordingConfiguration(CANONICAL_PID, properties);
    }

    /**
     * Counts the updates and keeps what the last one wrote, because pull() re-reads
     * localConfiguration.getProperties() on every iteration. A configuration that recorded the call without
     * storing its argument would report a second update that the real Configuration Admin never receives, and
     * the count below would measure the stub rather than pull().
     */
    private static class RecordingConfiguration extends StubConfiguration {

        private Dictionary<String, Object> properties;
        private int updates;

        private RecordingConfiguration(String pid, Dictionary<String, Object> properties) {
            super(pid, properties);
            this.properties = properties;
        }

        @Override
        public Dictionary<String, Object> getProperties() {
            return properties;
        }

        @SuppressWarnings("unchecked")
        @Override
        public void update(Dictionary<String, ?> updatedProperties) {
            properties = (Dictionary<String, Object>) updatedProperties;
            updates++;
        }
    }

    /** Answers pull()'s collaborators without a container. */
    private static class TestSynchronizer extends ConfigurationSynchronizer {

        private final Map<String, Boolean> synchronizerMap = new HashMap<String, Boolean>();

        private TestSynchronizer(Map<String, Properties> map, Configuration local, File storageRoot) {
            this.clusterManager = new StubClusterManager(map);
            this.configurationAdmin = new StubConfigurationAdmin(local);
            setStorage(storageRoot);
        }

        private String blocked;

        @Override
        public Boolean isAllowed(Group group, String category, String event, EventType type) {
            return Boolean.valueOf(!event.equals(blocked));
        }

        @Override
        protected Map<String, Boolean> getSynchronizerMap() {
            return synchronizerMap;
        }
    }
}
