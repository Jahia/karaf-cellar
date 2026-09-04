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
import org.apache.karaf.cellar.core.control.BasicSwitch;
import org.apache.karaf.cellar.core.control.Switch;
import org.apache.karaf.cellar.core.control.SwitchStatus;
import org.apache.karaf.cellar.core.event.EventHandler;
import org.apache.karaf.cellar.core.event.EventType;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Dictionary;
import java.util.Map;
import java.util.Properties;

/**
 * ConfigurationEventHandler handles received configuration cluster event.
 */
public class ConfigurationEventHandler extends ConfigurationSupport implements EventHandler<ClusterConfigurationEvent> {

    private static final transient Logger LOGGER = LoggerFactory.getLogger(ConfigurationEventHandler.class);

    public static final String SWITCH_ID = "org.apache.karaf.cellar.configuration.handler";
    public static final int INTEGRITY_TRY_COUNT_DEFAULT = 5;
    public static final String INTEGRITY_TRY_COUNT_PROP = "config.integrityCheck.retryCount";
    public static final int INTEGRITY_TRY_INTERVAL_DEFAULT = 100;
    public static final String INTEGRITY_TRY_INTERVAL_PROP = "config.integrityCheck.retryIntervalMS";

    private final Switch eventSwitch = new BasicSwitch(SWITCH_ID);

    private static final Object monitor = new Object();

    @Override
    public void handle(ClusterConfigurationEvent event) {
        // check if the handler is ON
        if (this.getSwitch().getStatus().equals(SwitchStatus.OFF)) {
            LOGGER.debug("CELLAR CONFIG: {} switch is OFF, cluster event not handled", SWITCH_ID);
            return;
        }

        if (groupManager == null) {
        	//in rare cases for example right after installation this happens!
        	LOGGER.error("CELLAR CONFIG: retrieved event {} while groupManager is not available yet!", event);
        	return;
        }

        // check if the group is local
        if (!groupManager.isLocalGroup(event.getSourceGroup().getName())) {
            LOGGER.debug("CELLAR CONFIG: node is not part of the event cluster group {}",event.getSourceGroup().getName());
            return;
        }

        // check if it's not a "local" event
        if (event.getLocal() != null && event.getLocal().getId().equalsIgnoreCase(clusterManager.getNode().getId())) {
            LOGGER.trace("CELLAR CONFIG: cluster event is local (coming from local synchronizer or listener)");
            return;
        }

        Group group = event.getSourceGroup();
        String groupName = group.getName();
        String pid = event.getId();

        if (isAllowed(event.getSourceGroup(), Constants.CATEGORY, pid, EventType.INBOUND)) {
            synchronized (monitor) {
                Map<String, Properties> clusterConfigurations = clusterManager.getMap(Constants.CONFIGURATION_MAP + Configurations.SEPARATOR + groupName);
                Dictionary clusterDictionary = clusterConfigurations.get(pid);
                LOGGER.debug("CELLAR CONFIG: Received event for configuration {}, type: {}, cluster data : {}", pid, event.getType(), clusterDictionary == null ? null : Collections.list(clusterDictionary.keys()));

                // Integrity check and retry if needed
                boolean clusterConfigIntegrityCheck = integrityCheck(event.getIntegrity(), clusterDictionary);
                if (!clusterConfigIntegrityCheck) {
                    int tries = getIntegrityTryCount();
                    int interval = getIntegrityTryInterval();

                    LOGGER.warn("CELLAR CONFIG: Integrity check failed between received config update event and cluster configuration for pid: {}, " +
                            "will retry {} times with {}ms interval", pid, tries, interval);
                    while (!clusterConfigIntegrityCheck && tries > 0) {
                        // Wait for a while before retrying
                        try {
                            Thread.sleep(interval);
                        } catch (InterruptedException ignored) {
                        }

                        // Reload cluster configuration
                        clusterConfigurations = clusterManager.getMap(Constants.CONFIGURATION_MAP + Configurations.SEPARATOR + groupName);
                        clusterDictionary = clusterConfigurations.get(pid);

                        // Check integrity again
                        if (!integrityCheck(event.getIntegrity(), clusterDictionary)) {
                            tries--;
                            if (tries > 0) {
                                LOGGER.warn("CELLAR CONFIG: Integrity check still incorrect for pid: {}, " +
                                        "will retry in {}ms, remaining tries: {}", pid, interval, tries);
                            } else {
                                LOGGER.error("CELLAR CONFIG: Integrity check still incorrect for pid: {}, giving up after retries limit reached, " +
                                        "this may let that node configuration inconsistent. It's recommended to perform a manual cluster configuration sync !", pid);
                            }
                        } else {
                            clusterConfigIntegrityCheck = true;
                            LOGGER.info("CELLAR CONFIG: Integrity check success for pid: {} after retries", pid);
                        }
                    }
                }

                try {
                    // update the local configuration
                    Configuration localConfiguration = findLocalConfiguration(pid, clusterDictionary);

                    if (event.getType() != null && event.getType() == ConfigurationEvent.CM_DELETED) {
                        // delete the configuration
                        if (localConfiguration != null) {
                            LOGGER.debug("CELLAR CONFIG: Local config found, deleting it - pid = {}, from {}", localConfiguration.getPid(), pid);
                            deleteConfiguration(localConfiguration);
                        }
                    } else {
                        if (clusterDictionary != null && shouldReplicateConfig(clusterDictionary)) {
                            if (localConfiguration == null) {
                                // Create new configuration
                                localConfiguration = createLocalConfiguration(pid, clusterDictionary);
                                LOGGER.debug("CELLAR CONFIG: Local config created - local pid: {}, from {} ", localConfiguration.getPid(), pid);
                            } else {
                                LOGGER.debug("CELLAR CONFIG: Local config found, updating - pid = {}, from {}", localConfiguration.getPid(), pid);
                            }
                            Dictionary localDictionary = localConfiguration.getProperties();
                            if (localDictionary == null) {
                                localDictionary = new Properties();
                            }
                            localDictionary = filter(localDictionary);
                            if (!areEquals(clusterDictionary, localDictionary) && canDistributeConfig(localDictionary)) {
                                persistConfiguration(localConfiguration.getPid(), localConfiguration.getProperties(), clusterDictionary);
                                Dictionary convertedDictionary = convertPropertiesFromCluster(clusterDictionary);
                                if (!localConfiguration.getPid().equals(pid)) {
                                    Properties p = dictionaryToProperties(filter(convertedDictionary));
                                    LOGGER.debug("CELLAR CONFIG: Storing factory configuration local pid: {}, from {} : {}", localConfiguration.getProperties(), pid, Collections.list(localDictionary.keys()));
                                    clusterConfigurations.put(localConfiguration.getPid(), p);
                                    declareHeldPid(groupName, localConfiguration.getPid());
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

    public void init() {
        // nothing to do
    }

    public void destroy() {
        // nothing to do
    }

    private boolean integrityCheck(String integrityHash, Dictionary dictionary) {
        // In case of deleted conf event, integrityHash is null, we consider it as correct
        return integrityHash == null ||
                (dictionary != null && integrityHash.equals(hash((Properties) dictionary)));
    }

    private int getIntegrityTryCount() {
        try {
            Configuration configuration = configurationAdmin.getConfiguration(Configurations.NODE, null);
            if (configuration != null) {
                String value = (String) configuration.getProperties().get(INTEGRITY_TRY_COUNT_PROP);
                if (value != null) {
                    return Integer.parseInt(value);
                }
            }
        } catch (Exception e) {
            LOGGER.error("CELLAR CONFIG: can't get integrity try count", e);
        }
        return INTEGRITY_TRY_COUNT_DEFAULT;
    }

    private int getIntegrityTryInterval() {
        try {
            Configuration configuration = configurationAdmin.getConfiguration(Configurations.NODE, null);
            if (configuration != null) {
                String value = (String) configuration.getProperties().get(INTEGRITY_TRY_INTERVAL_PROP);
                if (value != null) {
                    return Integer.parseInt(value);
                }
            }
        } catch (Exception e) {
            LOGGER.error("CELLAR CONFIG: can't get integrity try interval", e);
        }
        return INTEGRITY_TRY_INTERVAL_DEFAULT;
    }

    /**
     * Get the cluster configuration event handler switch.
     *
     * @return the cluster configuration event handler switch.
     */
    @Override
    public Switch getSwitch() {
        // load the switch status from the config
        try {
            Configuration configuration = configurationAdmin.getConfiguration(Configurations.NODE, null);
            if (configuration != null) {
                Boolean status = new Boolean((String) configuration.getProperties().get(Configurations.HANDLER + "." + this.getClass().getName()));
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

    /**
     * Get the cluster event type.
     *
     * @return the cluster configuration event type.
     */
    @Override
    public Class<ClusterConfigurationEvent> getType() {
        return ClusterConfigurationEvent.class;
    }

}
