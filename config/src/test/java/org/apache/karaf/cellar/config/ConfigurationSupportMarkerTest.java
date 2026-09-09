package org.apache.karaf.cellar.config;

import org.junit.Test;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the entry that marks a configuration as deleted for the rest of the cluster.
 * <p>
 * Properties is a Hashtable, so putting a null value throws, and a marker that cannot be built is a deletion no
 * other node ever hears about: the exception reaches the caller's catch and no cluster event is produced.
 */
public class ConfigurationSupportMarkerTest {

    private static final String FILENAME = "karaf.cellar.filename";
    private static final String REMOVED = "karaf.cellar.removed";

    private final ConfigurationSupport support = new ConfigurationSupport();

    @Test
    public void GIVEN_a_configuration_with_a_file_WHEN_marking_it_deleted_THEN_the_marker_carries_both() {
        Dictionary<String, Object> dictionary = new Hashtable<>();
        dictionary.put("service.pid", "org.jahia.bundles.api.authorization~sam");
        dictionary.put(FILENAME, "org.jahia.bundles.api.authorization-sam.yml");

        Properties marker = support.getDeletedConfigurationMarker(dictionary);

        assertEquals("org.jahia.bundles.api.authorization~sam", marker.get("service.pid"));
        assertEquals("org.jahia.bundles.api.authorization-sam.yml", marker.get(FILENAME));
        assertEquals(Boolean.TRUE, marker.get(REMOVED));
    }

    /**
     * push puts a new pid in the map without consulting canDistributeConfig, which only asks for a file name when
     * the configuration belongs to a factory. A singleton configuration that no file feeds therefore has an entry
     * with no karaf.cellar.filename, and marking it deleted used to throw on that entry.
     */
    @Test
    public void GIVEN_a_configuration_with_no_file_WHEN_marking_it_deleted_THEN_the_marker_leaves_it_out() {
        Dictionary<String, Object> dictionary = new Hashtable<>();
        dictionary.put("service.pid", "org.jahia.bundles.config");

        Properties marker = support.getDeletedConfigurationMarker(dictionary);

        assertEquals("org.jahia.bundles.config", marker.get("service.pid"));
        assertFalse(marker.containsKey(FILENAME));
        assertEquals(Boolean.TRUE, marker.get(REMOVED));
    }

    @Test
    public void GIVEN_an_entry_carrying_neither_WHEN_marking_it_deleted_THEN_the_marker_is_still_built() {
        Properties marker = support.getDeletedConfigurationMarker(new Hashtable<String, Object>());

        assertEquals(1, marker.size());
        assertEquals(Boolean.TRUE, marker.get(REMOVED));
    }

    @Test
    public void GIVEN_any_marker_WHEN_reading_it_THEN_it_is_not_replicated() {
        Dictionary<String, Object> dictionary = new Hashtable<>();
        dictionary.put("service.pid", "org.jahia.bundles.config");

        assertTrue(support.shouldReplicateConfig(dictionary));
        assertFalse(support.shouldReplicateConfig(support.getDeletedConfigurationMarker(dictionary)));
    }
}
