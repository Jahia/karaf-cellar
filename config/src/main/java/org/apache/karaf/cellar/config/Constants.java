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

/**
 * Cellar config constants.
 */
public class Constants {

    public static final String CATEGORY = "config";
    public static final String CONFIGURATION_MAP = "org.apache.karaf.cellar.configuration.map";

    /**
     * Map in which each node declares, under its own id, the configuration pids it holds. It is kept beside the
     * configuration map rather than inside each entry, because an extra key in a configuration dictionary would make
     * every local and cluster comparison unequal and produce an update loop.
     */
    public static final String CONFIGURATION_HELD_PIDS_MAP = "org.apache.karaf.cellar.configuration.heldpids.map";

}
