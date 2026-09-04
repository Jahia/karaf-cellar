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

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers which local deletions Cellar broadcasts to the cluster group.
 * <p>
 * A CM_DELETED event carries no reason. This is the decision table that tells a deleted configuration, which every
 * node must apply, from a retired pid, which leaves the configuration in service.
 */
public class LocalConfigurationListenerDeletionTest {

    private static final String FILE = "org.jahia.bundles.api.authorization-sam.yml";

    private boolean fileOnDisk;
    private boolean fileFeedsAConfiguration;
    private boolean lookupFails;
    private TestListener listener;

    /**
     * A listener whose view of the configuration storage directory is two fields.
     */
    private class TestListener extends LocalConfigurationListener {
        @Override
        protected boolean configurationFileExists(String filename) {
            return fileOnDisk;
        }

        @Override
        protected boolean feedsLocalConfiguration(String filename) throws IOException {
            if (lookupFails) {
                throw new IOException("configuration admin is unavailable");
            }
            return fileFeedsAConfiguration;
        }
    }

    @Before
    public void setUp() {
        fileOnDisk = true;
        fileFeedsAConfiguration = false;
        lookupFails = false;
        listener = new TestListener();
    }

    @Test
    public void GIVEN_the_file_is_gone_WHEN_a_configuration_is_deleted_THEN_the_deletion_propagates() {
        fileOnDisk = false;
        fileFeedsAConfiguration = false;

        assertFalse(listener.isRetiredPid(FILE));
    }

    @Test
    public void GIVEN_the_file_is_gone_but_another_configuration_still_names_it_WHEN_deleted_THEN_it_propagates() {
        // an operator removing the file is a deletion, whatever configuration still carries that file name
        fileOnDisk = false;
        fileFeedsAConfiguration = true;

        assertFalse(listener.isRetiredPid(FILE));
    }

    @Test
    public void GIVEN_the_file_is_there_and_no_configuration_reads_it_WHEN_deleted_THEN_the_deletion_propagates() {
        // cluster:config-delete on the last configuration of a file that is still on disk
        fileOnDisk = true;
        fileFeedsAConfiguration = false;

        assertFalse(listener.isRetiredPid(FILE));
    }

    @Test
    public void GIVEN_the_file_still_feeds_a_configuration_WHEN_one_pid_is_deleted_THEN_nothing_is_broadcast() {
        fileOnDisk = true;
        fileFeedsAConfiguration = true;

        assertTrue(listener.isRetiredPid(FILE));
    }

    @Test
    public void GIVEN_no_file_name_WHEN_a_configuration_is_deleted_THEN_the_deletion_propagates() {
        assertFalse(listener.isRetiredPid(null));
    }

    @Test
    public void GIVEN_the_lookup_fails_WHEN_a_configuration_is_deleted_THEN_the_deletion_propagates() {
        // an unreadable state must not silence a real deletion
        lookupFails = true;

        assertFalse(listener.isRetiredPid(FILE));
    }
}
