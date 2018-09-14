// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.tasks.params.NodeTaskParams;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.ShellProcessHandler;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.TaskType;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.NodeDetails.NodeState;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.runners.MockitoJUnitRunner;
import org.yb.client.YBClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import play.libs.Json;

import java.util.*;
import java.util.stream.Collectors;

import static com.yugabyte.yw.common.AssertHelper.assertJsonEqual;
import static com.yugabyte.yw.common.ModelFactory.createUniverse;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.*;
import org.yb.client.ModifyMasterClusterConfigBlacklist;

@RunWith(MockitoJUnitRunner.class)
public class AddNodeToUniverseTest extends CommissionerBaseTest {
  public static final Logger LOG = LoggerFactory.getLogger(AddNodeToUniverseTest.class);

  @InjectMocks
  Commissioner commissioner;
  Universe defaultUniverse;
  ShellProcessHandler.ShellResponse dummyShellResponse;
  YBClient mockClient;
  ModifyMasterClusterConfigBlacklist modifyBL;

  @Before
  public void setUp() {
    super.setUp();
    Region region = Region.create(defaultProvider, "region-1", "Region 1", "yb-image-1");
    AvailabilityZone.create(region, "az-1", "AZ 1", "subnet-1");
    // create default universe
    UniverseDefinitionTaskParams.UserIntent userIntent =
        new UniverseDefinitionTaskParams.UserIntent();
    userIntent.numNodes = 3;
    userIntent.ybSoftwareVersion = "yb-version";
    userIntent.accessKeyCode = "demo-access";
    userIntent.regionList = ImmutableList.of(region.uuid);
    userIntent.replicationFactor = 3;
    defaultUniverse = createUniverse(defaultCustomer.getCustomerId());
    Universe.saveDetails(defaultUniverse.universeUUID,
        ApiUtils.mockUniverseUpdater(userIntent, true /* setMasters */));

    // Change one of the nodes' state to removed.
    Universe.UniverseUpdater updater = new Universe.UniverseUpdater() {
      public void run(Universe universe) {
        UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
        Set<NodeDetails> nodes = universeDetails.nodeDetailsSet;
        for (NodeDetails node : nodes) {
          if (node.nodeName.equals("host-n1")) {
            node.state = NodeState.Removed;
            break;
          }
        }
        universe.setUniverseDetails(universeDetails);
      }
    };

    Universe.saveDetails(defaultUniverse.universeUUID, updater);

    mockClient = mock(YBClient.class);
    when(mockYBClient.getClient(any())).thenReturn(mockClient);
    when(mockClient.waitForLoadBalance(anyLong(), anyInt())).thenReturn(true);
    dummyShellResponse =  new ShellProcessHandler.ShellResponse();
    when(mockNodeManager.nodeCommand(any(), any())).thenReturn(dummyShellResponse);
    modifyBL = mock(ModifyMasterClusterConfigBlacklist.class);
    try {
      doNothing().when(modifyBL).doCall();
    } catch (Exception e) {}
  }

  private TaskInfo submitTask(NodeTaskParams taskParams, String nodeName, int version) {
    taskParams.expectedUniverseVersion = version;
    taskParams.nodeName = nodeName;
    try {
      UUID taskUUID = commissioner.submit(TaskType.AddNodeToUniverse, taskParams);
      return waitForTask(taskUUID);
    } catch (InterruptedException e) {
      assertNull(e.getMessage());
    }
    return null;
  }

  List<TaskType> ADD_NODE_TASK_SEQUENCE = ImmutableList.of(
    TaskType.SetNodeState,
    TaskType.ModifyBlackList,
    TaskType.AnsibleClusterServerCtl,
    TaskType.WaitForServer,
    TaskType.SwamperTargetsFileUpdate,
    TaskType.WaitForLoadBalance,
    TaskType.UpdateNodeProcess,
    TaskType.SetNodeState,
    TaskType.UniverseUpdateSucceeded
  );

  List<JsonNode> ADD_NODE_TASK_EXPECTED_RESULTS = ImmutableList.of(
    Json.toJson(ImmutableMap.of("state", "Adding")),
    Json.toJson(ImmutableMap.of()),
    Json.toJson(ImmutableMap.of("process", "tserver", "command", "start")),
    Json.toJson(ImmutableMap.of()),
    Json.toJson(ImmutableMap.of()),
    Json.toJson(ImmutableMap.of()),
    Json.toJson(ImmutableMap.of("processType", "TSERVER", "isAdd", true)),
    Json.toJson(ImmutableMap.of("state", "Live")),
    Json.toJson(ImmutableMap.of())
  );

  List<TaskType> WITH_MASTER_UNDER_REPLICATED = ImmutableList.of(
    TaskType.SetNodeState,
    TaskType.AnsibleConfigureServers,
    TaskType.AnsibleClusterServerCtl,
    TaskType.WaitForServer,
    TaskType.ChangeMasterConfig,
    TaskType.UpdateNodeProcess,
    TaskType.ModifyBlackList,
    TaskType.AnsibleClusterServerCtl,
    TaskType.SwamperTargetsFileUpdate,
    TaskType.WaitForLoadBalance,
    TaskType.UpdateNodeProcess,
    TaskType.SetNodeState,
    TaskType.UniverseUpdateSucceeded
  );

  List<JsonNode> WITH_MASTER_UNDER_REPLICATED_RESULTS = ImmutableList.of(
    Json.toJson(ImmutableMap.of("state", "Adding")),
    Json.toJson(ImmutableMap.of()),
    Json.toJson(ImmutableMap.of("process", "master", "command", "start")),
    Json.toJson(ImmutableMap.of()),
    Json.toJson(ImmutableMap.of()),
    Json.toJson(ImmutableMap.of("processType", "MASTER", "isAdd", true)),
    Json.toJson(ImmutableMap.of()),
    Json.toJson(ImmutableMap.of("process", "tserver", "command", "start")),
    Json.toJson(ImmutableMap.of()),
    Json.toJson(ImmutableMap.of()),
    Json.toJson(ImmutableMap.of("processType", "TSERVER", "isAdd", true)),
    Json.toJson(ImmutableMap.of("state", "Live")),
    Json.toJson(ImmutableMap.of())
  );

  private void assertAddNodeSequence(Map<Integer, List<TaskInfo>> subTasksByPosition,
                                     boolean masterUnderReplicated) {
    int position = 0;
    if (masterUnderReplicated) {
      for (TaskType taskType: WITH_MASTER_UNDER_REPLICATED) {
        List<TaskInfo> tasks = subTasksByPosition.get(position);
        assertEquals(1, tasks.size());
        assertEquals(taskType, tasks.get(0).getTaskType());
        JsonNode expectedResults =
            WITH_MASTER_UNDER_REPLICATED_RESULTS.get(position);
        List<JsonNode> taskDetails = tasks.stream()
            .map(t -> t.getTaskDetails())
            .collect(Collectors.toList());
        assertJsonEqual(expectedResults, taskDetails.get(0));
        position++;
      }
    } else {
      for (TaskType taskType: ADD_NODE_TASK_SEQUENCE) {
        List<TaskInfo> tasks = subTasksByPosition.get(position);
        assertEquals(1, tasks.size());
        assertEquals(taskType, tasks.get(0).getTaskType());
        JsonNode expectedResults =
            ADD_NODE_TASK_EXPECTED_RESULTS.get(position);
        List<JsonNode> taskDetails = tasks.stream()
            .map(t -> t.getTaskDetails())
            .collect(Collectors.toList());
        LOG.info(taskDetails.get(0).toString());
        assertJsonEqual(expectedResults, taskDetails.get(0));
        position++;
      }
    }
  }

  @Ignore // Calls modifyBlackList.doCall() even with modifyBL.doNothin()!
  public void testAddNodeSuccess() {
    NodeTaskParams taskParams = new NodeTaskParams();
    taskParams.universeUUID = defaultUniverse.universeUUID;

    TaskInfo taskInfo = submitTask(taskParams, "host-n1", 3);
    verify(mockNodeManager, times(1)).nodeCommand(any(), any());
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));
    assertAddNodeSequence(subTasksByPosition, false);
  }

  // Need to mockito of ModifyUniverseConfig to enable with master.
  @Ignore
  public void testAddNodeWithUnderReplicatedMaster() {
    // Change one of the nodes' state to removed.
    Universe.UniverseUpdater updater = new Universe.UniverseUpdater() {
      public void run(Universe universe) {
        UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
        Set<NodeDetails> nodes = universeDetails.nodeDetailsSet;
        for (NodeDetails node : nodes) {
          if (node.nodeName.equals("host-n1")) {
            node.isMaster = false;
            break;
          }
        }
        universe.setUniverseDetails(universeDetails);
      }
    };
    Universe.saveDetails(defaultUniverse.universeUUID, updater);

    NodeTaskParams taskParams = new NodeTaskParams();
    taskParams.universeUUID = defaultUniverse.universeUUID;
    TaskInfo taskInfo = submitTask(taskParams, "host-n1", 4);
    verify(mockNodeManager, times(2)).nodeCommand(any(), any());
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));
    assertAddNodeSequence(subTasksByPosition, true);
  }

  @Test
  public void testAddUnknownNode() {
    NodeTaskParams taskParams = new NodeTaskParams();
    taskParams.universeUUID = defaultUniverse.universeUUID;
    TaskInfo taskInfo = submitTask(taskParams, "host-n9", 3);
    verify(mockNodeManager, times(0)).nodeCommand(any(), any());
    assertEquals(TaskInfo.State.Failure, taskInfo.getTaskState());
  }
}

