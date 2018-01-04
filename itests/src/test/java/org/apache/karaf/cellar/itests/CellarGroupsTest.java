/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.karaf.cellar.itests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Set;

import org.apache.karaf.cellar.core.ClusterManager;
import org.apache.karaf.cellar.core.Group;
import org.apache.karaf.cellar.core.GroupManager;
import org.apache.karaf.cellar.core.Node;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public class CellarGroupsTest extends CellarTestSupport {

    private static void deleteSshKnownHostsFile() {
        File hosts = new File(System.getProperty("user.home"), ".sshkaraf/known_hosts");
        if (hosts.exists()) {
            hosts.delete();
        }
    }

    @Before
    public void setUp() throws InterruptedException {
        deleteSshKnownHostsFile();
        installCellar();
        createCellarChild("child1");
        Thread.sleep(DEFAULT_TIMEOUT);
    }

    @Test
    public void testGroupsWithChildNodes() throws InterruptedException {
        ClusterManager clusterManager = getOsgiService(ClusterManager.class);
        assertNotNull(clusterManager);

        System.err.println(executeCommand("cluster:bundle-install --start default mvn:org.apache.commons/commons-math/2.1"));
        Thread.sleep(2000);

        System.err.println(executeCommand("cluster:node-list"));
        Node localNode = clusterManager.getNode();
        Set<Node> nodes = clusterManager.listNodes();
        assertTrue("There should be at least 2 cellar nodes running", 2 <= nodes.size());

        System.err.println(executeCommand("cluster:group-list"));
        System.err.println(executeCommand("cluster:group-create testgroup"));
        Thread.sleep(2000);
        System.err.println(executeCommand("cluster:group-join testgroup " + localNode.getId()));
        System.err.println(executeCommand("cluster:group-list"));

        GroupManager groupManager = getOsgiService(GroupManager.class);
        assertNotNull(groupManager);

        Set<Group> groups = groupManager.listAllGroups();
        assertEquals("There should be 2 cellar groups", 2, groups.size());
        
        Set<Group> localGroups = groupManager.listLocalGroups();
        assertEquals("There should be 2 local cellar groups", 2, localGroups.size());
        for (Group g : localGroups) {
            System.err.println(g.getName());
        }

        // check state on current node
        String listResult = executeCommand("cluster:bundle-list -l default | grep 'mvn:org.apache.commons/commons-math/2.1'");
        System.err.println(listResult);
        assertTrue(listResult.contains("Active"));
        assertTrue(listResult.contains("cluster/local"));
        // check state on child1
        listResult = executeCommand("instance:connect -u karaf -p karaf child1 cluster:bundle-list -l default | grep 'mvn:org.apache.commons/commons-math/2.1'", COMMAND_TIMEOUT * 2, false);
        System.err.println(listResult);
        assertTrue(listResult.contains("Active"));
        assertTrue(listResult.contains("cluster/local"));
        
        // install another version on testgroup
        System.err.println(executeCommand("cluster:bundle-install --start testgroup mvn:org.apache.commons/commons-math/2.2"));
        Thread.sleep(2000);
        // stop version 2.1 on testgroup
        System.err.println(executeCommand("cluster:bundle-stop testgroup org.apache.commons.math/2.1"));

        listResult = executeCommand("bundle:list -l | grep 'mvn:org.apache.commons/commons-math/2.1'");
        System.err.println(listResult);
        assertTrue(listResult.contains("Resolved"));
        listResult = executeCommand("bundle:list -l | grep 'mvn:org.apache.commons/commons-math/2.2'");
        System.err.println(listResult);
        assertTrue(listResult.contains("Active"));

        listResult = executeCommand("instance:connect -u karaf -p karaf child1 bundle:list -l | grep 'mvn:org.apache.commons/commons-math/2.1'", COMMAND_TIMEOUT * 2, false);
        System.err.println(listResult);
        assertTrue(listResult.contains("Active"));
        listResult = executeCommand("instance:connect -u karaf -p karaf child1 bundle:list -l | grep 'mvn:org.apache.commons/commons-math/2.2'", COMMAND_TIMEOUT * 2, false);
        System.err.println(listResult);
        assertFalse(listResult.contains("mmvn:org.apache.commons/commons-math/2.2"));

        // executing cluster sync
        System.err.println(executeCommand("cluster:sync --bundle --group default"));
        
        listResult = executeCommand("bundle:list -l | grep 'mvn:org.apache.commons/commons-math/2.1'");
        System.err.println(listResult);
        assertTrue(listResult.contains("Resolved"));
        listResult = executeCommand("bundle:list -l | grep 'mvn:org.apache.commons/commons-math/2.2'");
        System.err.println(listResult);
        assertTrue(listResult.contains("Active"));

        listResult = executeCommand("instance:connect -u karaf -p karaf child1 bundle:list -l | grep 'mvn:org.apache.commons/commons-math/2.1'", COMMAND_TIMEOUT * 2, false);
        System.err.println(listResult);
        assertTrue(listResult.contains("Active"));
        listResult = executeCommand("instance:connect -u karaf -p karaf child1 bundle:list -l | grep 'mvn:org.apache.commons/commons-math/2.2'", COMMAND_TIMEOUT * 2, false);
        System.err.println(listResult);
        assertFalse(listResult.contains("mmvn:org.apache.commons/commons-math/2.2"));

        // uninstall version 2.1 on testgroup
        System.err.println(executeCommand("cluster:bundle-uninstall testgroup org.apache.commons.math/2.1"));

        
        listResult = executeCommand("bundle:list -l | grep 'mvn:org.apache.commons/commons-math/2.1'");
        System.err.println(listResult);
        assertFalse(listResult.contains("mvn:org.apache.commons/commons-math/2.1"));
        listResult = executeCommand("bundle:list -l | grep 'mvn:org.apache.commons/commons-math/2.2'");
        System.err.println(listResult);
        assertTrue(listResult.contains("Active"));

        listResult = executeCommand("instance:connect -u karaf -p karaf child1 bundle:list -l | grep 'mvn:org.apache.commons/commons-math/2.1'", COMMAND_TIMEOUT * 2, false);
        System.err.println(listResult);
        assertTrue(listResult.contains("Active"));
        listResult = executeCommand("instance:connect -u karaf -p karaf child1 bundle:list -l | grep 'mvn:org.apache.commons/commons-math/2.2'", COMMAND_TIMEOUT * 2, false);
        System.err.println(listResult);
        assertFalse(listResult.contains("mmvn:org.apache.commons/commons-math/2.2"));

        // executing cluster sync
        System.err.println(executeCommand("cluster:sync --bundle --group default"));

        listResult = executeCommand("bundle:list -l | grep 'mvn:org.apache.commons/commons-math/2.1'");
        System.err.println(listResult);
        assertFalse(listResult.contains("mvn:org.apache.commons/commons-math/2.1"));
        listResult = executeCommand("bundle:list -l | grep 'mvn:org.apache.commons/commons-math/2.2'");
        System.err.println(listResult);
        assertTrue(listResult.contains("Active"));

        listResult = executeCommand("instance:connect -u karaf -p karaf child1 bundle:list -l | grep 'mvn:org.apache.commons/commons-math/2.1'", COMMAND_TIMEOUT * 2, false);
        System.err.println(listResult);
        assertTrue(listResult.contains("Active"));
        listResult = executeCommand("instance:connect -u karaf -p karaf child1 bundle:list -l | grep 'mvn:org.apache.commons/commons-math/2.2'", COMMAND_TIMEOUT * 2, false);
        System.err.println(listResult);
        assertFalse(listResult.contains("mmvn:org.apache.commons/commons-math/2.2"));

        System.err.println(executeCommand("cluster:group-quit testgroup " + localNode.getId()));
        System.err.println(executeCommand("cluster:group-delete testgroup"));
        System.err.println(executeCommand("cluster:group-list"));
        groups = groupManager.listAllGroups();
        assertEquals("There should be a single cellar group", 1, groups.size());
    }

    @After
    public void tearDown() {
        try {
            destroyCellarChild("child1");
            unInstallCellar();
        } catch (Exception ex) {
            //Ignore
        } finally {
            deleteSshKnownHostsFile();
        }
    }

}
