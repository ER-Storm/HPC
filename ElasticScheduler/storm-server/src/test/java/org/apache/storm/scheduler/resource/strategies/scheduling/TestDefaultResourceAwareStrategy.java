/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.storm.scheduler.resource.strategies.scheduling;

import org.apache.storm.Config;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.generated.WorkerResources;
import org.apache.storm.metric.StormMetricsRegistry;
import org.apache.storm.networktopography.DNSToSwitchMapping;
import org.apache.storm.scheduler.*;
import org.apache.storm.scheduler.resource.RAS_Node;
import org.apache.storm.scheduler.resource.ResourceAwareScheduler;
import org.apache.storm.scheduler.resource.SchedulingResult;
import org.apache.storm.scheduler.resource.normalization.NormalizedResourcesRule;
import org.apache.storm.scheduler.resource.normalization.ResourceMetrics;
import org.apache.storm.scheduler.resource.strategies.scheduling.BaseResourceAwareStrategy.ObjectResources;
import org.apache.storm.topology.SharedOffHeapWithinNode;
import org.apache.storm.topology.SharedOffHeapWithinWorker;
import org.apache.storm.topology.SharedOnHeap;
import org.apache.storm.topology.TopologyBuilder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.Map.Entry;

import static org.apache.storm.scheduler.resource.TestUtilsForResourceAwareScheduler.*;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

public class TestDefaultResourceAwareStrategy {
    private static final Logger LOG = LoggerFactory.getLogger(TestDefaultResourceAwareStrategy.class);

    private static final int CURRENT_TIME = 1450418597;
    private static IScheduler scheduler = null;

    private static class TestDNSToSwitchMapping implements DNSToSwitchMapping {
        private final Map<String, String> result;

        public TestDNSToSwitchMapping(Map<String, SupervisorDetails> ... racks) {
            Map<String, String> ret = new HashMap<>();
            for (int rackNum = 0; rackNum < racks.length; rackNum++) {
                String rack = "rack-" + rackNum;
                for (SupervisorDetails sup : racks[rackNum].values()) {
                    ret.put(sup.getHost(), rack);
                }
            }
            result = Collections.unmodifiableMap(ret);
        }

        @Override
        public Map<String, String> resolve(List<String> names) {
            return result;
        }
    };

    @Rule
    public NormalizedResourcesRule nrRule = new NormalizedResourcesRule();

    @After
    public void cleanup() {
        if (scheduler != null) {
            scheduler.cleanup();
            scheduler = null;
        }
    }

    /*
     * test scheduling does not cause negative resources
     */
    @Test
    public void testSchedulingNegativeResources() {
        int spoutParallelism = 2;
        int boltParallelism = 2;
        double cpuPercent = 10;
        double memoryOnHeap = 10;
        double memoryOffHeap = 10;
        double sharedOnHeapWithinWorker = 400;
        double sharedOffHeapWithinNode = 700;
        double sharedOffHeapWithinWorker = 500;

        Config conf = createClusterConfig(cpuPercent, memoryOnHeap, memoryOffHeap, null);
        TopologyDetails[] topo = new TopologyDetails[2];

        // 1st topology
        TopologyBuilder builder = new TopologyBuilder();
        builder.setSpout("spout", new TestSpout(),
                spoutParallelism);
        builder.setBolt("bolt-1", new TestBolt(),
                boltParallelism).addSharedMemory(new SharedOffHeapWithinWorker(sharedOffHeapWithinWorker, "bolt-1 shared off heap within worker")).shuffleGrouping("spout");
        builder.setBolt("bolt-2", new TestBolt(),
                boltParallelism).addSharedMemory(new SharedOffHeapWithinNode(sharedOffHeapWithinNode, "bolt-2 shared off heap within node")).shuffleGrouping("bolt-1");
        builder.setBolt("bolt-3", new TestBolt(),
                boltParallelism).addSharedMemory(new SharedOnHeap(sharedOnHeapWithinWorker, "bolt-3 shared on heap within worker")).shuffleGrouping("bolt-2");
        StormTopology stormToplogy = builder.createTopology();

        conf.put(Config.TOPOLOGY_PRIORITY, 1);
        conf.put(Config.TOPOLOGY_NAME, "testTopology-0");
        conf.put(Config.TOPOLOGY_WORKER_MAX_HEAP_SIZE_MB, 2000);
        topo[0] = new TopologyDetails("testTopology-id-0", conf, stormToplogy, 0,
                genExecsAndComps(stormToplogy), CURRENT_TIME, "user");

        // 2nd topology
        builder = new TopologyBuilder();
        builder.setSpout("spout", new TestSpout(),
                spoutParallelism).addSharedMemory(new SharedOffHeapWithinNode(sharedOffHeapWithinNode, "spout shared off heap within node"));
        stormToplogy = builder.createTopology();

        conf.put(Config.TOPOLOGY_PRIORITY, 0);
        conf.put(Config.TOPOLOGY_NAME, "testTopology-1");
        topo[1] = new TopologyDetails("testTopology-id-1", conf, stormToplogy, 0,
                genExecsAndComps(stormToplogy), CURRENT_TIME, "user");

        Map<String, SupervisorDetails> supMap = genSupervisors(1, 4, 500, 2000);
        Topologies topologies = new Topologies(topo[0]);
        Cluster cluster = new Cluster(new INimbusTest(), new ResourceMetrics(new StormMetricsRegistry()), supMap, new HashMap<>(), topologies, conf);

        // schedule 1st topology
        scheduler = new ResourceAwareScheduler();
        scheduler.prepare(conf);
        scheduler.schedule(topologies, cluster);
        assertTopologiesFullyScheduled(cluster, topo[0].getName());

        // attempt scheduling both topologies.
        // this triggered negative resource event as the second topology incorrectly scheduled with the first in place
        // first topology should get evicted for higher priority (lower value) second topology to successfully schedule
        topologies = new Topologies(topo[0], topo[1]);
        cluster = new Cluster(cluster, topologies);
        scheduler.schedule(topologies, cluster);
        assertTopologiesNotScheduled(cluster, topo[0].getName());
        assertTopologiesFullyScheduled(cluster, topo[1].getName());

        // check negative resource count
        assertThat(cluster.getResourceMetrics().getNegativeResourceEventsMeter().getCount(), is(0L));
    }

    /**
     * test if the scheduling logic for the DefaultResourceAwareStrategy is correct
     */
    @Test
    public void testDefaultResourceAwareStrategySharedMemory() {
        int spoutParallelism = 2;
        int boltParallelism = 2;
        int numBolts = 3;
        double cpuPercent = 10;
        double memoryOnHeap = 10;
        double memoryOffHeap = 10;
        double sharedOnHeapWithinWorker = 400;
        double sharedOffHeapWithinNode = 700;
        double sharedOffHeapWithinWorker = 600;

        TopologyBuilder builder = new TopologyBuilder();
        builder.setSpout("spout", new TestSpout(),
                spoutParallelism);
        builder.setBolt("bolt-1", new TestBolt(),
                boltParallelism).addSharedMemory(new SharedOffHeapWithinWorker(sharedOffHeapWithinWorker, "bolt-1 shared off heap within worker")).shuffleGrouping("spout");
        builder.setBolt("bolt-2", new TestBolt(),
                boltParallelism).addSharedMemory(new SharedOffHeapWithinNode(sharedOffHeapWithinNode, "bolt-2 shared off heap within node")).shuffleGrouping("bolt-1");
        builder.setBolt("bolt-3", new TestBolt(),
                boltParallelism).addSharedMemory(new SharedOnHeap(sharedOnHeapWithinWorker, "bolt-3 shared on heap within worker")).shuffleGrouping("bolt-2");

        StormTopology stormToplogy = builder.createTopology();

        INimbus iNimbus = new INimbusTest();
        Map<String, SupervisorDetails> supMap = genSupervisors(4, 4, 500, 2000);
        Config conf = createClusterConfig(cpuPercent, memoryOnHeap, memoryOffHeap, null);

        conf.put(Config.TOPOLOGY_PRIORITY, 0);
        conf.put(Config.TOPOLOGY_NAME, "testTopology");
        conf.put(Config.TOPOLOGY_WORKER_MAX_HEAP_SIZE_MB, 2000);
        TopologyDetails topo = new TopologyDetails("testTopology-id", conf, stormToplogy, 0,
                genExecsAndComps(stormToplogy), CURRENT_TIME, "user");

        Topologies topologies = new Topologies(topo);
        Cluster cluster = new Cluster(iNimbus, new ResourceMetrics(new StormMetricsRegistry()), supMap, new HashMap<>(), topologies, conf);

        scheduler = new ResourceAwareScheduler();
        scheduler.prepare(conf);
        scheduler.schedule(topologies, cluster);

        for (Entry<String, SupervisorResources> entry: cluster.getSupervisorsResourcesMap().entrySet()) {
            String supervisorId = entry.getKey();
            SupervisorResources resources = entry.getValue();
            assertTrue(supervisorId, resources.getTotalCpu() >= resources.getUsedCpu());
            assertTrue(supervisorId, resources.getTotalMem() >= resources.getUsedMem());
        }

        // Everything should fit in a single slot
        int totalNumberOfTasks = (spoutParallelism + (boltParallelism * numBolts));
        double totalExpectedCPU = totalNumberOfTasks * cpuPercent;
        double totalExpectedOnHeap = (totalNumberOfTasks * memoryOnHeap) + sharedOnHeapWithinWorker;
        double totalExpectedWorkerOffHeap = (totalNumberOfTasks * memoryOffHeap) + sharedOffHeapWithinWorker;

        SchedulerAssignment assignment = cluster.getAssignmentById(topo.getId());
        assertThat(assignment.getSlots().size(), is(1));
        WorkerSlot ws = assignment.getSlots().iterator().next();
        String nodeId = ws.getNodeId();
        assertThat(assignment.getNodeIdToTotalSharedOffHeapNodeMemory().size(), is(1));
        assertThat(assignment.getNodeIdToTotalSharedOffHeapNodeMemory().get(nodeId), closeTo(sharedOffHeapWithinNode, 0.01));
        assertThat(assignment.getScheduledResources().size(), is(1));
        WorkerResources resources = assignment.getScheduledResources().get(ws);
        assertThat(resources.get_cpu(), closeTo(totalExpectedCPU, 0.01));
        assertThat(resources.get_mem_on_heap(), closeTo(totalExpectedOnHeap, 0.01));
        assertThat(resources.get_mem_off_heap(), closeTo(totalExpectedWorkerOffHeap, 0.01));
        assertThat(resources.get_shared_mem_on_heap(), closeTo(sharedOnHeapWithinWorker, 0.01));
        assertThat(resources.get_shared_mem_off_heap(), closeTo(sharedOffHeapWithinWorker, 0.01));
    }
    
    /**
     * test if the scheduling logic for the DefaultResourceAwareStrategy is correct
     */
    @Test
    public void testDefaultResourceAwareStrategy() {
        int spoutParallelism = 1;
        int boltParallelism = 2;
        TopologyBuilder builder = new TopologyBuilder();
        builder.setSpout("spout", new TestSpout(),
                spoutParallelism);
        builder.setBolt("bolt-1", new TestBolt(),
                boltParallelism).shuffleGrouping("spout");
        builder.setBolt("bolt-2", new TestBolt(),
                boltParallelism).shuffleGrouping("bolt-1");
        builder.setBolt("bolt-3", new TestBolt(),
                boltParallelism).shuffleGrouping("bolt-2");

        StormTopology stormToplogy = builder.createTopology();

        INimbus iNimbus = new INimbusTest();
        Map<String, SupervisorDetails> supMap = genSupervisors(4, 4, 150, 1500);
        Config conf = createClusterConfig(50, 250, 250, null);
        conf.put(Config.TOPOLOGY_PRIORITY, 0);
        conf.put(Config.TOPOLOGY_NAME, "testTopology");
        conf.put(Config.TOPOLOGY_WORKER_MAX_HEAP_SIZE_MB, Double.MAX_VALUE);
        conf.put(Config.TOPOLOGY_SUBMITTER_USER, "user");

        TopologyDetails topo = new TopologyDetails("testTopology-id", conf, stormToplogy, 0,
                genExecsAndComps(stormToplogy), CURRENT_TIME, "user");

        Topologies topologies = new Topologies(topo);
        Cluster cluster = new Cluster(iNimbus, new ResourceMetrics(new StormMetricsRegistry()), supMap, new HashMap<>(), topologies, conf);

        scheduler = new ResourceAwareScheduler();

        scheduler.prepare(conf);
        scheduler.schedule(topologies, cluster);

        HashSet<HashSet<ExecutorDetails>> expectedScheduling = new HashSet<>();
        expectedScheduling.add(new HashSet<>(Arrays.asList(new ExecutorDetails(0, 0)))); //Spout
        expectedScheduling.add(new HashSet<>(Arrays.asList(
            new ExecutorDetails(2, 2), //bolt-1
            new ExecutorDetails(4, 4), //bolt-2
            new ExecutorDetails(6, 6)))); //bolt-3
        expectedScheduling.add(new HashSet<>(Arrays.asList(
            new ExecutorDetails(1, 1), //bolt-1
            new ExecutorDetails(3, 3), //bolt-2
            new ExecutorDetails(5, 5)))); //bolt-3
        HashSet<HashSet<ExecutorDetails>> foundScheduling = new HashSet<>();
        SchedulerAssignment assignment = cluster.getAssignmentById("testTopology-id");
        for (Collection<ExecutorDetails> execs : assignment.getSlotToExecutors().values()) {
            foundScheduling.add(new HashSet<>(execs));
        }

        Assert.assertEquals(expectedScheduling, foundScheduling);
    }

    /**
     * Test whether strategy will choose correct rack
     */
    @Test
    public void testMultipleRacks() {
        final Map<String, SupervisorDetails> supMap = new HashMap<>();
        final Map<String, SupervisorDetails> supMapRack0 = genSupervisors(10, 4, 0, 400, 8000);
        //generate another rack of supervisors with less resources
        final Map<String, SupervisorDetails> supMapRack1 = genSupervisors(10, 4, 10, 200, 4000);

        //generate some supervisors that are depleted of one resource
        final Map<String, SupervisorDetails> supMapRack2 = genSupervisors(10, 4, 20, 0, 8000);

        //generate some that has alot of memory but little of cpu
        final Map<String, SupervisorDetails> supMapRack3 = genSupervisors(10, 4, 30, 10, 8000 * 2 + 4000);

        //generate some that has alot of cpu but little of memory
        final Map<String, SupervisorDetails> supMapRack4 = genSupervisors(10, 4, 40, 400 + 200 + 10, 1000);

        //Generate some that have neither resource, to verify that the strategy will prioritize this last
        //Also put a generic resource with 0 value in the resources list, to verify that it doesn't affect the sorting
        final Map<String, SupervisorDetails> supMapRack5 = genSupervisors(10, 4, 50, 0.0, 0.0, Collections.singletonMap("gpu.count", 0.0));

        supMap.putAll(supMapRack0);
        supMap.putAll(supMapRack1);
        supMap.putAll(supMapRack2);
        supMap.putAll(supMapRack3);
        supMap.putAll(supMapRack4);
        supMap.putAll(supMapRack5);

        Config config = createClusterConfig(100, 500, 500, null);
        config.put(Config.TOPOLOGY_WORKER_MAX_HEAP_SIZE_MB, Double.MAX_VALUE);
        INimbus iNimbus = new INimbusTest();

        //create test DNSToSwitchMapping plugin
        DNSToSwitchMapping TestNetworkTopographyPlugin =
            new TestDNSToSwitchMapping(supMapRack0, supMapRack1, supMapRack2, supMapRack3, supMapRack4, supMapRack5);

        //generate topologies
        TopologyDetails topo1 = genTopology("topo-1", config, 8, 0, 2, 0, CURRENT_TIME - 2, 10, "user");
        TopologyDetails topo2 = genTopology("topo-2", config,  8, 0, 2, 0, CURRENT_TIME - 2, 10, "user");
        
        Topologies topologies = new Topologies(topo1, topo2);
        Cluster cluster = new Cluster(iNimbus, new ResourceMetrics(new StormMetricsRegistry()), supMap, new HashMap<>(), topologies, config);
        
        List<String> supHostnames = new LinkedList<>();
        for (SupervisorDetails sup : supMap.values()) {
            supHostnames.add(sup.getHost());
        }
        Map<String, List<String>> rackToNodes = new HashMap<>();
        Map<String, String> resolvedSuperVisors =  TestNetworkTopographyPlugin.resolve(supHostnames);
        for (Map.Entry<String, String> entry : resolvedSuperVisors.entrySet()) {
            String hostName = entry.getKey();
            String rack = entry.getValue();
            List<String> nodesForRack = rackToNodes.get(rack);
            if (nodesForRack == null) {
                nodesForRack = new ArrayList<>();
                rackToNodes.put(rack, nodesForRack);
            }
            nodesForRack.add(hostName);
        }
        cluster.setNetworkTopography(rackToNodes);

        DefaultResourceAwareStrategy rs = new DefaultResourceAwareStrategy();
        
        rs.prepare(cluster);
        TreeSet<ObjectResources> sortedRacks = rs.sortRacks(null, topo1);
        LOG.info("Sorted Racks {}", sortedRacks);

        Assert.assertEquals("# of racks sorted", 6, sortedRacks.size());
        Iterator<ObjectResources> it = sortedRacks.iterator();
        // Ranked first since rack-0 has the most balanced set of resources
        Assert.assertEquals("rack-0 should be ordered first", "rack-0", it.next().id);
        // Ranked second since rack-1 has a balanced set of resources but less than rack-0
        Assert.assertEquals("rack-1 should be ordered second", "rack-1", it.next().id);
        // Ranked third since rack-4 has a lot of cpu but not a lot of memory
        Assert.assertEquals("rack-4 should be ordered third", "rack-4", it.next().id);
        // Ranked fourth since rack-3 has alot of memory but not cpu
        Assert.assertEquals("rack-3 should be ordered fourth", "rack-3", it.next().id);
        //Ranked fifth since rack-2 has not cpu resources
        Assert.assertEquals("rack-2 should be ordered fifth", "rack-2", it.next().id);
        //Ranked last since rack-5 has neither CPU nor memory available
        assertEquals("Rack-5 should be ordered sixth", "rack-5", it.next().id);

        SchedulingResult schedulingResult = rs.schedule(cluster, topo1);
        assert(schedulingResult.isSuccess());
        SchedulerAssignment assignment = cluster.getAssignmentById(topo1.getId());
        for (WorkerSlot ws : assignment.getSlotToExecutors().keySet()) {
            //make sure all workers on scheduled in rack-0
            Assert.assertEquals("assert worker scheduled on rack-0", "rack-0", resolvedSuperVisors.get(rs.idToNode(ws.getNodeId()).getHostname()));
        }
        Assert.assertEquals("All executors in topo-1 scheduled", 0, cluster.getUnassignedExecutors(topo1).size());

        //Test if topology is already partially scheduled on one rack
        Iterator<ExecutorDetails> executorIterator = topo2.getExecutors().iterator();
        List<String> nodeHostnames = rackToNodes.get("rack-1");
        for (int i = 0; i< topo2.getExecutors().size()/2; i++) {
            String nodeHostname = nodeHostnames.get(i % nodeHostnames.size());
            RAS_Node node = rs.hostnameToNodes(nodeHostname).get(0);
            WorkerSlot targetSlot = node.getFreeSlots().iterator().next();
            ExecutorDetails targetExec = executorIterator.next();
            // to keep track of free slots
            node.assign(targetSlot, topo2, Arrays.asList(targetExec));
        }

        rs = new DefaultResourceAwareStrategy();
        // schedule topo2
        schedulingResult = rs.schedule(cluster, topo2);
        assert(schedulingResult.isSuccess());
        assignment = cluster.getAssignmentById(topo2.getId());
        for (WorkerSlot ws : assignment.getSlotToExecutors().keySet()) {
            //make sure all workers on scheduled in rack-1
            Assert.assertEquals("assert worker scheduled on rack-1", "rack-1", resolvedSuperVisors.get(rs.idToNode(ws.getNodeId()).getHostname()));
        }
        Assert.assertEquals("All executors in topo-2 scheduled", 0, cluster.getUnassignedExecutors(topo1).size());
    }

    /**
     * Test whether strategy will choose correct rack
     */
    @Test
    public void testMultipleRacksWithFavoritism() {
        final Map<String, SupervisorDetails> supMap = new HashMap<>();
        final Map<String, SupervisorDetails> supMapRack0 = genSupervisors(10, 4, 0, 400, 8000);
        //generate another rack of supervisors with less resources
        final Map<String, SupervisorDetails> supMapRack1 = genSupervisors(10, 4, 10, 200, 4000);

        //generate some supervisors that are depleted of one resource
        final Map<String, SupervisorDetails> supMapRack2 = genSupervisors(10, 4, 20, 0, 8000);

        //generate some that has alot of memory but little of cpu
        final Map<String, SupervisorDetails> supMapRack3 = genSupervisors(10, 4, 30, 10, 8000 * 2 + 4000);

        //generate some that has alot of cpu but little of memory
        final Map<String, SupervisorDetails> supMapRack4 = genSupervisors(10, 4, 40, 400 + 200 + 10, 1000);

        supMap.putAll(supMapRack0);
        supMap.putAll(supMapRack1);
        supMap.putAll(supMapRack2);
        supMap.putAll(supMapRack3);
        supMap.putAll(supMapRack4);

        Config config = createClusterConfig(100, 500, 500, null);
        config.put(Config.TOPOLOGY_WORKER_MAX_HEAP_SIZE_MB, Double.MAX_VALUE);
        INimbus iNimbus = new INimbusTest();

        //create test DNSToSwitchMapping plugin
        DNSToSwitchMapping TestNetworkTopographyPlugin =
            new TestDNSToSwitchMapping(supMapRack0, supMapRack1, supMapRack2, supMapRack3, supMapRack4);

        Config t1Conf = new Config();
        t1Conf.putAll(config);
        final List<String> t1FavoredHostNames = Arrays.asList("host-41", "host-42", "host-43");
        t1Conf.put(Config.TOPOLOGY_SCHEDULER_FAVORED_NODES, t1FavoredHostNames);
        final List<String> t1UnfavoredHostIds = Arrays.asList("host-1", "host-2", "host-3");
        t1Conf.put(Config.TOPOLOGY_SCHEDULER_UNFAVORED_NODES, t1UnfavoredHostIds);
        //generate topologies
        TopologyDetails topo1 = genTopology("topo-1", t1Conf, 8, 0, 2, 0, CURRENT_TIME - 2, 10, "user");


        Config t2Conf = new Config();
        t2Conf.putAll(config);
        t2Conf.put(Config.TOPOLOGY_SCHEDULER_FAVORED_NODES, Arrays.asList("host-31", "host-32", "host-33"));
        t2Conf.put(Config.TOPOLOGY_SCHEDULER_UNFAVORED_NODES, Arrays.asList("host-11", "host-12", "host-13"));
        TopologyDetails topo2 = genTopology("topo-2", t2Conf, 8, 0, 2, 0, CURRENT_TIME - 2, 10, "user");

        Topologies topologies = new Topologies(topo1, topo2);
        Cluster cluster = new Cluster(iNimbus, new ResourceMetrics(new StormMetricsRegistry()), supMap, new HashMap<>(), topologies, config);

        List<String> supHostnames = new LinkedList<>();
        for (SupervisorDetails sup : supMap.values()) {
            supHostnames.add(sup.getHost());
        }
        Map<String, List<String>> rackToNodes = new HashMap<>();
        Map<String, String> resolvedSuperVisors =  TestNetworkTopographyPlugin.resolve(supHostnames);
        for (Map.Entry<String, String> entry : resolvedSuperVisors.entrySet()) {
            String hostName = entry.getKey();
            String rack = entry.getValue();
            List<String> nodesForRack = rackToNodes.get(rack);
            if (nodesForRack == null) {
                nodesForRack = new ArrayList<>();
                rackToNodes.put(rack, nodesForRack);
            }
            nodesForRack.add(hostName);
        }
        cluster.setNetworkTopography(rackToNodes);

        DefaultResourceAwareStrategy rs = new DefaultResourceAwareStrategy();

        rs.prepare(cluster);
        TreeSet<ObjectResources> sortedRacks= rs.sortRacks(null, topo1);

        Assert.assertEquals("# of racks sorted", 5, sortedRacks.size());
        Iterator<ObjectResources> it = sortedRacks.iterator();
        // Ranked first since rack-0 has the most balanced set of resources
        Assert.assertEquals("rack-0 should be ordered first", "rack-0", it.next().id);
        // Ranked second since rack-1 has a balanced set of resources but less than rack-0
        Assert.assertEquals("rack-1 should be ordered second", "rack-1", it.next().id);
        // Ranked third since rack-4 has a lot of cpu but not a lot of memory
        Assert.assertEquals("rack-4 should be ordered third", "rack-4", it.next().id);
        // Ranked fourth since rack-3 has alot of memory but not cpu
        Assert.assertEquals("rack-3 should be ordered fourth", "rack-3", it.next().id);
        //Ranked last since rack-2 has not cpu resources
        Assert.assertEquals("rack-2 should be ordered fifth", "rack-2", it.next().id);

        SchedulingResult schedulingResult = rs.schedule(cluster, topo1);
        assert(schedulingResult.isSuccess());
        SchedulerAssignment assignment = cluster.getAssignmentById(topo1.getId());
        for (WorkerSlot ws : assignment.getSlotToExecutors().keySet()) {
            String hostName = rs.idToNode(ws.getNodeId()).getHostname();
            String rackId = resolvedSuperVisors.get(hostName);
            Assert.assertTrue(ws + " is neither on a favored node " + t1FavoredHostNames + " nor the highest priority rack (rack-0)",
                t1FavoredHostNames.contains(hostName) || "rack-0".equals(rackId));
            Assert.assertFalse(ws + " is a part of an unfavored node " + t1UnfavoredHostIds,
                t1UnfavoredHostIds.contains(hostName));
        }
        Assert.assertEquals("All executors in topo-1 scheduled", 0, cluster.getUnassignedExecutors(topo1).size());

        //Test if topology is already partially scheduled on one rack
        Iterator<ExecutorDetails> executorIterator = topo2.getExecutors().iterator();
        List<String> nodeHostnames = rackToNodes.get("rack-1");
        for (int i = 0; i< topo2.getExecutors().size()/2; i++) {
            String nodeHostname = nodeHostnames.get(i % nodeHostnames.size());
            RAS_Node node = rs.hostnameToNodes(nodeHostname).get(0);
            WorkerSlot targetSlot = node.getFreeSlots().iterator().next();
            ExecutorDetails targetExec = executorIterator.next();
            // to keep track of free slots
            node.assign(targetSlot, topo2, Arrays.asList(targetExec));
        }

        rs = new DefaultResourceAwareStrategy();
        // schedule topo2
        schedulingResult = rs.schedule(cluster, topo2);
        assert(schedulingResult.isSuccess());
        assignment = cluster.getAssignmentById(topo2.getId());
        for (WorkerSlot ws : assignment.getSlotToExecutors().keySet()) {
            //make sure all workers on scheduled in rack-1
            // The favored nodes would have put it on a different rack, but because that rack does not have free space to run the
            // topology it falls back to this rack
            Assert.assertEquals("assert worker scheduled on rack-1", "rack-1", resolvedSuperVisors.get(rs.idToNode(ws.getNodeId()).getHostname()));
        }
        Assert.assertEquals("All executors in topo-2 scheduled", 0, cluster.getUnassignedExecutors(topo1).size());
    }
}
