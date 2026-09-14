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

    /**
     * Build the entry that marks a configuration as deleted for the rest of the cluster.
     * <p>
     * Properties is a Hashtable, so putting a null value throws. Neither of the two properties copied here is
     * guaranteed to be present: push puts a new pid in the map without consulting canDistributeConfig, which only
     * asks for a file name when the configuration belongs to a factory, so a singleton configuration that no file
     * feeds has an entry with no karaf.cellar.filename. Copy what is there and leave out what is not, because a
     * marker that cannot be built is a deletion no other node ever hears about. A null dictionary is read the
     * same way, and gives the bare marker.
     */
    public Properties getDeletedConfigurationMarker(Dictionary dictionary) {
        Properties result = new Properties();
        copyIfPresent(dictionary, result, org.osgi.framework.Constants.SERVICE_PID);
        copyIfPresent(dictionary, result, KARAF_CELLAR_FILENAME);
        result.put(KARAF_CELLAR_REMOVED, true);
        return result;
    }

    private static void copyIfPresent(Dictionary source, Properties target, String key) {
        Object value = source != null ? source.get(key) : null;
        if (value != null) {
            target.put(key, value);
        }
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
        Object factoryPid = clusterDictionary.get(ConfigurationAdmin.SERVICE_FACTORYPID);
        if (factoryPid == null) {
            return configurationAdmin.getConfiguration(pid, "?");
        }
        // A factory configuration that a file feeds is named after that file, so every node names it the same way
        // and names it the same way again after its configuration store is rebuilt. createFactoryConfiguration
        // would mint a generated pid instead, which makes the same file a different resource on this node and
        // leaves an entry behind in the cluster map for as long as the cluster lives.
        String alias = aliasFromFilename(factoryPid.toString(),
                Objects.toString(clusterDictionary.get(KARAF_CELLAR_FILENAME), null));
        if (alias == null) {
            return configurationAdmin.createFactoryConfiguration(factoryPid.toString(), "?");
        }
        return configurationAdmin.getFactoryConfiguration(factoryPid.toString(), alias, "?");
    }

    /**
     * Read, from the name of the file a configuration is written to, the alias that names it inside its factory.
     *
     * @param factoryPid the factory pid the configuration belongs to.
     * @param filename the karaf.cellar.filename of the configuration, {@code <factoryPid>-<alias>.<extension>}.
     * @return the alias, or null when there is no file name or it does not carry the factory pid.
     */
    static String aliasFromFilename(String factoryPid, String filename) {
        if (factoryPid == null || filename == null) {
            return null;
        }
        int extension = filename.lastIndexOf('.');
        String stem = extension > 0 ? filename.substring(0, extension) : filename;
        String prefix = factoryPid + "-";
        // An empty alias is an alias: Jahia core's parsePid and Felix's ConfigInstaller both read one out of
        // <factoryPid>-.yml, so rejecting it here would name that file's configuration differently from them.
        return stem.startsWith(prefix) ? stem.substring(prefix.length()) : null;
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
        // Read everything this method needs before deleting, because Configuration Admin answers nothing about a
        // configuration that is gone: Felix checks for the deletion in getPid as well as in getProperties, and
        // throws IllegalStateException. The file name is only missing when no file feeds the configuration, so the
        // pid was read after the deletion exactly in the case where it is the one thing left to read.
        String pid = localConfiguration.getPid();
        String filename = getKarafFilename(localConfiguration.getProperties());
        localConfiguration.delete();
        File cfgFile = new File(storage, filename == null ? (pid + ".cfg") : filename);
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
