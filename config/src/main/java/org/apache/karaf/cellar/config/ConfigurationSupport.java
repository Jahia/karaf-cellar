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

import org.apache.karaf.cellar.core.CellarSupport;
import org.apache.karaf.cellar.core.Configurations;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Generic configuration support.
 */
public class ConfigurationSupport extends CellarSupport {

    public static final String FELIX_FILEINSTALL_FILENAME = "felix.fileinstall.filename";
    public static final String KARAF_CELLAR_FILENAME = "karaf.cellar.filename";
    public static final String KARAF_CELLAR_CONTENT = "karaf.cellar.content";
    public static final String KARAF_CELLAR_REMOVED = "karaf.cellar.removed";

    protected File storage;

    /** A newline cannot appear in a configuration pid, so it needs no escaping. */
    private static final String HELD_PIDS_SEPARATOR = "\n";

    /** Serializes the read-modify-write of this node's own declaration, which several Cellar components perform. */
    private static final Object HELD_PIDS_LOCK = new Object();

    /**
     * Read a {@code Dictionary} and create a corresponding {@code Properties}.
     *
     * @param dictionary the source dictionary.
     * @return the corresponding properties.
     */
    public static Properties dictionaryToProperties(Dictionary dictionary) {
        Properties properties = new Properties();
        if (dictionary != null) {
            Enumeration keys = dictionary.keys();
            while (keys.hasMoreElements()) {
                Object key = keys.nextElement();
                if (key != null && dictionary.get(key) != null) {
                    properties.put(key, dictionary.get(key));
                }
            }
        }
        return properties;
    }

    /**
     * Read a {@code Properties} and generate a hash of its keys and values.
     *
     * @param properties the source properties.
     * @return the generated hashcode.
     */
    public static String hash(Properties properties) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-1");
            hash.reset();
            List<String> keys = new ArrayList(properties.stringPropertyNames());
            Collections.sort(keys);
            for (String key : keys) {
                hash.update(key.getBytes("UTF-8"));
                hash.update(properties.getProperty(key).getBytes("UTF-8"));
            }
            return Base64.getEncoder().encodeToString(hash.digest());
        } catch(NoSuchAlgorithmException | UnsupportedEncodingException e) {
            return "error";
        }
    }

    /**
     * Returns true if dictionaries are equal.
     *
     * @param source the source dictionary.
     * @param target the target dictionary.
     * @return true if the two dictionaries are equal, false else.
     */
    protected static boolean areEquals(Dictionary source, Dictionary target) {
        if (Objects.equals(source, target)) {
            return true;
        }
        if (source == null || target == null || source.size() != target.size()) {
            return false;
        }

        Enumeration keys = source.keys();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            if (!key.equals(org.osgi.framework.Constants.SERVICE_PID)) {
                Object sourceValue = source.get(key);
                Object targetValue = target.get(key);
                if (!Objects.deepEquals(sourceValue, targetValue)) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean canDistributeConfig(Dictionary dictionary) {
        if (dictionary.get(ConfigurationAdmin.SERVICE_FACTORYPID) != null) {
            return dictionary.get(KARAF_CELLAR_FILENAME) != null;
        }
        return true;
    }

    public boolean shouldReplicateConfig(Dictionary clusterDictionary) {
        return clusterDictionary.get(KARAF_CELLAR_REMOVED) == null;
    }

    /**
     * Filter a dictionary, and populate a target dictionary.
     *
     * @param dictionary the source dictionary.
     * @return the filtered dictionary
     */
    public Dictionary filter(Dictionary dictionary) {
        Dictionary result = new Properties();
        if (dictionary != null) {
            Enumeration sourceKeys = dictionary.keys();
            while (sourceKeys.hasMoreElements()) {
                String key = (String) sourceKeys.nextElement();
                if (key.equals(FELIX_FILEINSTALL_FILENAME)) {
                    String value = URI.create(dictionary.get(key).toString()).getPath();
                    value = value.substring(value.lastIndexOf("/") + 1);
                    result.put(KARAF_CELLAR_FILENAME, value);
                    try {
                        result.put(KARAF_CELLAR_CONTENT, readFile(new File(storage, value)));
                    } catch (IOException e) {
                        LOGGER.debug("Cannot read file content for {}", value);
                        // Cannot read file
                    }
                } else if (!isExcludedProperty(key)) {
                    Object value = dictionary.get(key);
                    result.put(key, value);
                }
            }
        }
        return result;
    }

    public Properties getDeletedConfigurationMarker(Dictionary dictionary) {
        Properties result = new Properties();
        result.put(org.osgi.framework.Constants.SERVICE_PID, dictionary.get(org.osgi.framework.Constants.SERVICE_PID));
        result.put(KARAF_CELLAR_FILENAME, dictionary.get(KARAF_CELLAR_FILENAME));
        result.put(KARAF_CELLAR_REMOVED, true);
        return result;
    }

    /**
     * Get the map in which each node declares the configuration pids it holds, for one cluster group.
     *
     * @param groupName the cluster group name.
     * @return the map from node id to that node's declaration.
     */
    protected Map<String, String> getHeldPidsMap(String groupName) {
        return clusterManager.getMap(Constants.CONFIGURATION_HELD_PIDS_MAP + Configurations.SEPARATOR + groupName);
    }

    /**
     * Declare the whole set of configuration pids the local node holds in a cluster group, replacing its previous
     * declaration. A pid that has gone is what the replacement removes.
     *
     * @param groupName the cluster group name.
     * @param pids every pid the local node holds and publishes to that cluster group.
     */
    protected void declareHeldPids(String groupName, Set<String> pids) {
        synchronized (HELD_PIDS_LOCK) {
            getHeldPidsMap(groupName).put(getLocalNodeId(), joinPids(pids));
        }
    }

    /**
     * Add one pid to what the local node declares it holds, so that an entry published between two synchronizations
     * is not read as an entry no node holds.
     *
     * @param groupName the cluster group name.
     * @param pid the pid the local node has just published.
     */
    protected void declareHeldPid(String groupName, String pid) {
        synchronized (HELD_PIDS_LOCK) {
            Map<String, String> declarations = getHeldPidsMap(groupName);
            String nodeId = getLocalNodeId();
            Set<String> pids = readHeldPids(declarations.get(nodeId));
            if (pids.add(pid)) {
                declarations.put(nodeId, joinPids(pids));
            }
        }
    }

    /**
     * @return the id of the local node.
     */
    protected String getLocalNodeId() {
        return clusterManager.getNode().getId();
    }

    /**
     * @param declaration the value one node wrote in the held pids map, or null.
     * @return the pids it names, empty when there is no declaration.
     */
    protected Set<String> readHeldPids(String declaration) {
        Set<String> pids = new LinkedHashSet<String>();
        if (declaration != null && declaration.length() > 0) {
            for (String pid : declaration.split(HELD_PIDS_SEPARATOR)) {
                if (pid.length() > 0) {
                    pids.add(pid);
                }
            }
        }
        return pids;
    }

    private static String joinPids(Set<String> pids) {
        StringBuilder declaration = new StringBuilder();
        for (String pid : pids) {
            if (declaration.length() > 0) {
                declaration.append(HELD_PIDS_SEPARATOR);
            }
            declaration.append(pid);
        }
        return declaration.toString();
    }

    /**
     * Find a local configuration fed by this file, whatever pid it carries.
     *
     * @param filename the karaf.cellar.filename of a configuration.
     * @return a local configuration reading that file, or null when none does.
     */
    protected Configuration findLocalConfigurationByFilename(String filename) throws IOException, InvalidSyntaxException {
        String uri = new File(storage, filename).toURI().toString();
        Configuration[] configurations = configurationAdmin.listConfigurations(
                "(|(" + FELIX_FILEINSTALL_FILENAME + "=" + uri + ")(" + KARAF_CELLAR_FILENAME + "=" + filename + "))");
        return (configurations != null && configurations.length > 0) ? configurations[0] : null;
    }

    /**
     * @param filename the karaf.cellar.filename of a configuration.
     * @return true when that file is still on disk in the configuration storage directory.
     */
    protected boolean configurationFileExists(String filename) {
        return new File(storage, filename).isFile();
    }

    public Configuration findLocalConfiguration(String pid, Dictionary dictionary) throws IOException, InvalidSyntaxException {
        String filter;
        Object filename = dictionary != null ? dictionary.get(KARAF_CELLAR_FILENAME) : null;
        if (filename != null) {
            String uri = new File(storage, filename.toString()).toURI().toString();
            filter = "(|(" + FELIX_FILEINSTALL_FILENAME + "=" + uri + ")(" + KARAF_CELLAR_FILENAME + "=" + dictionary.get(KARAF_CELLAR_FILENAME) + ")(" + org.osgi.framework.Constants.SERVICE_PID + "=" + pid + "))";
        } else {
            filter = "(" + org.osgi.framework.Constants.SERVICE_PID + "=" + pid + ")";
        }

        Configuration[] localConfigurations = configurationAdmin.listConfigurations(filter);

        return (localConfigurations != null && localConfigurations.length > 0) ? localConfigurations[0] : null;
    }

    public Configuration createLocalConfiguration(String pid, Dictionary clusterDictionary) throws IOException {
        Configuration localConfiguration;
        Object factoryPid = clusterDictionary.get(ConfigurationAdmin.SERVICE_FACTORYPID);
        if (factoryPid != null) {
            localConfiguration = configurationAdmin.createFactoryConfiguration(factoryPid.toString(), "?");
        } else {
            localConfiguration = configurationAdmin.getConfiguration(pid, "?");
        }
        return localConfiguration;
    }

    public Dictionary convertPropertiesFromCluster(Dictionary dictionary) {
        Dictionary result = new Properties();
        if (dictionary != null) {
            Enumeration sourceKeys = dictionary.keys();
            while (sourceKeys.hasMoreElements()) {
                String key = (String) sourceKeys.nextElement();
                if (key.equals(KARAF_CELLAR_FILENAME)) {
                    String value = dictionary.get(key).toString();
                    result.put(FELIX_FILEINSTALL_FILENAME, new File(storage, value).toURI().toString());
                } else if (key.equals(KARAF_CELLAR_CONTENT)) {
                    // skip
                } else {
                    Object value = dictionary.get(key);
                    result.put(key, value);
                }
            }
        }
        return result;
    }

    /**
     * Check if a property is in the default excluded list.
     *
     * @param propertyName the property name to check.
     * @return true is the property is excluded, false else.
     */
    public boolean isExcludedProperty(String propertyName) {
        try {
            Configuration nodeConfiguration = configurationAdmin.getConfiguration(Configurations.NODE, null);
            if (nodeConfiguration != null) {
                Dictionary properties = nodeConfiguration.getProperties();
                if (properties != null) {
                    String property = properties.get("config.excluded.properties").toString();
                    String[] excludedProperties = property.split(",");
                    for (int i = 0; i < excludedProperties.length; i++) {
                        if (excludedProperties[i].trim().equals(propertyName))
                            return true;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("CELLAR CONFIG: can't check excluded properties", e);
        }
        return false;
    }

    /**
     * Persist a configuration to a storage.
     * @param pid the pid of the configuration.
     * @param localDictionary the local dictionary of configuration properties
     * @param clusterDictionary the cluster dictionary of configuration properties
     */
    protected void persistConfiguration(String pid, Dictionary localDictionary, Dictionary clusterDictionary) {
        try {
            File storageFile = getStorageFile(localDictionary);

            if (storageFile == null && clusterDictionary != null && clusterDictionary.get(KARAF_CELLAR_FILENAME) != null) {
                LOGGER.debug("Getting filename from cluster dictionary {}", clusterDictionary.get(KARAF_CELLAR_FILENAME));
                storageFile = new File(storage, (String) clusterDictionary.get(KARAF_CELLAR_FILENAME));
            }

            if (storageFile == null && (localDictionary != null && localDictionary.get(ConfigurationAdmin.SERVICE_FACTORYPID) != null)) {
                LOGGER.debug("Creating filename from pid : {}", pid + ".cfg");
                storageFile = new File(storage, pid + ".cfg");
            }

            if( storageFile == null) {
                LOGGER.debug("Cannot find storage filename {}, localDictionary is null = {}, clusterDictionary contains filename = {}", pid, localDictionary == null, clusterDictionary.get(KARAF_CELLAR_FILENAME) != null);
                return;
            }

            String name = storageFile.getName().toLowerCase();
            boolean isCfg = name.endsWith(".cfg") || name.endsWith(".config");
            boolean isYml = name.endsWith(".yml") || name.endsWith(".yaml");

            if (!isCfg && !isYml) {
                LOGGER.debug("Filename is neither cfg or yml, skipping");
                return;
            }

            String content = clusterDictionary == null ? null : (String) clusterDictionary.get(KARAF_CELLAR_CONTENT);
            if (content != null) {
                LOGGER.debug("Persisting file base on KARAF_CELLAR_CONTENT");
                writeFile(storageFile, content);
            } else {
                LOGGER.debug("File content is null, don't save file now. Dictionary : {}", clusterDictionary);
            }
        } catch (Exception e) {
            LOGGER.error("CELLAR CONFIG: Issue when trying to persist configuration file", e);
        }
    }

    private File getStorageFile(Dictionary properties) throws IOException {
        File storageFile = null;
        if (properties != null) {
            Object val = properties.get(FELIX_FILEINSTALL_FILENAME);
            try {
                if (val instanceof URL) {
                    storageFile = new File(((URL) val).toURI());
                }
                if (val instanceof URI) {
                    storageFile = new File((URI) val);
                }
                if (val instanceof String) {
                    storageFile = new File(new URL((String) val).toURI());
                }
            } catch (Exception e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        return storageFile;
    }

    public String getKarafFilename(Dictionary dictionary) {
        return (String) filter(dictionary).get(KARAF_CELLAR_FILENAME);
    }

    /**
     * Delete the configuration.
     *
     * @param localConfiguration the configuration PID to delete.
     */
    protected void deleteConfiguration(Configuration localConfiguration) throws IOException {
        String filename = getKarafFilename(localConfiguration.getProperties());
        localConfiguration.delete();
        File cfgFile = new File(storage, filename == null ? (localConfiguration.getPid() + ".cfg") : filename);
        if (cfgFile.exists()) {
            cfgFile.delete();
        }
    }

    public File getStorage() {
        return storage;
    }

    public void setStorage(File storage) {
        this.storage = storage;
    }

    private String readFile(File file) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(file));

        try {
            String line = reader.readLine();
            StringBuilder sb = new StringBuilder();

            while(line != null){
                sb.append(line).append("\n");
                line = reader.readLine();
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }
    private void writeFile(File file, String content) throws IOException {
        BufferedWriter writer = new BufferedWriter(new FileWriter(file));
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }

}
