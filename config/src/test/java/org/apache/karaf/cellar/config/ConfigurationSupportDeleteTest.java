package org.apache.karaf.cellar.config;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.osgi.service.cm.Configuration;

import java.io.File;
import java.util.Dictionary;
import java.util.Hashtable;

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
     * Answers like Felix: a read of a deleted configuration throws IllegalStateException. deleteConfiguration
     * makes two of them, getPid and getProperties, and getFactoryPid is guarded against a third that does not
     * exist yet, because a read added on the wrong side of the delete is the defect this file catches. The rest
     * comes from StubConfiguration, so one hand-maintained implementation of Configuration in this package is
     * enough to keep up with what the OSGi Compendium adds, and its own answers are inert here.
     */
    private static class DeletableConfiguration extends StubConfiguration {

        private boolean deleted;

        private DeletableConfiguration(String pid, Dictionary<String, Object> properties) {
            super(pid, properties);
        }

        private void checkDeleted() {
            if (deleted) {
                throw new IllegalStateException("Configuration " + super.getPid() + " deleted");
            }
        }

        @Override
        public String getPid() {
            checkDeleted();
            return super.getPid();
        }

        @Override
        public Dictionary<String, Object> getProperties() {
            checkDeleted();
            return super.getProperties();
        }

        @Override
        public String getFactoryPid() {
            // deleteConfiguration does not read this today; the check is here so that adding such a read on the
            // wrong side of the delete fails here rather than in a cluster
            checkDeleted();
            return super.getFactoryPid();
        }

        @Override
        public void delete() {
            checkDeleted();
            deleted = true;
        }
    }
}
