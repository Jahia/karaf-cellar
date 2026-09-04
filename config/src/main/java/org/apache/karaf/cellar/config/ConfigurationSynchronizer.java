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

import org.apache.karaf.cellar.core.Configurations;
import org.apache.karaf.cellar.core.Group;
import org.apache.karaf.cellar.core.Node;
import org.apache.karaf.cellar.core.Synchronizer;
import org.apache.karaf.cellar.core.control.SwitchStatus;
import org.apache.karaf.cellar.core.event.EventProducer;
import org.apache.karaf.cellar.core.event.EventType;
import org.apache.karaf.cellar.core.utils.CellarUtils;
import org.apache.karaf.features.BootFinished;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.cm.Configuration;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * The ConfigurationSynchronizer is called when Cellar starts or when a node joins a cluster group.
 * The purpose is to synchronize local configurations with the configurations in the cluster groups.
 */
public class ConfigurationSynchronizer extends ConfigurationSupport implements Synchronizer {

    private static final transient Logger LOGGER = LoggerFactory.getLogger(ConfigurationSynchronizer.class);

    private EventProducer eventProducer;

    public void setEventProducer(EventProducer eventProducer) {
        this.eventProducer = eventProducer;
    }

    public void init(BundleContext bundleContext) {
        // wait the end of Karaf boot process
        ServiceTracker tracker = new ServiceTracker(bundleContext, BootFinished.class, null);
        try {
            tracker.waitForService(120000);
        } catch (Exception e) {
            LOGGER.warn("Can't start BootFinished service tracker", e);
        }
        if (groupManager == null)
            return;
        Set<Group> groups = groupManager.listLocalGroups();
        if (groups != null && !groups.isEmpty()) {
            for (Group group : groups) {
                sync(group);
            }
        }
    }

    public void destroy() {
        // nothing to do
    }

    /**
     * Sync node and cluster states, depending of the sync policy.
     *
     * @param group the target cluster group.
     */
    @Override
    public void sync(Group group) {
        String policy = getSyncPolicy(group);
        if (policy == null) {
            LOGGER.warn("CELLAR CONFIG: sync policy is not defined for cluster group {}", group.getName());
        } else if (policy.equalsIgnoreCase("cluster")) {
            LOGGER.debug("CELLAR CONFIG: sync policy set as 'cluster' for cluster group {}", group.getName());
            LOGGER.debug("CELLAR CONFIG: updating node from the cluster (pull first)");
            pull(group);
            LOGGER.debug("CELLAR CONFIG: node is the first one in the cluster group, no pull");
            LOGGER.debug("CELLAR CONFIG: updating cluster from the local node (push after)");
            push(group);
        } else if (policy.equalsIgnoreCase("node")) {
            LOGGER.debug("CELLAR CONFIG: sync policy set as 'node' for cluster group {}", group.getName());
            LOGGER.debug("CELLAR CONFIG: updating cluster from the local node (push first)");
            push(group);
            LOGGER.debug("CELLAR CONFIG: updating node from the cluster (pull after)");
            pull(group);
        } else if (policy.equalsIgnoreCase("clusterOnly")) {
            LOGGER.debug("CELLAR CONFIG: sync policy set as 'clusterOnly' for cluster group " + group.getName());
            LOGGER.debug("CELLAR CONFIG: updating node from the cluster (pull only)");
            pull(group);
            LOGGER.debug("CELLAR CONFIG: node is the first one in the cluster group, no pull");
        } else if (policy.equalsIgnoreCase("nodeOnly")) {
            LOGGER.debug("CELLAR CONFIG: sync policy set as 'nodeOnly' for cluster group " + group.getName());
            LOGGER.debug("CELLAR CONFIG: updating cluster from the local node (push only)");
            push(group);
        } else {
            LOGGER.debug("CELLAR CONFIG: sync policy set as 'disabled' for cluster group " + group.getName());
            LOGGER.debug("CELLAR CONFIG: no sync");
        }
    }

    /**
     * Pull the configuration from a cluster group to update the local ones.
     *
     * @param group the cluster group where to get the configurations.
     */
    public void pull(Group group) {
        if (group != null) {
            String groupName = group.getName();
            LOGGER.debug("CELLAR CONFIG: pulling configurations from cluster group {}", groupName);

            Map<String, Properties> clusterConfigurations = clusterManager.getMap(Constants.CONFIGURATION_MAP + Configurations.SEPARATOR + groupName);

            ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

                // drop the entries no node holds, before any of them is applied to a local configuration
                Set<String> unheld;
                synchronized (clusterConfigurations) {
                    declareHeldPids(groupName, listHeldPids(group));
                    unheld = collectUnheldConfigurations(group, clusterConfigurations);
                }

                // get configurations on the cluster to update local configurations
                for (String pid : clusterConfigurations.keySet()) {
                    if (unheld.contains(pid)) {
                        // kept in the map only so that the file name still has an entry, never applied: no node
                        // holds this pid, so nothing vouches for what it carries
                        LOGGER.info("CELLAR CONFIG: not applying cluster entry {}, no node of cluster group {} holds "
                                + "a configuration with that pid", pid, groupName);
                    } else if (isAllowed(group, Constants.CATEGORY, pid, EventType.INBOUND) && shouldReplicateConfig(clusterConfigurations.get(pid))) {
                        synchronized (clusterConfigurations) {
                            Dictionary clusterDictionary = clusterConfigurations.get(pid);
                            try {
                                // update the local configuration if needed
                                Configuration localConfiguration = findLocalConfiguration(pid, clusterDictionary);
                                if (localConfiguration == null) {
                                    // Create new configuration
                                    localConfiguration = createLocalConfiguration(pid, clusterDictionary);
                                }
                                Dictionary localDictionary = localConfiguration.getProperties();
                                if (localDictionary == null)
                                    localDictionary = new Properties();

                                localDictionary = filter(localDictionary);
                                if (!areEquals(clusterDictionary, localDictionary) && canDistributeConfig(localDictionary) && shouldReplicateConfig(clusterDictionary)) {
                                    LOGGER.debug("CELLAR CONFIG: updating configration {} on node", pid);
                                    if (!pid.equals(localConfiguration.getPid())) {
                                        LOGGER.info("CELLAR CONFIG: cluster entry {} is overwriting the local "
                                                + "configuration {} of {}. The two pids name the same file, so what "
                                                + "this node wrote at startup is being replaced by a copy another "
                                                + "node published.", pid, localConfiguration.getPid(),
                                                getKarafFilename(clusterDictionary));
                                    }
                                    Dictionary convertedDictionary = convertPropertiesFromCluster(clusterDictionary);
                                    persistConfiguration(localConfiguration.getPid(), localConfiguration.getProperties(), clusterDictionary);
                                    localConfiguration.update(convertedDictionary);
                                }
                            } catch (IOException ex) {
                                LOGGER.error("CELLAR CONFIG: failed to read local configuration", ex);
                            }
                        }
                    } else  LOGGER.trace("CELLAR CONFIG: configuration with PID {} is marked BLOCKED INBOUND for cluster group {}", pid, groupName);
                }
                // cleanup the local configurations not present on the cluster if the node is not the first one in the cluster
                if (CellarUtils.doCleanupResourcesNotPresentInCluster(configurationAdmin) && getSynchronizerMap().containsKey(Constants.CONFIGURATION_MAP + Configurations.SEPARATOR + groupName)) {
                    try {
                        Set<String> filenames = new HashSet();
                        for (Properties configuration : clusterConfigurations.values()) {
                            if (shouldReplicateConfig(configuration)) {
                                filenames.add(getKarafFilename(configuration));
                            }
                        }
                        filenames.remove(null);
                        for (Configuration configuration : configurationAdmin.listConfigurations(null)) {
                            String pid = configuration.getPid();
                            if ((!clusterConfigurations.containsKey(pid) || !shouldReplicateConfig(clusterConfigurations.get(pid))) && !filenames.contains(getKarafFilename(configuration.getProperties())) && isAllowed(group, Constants.CATEGORY, pid, EventType.INBOUND)) {
                                LOGGER.debug("CELLAR CONFIG: deleting local configuration {} which is not present in cluster", pid);
                                deleteConfiguration(configuration);
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Can't get local configurations", e);
                    }
                }
            } catch (Exception ex) {
                LOGGER.error("CELLAR CONFIG: failed to read cluster configuration", ex);
            } finally {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
            }
        }
    }

    /**
     * Drop the entries of the cluster configuration map that no node of the cluster group holds.
     * <p>
     * A configuration read from a file gets a pid that Felix generates, so the same file is a different pid on every
     * node and on every container incarnation. The cluster map is keyed by pid, so one {@code karaf.cellar.filename}
     * ends up with one entry per (node, incarnation). An entry left behind by an incarnation that no longer exists is
     * never rewritten, so it keeps the content that was current when that incarnation last wrote it, and
     * {@link #pull(Group)} applies every entry that resolves to the same local configuration, in no defined order.
     * A configuration from weeks ago can therefore be reinstated on every node at a restart.
     * <p>
     * Each node declares, in {@link Constants#CONFIGURATION_HELD_PIDS_MAP}, the pids it holds, and rewrites that
     * declaration in full at every {@link #push(Group)}. An entry is collected when its pid is in no declaration of
     * any node currently in the cluster group. This asks the question directly rather than through the identity of
     * the publishing node, which a replaced container inherits whenever the node address is stable.
     * <p>
     * Nothing is collected while any node of the group has no declaration at all. That is the state of a cluster
     * running mixed versions during a rolling upgrade, where a node on the older code declares nothing and its
     * entries would otherwise be read as entries no node holds. The collector therefore starts working once every
     * node runs this code, and is inert until then.
     * <p>
     * The last entry carrying a given {@code karaf.cellar.filename} is never collected, because
     * {@code org.apache.karaf.cellar.cleanupResourcesNotPresentInCluster} would then delete the local configuration
     * of every node at its next boot.
     * <p>
     * Removing an entry from the map raises no cluster event, so no local configuration is touched.
     *
     * @param group the cluster group.
     * @param clusterConfigurations the configuration map of that cluster group.
     */
    protected Set<String> collectUnheldConfigurations(Group group, Map<String, Properties> clusterConfigurations) {
        String groupName = group.getName();
        Map<String, String> declarations = getHeldPidsMap(groupName);
        Set<String> kept = new LinkedHashSet<String>();

        Set<String> held = new HashSet<String>();
        Set<String> silent = new LinkedHashSet<String>();
        for (Node node : listGroupMembers(group)) {
            String declaration = declarations.get(node.getId());
            if (declaration == null) {
                silent.add(node.getId());
            } else {
                held.addAll(readHeldPids(declaration));
            }
        }
        if (!silent.isEmpty()) {
            LOGGER.info("CELLAR CONFIG: not collecting entries of cluster group {}, because node(s) {} have not "
                    + "declared which configurations they hold. This is the expected state of a cluster running "
                    + "mixed versions.", groupName, silent);
            return kept;
        }

        // one pass to read each entry's file name, which is what the two rules below are expressed in
        Map<String, String> filenames = new LinkedHashMap<String, String>();
        Map<String, Integer> entriesPerFilename = new HashMap<String, Integer>();
        for (Map.Entry<String, Properties> entry : clusterConfigurations.entrySet()) {
            Properties properties = entry.getValue();
            String filename = getKarafFilename(properties);
            if (filename == null || !shouldReplicateConfig(properties)) {
                // a configuration that is not file backed carries one pid for the whole cluster, and a deletion
                // marker has to survive until every node has applied it
                continue;
            }
            filenames.put(entry.getKey(), filename);
            Integer count = entriesPerFilename.get(filename);
            entriesPerFilename.put(filename, count == null ? 1 : count + 1);
        }

        for (Map.Entry<String, String> entry : filenames.entrySet()) {
            String pid = entry.getKey();
            String filename = entry.getValue();
            if (held.contains(pid)) {
                continue;
            }
            Integer remaining = entriesPerFilename.get(filename);
            if (remaining == null || remaining <= 1) {
                LOGGER.info("CELLAR CONFIG: keeping cluster entry {}, which no node holds, because it is the last "
                        + "one carrying {}", pid, filename);
                kept.add(pid);
                continue;
            }
            entriesPerFilename.put(filename, remaining - 1);
            clusterConfigurations.remove(pid);
            LOGGER.warn("CELLAR CONFIG: removed cluster entry {} of {} from cluster group {}, no node of the group "
                    + "holds a configuration with that pid", pid, filename, groupName);
        }

        warnOnDivergentEntries(clusterConfigurations, filenames);
        return kept;
    }

    /**
     * @param group the cluster group.
     * @return the pids of the local configurations this node publishes to that cluster group.
     */
    protected Set<String> listHeldPids(Group group) {
        Set<String> pids = new LinkedHashSet<String>();
        try {
            Configuration[] localConfigurations = configurationAdmin.listConfigurations(null);
            if (localConfigurations != null) {
                for (Configuration localConfiguration : localConfigurations) {
                    String pid = localConfiguration.getPid();
                    if (isAllowed(group, Constants.CATEGORY, pid, EventType.OUTBOUND)) {
                        pids.add(pid);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("CELLAR CONFIG: failed to list local configurations", e);
        }
        return pids;
    }

    /**
     * @param group the cluster group.
     * @return the nodes currently in that cluster group.
     */
    protected Set<Node> listGroupMembers(Group group) {
        return clusterManager.listNodesByGroup(group);
    }

    /**
     * Warn when several entries carry one file name and they do not all publish the same content.
     * <p>
     * Entries that agree are harmless, whichever order {@link #pull(Group)} applies them in. Entries that disagree
     * make the outcome of a synchronization depend on the iteration order of the cluster map rather than on the file.
     *
     * @param clusterConfigurations the configuration map of the cluster group.
     * @param filenames the file name of each file backed entry, as read by the caller.
     */
    private void warnOnDivergentEntries(Map<String, Properties> clusterConfigurations, Map<String, String> filenames) {
        Map<String, Set<String>> contents = new LinkedHashMap<String, Set<String>>();
        for (Map.Entry<String, String> entry : filenames.entrySet()) {
            Properties properties = clusterConfigurations.get(entry.getKey());
            Object content = properties == null ? null : properties.get(KARAF_CELLAR_CONTENT);
            if (content == null) {
                continue;
            }
            Set<String> published = contents.get(entry.getValue());
            if (published == null) {
                published = new HashSet<String>();
                contents.put(entry.getValue(), published);
            }
            published.add(content.toString());
        }
        for (Map.Entry<String, Set<String>> entry : contents.entrySet()) {
            if (entry.getValue().size() > 1) {
                LOGGER.warn("CELLAR CONFIG: {} different contents are published for {}. They are applied in no "
                        + "defined order and the last one wins, so what this node ends up with is not determined by "
                        + "the file itself.", entry.getValue().size(), entry.getKey());
            }
        }
    }

    /**
     * Push local configurations to a cluster group.
     *
     * @param group the cluster group where to update the configurations.
     */
    public void push(Group group) {

        if (eventProducer.getSwitch().getStatus().equals(SwitchStatus.OFF)) {
            LOGGER.warn("CELLAR CONFIG: cluster event producer is OFF");
            return;
        }

        if (group != null) {
            String groupName = group.getName();
            LOGGER.debug("CELLAR CONFIG: pushing configurations to cluster group {}", groupName);
            Map<String, Properties> clusterConfigurations = clusterManager.getMap(Constants.CONFIGURATION_MAP + Configurations.SEPARATOR + groupName);

            ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
                Configuration[] localConfigurations;
                Set<String> heldPids = new LinkedHashSet<String>();
                try {
                    localConfigurations = configurationAdmin.listConfigurations(null);
                    // push local configurations to the cluster
                    for (Configuration localConfiguration : localConfigurations) {
                        String pid = localConfiguration.getPid();
                        // check if the pid is marked as local.
                        if (isAllowed(group, Constants.CATEGORY, pid, EventType.OUTBOUND)) {
                            heldPids.add(pid);
                            synchronized (clusterConfigurations) {
                                Dictionary localDictionary = localConfiguration.getProperties();
                                localDictionary = filter(localDictionary);
                                if (!clusterConfigurations.containsKey(pid)) {
                                    LOGGER.debug("CELLAR CONFIG: creating configuration pid {} on the cluster: {}", pid, Collections.list(localDictionary.keys()));
                                    // update cluster configurations
                                    Properties props = dictionaryToProperties(localDictionary);
                                    clusterConfigurations.put(pid, props);
                                    // send cluster event
                                    ClusterConfigurationEvent event = new ClusterConfigurationEvent(pid);
                                    event.setSourceGroup(group);
                                    event.setSourceNode(clusterManager.getNode());
                                    event.setLocal(clusterManager.getNode());
                                    event.setIntegrity(hash(props));
                                    eventProducer.produce(event);
                                } else {
                                    Dictionary clusterDictionary = clusterConfigurations.get(pid);
                                    if (!areEquals(clusterDictionary, localDictionary) && canDistributeConfig(localDictionary)) {
                                        LOGGER.debug("CELLAR CONFIG: updating configuration pid {} on the cluster", pid);
                                        // update cluster configurations
                                        Properties props = dictionaryToProperties(localDictionary);
                                        clusterConfigurations.put(pid, props);
                                        // send cluster event
                                        ClusterConfigurationEvent event = new ClusterConfigurationEvent(pid);
                                        event.setSourceGroup(group);
                                        event.setLocal(clusterManager.getNode());
                                        event.setSourceNode(clusterManager.getNode());
                                        event.setIntegrity(hash(props));
                                        eventProducer.produce(event);
                                    }
                                }
                            }
                        } else {
                            LOGGER.trace("CELLAR CONFIG: configuration with PID {} is marked BLOCKED OUTBOUND for cluster group {}", pid, groupName);
                        }
                    }
                    // clean configurations on the cluster not present locally
                    for (String pid : clusterConfigurations.keySet()) {
                        if (isAllowed(group, Constants.CATEGORY, pid, EventType.OUTBOUND)) {
                            if (findLocalConfiguration(pid, clusterConfigurations.get(pid)) == null) {
                                clusterConfigurations.remove(pid);
                            }
                        }
                    }
                    declareHeldPids(groupName, heldPids);
                    getSynchronizerMap().putIfAbsent(Constants.CONFIGURATION_MAP + Configurations.SEPARATOR + groupName, true);
                } catch (IOException ex) {
                    LOGGER.error("CELLAR CONFIG: failed to read configuration (IO error)", ex);
                } catch (InvalidSyntaxException ex) {
                    LOGGER.error("CELLAR CONFIG: failed to read configuration (invalid filter syntax)", ex);
                }
            } finally {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
            }
        }
    }

    /**
     * Get the configuration sync policy for the given cluster group.
     *
     * @param group the cluster group.
     * @return the current configuration sync policy for the given cluster group.
     */
    @Override
    public String getSyncPolicy(Group group) {
        String groupName = group.getName();
        try {
            Configuration configuration = configurationAdmin.getConfiguration(Configurations.GROUP, null);
            Dictionary<String, Object> properties = configuration.getProperties();
            if (properties != null) {
                String propertyKey = groupName + Configurations.SEPARATOR + Constants.CATEGORY + Configurations.SEPARATOR + Configurations.SYNC;
                if (properties.get(propertyKey) != null) {
                    return properties.get(propertyKey).toString();
                }
            }
        } catch (IOException e) {
            LOGGER.error("CELLAR CONFIG: error while retrieving the sync policy", e);
        }

        return null;
    }

}
