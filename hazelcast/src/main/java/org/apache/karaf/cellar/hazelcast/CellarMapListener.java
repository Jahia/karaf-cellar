/*
 * ==========================================================================================
 * =                            JAHIA'S ENTERPRISE DISTRIBUTION                             =
 * ==========================================================================================
 *
 *                                  http://www.jahia.com
 *
 * JAHIA'S ENTERPRISE DISTRIBUTIONS LICENSING - IMPORTANT INFORMATION
 * ==========================================================================================
 *
 *     Copyright (C) 2002-2024 Jahia Solutions Group. All rights reserved.
 *
 *     This file is part of a Jahia's Enterprise Distribution.
 *
 *     Jahia's Enterprise Distributions must be used in accordance with the terms
 *     contained in the Jahia Solutions Group Terms &amp; Conditions as well as
 *     the Jahia Sustainable Enterprise License (JSEL).
 *
 *     For questions regarding licensing, support, production usage...
 *     please contact our team at sales@jahia.com or go to http://www.jahia.com/license.
 *
 * ==========================================================================================
 */
package org.apache.karaf.cellar.hazelcast;

import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryListener;
import com.hazelcast.core.MapEvent;
import org.apache.karaf.cellar.core.listener.ClusterMapListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Jerome Blanchard
 */
public class CellarMapListener extends HazelcastInstanceAware implements EntryListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(CellarMapListener.class);

    private String id;
    private String mapName;
    private ClusterMapListener listener;

    public CellarMapListener(String mapName, ClusterMapListener listener) {
        this.mapName = mapName;
        this.listener = listener;
    }

    public void setId(String id) {
        this.id = id;
        this.listener.setId(id);
    }

    @Override
    public void entryAdded(EntryEvent event) {
        LOGGER.debug("CELLAR HAZELCAST: entry added in Hazelcast map {}", mapName);
        listener.handle(mapName, event.getKey(), ClusterMapListener.ActionType.ADD);
    }

    @Override
    public void entryRemoved(EntryEvent event) {
        LOGGER.debug("CELLAR HAZELCAST: entry removed in Hazelcast map {}", mapName);
        listener.handle(mapName, event.getKey(), ClusterMapListener.ActionType.REMOVE);
    }

    @Override
    public void entryUpdated(EntryEvent event) {
        LOGGER.debug("CELLAR HAZELCAST: entry updated in Hazelcast map {}", mapName);
        listener.handle(mapName, event.getKey(), ClusterMapListener.ActionType.UPDATE);
    }

    @Override
    public void entryEvicted(EntryEvent event) {
        LOGGER.debug("CELLAR HAZELCAST: entry evicted in Hazelcast map {}", mapName);
    }

    @Override
    public void mapCleared(MapEvent event) {
        LOGGER.debug("CELLAR HAZELCAST: map cleared in Hazelcast map {}", mapName);
    }

    @Override
    public void mapEvicted(MapEvent event) {
        LOGGER.debug("CELLAR HAZELCAST: map evicted in Hazelcast map {}", mapName);
    }

}
