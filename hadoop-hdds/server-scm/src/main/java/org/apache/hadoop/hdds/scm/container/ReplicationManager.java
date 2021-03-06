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

package org.apache.hadoop.hdds.scm.container;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.hadoop.hdds.conf.Config;
import org.apache.hadoop.hdds.conf.ConfigGroup;
import org.apache.hadoop.hdds.conf.ConfigType;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleState;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeState;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.ContainerReplicaProto.State;
import org.apache.hadoop.hdds.scm.ContainerPlacementStatus;
import org.apache.hadoop.hdds.scm.PlacementPolicy;
import org.apache.hadoop.hdds.scm.events.SCMEvents;
import org.apache.hadoop.hdds.scm.node.NodeManager;
import org.apache.hadoop.hdds.scm.safemode.SCMSafeModeManager.SafeModeStatus;
import org.apache.hadoop.hdds.server.events.EventHandler;
import org.apache.hadoop.hdds.server.events.EventPublisher;
import org.apache.hadoop.metrics2.MetricsCollector;
import org.apache.hadoop.metrics2.MetricsInfo;
import org.apache.hadoop.metrics2.MetricsSource;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.ozone.lock.LockManager;
import org.apache.hadoop.ozone.protocol.commands.CloseContainerCommand;
import org.apache.hadoop.ozone.protocol.commands.CommandForDatanode;
import org.apache.hadoop.ozone.protocol.commands.DeleteContainerCommand;
import org.apache.hadoop.ozone.protocol.commands.ReplicateContainerCommand;
import org.apache.hadoop.ozone.protocol.commands.SCMCommand;
import org.apache.hadoop.util.ExitUtil;
import org.apache.hadoop.util.Time;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.GeneratedMessage;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import static org.apache.hadoop.hdds.conf.ConfigTag.OZONE;
import static org.apache.hadoop.hdds.conf.ConfigTag.SCM;
import org.apache.ratis.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replication Manager (RM) is the one which is responsible for making sure
 * that the containers are properly replicated. Replication Manager deals only
 * with Quasi Closed / Closed container.
 */
public class ReplicationManager
    implements MetricsSource, EventHandler<SafeModeStatus> {

  public static final Logger LOG =
      LoggerFactory.getLogger(ReplicationManager.class);

  public static final String METRICS_SOURCE_NAME = "SCMReplicationManager";

  /**
   * Reference to the ContainerManager.
   */
  private final ContainerManager containerManager;

  /**
   * PlacementPolicy which is used to identify where a container
   * should be replicated.
   */
  private final PlacementPolicy containerPlacement;

  /**
   * EventPublisher to fire Replicate and Delete container events.
   */
  private final EventPublisher eventPublisher;

  /**
   * Used for locking a container using its ID while processing it.
   */
  private final LockManager<ContainerID> lockManager;

  /**
   * This is used for tracking container replication commands which are issued
   * by ReplicationManager and not yet complete.
   */
  private final Map<ContainerID, List<InflightAction>> inflightReplication;

  /**
   * This is used for tracking container deletion commands which are issued
   * by ReplicationManager and not yet complete.
   */
  private final Map<ContainerID, List<InflightAction>> inflightDeletion;

  /**
   * ReplicationManager specific configuration.
   */
  private final ReplicationManagerConfiguration conf;

  /**
   * ReplicationMonitor thread is the one which wakes up at configured
   * interval and processes all the containers.
   */
  private Thread replicationMonitor;

  /**
   * Flag used for checking if the ReplicationMonitor thread is running or
   * not.
   */
  private volatile boolean running;

  /**
   * Used for check datanode state.
   */
  private final NodeManager nodeManager;

  /**
   * Constructs ReplicationManager instance with the given configuration.
   *
   * @param conf OzoneConfiguration
   * @param containerManager ContainerManager
   * @param containerPlacement PlacementPolicy
   * @param eventPublisher EventPublisher
   */
  public ReplicationManager(final ReplicationManagerConfiguration conf,
                            final ContainerManager containerManager,
                            final PlacementPolicy containerPlacement,
                            final EventPublisher eventPublisher,
                            final LockManager<ContainerID> lockManager,
                            final NodeManager nodeManager) {
    this.containerManager = containerManager;
    this.containerPlacement = containerPlacement;
    this.eventPublisher = eventPublisher;
    this.lockManager = lockManager;
    this.conf = conf;
    this.running = false;
    this.inflightReplication = new ConcurrentHashMap<>();
    this.inflightDeletion = new ConcurrentHashMap<>();
    this.nodeManager = nodeManager;
  }

  /**
   * Starts Replication Monitor thread.
   */
  public synchronized void start() {

    if (!isRunning()) {
      DefaultMetricsSystem.instance().register(METRICS_SOURCE_NAME,
          "SCM Replication manager (closed container replication) related "
              + "metrics",
          this);
      LOG.info("Starting Replication Monitor Thread.");
      running = true;
      replicationMonitor = new Thread(this::run);
      replicationMonitor.setName("ReplicationMonitor");
      replicationMonitor.setDaemon(true);
      replicationMonitor.start();
    } else {
      LOG.info("Replication Monitor Thread is already running.");
    }
  }

  /**
   * Returns true if the Replication Monitor Thread is running.
   *
   * @return true if running, false otherwise
   */
  public boolean isRunning() {
    if (!running) {
      synchronized (this) {
        return replicationMonitor != null
            && replicationMonitor.isAlive();
      }
    }
    return true;
  }

  /**
   * Process all the containers immediately.
   */
  @VisibleForTesting
  @SuppressFBWarnings(value="NN_NAKED_NOTIFY",
      justification="Used only for testing")
  public synchronized void processContainersNow() {
    notifyAll();
  }

  /**
   * Stops Replication Monitor thread.
   */
  public synchronized void stop() {
    if (running) {
      LOG.info("Stopping Replication Monitor Thread.");
      inflightReplication.clear();
      inflightDeletion.clear();
      running = false;
      DefaultMetricsSystem.instance().unregisterSource(METRICS_SOURCE_NAME);
      notifyAll();
    } else {
      LOG.info("Replication Monitor Thread is not running.");
    }
  }

  /**
   * ReplicationMonitor thread runnable. This wakes up at configured
   * interval and processes all the containers in the system.
   */
  private synchronized void run() {
    try {
      while (running) {
        final long start = Time.monotonicNow();
        final Set<ContainerID> containerIds =
            containerManager.getContainerIDs();
        containerIds.forEach(this::processContainer);

        LOG.info("Replication Monitor Thread took {} milliseconds for" +
                " processing {} containers.", Time.monotonicNow() - start,
            containerIds.size());

        wait(conf.getInterval());
      }
    } catch (Throwable t) {
      // When we get runtime exception, we should terminate SCM.
      LOG.error("Exception in Replication Monitor Thread.", t);
      ExitUtil.terminate(1, t);
    }
  }

  /**
   * Process the given container.
   *
   * @param id ContainerID
   */
  private void processContainer(ContainerID id) {
    lockManager.lock(id);
    try {
      final ContainerInfo container = containerManager.getContainer(id);
      final Set<ContainerReplica> replicas = containerManager
          .getContainerReplicas(container.containerID());
      final LifeCycleState state = container.getState();

      /*
       * We don't take any action if the container is in OPEN state and
       * the container is healthy. If the container is not healthy, i.e.
       * the replicas are not in OPEN state, send CLOSE_CONTAINER command.
       */
      if (state == LifeCycleState.OPEN) {
        if (!isContainerHealthy(container, replicas)) {
          eventPublisher.fireEvent(SCMEvents.CLOSE_CONTAINER, id);
        }
        return;
      }

      /*
       * If the container is in CLOSING state, the replicas can either
       * be in OPEN or in CLOSING state. In both of this cases
       * we have to resend close container command to the datanodes.
       */
      if (state == LifeCycleState.CLOSING) {
        replicas.forEach(replica -> sendCloseCommand(
            container, replica.getDatanodeDetails(), false));
        return;
      }

      /*
       * If the container is in QUASI_CLOSED state, check and close the
       * container if possible.
       */
      if (state == LifeCycleState.QUASI_CLOSED &&
          canForceCloseContainer(container, replicas)) {
        forceCloseContainer(container, replicas);
        return;
      }

      /*
       * Before processing the container we have to reconcile the
       * inflightReplication and inflightDeletion actions.
       *
       * We remove the entry from inflightReplication and inflightDeletion
       * list, if the operation is completed or if it has timed out.
       */
      updateInflightAction(container, inflightReplication,
          action -> replicas.stream()
              .anyMatch(r -> r.getDatanodeDetails().equals(action.datanode)));

      updateInflightAction(container, inflightDeletion,
          action -> replicas.stream()
              .noneMatch(r -> r.getDatanodeDetails().equals(action.datanode)));

      /*
       * If container is under deleting and all it's replicas are deleted, then
       * make the container as CLEANED, or resend the delete replica command if
       * needed.
       */
      if (state == LifeCycleState.DELETING) {
        handleContainerUnderDelete(container, replicas);
        return;
      }

      /*
       * We don't have to take any action if the container is healthy.
       *
       * According to ReplicationMonitor container is considered healthy if
       * the container is either in QUASI_CLOSED or in CLOSED state and has
       * exact number of replicas in the same state.
       */
      if (isContainerHealthy(container, replicas)) {
        /*
         *  If container is empty, schedule task to delete the container.
         */
        if (isContainerEmpty(container, replicas)) {
          deleteContainerReplicas(container, replicas);
        }
        return;
      }

      /*
       * Check if the container is under replicated and take appropriate
       * action.
       */
      if (isContainerUnderReplicated(container, replicas)) {
        handleUnderReplicatedContainer(container, replicas);
        return;
      }

      /*
       * Check if the container is over replicated and take appropriate
       * action.
       */
      if (isContainerOverReplicated(container, replicas)) {
        handleOverReplicatedContainer(container, replicas);
        return;
      }

      /*
       * The container is neither under nor over replicated and the container
       * is not healthy. This means that the container has unhealthy/corrupted
       * replica.
       */
      handleUnstableContainer(container, replicas);

    } catch (ContainerNotFoundException ex) {
      LOG.warn("Missing container {}.", id);
    } catch (Exception ex) {
      LOG.warn("Process container {} error: ", id, ex);
    } finally {
      lockManager.unlock(id);
    }
  }

  /**
   * Reconciles the InflightActions for a given container.
   *
   * @param container Container to update
   * @param inflightActions inflightReplication (or) inflightDeletion
   * @param filter filter to check if the operation is completed
   */
  private void updateInflightAction(final ContainerInfo container,
      final Map<ContainerID, List<InflightAction>> inflightActions,
      final Predicate<InflightAction> filter) {
    final ContainerID id = container.containerID();
    final long deadline = Time.monotonicNow() - conf.getEventTimeout();
    if (inflightActions.containsKey(id)) {
      final List<InflightAction> actions = inflightActions.get(id);

      actions.removeIf(action ->
          nodeManager.getNodeState(action.datanode) != NodeState.HEALTHY);
      actions.removeIf(action -> action.time < deadline);
      actions.removeIf(filter);
      if (actions.isEmpty()) {
        inflightActions.remove(id);
      }
    }
  }

  /**
   * Returns true if the container is healthy according to ReplicationMonitor.
   *
   * According to ReplicationMonitor container is considered healthy if
   * it has exact number of replicas in the same state as the container.
   *
   * @param container Container to check
   * @param replicas Set of ContainerReplicas
   * @return true if the container is healthy, false otherwise
   */
  private boolean isContainerHealthy(final ContainerInfo container,
                                     final Set<ContainerReplica> replicas) {
    return !isContainerUnderReplicated(container, replicas) &&
        !isContainerOverReplicated(container, replicas) &&
        replicas.stream().allMatch(
            r -> compareState(container.getState(), r.getState()));
  }

  /**
   * Returns true if the container is empty and CLOSED.
   *
   * @param container Container to check
   * @param replicas Set of ContainerReplicas
   * @return true if the container is empty, false otherwise
   */
  private boolean isContainerEmpty(final ContainerInfo container,
      final Set<ContainerReplica> replicas) {
    return container.getState() == LifeCycleState.CLOSED &&
        (container.getUsedBytes() == 0 && container.getNumberOfKeys() == 0) &&
        replicas.stream().allMatch(r -> r.getState() == State.CLOSED &&
            r.getBytesUsed() == 0 && r.getKeyCount() == 0);
  }

  /**
   * Checks if the container is under replicated or not.
   *
   * @param container Container to check
   * @param replicas Set of ContainerReplicas
   * @return true if the container is under replicated, false otherwise
   */
  private boolean isContainerUnderReplicated(final ContainerInfo container,
      final Set<ContainerReplica> replicas) {
    if (container.getState() == LifeCycleState.DELETING ||
        container.getState() == LifeCycleState.DELETED) {
      return false;
    }
    boolean misReplicated = !getPlacementStatus(
        replicas, container.getReplicationFactor().getNumber())
        .isPolicySatisfied();
    return container.getReplicationFactor().getNumber() >
        getReplicaCount(container.containerID(), replicas) || misReplicated;
  }

  /**
   * Checks if the container is over replicated or not.
   *
   * @param container Container to check
   * @param replicas Set of ContainerReplicas
   * @return true if the container if over replicated, false otherwise
   */
  private boolean isContainerOverReplicated(final ContainerInfo container,
      final Set<ContainerReplica> replicas) {
    return container.getReplicationFactor().getNumber() <
        getReplicaCount(container.containerID(), replicas);
  }

  /**
   * Returns the replication count of the given container. This also
   * considers inflight replication and deletion.
   *
   * @param id ContainerID
   * @param replicas Set of existing replicas
   * @return number of estimated replicas for this container
   */
  private int getReplicaCount(final ContainerID id,
                              final Set<ContainerReplica> replicas) {
    return replicas.size()
        + inflightReplication.getOrDefault(id, Collections.emptyList()).size()
        - inflightDeletion.getOrDefault(id, Collections.emptyList()).size();
  }

  /**
   * Returns true if more than 50% of the container replicas with unique
   * originNodeId are in QUASI_CLOSED state.
   *
   * @param container Container to check
   * @param replicas Set of ContainerReplicas
   * @return true if we can force close the container, false otherwise
   */
  private boolean canForceCloseContainer(final ContainerInfo container,
      final Set<ContainerReplica> replicas) {
    Preconditions.assertTrue(container.getState() ==
        LifeCycleState.QUASI_CLOSED);
    final int replicationFactor = container.getReplicationFactor().getNumber();
    final long uniqueQuasiClosedReplicaCount = replicas.stream()
        .filter(r -> r.getState() == State.QUASI_CLOSED)
        .map(ContainerReplica::getOriginDatanodeId)
        .distinct()
        .count();
    return uniqueQuasiClosedReplicaCount > (replicationFactor / 2);
  }

  /**
   * Delete the container and its replicas.
   *
   * @param container ContainerInfo
   * @param replicas Set of ContainerReplicas
   */
  private void deleteContainerReplicas(final ContainerInfo container,
      final Set<ContainerReplica> replicas) throws IOException {
    Preconditions.assertTrue(container.getState() ==
        LifeCycleState.CLOSED);
    Preconditions.assertTrue(container.getNumberOfKeys() == 0);
    Preconditions.assertTrue(container.getUsedBytes() == 0);

    replicas.stream().forEach(rp -> {
      Preconditions.assertTrue(rp.getState() == State.CLOSED);
      Preconditions.assertTrue(rp.getBytesUsed() == 0);
      Preconditions.assertTrue(rp.getKeyCount() == 0);
      sendDeleteCommand(container, rp.getDatanodeDetails(), false);
    });
    containerManager.updateContainerState(container.containerID(),
        HddsProtos.LifeCycleEvent.DELETE);
    LOG.debug("Deleting empty container {} replicas,", container.containerID());
  }

  /**
   * Handle the container which is under delete.
   *
   * @param container ContainerInfo
   * @param replicas Set of ContainerReplicas
   */
  private void handleContainerUnderDelete(final ContainerInfo container,
      final Set<ContainerReplica> replicas) throws IOException {
    if (replicas.size() == 0) {
      containerManager.updateContainerState(container.containerID(),
          HddsProtos.LifeCycleEvent.CLEANUP);
      LOG.debug("Container {} state changes to DELETED",
          container.containerID());
    } else {
      // Check whether to resend the delete replica command
      final List<DatanodeDetails> deletionInFlight = inflightDeletion
          .getOrDefault(container.containerID(), Collections.emptyList())
          .stream()
          .map(action -> action.datanode)
          .collect(Collectors.toList());
      Set<ContainerReplica> filteredReplicas = replicas.stream().filter(
          r -> !deletionInFlight.contains(r.getDatanodeDetails()))
          .collect(Collectors.toSet());
      // Resend the delete command
      if (filteredReplicas.size() > 0) {
        filteredReplicas.stream().forEach(rp -> {
          sendDeleteCommand(container, rp.getDatanodeDetails(), false);
        });
        LOG.debug("Resend delete Container {} command",
            container.containerID());
      }
    }
  }

  /**
   * Force close the container replica(s) with highest sequence Id.
   *
   * <p>
   *   Note: We should force close the container only if >50% (quorum)
   *   of replicas with unique originNodeId are in QUASI_CLOSED state.
   * </p>
   *
   * @param container ContainerInfo
   * @param replicas Set of ContainerReplicas
   */
  private void forceCloseContainer(final ContainerInfo container,
                                   final Set<ContainerReplica> replicas) {
    Preconditions.assertTrue(container.getState() ==
        LifeCycleState.QUASI_CLOSED);

    final List<ContainerReplica> quasiClosedReplicas = replicas.stream()
        .filter(r -> r.getState() == State.QUASI_CLOSED)
        .collect(Collectors.toList());

    final Long sequenceId = quasiClosedReplicas.stream()
        .map(ContainerReplica::getSequenceId)
        .max(Long::compare)
        .orElse(-1L);

    LOG.info("Force closing container {} with BCSID {}," +
        " which is in QUASI_CLOSED state.",
        container.containerID(), sequenceId);

    quasiClosedReplicas.stream()
        .filter(r -> sequenceId != -1L)
        .filter(replica -> replica.getSequenceId().equals(sequenceId))
        .forEach(replica -> sendCloseCommand(
            container, replica.getDatanodeDetails(), true));
  }

  /**
   * If the given container is under replicated, identify a new set of
   * datanode(s) to replicate the container using PlacementPolicy
   * and send replicate container command to the identified datanode(s).
   *
   * @param container ContainerInfo
   * @param replicas Set of ContainerReplicas
   */
  private void handleUnderReplicatedContainer(final ContainerInfo container,
      final Set<ContainerReplica> replicas) {
    LOG.debug("Handling under-replicated container: {}",
        container.getContainerID());
    try {
      final ContainerID id = container.containerID();
      final List<DatanodeDetails> deletionInFlight = inflightDeletion
          .getOrDefault(id, Collections.emptyList())
          .stream()
          .map(action -> action.datanode)
          .collect(Collectors.toList());
      final List<DatanodeDetails> replicationInFlight = inflightReplication
          .getOrDefault(id, Collections.emptyList())
          .stream()
          .map(action -> action.datanode)
          .collect(Collectors.toList());
      final List<DatanodeDetails> source = replicas.stream()
          .filter(r ->
              r.getState() == State.QUASI_CLOSED ||
              r.getState() == State.CLOSED)
          .filter(r -> !deletionInFlight.contains(r.getDatanodeDetails()))
          .sorted((r1, r2) -> r2.getSequenceId().compareTo(r1.getSequenceId()))
          .map(ContainerReplica::getDatanodeDetails)
          .collect(Collectors.toList());
      if (source.size() > 0) {
        final int replicationFactor = container
            .getReplicationFactor().getNumber();
        // Want to check if the container is mis-replicated after considering
        // inflight add and delete.
        // Create a new list from source (healthy replicas minus pending delete)
        List<DatanodeDetails> targetReplicas = new ArrayList<>(source);
        // Then add any pending additions
        targetReplicas.addAll(replicationInFlight);
        final ContainerPlacementStatus placementStatus =
            containerPlacement.validateContainerPlacement(
                targetReplicas, replicationFactor);
        int delta = replicationFactor - getReplicaCount(id, replicas);
        final int misRepDelta = placementStatus.misReplicationCount();
        final int replicasNeeded
            = delta < misRepDelta ? misRepDelta : delta;
        if (replicasNeeded <= 0) {
          LOG.debug("Container {} meets replication requirement with " +
              "inflight replicas", id);
          return;
        }

        final List<DatanodeDetails> excludeList = replicas.stream()
            .map(ContainerReplica::getDatanodeDetails)
            .collect(Collectors.toList());
        excludeList.addAll(replicationInFlight);
        final List<DatanodeDetails> selectedDatanodes = containerPlacement
            .chooseDatanodes(excludeList, null, replicasNeeded,
                container.getUsedBytes());
        if (delta > 0) {
          LOG.info("Container {} is under replicated. Expected replica count" +
                  " is {}, but found {}.", id, replicationFactor,
              replicationFactor - delta);
        }
        int newMisRepDelta = misRepDelta;
        if (misRepDelta > 0) {
          LOG.info("Container: {}. {}",
              id, placementStatus.misReplicatedReason());
          // Check if the new target nodes (original plus newly selected nodes)
          // makes the placement policy valid.
          targetReplicas.addAll(selectedDatanodes);
          newMisRepDelta = containerPlacement.validateContainerPlacement(
              targetReplicas, replicationFactor).misReplicationCount();
        }
        if (delta > 0 || newMisRepDelta < misRepDelta) {
          // Only create new replicas if we are missing a replicas or
          // the number of pending mis-replication has improved. No point in
          // creating new replicas for mis-replicated containers unless it
          // improves things.
          for (DatanodeDetails datanode : selectedDatanodes) {
            sendReplicateCommand(container, datanode, source);
          }
        } else {
          LOG.warn("Container {} is mis-replicated, requiring {} additional " +
              "replicas. After selecting new nodes, mis-replication has not " +
              "improved. No additional replicas will be scheduled",
              id, misRepDelta);
        }
      } else {
        LOG.warn("Cannot replicate container {}, no healthy replica found.",
            container.containerID());
      }
    } catch (IOException ex) {
      LOG.warn("Exception while replicating container {}.",
          container.getContainerID(), ex);
    }
  }

  /**
   * If the given container is over replicated, identify the datanode(s)
   * to delete the container and send delete container command to the
   * identified datanode(s).
   *
   * @param container ContainerInfo
   * @param replicas Set of ContainerReplicas
   */
  private void handleOverReplicatedContainer(final ContainerInfo container,
      final Set<ContainerReplica> replicas) {

    final ContainerID id = container.containerID();
    final int replicationFactor = container.getReplicationFactor().getNumber();
    // Don't consider inflight replication while calculating excess here.
    int excess = replicas.size() - replicationFactor -
        inflightDeletion.getOrDefault(id, Collections.emptyList()).size();

    if (excess > 0) {

      LOG.info("Container {} is over replicated. Expected replica count" +
              " is {}, but found {}.", id, replicationFactor,
          replicationFactor + excess);

      final List<ContainerReplica> eligibleReplicas = new ArrayList<>(replicas);

      final Map<UUID, ContainerReplica> uniqueReplicas =
          new LinkedHashMap<>();

      if (container.getState() != LifeCycleState.CLOSED) {
        replicas.stream()
            .filter(r -> compareState(container.getState(), r.getState()))
            .forEach(r -> uniqueReplicas
                .putIfAbsent(r.getOriginDatanodeId(), r));

        // Retain one healthy replica per origin node Id.
        eligibleReplicas.removeAll(uniqueReplicas.values());
      }

      final List<ContainerReplica> unhealthyReplicas = eligibleReplicas
          .stream()
          .filter(r -> !compareState(container.getState(), r.getState()))
          .collect(Collectors.toList());

      // If there are unhealthy replicas, then we should remove them even if it
      // makes the container violate the placement policy, as excess unhealthy
      // containers are not really useful. It will be corrected later as a
      // mis-replicated container will be seen as under-replicated.
      for (ContainerReplica r : unhealthyReplicas) {
        if (excess > 0) {
          sendDeleteCommand(container, r.getDatanodeDetails(), true);
          excess -= 1;
        } else {
          break;
        }
      }
      // After removing all unhealthy replicas, if the container is still over
      // replicated then we need to check if it is already mis-replicated.
      // If it is, we do no harm by removing excess replicas. However, if it is
      // not mis-replicated, then we can only remove replicas if they don't
      // make the container become mis-replicated.
      if (excess > 0) {
        eligibleReplicas.removeAll(unhealthyReplicas);
        Set<ContainerReplica> replicaSet = new HashSet<>(eligibleReplicas);
        ContainerPlacementStatus ps =
            getPlacementStatus(replicaSet, replicationFactor);
        for (ContainerReplica r : eligibleReplicas) {
          if (excess <= 0) {
            break;
          }
          // First remove the replica we are working on from the set, and then
          // check if the set is now mis-replicated.
          replicaSet.remove(r);
          ContainerPlacementStatus nowPS =
              getPlacementStatus(replicaSet, replicationFactor);
          if ((!ps.isPolicySatisfied()
                && nowPS.actualPlacementCount() == ps.actualPlacementCount())
              || (ps.isPolicySatisfied() && nowPS.isPolicySatisfied())) {
            // Remove the replica if the container was already unsatisfied
            // and losing this replica keep actual placement count unchanged.
            // OR if losing this replica still keep satisfied
            sendDeleteCommand(container, r.getDatanodeDetails(), true);
            excess -= 1;
            continue;
          }
          // If we decided not to remove this replica, put it back into the set
          replicaSet.add(r);
        }
        if (excess > 0) {
          LOG.info("The container {} is over replicated with {} excess " +
              "replica. The excess replicas cannot be removed without " +
              "violating the placement policy", container, excess);
        }
      }
    }
  }

  /**
   * Given a set of ContainerReplica, transform it to a list of DatanodeDetails
   * and then check if the list meets the container placement policy.
   * @param replicas List of containerReplica
   * @param replicationFactor Expected Replication Factor of the containe
   * @return ContainerPlacementStatus indicating if the policy is met or not
   */
  private ContainerPlacementStatus getPlacementStatus(
      Set<ContainerReplica> replicas, int replicationFactor) {
    List<DatanodeDetails> replicaDns = replicas.stream()
        .map(c -> c.getDatanodeDetails()).collect(Collectors.toList());
    return containerPlacement.validateContainerPlacement(
        replicaDns, replicationFactor);
  }

  /**
   * Handles unstable container.
   * A container is inconsistent if any of the replica state doesn't
   * match the container state. We have to take appropriate action
   * based on state of the replica.
   *
   * @param container ContainerInfo
   * @param replicas Set of ContainerReplicas
   */
  private void handleUnstableContainer(final ContainerInfo container,
      final Set<ContainerReplica> replicas) {
    // Find unhealthy replicas
    List<ContainerReplica> unhealthyReplicas = replicas.stream()
        .filter(r -> !compareState(container.getState(), r.getState()))
        .collect(Collectors.toList());

    Iterator<ContainerReplica> iterator = unhealthyReplicas.iterator();
    while (iterator.hasNext()) {
      final ContainerReplica replica = iterator.next();
      final State state = replica.getState();
      if (state == State.OPEN || state == State.CLOSING) {
        sendCloseCommand(container, replica.getDatanodeDetails(), false);
        iterator.remove();
      }

      if (state == State.QUASI_CLOSED) {
        // Send force close command if the BCSID matches
        if (container.getSequenceId() == replica.getSequenceId()) {
          sendCloseCommand(container, replica.getDatanodeDetails(), true);
          iterator.remove();
        }
      }
    }

    // Now we are left with the replicas which are either unhealthy or
    // the BCSID doesn't match. These replicas should be deleted.

    /*
     * If we have unhealthy replicas we go under replicated and then
     * replicate the healthy copy.
     *
     * We also make sure that we delete only one unhealthy replica at a time.
     *
     * If there are two unhealthy replica:
     *  - Delete first unhealthy replica
     *  - Re-replicate the healthy copy
     *  - Delete second unhealthy replica
     *  - Re-replicate the healthy copy
     *
     * Note: Only one action will be executed in a single ReplicationMonitor
     *       iteration. So to complete all the above actions we need four
     *       ReplicationMonitor iterations.
     */

    unhealthyReplicas.stream().findFirst().ifPresent(replica ->
        sendDeleteCommand(container, replica.getDatanodeDetails(), false));

  }

  /**
   * Sends close container command for the given container to the given
   * datanode.
   *
   * @param container Container to be closed
   * @param datanode The datanode on which the container
   *                  has to be closed
   * @param force Should be set to true if we want to close a
   *               QUASI_CLOSED container
   */
  private void sendCloseCommand(final ContainerInfo container,
                                final DatanodeDetails datanode,
                                final boolean force) {

    LOG.info("Sending close container command for container {}" +
            " to datanode {}.", container.containerID(), datanode);

    CloseContainerCommand closeContainerCommand =
        new CloseContainerCommand(container.getContainerID(),
            container.getPipelineID(), force);
    eventPublisher.fireEvent(SCMEvents.DATANODE_COMMAND,
        new CommandForDatanode<>(datanode.getUuid(), closeContainerCommand));
  }

  /**
   * Sends replicate container command for the given container to the given
   * datanode.
   *
   * @param container Container to be replicated
   * @param datanode The destination datanode to replicate
   * @param sources List of source nodes from where we can replicate
   */
  private void sendReplicateCommand(final ContainerInfo container,
                                    final DatanodeDetails datanode,
                                    final List<DatanodeDetails> sources) {

    LOG.info("Sending replicate container command for container {}" +
            " to datanode {}", container.containerID(), datanode);

    final ContainerID id = container.containerID();
    final ReplicateContainerCommand replicateCommand =
        new ReplicateContainerCommand(id.getId(), sources);
    inflightReplication.computeIfAbsent(id, k -> new ArrayList<>());
    sendAndTrackDatanodeCommand(datanode, replicateCommand,
        action -> inflightReplication.get(id).add(action));
  }

  /**
   * Sends delete container command for the given container to the given
   * datanode.
   *
   * @param container Container to be deleted
   * @param datanode The datanode on which the replica should be deleted
   * @param force Should be set to true to delete an OPEN replica
   */
  private void sendDeleteCommand(final ContainerInfo container,
                                 final DatanodeDetails datanode,
                                 final boolean force) {

    LOG.info("Sending delete container command for container {}" +
            " to datanode {}", container.containerID(), datanode);

    final ContainerID id = container.containerID();
    final DeleteContainerCommand deleteCommand =
        new DeleteContainerCommand(id.getId(), force);
    inflightDeletion.computeIfAbsent(id, k -> new ArrayList<>());
    sendAndTrackDatanodeCommand(datanode, deleteCommand,
        action -> inflightDeletion.get(id).add(action));
  }

  /**
   * Creates CommandForDatanode with the given SCMCommand and fires
   * DATANODE_COMMAND event to event queue.
   *
   * Tracks the command using the given tracker.
   *
   * @param datanode Datanode to which the command has to be sent
   * @param command SCMCommand to be sent
   * @param tracker Tracker which tracks the inflight actions
   * @param <T> Type of SCMCommand
   */
  private <T extends GeneratedMessage> void sendAndTrackDatanodeCommand(
      final DatanodeDetails datanode,
      final SCMCommand<T> command,
      final Consumer<InflightAction> tracker) {
    final CommandForDatanode<T> datanodeCommand =
        new CommandForDatanode<>(datanode.getUuid(), command);
    eventPublisher.fireEvent(SCMEvents.DATANODE_COMMAND, datanodeCommand);
    tracker.accept(new InflightAction(datanode, Time.monotonicNow()));
  }

  /**
   * Compares the container state with the replica state.
   *
   * @param containerState ContainerState
   * @param replicaState ReplicaState
   * @return true if the state matches, false otherwise
   */
  private static boolean compareState(final LifeCycleState containerState,
                                      final State replicaState) {
    switch (containerState) {
    case OPEN:
      return replicaState == State.OPEN;
    case CLOSING:
      return replicaState == State.CLOSING;
    case QUASI_CLOSED:
      return replicaState == State.QUASI_CLOSED;
    case CLOSED:
      return replicaState == State.CLOSED;
    case DELETING:
      return false;
    case DELETED:
      return false;
    default:
      return false;
    }
  }

  @Override
  public void getMetrics(MetricsCollector collector, boolean all) {
    collector.addRecord(ReplicationManager.class.getSimpleName())
        .addGauge(ReplicationManagerMetrics.INFLIGHT_REPLICATION,
            inflightReplication.size())
        .addGauge(ReplicationManagerMetrics.INFLIGHT_DELETION,
            inflightDeletion.size())
        .endRecord();
  }

  @Override
  public void onMessage(SafeModeStatus status,
      EventPublisher publisher) {
    if (!status.isInSafeMode() && !this.isRunning()) {
      this.start();
    }
  }

  /**
   * Wrapper class to hold the InflightAction with its start time.
   */
  private static final class InflightAction {

    private final DatanodeDetails datanode;
    private final long time;

    private InflightAction(final DatanodeDetails datanode,
                           final long time) {
      this.datanode = datanode;
      this.time = time;
    }
  }

  /**
   * Configuration used by the Replication Manager.
   */
  @ConfigGroup(prefix = "hdds.scm.replication")
  public static class ReplicationManagerConfiguration {
    /**
     * The frequency in which ReplicationMonitor thread should run.
     */
    @Config(key = "thread.interval",
        type = ConfigType.TIME,
        defaultValue = "300s",
        tags = {SCM, OZONE},
        description = "There is a replication monitor thread running inside " +
            "SCM which takes care of replicating the containers in the " +
            "cluster. This property is used to configure the interval in " +
            "which that thread runs."
    )
    private long interval = Duration.ofSeconds(300).toMillis();

    /**
     * Timeout for container replication & deletion command issued by
     * ReplicationManager.
     */
    @Config(key = "event.timeout",
        type = ConfigType.TIME,
        defaultValue = "30m",
        tags = {SCM, OZONE},
        description = "Timeout for the container replication/deletion commands "
            + "sent  to datanodes. After this timeout the command will be "
            + "retried.")
    private long eventTimeout = Duration.ofMinutes(30).toMillis();

    public void setInterval(Duration interval) {
      this.interval = interval.toMillis();
    }

    public void setEventTimeout(Duration timeout) {
      this.eventTimeout = timeout.toMillis();
    }

    public long getInterval() {
      return interval;
    }

    public long getEventTimeout() {
      return eventTimeout;
    }
  }

  /**
   * Metric name definitions for Replication manager.
   */
  public enum ReplicationManagerMetrics implements MetricsInfo {

    INFLIGHT_REPLICATION("Tracked inflight container replication requests."),
    INFLIGHT_DELETION("Tracked inflight container deletion requests.");

    private final String desc;

    ReplicationManagerMetrics(String desc) {
      this.desc = desc;
    }

    @Override
    public String description() {
      return desc;
    }

    @Override
    public String toString() {
      return new StringJoiner(", ", this.getClass().getSimpleName() + "{", "}")
          .add("name=" + name())
          .add("description=" + desc)
          .toString();
    }
  }
}