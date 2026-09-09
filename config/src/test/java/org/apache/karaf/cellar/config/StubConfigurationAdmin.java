package org.apache.karaf.cellar.config;

import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

/** Answers listConfigurations with the configurations a test hands it, and refuses to create anything. */
class StubConfigurationAdmin implements ConfigurationAdmin {

    private final Configuration[] configurations;

    StubConfigurationAdmin(Configuration... configurations) {
        this.configurations = configurations;
    }

    @Override
    public Configuration[] listConfigurations(String filter) {
        return configurations.length == 0 ? null : configurations;
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
    public Configuration createFactoryConfiguration(String factoryPid) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Configuration createFactoryConfiguration(String factoryPid, String location) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Configuration getFactoryConfiguration(String factoryPid, String name, String location) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Configuration getFactoryConfiguration(String factoryPid, String name) {
        throw new UnsupportedOperationException();
    }
}
