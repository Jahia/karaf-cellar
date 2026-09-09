package org.apache.karaf.cellar.config;

import org.apache.karaf.cellar.core.ClusterManager;
import org.apache.karaf.cellar.core.Group;
import org.apache.karaf.cellar.core.Node;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Hands out one map, and refuses everything a test has no business calling. */
class StubClusterManager implements ClusterManager {

    private final Map map;

    StubClusterManager(Map map) {
        this.map = map;
    }

    @Override
    public Map getMap(String name) {
        return map;
    }

    @Override
    public List getList(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set getSet(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Node> listNodes() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Node> listNodes(Collection<String> ids) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Node> listNodesByGroup(Group group) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Node findNodeById(String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Node findNodeByAlias(String alias) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Node findNodeByIdOrAlias(String idOrAlias) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Node getNode() {
        return null;
    }

    @Override
    public void setNodeAlias(String alias) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String generateId() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void start() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void stop() {
        throw new UnsupportedOperationException();
    }
}
