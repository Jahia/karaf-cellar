package org.apache.karaf.cellar.config;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConfigurationSupportTest {

    @Test
    public void GIVEN_both_null_dictionaries_WHEN_comparing_THEN_equal() {
        assertTrue(ConfigurationSupport.areEquals(null, null));
    }

    @Test
    public void GIVEN_a_null_dictionary_and_a_non_null_dictionary_WHEN_comparing_THEN_different() {
        Dictionary<String, String> source = new Hashtable<>();
        source.put("key1", "value1");

        assertFalse(ConfigurationSupport.areEquals(source, null));
        assertFalse(ConfigurationSupport.areEquals(null, source));
    }

    @Test
    public void GIVEN_both_empty_dictionaries_WHEN_comparing_THEN_equal() {
        assertTrue(ConfigurationSupport.areEquals(new Hashtable<>(), new Hashtable<>()));
    }

    @Test
    public void GIVEN_two_dictionaries_of_different_size_WHEN_comparing_THEN_different() {
        Dictionary<String, String> source = new Hashtable<>();
        source.put("key1", "value1");

        Dictionary<String, String> target = new Hashtable<>();
        target.put("key1", "value1");
        target.put("key2", "value2");

        assertFalse(ConfigurationSupport.areEquals(source, target));
    }

    @Test
    public void GIVEN_two_dictionaries_of_same_size_but_different_keys_WHEN_comparing_THEN_different() {
        Dictionary<String, String> source = new Hashtable<>();
        source.put("key1", "value1");
        source.put("key2", "value2");

        Dictionary<String, String> target = new Hashtable<>();
        target.put("key1", "value1");
        target.put("differentKey", "value2");

        assertFalse(ConfigurationSupport.areEquals(source, target));
    }

    @Test
    public void GIVEN_two_dictionaries_of_same_size_same_keys_but_different_values_WHEN_comparing_THEN_different() {
        Dictionary<String, String> source = new Hashtable<>();
        source.put("key1", "value1");
        source.put("key2", "value2");

        Dictionary<String, String> target = new Hashtable<>();
        target.put("key1", "value1");
        target.put("key2", "differentValue");

        assertFalse(ConfigurationSupport.areEquals(source, target));
    }

    @Test
    public void GIVEN_two_dictionaries_of_same_size_same_keys_and_same_values_WHEN_comparing_THEN_equal() {
        Dictionary<String, String> source = new Hashtable<>();
        source.put("key1", "value1");
        source.put("key2", "value2");

        Dictionary<String, String> target = new Hashtable<>();
        target.put("key1", "value1");
        target.put("key2", "value2");

        assertTrue(ConfigurationSupport.areEquals(source, target));
    }

    @Test
    public void GIVEN_two_dictionaries_with_identical_arrays_as_values_WHEN_comparing_THEN_equal() {
        Dictionary<String, Object> source = new Hashtable<>();
        source.put("key", new String[]{"foo", "bar"});

        Dictionary<String, Object> target = new Hashtable<>();
        target.put("key", new String[]{"foo", "bar"});

        assertTrue(ConfigurationSupport.areEquals(source, target));
    }

    @Test
    public void GIVEN_two_dictionaries_with_identical_lists_as_values_WHEN_comparing_THEN_equal() {
        Dictionary<String, Object> source = new Hashtable<>();
        List<String> sourceValue = new ArrayList<>();
        sourceValue.add("foo");
        sourceValue.add("bar");
        source.put("key", sourceValue);

        Dictionary<String, Object> target = new Hashtable<>();
        List<String> targetValue = new ArrayList<>();
        targetValue.add("foo");
        targetValue.add("bar");
        target.put("key", targetValue);

        assertTrue(ConfigurationSupport.areEquals(source, target));
    }


    @Test
    public void GIVEN_two_dictionaries_identical_except_the_service_pid_WHEN_comparing_THEN_equal() {
        Dictionary<String, String> source = new Hashtable<>();
        source.put("key1", "value1");
        source.put(org.osgi.framework.Constants.SERVICE_PID, "pid1");

        Dictionary<String, String> target = new Hashtable<>();
        target.put("key1", "value1");
        target.put(org.osgi.framework.Constants.SERVICE_PID, "pid2");

        assertTrue(ConfigurationSupport.areEquals(source, target));
    }
}