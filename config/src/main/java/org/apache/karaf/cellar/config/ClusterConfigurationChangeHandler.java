/*
 * ==========================================================================================
 * =                            JAHIA'S ENTERPRISE DISTRIBUTION                             =
 * ==========================================================================================
 *
 *                                  http://www.jahia.com
 *
 * JAHIA'S ENTERPRISE DISTRIBUTIONS LICENSING - IMPORTANT INFORMATION
 * ==========================================================================================
 *
 *     Copyright (C) 2002-2024 Jahia Solutions Group. All rights reserved.
 *
 *     This file is part of a Jahia's Enterprise Distribution.
 *
 *     Jahia's Enterprise Distributions must be used in accordance with the terms
 *     contained in the Jahia Solutions Group Terms &amp; Conditions as well as
 *     the Jahia Sustainable Enterprise License (JSEL).
 *
 *     For questions regarding licensing, support, production usage...
 *     please contact our team at sales@jahia.com or go to http://www.jahia.com/license.
 *
 * ==========================================================================================
 */
package org.apache.karaf.cellar.config;

import org.apache.karaf.cellar.core.Configurations;
import org.apache.karaf.cellar.core.Group;
import org.apache.karaf.cellar.core.control.BasicSwitch;
import org.apache.karaf.cellar.core.control.Switch;
import org.apache.karaf.cellar.core.control.SwitchStatus;
import org.apache.karaf.cellar.core.event.EventType;
import org.apache.karaf.cellar.core.listener.ClusterMapListener;
import org.osgi.service.cm.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * @author Jerome Blanchard
 */
public class ClusterConfigurationChangeHandler extends ConfigurationSupport implements ClusterMapListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClusterConfigurationChangeHandler.class);

    public static final String SWITCH_ID = "org.apache.karaf.cellar.configuration.handler";

    private final Switch eventSwitch = new BasicSwitch(SWITCH_ID);

    private String id;

    public void init() {
        Set<Group> groups = groupManager.listLocalGroups();
        for (Group group : groups) {
            clusterManager.addMapListener(Constants.CONFIGURATION_MAP + Configurations.SEPARATOR + group.getName(), this);
        }
    }

    public void destroy() {
        Set<Group> groups = groupManager.listLocalGroups();
        for (Group group : groups) {
            clusterManager.removeMapListener(Constants.CONFIGURATION_MAP + Configurations.SEPARATOR + group.getName(), this.id);
        }
    }

    @Override public void handle(String mapName, Object key, ActionType action) {
        LOGGER.info("CELLAR CONFIG: cluster map entry {} modified in Hazelcast map {} by action {}", key, mapName, action);
        if (this.getSwitch().getStatus().equals(SwitchStatus.OFF)) {
            LOGGER.debug("CELLAR CONFIG: {} switch is OFF, entry modification not handled", SWITCH_ID);
            return;
        }
        if (groupManager == null) {
            //in rare cases for example right after installation this happens!
            LOGGER.error("CELLAR CONFIG: received entry modification {} while groupManager is not available yet!");
            return;
        }

        String groupName = parseGroupName(mapName);
        if (!groupManager.isLocalGroup(groupName)) {
            LOGGER.debug("CELLAR CONFIG: node is not part of the cluster groups {}", groupName);
            return;
        }

        String pid = (String) key;
        if (isAllowed(groupName, Constants.CATEGORY, pid, EventType.INBOUND)) {
            Map<String, Properties> clusterConfigurations = clusterManager.getMap(mapName);
            synchronized (clusterConfigurations) {
                Dictionary clusterDictionary = clusterConfigurations.get(pid);
                LOGGER.debug("Received entry modification for configuration {} , cluster data : {}", pid, Collections.list(clusterDictionary.keys()));

                try {
                    // update the local configuration
                    Configuration localConfiguration = findLocalConfiguration(pid, clusterDictionary);
                    if (action == ActionType.REMOVE) {
                        // delete the configuration
                        if (localConfiguration != null) {
                            LOGGER.debug("Local config found, deleting it - pid = {}, from {}", localConfiguration.getPid(), pid);
                            deleteConfiguration(localConfiguration);
                        }
                    } else {
                        if (clusterDictionary != null && shouldReplicateConfig(clusterDictionary)) {
                            if (localConfiguration == null) {
                                // Create new configuration
                                localConfiguration = createLocalConfiguration(pid, clusterDictionary);
                                LOGGER.debug("Local config created - local pid: {}, from {} ", localConfiguration.getPid(), pid);
                            } else {
                                LOGGER.debug("Local config found, updating - pid = {}, from {}", localConfiguration.getPid(), pid);
                            }
                            Dictionary localDictionary = localConfiguration.getProperties();
                            if (localDictionary == null) {
                                localDictionary = new Properties();
                            }
                            localDictionary = filter(localDictionary);
                            if (!equals(clusterDictionary, localDictionary) && canDistributeConfig(localDictionary)) {
                                persistConfiguration(localConfiguration.getPid(), localConfiguration.getProperties(), clusterDictionary);
                                Dictionary convertedDictionary = convertPropertiesFromCluster(clusterDictionary);
                                if (!localConfiguration.getPid().equals(pid)) {
                                    Properties p = dictionaryToProperties(filter(convertedDictionary));
                                    LOGGER.debug("Storing factory configuration local pid: {}, from {} : {}", localConfiguration.getProperties(), pid, Collections.list(localDictionary.keys()));
                                    clusterConfigurations.put(localConfiguration.getPid(), p);
                                }
                                localConfiguration.update(convertedDictionary);
                            }
                        }
                    }
                } catch (Exception ex) {
                    LOGGER.error("CELLAR CONFIG: failed to read cluster configuration", ex);
                }
            }
        } else LOGGER.trace("CELLAR CONFIG: configuration PID {} is marked BLOCKED INBOUND for cluster group {}", pid, groupName);


    }

    @Override
    public String getId() {
        return this.id;
    }

    @Override public void setId(String id) {
        this.id = id;
    }

    /**
     * Get the cluster configuration listener switch.
     *
     * @return the cluster configuration listener switch.
     */
    public Switch getSwitch() {
        // load the switch status from the config
        try {
            Configuration configuration = configurationAdmin.getConfiguration(Configurations.NODE, null);
            if (configuration != null) {
                Boolean status = new Boolean((String) configuration.getProperties().get(Configurations.LISTENER + "." + this.getClass().getName()));
                if (status) {
                    eventSwitch.turnOn();
                } else {
                    eventSwitch.turnOff();
                }
            }
        } catch (Exception e) {
            // nothing to do
        }
        return eventSwitch;
    }

    private String parseGroupName(String mapName) {
        return mapName.substring(Constants.CONFIGURATION_MAP.length() + Configurations.SEPARATOR.length());
    }
}
