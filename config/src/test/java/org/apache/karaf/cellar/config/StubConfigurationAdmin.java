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

    /**
     * Configuration Admin always answers this, creating the configuration when it does not exist, and Cellar
     * reads its own node configuration through it to find out whether a property is set. Answering with a
     * configuration that carries no properties is what an untouched node looks like, and lets the caller's
     * default apply. Throwing here instead would abort the caller for a reason that has nothing to do with it.
     */
    @Override
    public Configuration getConfiguration(String pid, String location) {
        return new StubConfiguration(pid, null);
    }

    @Override
    public Configuration getConfiguration(String pid) {
        return getConfiguration(pid, null);
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
