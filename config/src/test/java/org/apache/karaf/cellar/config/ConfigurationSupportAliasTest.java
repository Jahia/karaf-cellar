package org.apache.karaf.cellar.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Covers the alias a configuration file name gives to the configuration it feeds.
 */
public class ConfigurationSupportAliasTest {

    private static final String FACTORY_PID = "org.jahia.bundles.api.authorization";

    @Test
    public void GIVEN_a_file_named_after_its_factory_WHEN_reading_the_alias_THEN_it_is_what_follows_the_dash() {
        assertEquals("sam", ConfigurationSupport.aliasFromFilename(FACTORY_PID, FACTORY_PID + "-sam.yml"));
        assertEquals("default", ConfigurationSupport.aliasFromFilename(FACTORY_PID, FACTORY_PID + "-default.yaml"));
        assertEquals("jwt", ConfigurationSupport.aliasFromFilename(FACTORY_PID, FACTORY_PID + "-jwt.cfg"));
        // the alias keeps every dash after the factory pid, which is what Jahia core's parsePid does. Splitting
        // at the last dash instead would rename every configuration of a multi-dash file, and only this row says so.
        assertEquals("module-sam", ConfigurationSupport.aliasFromFilename("org.jahia.my", "org.jahia.my-module-sam.yml"));
    }

    @Test
    public void GIVEN_an_alias_carrying_a_dot_WHEN_reading_it_THEN_only_the_extension_is_removed() {
        assertEquals("my.scope", ConfigurationSupport.aliasFromFilename(FACTORY_PID, FACTORY_PID + "-my.scope.yml"));
    }

    @Test
    public void GIVEN_a_file_that_does_not_carry_the_factory_pid_WHEN_reading_the_alias_THEN_there_is_none() {
        assertNull(ConfigurationSupport.aliasFromFilename(FACTORY_PID, "something-else.yml"));
        assertNull(ConfigurationSupport.aliasFromFilename(FACTORY_PID, FACTORY_PID + ".yml"));
    }

    /**
     * Jahia core's parsePid and Felix's ConfigInstaller both read an empty alias out of this name, and name the
     * configuration <factoryPid>~. Reading no alias here would give that one file a generated pid on this node and
     * the named pid on a node that installs it from its own file, which is the divergence this naming removes.
     */
    @Test
    public void GIVEN_a_file_whose_alias_is_empty_WHEN_reading_it_THEN_it_is_empty_and_not_absent() {
        assertEquals("", ConfigurationSupport.aliasFromFilename(FACTORY_PID, FACTORY_PID + "-.yml"));
    }

    @Test
    public void GIVEN_no_file_name_WHEN_reading_the_alias_THEN_there_is_none() {
        assertNull(ConfigurationSupport.aliasFromFilename(FACTORY_PID, null));
        assertNull(ConfigurationSupport.aliasFromFilename(null, FACTORY_PID + "-sam.yml"));
    }
}
