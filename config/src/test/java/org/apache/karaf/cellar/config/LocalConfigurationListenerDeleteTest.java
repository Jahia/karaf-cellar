package org.apache.karaf.cellar.config;

import org.apache.karaf.cellar.core.Group;
import org.apache.karaf.cellar.core.GroupManager;
import org.apache.karaf.cellar.core.Node;
import org.apache.karaf.cellar.core.control.BasicSwitch;
import org.apache.karaf.cellar.core.control.Switch;
import org.apache.karaf.cellar.core.control.SwitchStatus;
import org.apache.karaf.cellar.core.event.Event;
import org.apache.karaf.cellar.core.event.EventProducer;
import org.apache.karaf.cellar.core.event.EventType;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationEvent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers what a local deletion writes into the cluster map, and whether it is announced.
 * <p>
 * This is the reader whose failure is the quietest: a throw here writes no marker and produces no cluster event,
 * so no other node learns the configuration was deleted, and they keep it and push it back. The released version
 * threw on an entry that carries no file name, which is why this had no test to inherit.
 */
public class LocalConfigurationListenerDeleteTest {

    private static final String PID = "org.jahia.bundles.api.authorization~sam";
    private static final String FILENAME = "org.jahia.bundles.api.authorization-sam.yml";
    private static final String MAP = "org.apache.karaf.cellar.configuration.map.default";

    private Map<String, Properties> clusterMap;
    private List<Event> produced;
    private TestListener listener;

    @Before
    public void setUp() {
        clusterMap = new HashMap<>();
        produced = new ArrayList<>();
        listener = new TestListener(clusterMap, produced);
    }

    private static Properties entry(String pid, String filename, boolean removed) {
        Properties properties = new Properties();
        properties.put("service.pid", pid);
        if (filename != null) {
            properties.put("karaf.cellar.filename", filename);
        }
        if (removed) {
            properties.put("karaf.cellar.removed", true);
        }
        return properties;
    }

    @Test
    public void GIVEN_an_entry_a_file_feeds_WHEN_it_is_deleted_locally_THEN_every_entry_of_that_file_is_marked() {
        clusterMap.put(PID, entry(PID, FILENAME, false));
        clusterMap.put("org.jahia.bundles.api.authorization.0f4f8872", entry("org.jahia.bundles.api.authorization.0f4f8872", FILENAME, false));

        listener.configurationEvent(deletionOf(PID));

        for (Map.Entry<String, Properties> marked : clusterMap.entrySet()) {
            assertEquals("entry " + marked.getKey() + " was not marked deleted",
                    Boolean.TRUE, marked.getValue().get("karaf.cellar.removed"));
        }
        assertEquals("the deletion was not announced to the cluster", 1, produced.size());
    }

    /**
     * An entry with no karaf.cellar.filename is what push writes for a singleton configuration no file feeds, and
     * it is the entry the released version threw on. It answers for its own pid alone: matching every other entry
     * that also carries no file name would mark configurations that have nothing to do with it.
     */
    @Test
    public void GIVEN_an_entry_no_file_feeds_WHEN_it_is_deleted_locally_THEN_only_its_own_pid_is_marked() {
        clusterMap.put(PID, entry(PID, null, false));
        clusterMap.put("org.cortex.unrelated", entry("org.cortex.unrelated", null, false));

        listener.configurationEvent(deletionOf(PID));

        assertEquals(Boolean.TRUE, clusterMap.get(PID).get("karaf.cellar.removed"));
        assertNull("an unrelated configuration with no file name was marked too",
                clusterMap.get("org.cortex.unrelated").get("karaf.cellar.removed"));
        assertEquals(1, produced.size());
    }

    @Test
    public void GIVEN_an_entry_already_gone_from_the_map_WHEN_it_is_deleted_locally_THEN_nothing_is_announced() {
        listener.configurationEvent(deletionOf(PID));

        assertTrue("a marker was written for an entry the map no longer holds", clusterMap.isEmpty());
        assertEquals("a deletion nothing holds was announced to the cluster", 0, produced.size());
    }

    /**
     * A real CM_DELETED event. Its service reference leads to a bundle context that publishes no file installer,
     * so the loop the listener runs over them does nothing and the deletion handling is what the test observes.
     */
    private static ConfigurationEvent deletionOf(String pid) {
        return new ConfigurationEvent(referenceToAnEmptyContext(), ConfigurationEvent.CM_DELETED, null, pid);
    }

    @SuppressWarnings("unchecked")
    private static ServiceReference<ConfigurationAdmin> referenceToAnEmptyContext() {
        final InvocationHandler osgi = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                switch (method.getName()) {
                    case "getBundle":
                    case "getBundleContext":
                        return Proxy.newProxyInstance(
                                LocalConfigurationListenerDeleteTest.class.getClassLoader(),
                                new Class<?>[]{Bundle.class, BundleContext.class}, this);
                    case "getServiceReferences":
                        return new ArrayList<ServiceReference<?>>();
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            }
        };
        return (ServiceReference<ConfigurationAdmin>) Proxy.newProxyInstance(
                LocalConfigurationListenerDeleteTest.class.getClassLoader(),
                new Class<?>[]{ServiceReference.class}, osgi);
    }

    /** Answers the listener's collaborators without a container. */
    private static class TestListener extends LocalConfigurationListener {

        private final Map<String, Properties> map;

        private TestListener(Map<String, Properties> map, final List<Event> produced) {
            this.map = map;
            this.clusterManager = new StubClusterManager(map);
            // config.listener has to be on, or the listener returns before it reads anything
            this.configurationAdmin = new StubConfigurationAdmin() {
                @Override
                public org.osgi.service.cm.Configuration getConfiguration(String pid, String location) {
                    Hashtable<String, Object> node = new Hashtable<>();
                    node.put("config.listener", "true");
                    return new StubConfiguration(pid, node);
                }
            };
            this.groupManager = groupManagerOf("default");
            setEventProducer(new EventProducer<Event>() {
                @Override
                public void produce(Event event) {
                    produced.add(event);
                }

                @Override
                public Switch getSwitch() {
                    return new BasicSwitch("test", SwitchStatus.ON);
                }
            });
        }

        @Override
        public Boolean isAllowed(Group group, String category, String event, EventType type) {
            return Boolean.TRUE;
        }
    }

    private static GroupManager groupManagerOf(final String name) {
        return (GroupManager) Proxy.newProxyInstance(
                LocalConfigurationListenerDeleteTest.class.getClassLoader(),
                new Class<?>[]{GroupManager.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("listLocalGroups".equals(method.getName())) {
                            Set<Group> groups = new HashSet<>();
                            groups.add(new Group(name));
                            return groups;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
