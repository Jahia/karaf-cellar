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

                // Several entries can name one file, so the file has to be looked at before the pids that feed
                // it. See the method for what the index does and does not promise.
                Set<String> ambiguousFilenames = findAmbiguousFilenames(clusterConfigurations, group);

                // get configurations on the cluster to update local configurations
                for (String pid : clusterConfigurations.keySet()) {
                    // The key set is a snapshot and every node writes this map, so the entry can be gone by the
                    // time it is read. It is read twice on purpose: once to decide whether to take the monitor, and
                    // again while holding it, because a deletion marker written between the two has to be seen.
                    // Acting on the first read would apply a value the cluster has since replaced, and the update
                    // would publish it back over the marker.
                    Properties sampled = clusterConfigurations.get(pid);
                    if (sampled == null) {
                        LOGGER.debug("CELLAR CONFIG: configuration with PID {} was removed from cluster group {} while pulling", pid, groupName);
                    } else if (isAllowed(group, Constants.CATEGORY, pid, EventType.INBOUND) && shouldReplicateConfig(sampled)) {
                        synchronized (clusterConfigurations) {
                            Dictionary clusterDictionary = clusterConfigurations.get(pid);
                            if (clusterDictionary == null || !shouldReplicateConfig(clusterDictionary)) {
                                LOGGER.debug("CELLAR CONFIG: configuration with PID {} was removed or marked deleted in cluster group {} while pulling", pid, groupName);
                                continue;
                            }
                            // Applying this entry would write one file that other entries also describe
                            // differently, and the pid the map hands out last would decide the content. Not
                            // applying it leaves the file as this node read it, which is the current content on a
                            // node whose configuration store was just rebuilt. The push that follows publishes
                            // that content under the canonical pid. It does not remove the entries that disagree,
                            // so the refusal holds on every node and at every pull until something else removes
                            // them, which is the clustering module's map cleaner.
                            // The entry is skipped, never treated as absent: the cleanup below reads the map on
                            // its own and would delete the local configuration instead of leaving it alone.
                            // What makes not creating a local configuration safe here is outside this class, and
                            // outside Cellar. push() runs right after for the cluster sync policy, and neither of
                            // its two branches is guarded the way this one is: the create branch publishes the
                            // local dictionary with no content comparison at all, and its cluster cleanup removes
                            // any pid findLocalConfiguration cannot resolve, with no gate above it. So a node that
                            // reaches sync holding only its module's shipped default for an ambiguous file would
                            // publish that default and have every other node apply it, through an event handler
                            // that has no ambiguity guard either.
                            // The upgrade procedure is what prevents it. A node is restarted on the same data
                            // volume, so karaf/etc survives while the OSGi configuration store is rebuilt, and the
                            // file this node keeps is the one it already had. From here a rebuilt node and a node
                            // with a fresh disk are indistinguishable, so the procedure is the guarantee and the
                            // code cannot check it.
                            if (ambiguousFilenames.contains(clusterDictionary.get(KARAF_CELLAR_FILENAME))) {
                                LOGGER.debug("CELLAR CONFIG: configuration with PID {} names a file whose entries disagree in cluster group {}, so it is not applied", pid, groupName);
                                continue;
                            }
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
                                // Read the entry from the map again rather than re-testing the reference above.
                                // A put replaces the entry, it does not mutate the instance already held, so
                                // testing that instance sees nothing written since it was read, and the work
                                // between the two is a Configuration Admin lookup, a filter that reads the node
                                // configuration per key, and a file write. What this cannot close is a marker
                                // written on another node: the map is a Hazelcast ReplicatedMap, so that write
                                // is not visible here until replication lands, whichever line reads it.
                                // The entry has to be unchanged since it was read under the monitor, because what
                                // is applied below is that read and not this one. A newer value another node
                                // pushed would otherwise pass the gate and lose to the older one: the update
                                // fires a CM_UPDATED, and LocalConfigurationListener publishes the local value
                                // back over the newer entry. When it has changed this pull defers the pid, and
                                // the push that changed it produced a cluster event that brings it here anyway.
                                // areEquals is null-safe, and clusterDictionary is never a marker because it
                                // passed the guard above, so one term covers an entry that changed, one that is
                                // gone and one that is now a marker.
                                Properties current = clusterConfigurations.get(pid);
                                boolean unchanged = areEquals(clusterDictionary, current);
                                if (!unchanged) {
                                    LOGGER.debug("CELLAR CONFIG: configuration with PID {} no longer matches the entry read under the monitor in cluster group {}, deferring it to the next pull", pid, groupName);
                                }
                                if (!areEquals(clusterDictionary, localDictionary) && canDistributeConfig(localDictionary) && unchanged) {
                                    LOGGER.debug("CELLAR CONFIG: updating configration {} on node", pid);
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
                            Properties clusterDictionary = clusterConfigurations.get(pid);
                            if ((clusterDictionary == null || !shouldReplicateConfig(clusterDictionary)) && !filenames.contains(getKarafFilename(configuration.getProperties())) && isAllowed(group, Constants.CATEGORY, pid, EventType.INBOUND)) {
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
     * Answer the files that several cluster map entries describe differently.
     * <p>
     * An entry names the file it was read from in {@code karaf.cellar.filename}, and a node that mints a generated
     * pid for a factory configuration publishes its own entry for a file every other node also publishes. The pull
     * loop below iterates over pids, so it would apply each of those entries to the same local configuration in
     * turn and the pid read last would decide the content. The map is a Hazelcast ReplicatedMap with no ordering
     * contract on its entry set, which makes that a draw.
     * <p>
     * Only a disagreement is reported. Entries that carry the same content name one value, so applying them is
     * what the pull is for, and a map written before a file-backed factory configuration was named after its file
     * carries one entry per node for nearly every file. Refusing those as well would stop a node whose own file is
     * behind the cluster from receiving the shared value, and its push would then write that stale content back
     * into the map and raise a cluster event carrying it.
     * <p>
     * An entry that names no file is not a candidate. A singleton configuration carries no filename, and neither
     * does a factory configuration no file feeds, so there is no file for such an entry to be ambiguous about.
     * A deletion marker is not a candidate either, so a file with one live entry and a marker still applies.
     * {@code areEquals} ignores {@code service.pid}, which is what makes the comparison possible at all: two
     * entries of one file differ on that key by construction, since it is their key in the map.
     * <p>
     * The index is a snapshot, so an entry can appear or disappear before the loop reads it. A file counted with
     * one entry can have two by then, which is the behaviour this method exists to change and no worse than
     * today. A file counted with two can have one, and a pull that could have applied it skips it until the next
     * one. Reading the entry set once keeps that window as small as this method can make it.
     *
     * @param clusterConfigurations the cluster group's configuration map.
     * @param group the cluster group, for the inbound gate and for the log.
     * @return the filenames whose entries disagree, empty when none do.
     */
    private Set<String> findAmbiguousFilenames(Map<String, Properties> clusterConfigurations, Group group) {
        String groupName = group.getName();
        Map<String, Properties> candidateByFilename = new HashMap<String, Properties>();
        Set<String> ambiguousFilenames = new HashSet<String>();
        for (Map.Entry<String, Properties> entry : clusterConfigurations.entrySet()) {
            Properties candidate = entry.getValue();
            if (candidate == null || !shouldReplicateConfig(candidate)) {
                continue;
            }
            // The same gate the loop below applies to the same entry. An entry this node blocks
            // inbound is never applied, so it cannot take part in a draw, and counting it here
            // would make its file ambiguous and refuse the entry that is allowed. The node would
            // then never receive the value it is entitled to, at this pull or at any later one.
            if (!isAllowed(group, Constants.CATEGORY, entry.getKey(), EventType.INBOUND)) {
                continue;
            }
            // The key is read straight off the entry. getKarafFilename would filter the dictionary first, and
            // filter asks isExcludedProperty for every key, which reads the node configuration each time. A
            // cluster entry cannot carry felix.fileinstall.filename, because push, LocalConfigurationListener and
            // ConfigurationEventHandler all filter before they write, so filtering here would convert nothing.
            // findLocalConfiguration and the clustering module's map cleaner read the key the same way.
            String filename = (String) candidate.get(KARAF_CELLAR_FILENAME);
            if (filename == null) {
                continue;
            }
            // areEquals skips service.pid, and the whole rule rests on that: two entries of one file differ on
            // that key by construction, since it is their key in this map.
            Properties known = candidateByFilename.get(filename);
            if (known == null) {
                candidateByFilename.put(filename, candidate);
            } else if (!areEquals(known, candidate) && ambiguousFilenames.add(filename)) {
                LOGGER.warn("CELLAR CONFIG: the entries of {} in cluster group {} do not agree on its content, so none of them is applied. A node that holds the current file republishes it, and the next pull applies it once the entries agree.", filename, groupName);
            }
        }
        return ambiguousFilenames;
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
                try {
                    localConfigurations = configurationAdmin.listConfigurations(null);
                    // push local configurations to the cluster
                    for (Configuration localConfiguration : localConfigurations) {
                        String pid = localConfiguration.getPid();
                        // check if the pid is marked as local.
                        if (isAllowed(group, Constants.CATEGORY, pid, EventType.OUTBOUND)) {
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
