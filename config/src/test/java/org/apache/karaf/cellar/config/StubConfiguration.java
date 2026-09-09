package org.apache.karaf.cellar.config;

import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;

import java.util.Dictionary;
import java.util.Set;

/**
 * A Configuration that answers what a test sets on it and refuses everything else, so a test that starts
 * depending on a method nobody thought about fails here rather than somewhere further away.
 */
class StubConfiguration implements Configuration {

    private final String pid;
    private final Dictionary<String, Object> properties;

    StubConfiguration(String pid, Dictionary<String, Object> properties) {
        this.pid = pid;
        this.properties = properties;
    }

    @Override
    public String getPid() {
        return pid;
    }

    @Override
    public Dictionary<String, Object> getProperties() {
        return properties;
    }

    @Override
    public String getFactoryPid() {
        return null;
    }

    @Override
    public void update(Dictionary<String, ?> properties) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void delete() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Dictionary<String, Object> getProcessedProperties(ServiceReference<?> reference) {
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
        return "?";
    }

    @Override
    public long getChangeCount() {
        return 0;
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
