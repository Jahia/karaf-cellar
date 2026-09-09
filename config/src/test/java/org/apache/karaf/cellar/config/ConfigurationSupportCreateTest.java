package org.apache.karaf.cellar.config;

import org.junit.Before;
import org.junit.Test;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Covers the pid a configuration receives when this node learns it from the cluster rather than from its own file.
 * <p>
 * aliasFromFilename being correct proves nothing on its own: what matters is that createLocalConfiguration uses it,
 * so that one file is one pid on every node. These tests fail if the alias is computed and then ignored.
 */
public class ConfigurationSupportCreateTest {

    private static final String FACTORY_PID = "org.jahia.bundles.api.authorization";
    private static final String FILENAME = FACTORY_PID + "-sam.yml";

    private RecordingConfigurationAdmin configurationAdmin;
    private ConfigurationSupport support;

    @Before
    public void setUp() {
        configurationAdmin = new RecordingConfigurationAdmin();
        support = new ConfigurationSupport();
        support.setConfigurationAdmin(configurationAdmin);
        support.setStorage(new File(System.getProperty("java.io.tmpdir")));
    }

    private static Dictionary<String, Object> clusterEntry(String filename) {
        Hashtable<String, Object> dictionary = new Hashtable<>();
        dictionary.put("service.factoryPid", FACTORY_PID);
        if (filename != null) {
            dictionary.put("karaf.cellar.filename", filename);
        }
        return dictionary;
    }

    @Test
    public void GIVEN_an_entry_whose_file_names_it_WHEN_creating_it_locally_THEN_the_pid_comes_from_that_name()
            throws Exception {
        Configuration created = support.createLocalConfiguration("whatever-pid-the-peer-used", clusterEntry(FILENAME));

        assertEquals("createLocalConfiguration did not name the configuration after its file",
                FACTORY_PID + "~sam", created.getPid());
        assertEquals("a generated pid was minted instead of the pid the file name gives",
                0, configurationAdmin.generated);
    }

    @Test
    public void GIVEN_an_entry_with_no_file_WHEN_creating_it_locally_THEN_a_generated_pid_is_still_used()
            throws Exception {
        support.createLocalConfiguration("whatever-pid-the-peer-used", clusterEntry(null));

        assertEquals("an entry no file feeds has no name to take, so it keeps a generated pid",
                1, configurationAdmin.generated);
    }

    @Test
    public void GIVEN_an_entry_whose_file_does_not_carry_the_factory_pid_WHEN_creating_it_locally_THEN_the_pid_is_generated()
            throws Exception {
        support.createLocalConfiguration("whatever-pid-the-peer-used", clusterEntry("something-else.yml"));

        assertEquals(1, configurationAdmin.generated);
    }

    /**
     * filter() reduces karaf.cellar.filename to a base name, and that reduction is the single guarantee
     * aliasFromFilename is handed a name and not a URI. Without it every alias is null and every pid generated.
     */
    @Test
    public void GIVEN_a_file_name_that_is_a_path_WHEN_filtering_it_THEN_only_the_base_name_is_kept() {
        Properties source = new Properties();
        source.put("felix.fileinstall.filename", "file:/var/jahia/karaf/etc/" + FILENAME);

        assertEquals(FILENAME, support.filter(source).get("karaf.cellar.filename"));
    }

    /**
     * Records whether a generated pid was minted, answers the named one, and refuses everything else so a test
     * that starts depending on a method nobody thought about fails here rather than somewhere further away.
     */
    private static class RecordingConfigurationAdmin implements ConfigurationAdmin {

        private int generated;

        @Override
        public Configuration createFactoryConfiguration(String factoryPid, String location) {
            generated++;
            return named(factoryPid + ".0f4f8872-generated");
        }

        @Override
        public Configuration getFactoryConfiguration(String factoryPid, String name, String location) {
            return named(factoryPid + "~" + name);
        }

        @Override
        public Configuration createFactoryConfiguration(String factoryPid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Configuration getConfiguration(String pid, String location) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Configuration getConfiguration(String pid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Configuration getFactoryConfiguration(String factoryPid, String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Configuration[] listConfigurations(String filter) {
            return null;
        }

        /** A Configuration that answers its pid and refuses the rest. */
        private static Configuration named(final String pid) {
            return (Configuration) Proxy.newProxyInstance(
                    ConfigurationSupportCreateTest.class.getClassLoader(),
                    new Class<?>[]{Configuration.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("getPid".equals(method.getName())) {
                                return pid;
                            }
                            throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }
}
