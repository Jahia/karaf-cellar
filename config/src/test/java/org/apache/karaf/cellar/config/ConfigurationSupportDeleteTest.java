package org.apache.karaf.cellar.config;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;

import java.io.File;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the deletion of a local configuration, and the order in which it reads what it needs.
 * <p>
 * Configuration Admin answers nothing about a configuration that is gone: Felix checks for the deletion in getPid
 * as well as in getProperties, and throws IllegalStateException. The stub below does the same, so a method that
 * reads the configuration after deleting it fails this test rather than the cluster.
 */
public class ConfigurationSupportDeleteTest {

    @Rule
    public TemporaryFolder storage = new TemporaryFolder();

    private ConfigurationSupport support;

    @Before
    public void setUp() {
        support = new ConfigurationSupport();
        support.setStorage(storage.getRoot());
    }

    @Test
    public void GIVEN_a_configuration_a_file_feeds_WHEN_deleting_it_THEN_the_file_goes_with_it() throws Exception {
        File file = storage.newFile("org.jahia.bundles.api.authorization-sam.yml");
        Dictionary<String, Object> properties = new Hashtable<>();
        properties.put("service.pid", "org.jahia.bundles.api.authorization~sam");
        properties.put("karaf.cellar.filename", file.getName());
        DeletableConfiguration configuration =
                new DeletableConfiguration("org.jahia.bundles.api.authorization~sam", properties);

        support.deleteConfiguration(configuration);

        assertTrue(configuration.deleted);
        assertFalse(file.exists());
    }

    /**
     * A configuration no file feeds has no karaf.cellar.filename, so the file to remove is named after its pid.
     * Reading that pid after the deletion threw, which reached the caller's catch and logged the failure of a
     * deletion that had in fact happened.
     */
    @Test
    public void GIVEN_a_configuration_no_file_feeds_WHEN_deleting_it_THEN_nothing_reads_it_afterwards() throws Exception {
        File guessed = storage.newFile("org.cortex.singleton.cfg");
        Dictionary<String, Object> properties = new Hashtable<>();
        properties.put("service.pid", "org.cortex.singleton");
        DeletableConfiguration configuration = new DeletableConfiguration("org.cortex.singleton", properties);

        support.deleteConfiguration(configuration);

        assertTrue(configuration.deleted);
        assertFalse(guessed.exists());
    }

    @Test
    public void GIVEN_a_configuration_whose_file_is_already_gone_WHEN_deleting_it_THEN_it_is_still_deleted() throws Exception {
        Dictionary<String, Object> properties = new Hashtable<>();
        properties.put("service.pid", "org.cortex.singleton");
        properties.put("karaf.cellar.filename", "never-written.yml");
        DeletableConfiguration configuration = new DeletableConfiguration("org.cortex.singleton", properties);

        support.deleteConfiguration(configuration);

        assertTrue(configuration.deleted);
    }

    /**
     * Answers like Felix: every read of a deleted configuration throws IllegalStateException.
     */
    private static class DeletableConfiguration implements Configuration {

        private final String pid;
        private final Dictionary<String, Object> properties;
        private boolean deleted;

        private DeletableConfiguration(String pid, Dictionary<String, Object> properties) {
            this.pid = pid;
            this.properties = properties;
        }

        private void checkDeleted() {
            if (deleted) {
                throw new IllegalStateException("Configuration " + pid + " deleted");
            }
        }

        @Override
        public String getPid() {
            checkDeleted();
            return pid;
        }

        @Override
        public Dictionary<String, Object> getProperties() {
            checkDeleted();
            return properties;
        }

        @Override
        public void delete() {
            checkDeleted();
            deleted = true;
        }

        @Override
        public String getFactoryPid() {
            checkDeleted();
            return null;
        }

        // Nothing below is reached by deleteConfiguration. They fail loudly rather than quietly, so a later
        // change that starts calling one of them shows up here instead of passing.

        @Override
        public Dictionary<String, Object> getProcessedProperties(ServiceReference<?> reference) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update(Dictionary<String, ?> properties) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean updateIfDifferent(Dictionary<String, ?> properties) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setBundleLocation(String location) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getBundleLocation() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getChangeCount() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addAttributes(ConfigurationAttribute... attributes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<ConfigurationAttribute> getAttributes() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeAttributes(ConfigurationAttribute... attributes) {
            throw new UnsupportedOperationException();
        }
    }
}
