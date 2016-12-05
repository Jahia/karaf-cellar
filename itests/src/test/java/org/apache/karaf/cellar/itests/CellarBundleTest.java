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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.apache.karaf.cellar.core.ClusterManager;
import org.apache.karaf.features.FeaturesService;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public class CellarBundleTest extends CellarTestSupport {

    @Test
    //@Ignore
    public void testCellarBundleModule() throws Exception {
    	FeaturesService featuresService = getOsgiService(FeaturesService.class);
    	assertNotNull(featuresService);
    	
        installCellar();
        createCellarChild("child1");
        Thread.sleep(COMMAND_TIMEOUT);
        ClusterManager clusterManager = getOsgiService(ClusterManager.class);
        assertNotNull(clusterManager);
        
        System.err.println(executeCommand("instance:list"));
        
        System.err.println(executeCommand("cluster:node-list"));

        System.err.println(executeCommand("cluster:bundle-list default"));
        
        // test install
        System.err.println(executeCommand("cluster:bundle-install default mvn:org.apache.commons/commons-math/2.2"));
        Thread.sleep(2000);
        // check state on current node
        String listResult = executeCommand("cluster:bundle-list default | grep 'Commons Math'");
        System.err.println(listResult);
        assertTrue(listResult.contains("Installed"));
        assertTrue(listResult.contains("cluster/local"));
        // check state on child1
        listResult = executeCommand("instance:connect -u karaf -p karaf child1 cluster:bundle-list default | grep 'Commons Math'", COMMAND_TIMEOUT * 2, false);
        System.err.println(listResult);
        assertTrue(listResult.contains("Installed"));
        assertTrue(listResult.contains("cluster/local"));
        
        // test start
        listResult = executeCommand("cluster:bundle-start default org.apache.commons.math/2.2");
        Thread.sleep(2000);
        // check state on current node
        listResult = executeCommand("cluster:bundle-list default | grep 'Commons Math'");
        System.err.println(listResult);
        assertTrue(listResult.contains("Active"));
        assertTrue(listResult.contains("cluster/local"));
        // check state on child1
        listResult = executeCommand("instance:connect -u karaf -p karaf child1 cluster:bundle-list default | grep 'Commons Math'", COMMAND_TIMEOUT * 2, false);
        System.err.println(listResult);
        assertTrue(listResult.contains("Active"));
        assertTrue(listResult.contains("cluster/local"));
        
        // test install and start
        System.err.println(executeCommand("cluster:bundle-install -s default mvn:commons-lang/commons-lang/2.6"));
        Thread.sleep(2000);
        // check state on current node
        listResult = executeCommand("cluster:bundle-list default | grep 'Commons Lang'");
        System.err.println(listResult);
        assertTrue(listResult.contains("Active"));
        assertTrue(listResult.contains("cluster/local"));
        // check state on child1
        listResult = executeCommand("instance:connect -u karaf -p karaf child1 cluster:bundle-list default | grep 'Commons Lang'", COMMAND_TIMEOUT * 2, false);
        System.err.println(listResult);
        assertTrue(listResult.contains("Active"));
        assertTrue(listResult.contains("cluster/local"));

        
        // test stop and uninstall with second node offline
        // stop child1 instance
        executeCommand("instance:stop child1");
        Thread.sleep(COMMAND_TIMEOUT);
        // uninstall bundle from cluster
        listResult = executeCommand("cluster:bundle-uninstall default org.apache.commons.math/2.2");
        Thread.sleep(2000);
        // check state on current node
        listResult = executeCommand("cluster:bundle-list default | grep 'Commons Math'");
        System.err.println(listResult);
        assertTrue(listResult.length() == 0);
        listResult = executeCommand("bundle:list | grep 'Commons Math'");
        System.err.println(listResult);
        assertTrue(listResult.length() == 0);
        // stop bundle in cluster
        listResult = executeCommand("cluster:bundle-stop default org.apache.commons.lang/2.6");
        Thread.sleep(2000);
        // check state on current node
        listResult = executeCommand("cluster:bundle-list default | grep 'Commons Lang'");
        System.err.println(listResult);
        assertTrue(listResult.contains("Resolved"));
        assertTrue(listResult.contains("cluster/local"));
        listResult = executeCommand("bundle:list | grep 'Commons Lang'");
        System.err.println(listResult);
        assertTrue(listResult.contains("Resolved"));
        // start child1 instance back
        executeCommand("instance:start child1");
        Thread.sleep(DEFAULT_TIMEOUT);
        // check state on current node
        listResult = executeCommand("cluster:bundle-list default | grep 'Commons Math'");
        System.err.println(listResult);
        assertTrue(listResult.length() == 0);
        listResult = executeCommand("bundle:list | grep 'Commons Math'");
        System.err.println(listResult);
        assertTrue(listResult.length() == 0);
        listResult = executeCommand("cluster:bundle-list default | grep 'Commons Lang'");
        System.err.println(listResult);
        assertTrue(listResult.contains("Resolved"));
        assertTrue(listResult.contains("cluster/local"));
        listResult = executeCommand("bundle:list | grep 'Commons Lang'");
        System.err.println(listResult);
        assertTrue(listResult.contains("Resolved"));
        // check state on child1
        listResult = executeCommand("instance:connect -u karaf -p karaf child1 cluster:bundle-list default | grep 'Commons Math'", COMMAND_TIMEOUT * 2, false);
        System.err.println(listResult);
        assertTrue(listResult.length() == 0);
        listResult = executeCommand("instance:connect -u karaf -p karaf child1 bundle:list | grep 'Commons Math'");
        System.err.println(listResult);
        assertTrue(listResult.length() == 0);
        // check state on child1
        listResult = executeCommand("instance:connect -u karaf -p karaf child1 cluster:bundle-list default | grep 'Commons Lang'", COMMAND_TIMEOUT * 2, false);
        System.err.println(listResult);
        assertTrue(listResult.contains("Resolved"));
        assertTrue(listResult.contains("cluster/local"));
        listResult = executeCommand("instance:connect -u karaf -p karaf child1 bundle:list | grep 'Commons Lang'");
        System.err.println(listResult);
        assertTrue(listResult.contains("Resolved"));
    }

    @After
    public void tearDown() {
        try {
            unInstallCellar();
        } catch (Exception ex) {
            //Ignore
        }
    }

}
