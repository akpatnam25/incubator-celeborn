/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.celeborn.client

import java.util.{HashSet => JHashSet, Set => JSet}
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._

import org.apache.celeborn.client.LifecycleManager.ShuffleFailedWorkers
import org.apache.celeborn.client.listener.{WorkersStatus, WorkerStatusListener}
import org.apache.celeborn.common.CelebornConf
import org.apache.celeborn.common.internal.Logging
import org.apache.celeborn.common.meta.WorkerInfo
import org.apache.celeborn.common.protocol.PartitionLocation
import org.apache.celeborn.common.protocol.message.ControlMessages.HeartbeatFromApplicationResponse
import org.apache.celeborn.common.protocol.message.StatusCode
import org.apache.celeborn.common.util.{JavaUtils, Utils}

class WorkerStatusTracker(
    conf: CelebornConf,
    lifecycleManager: LifecycleManager) extends Logging {
  private val excludedWorkerExpireTimeout = conf.clientExcludedWorkerExpireTimeout
  private val workerStatusListeners = ConcurrentHashMap.newKeySet[WorkerStatusListener]()
  private val clientShuffleDynamicResourceEnabled = conf.clientShuffleDynamicResourceEnabled

  val excludedWorkers = new ShuffleFailedWorkers()
  val shuttingWorkers: JSet[WorkerInfo] = new JHashSet[WorkerInfo]()

  // Workers that have already set an endpoint can skip the setupEndpoint process in changePartition when reviving
  // key: WorkerInfo.toUniqueId value: WorkerInfo
  val availableWorkersWithEndpoint: ConcurrentHashMap[String, WorkerInfo] =
    JavaUtils.newConcurrentHashMap[String, WorkerInfo]()

  // Workers that may be available but have not been used（without endpoint）
  // availableWorkersWithoutEndpoint is empty until appHeartbeatWithAvailableWorkers set to true
  val availableWorkersWithoutEndpoint = ConcurrentHashMap.newKeySet[WorkerInfo]()

  def registerWorkerStatusListener(workerStatusListener: WorkerStatusListener): Unit = {
    workerStatusListeners.add(workerStatusListener)
  }

  def getNeedCheckedWorkers(): Set[WorkerInfo] = {
    if (conf.clientCheckedUseAllocatedWorkers) {
      lifecycleManager.getAllocatedWorkers()
    } else {
      excludedWorkers.asScala.keys.toSet ++ shuttingWorkers.asScala.toSet
    }
  }

  def workerExcluded(worker: WorkerInfo): Boolean = {
    excludedWorkers.containsKey(worker)
  }

  def workerAvailable(worker: WorkerInfo): Boolean = {
    !excludedWorkers.containsKey(worker) && !shuttingWorkers.contains(worker)
  }

  def workerAvailableByLocation(loc: PartitionLocation): Boolean = {
    if (loc == null) {
      false
    } else {
      workerAvailable(loc.getWorker)
    }
  }

  def excludeWorkerFromPartition(
      shuffleId: Int,
      oldPartition: PartitionLocation,
      cause: StatusCode): Unit = {
    val failedWorker = new ShuffleFailedWorkers()

    def excludeWorker(partition: PartitionLocation, statusCode: StatusCode): Unit = {
      val tmpWorker = partition.getWorker
      val worker =
        lifecycleManager.workerSnapshots(shuffleId).keySet().asScala.find(_.equals(tmpWorker))
      if (worker.isDefined) {
        failedWorker.put(worker.get, (statusCode, System.currentTimeMillis()))
      }
    }

    if (oldPartition != null) {
      cause match {
        case StatusCode.PUSH_DATA_WRITE_FAIL_PRIMARY =>
          excludeWorker(oldPartition, StatusCode.PUSH_DATA_WRITE_FAIL_PRIMARY)
        case StatusCode.PUSH_DATA_WRITE_FAIL_REPLICA
            if oldPartition.hasPeer && conf.clientExcludeReplicaOnFailureEnabled =>
          excludeWorker(oldPartition.getPeer, StatusCode.PUSH_DATA_WRITE_FAIL_REPLICA)
        case StatusCode.PUSH_DATA_CREATE_CONNECTION_FAIL_PRIMARY =>
          excludeWorker(oldPartition, StatusCode.PUSH_DATA_CREATE_CONNECTION_FAIL_PRIMARY)
        case StatusCode.PUSH_DATA_CREATE_CONNECTION_FAIL_REPLICA
            if oldPartition.hasPeer && conf.clientExcludeReplicaOnFailureEnabled =>
          excludeWorker(
            oldPartition.getPeer,
            StatusCode.PUSH_DATA_CREATE_CONNECTION_FAIL_REPLICA)
        case StatusCode.PUSH_DATA_CONNECTION_EXCEPTION_PRIMARY =>
          excludeWorker(oldPartition, StatusCode.PUSH_DATA_CONNECTION_EXCEPTION_PRIMARY)
        case StatusCode.PUSH_DATA_CONNECTION_EXCEPTION_REPLICA
            if oldPartition.hasPeer && conf.clientExcludeReplicaOnFailureEnabled =>
          excludeWorker(
            oldPartition.getPeer,
            StatusCode.PUSH_DATA_CONNECTION_EXCEPTION_REPLICA)
        case StatusCode.PUSH_DATA_TIMEOUT_PRIMARY =>
          excludeWorker(oldPartition, StatusCode.PUSH_DATA_TIMEOUT_PRIMARY)
        case StatusCode.PUSH_DATA_TIMEOUT_REPLICA
            if oldPartition.hasPeer && conf.clientExcludeReplicaOnFailureEnabled =>
          excludeWorker(
            oldPartition.getPeer,
            StatusCode.PUSH_DATA_TIMEOUT_REPLICA)
        case _ =>
      }
    }
    recordWorkerFailure(failedWorker)
  }

  def recordWorkerFailure(failures: ShuffleFailedWorkers): Unit = {
    if (!failures.isEmpty) {
      val failedWorkers = new ShuffleFailedWorkers(failures)
      val failedWorkersMsg = failedWorkers.asScala.map { case (worker, (status, time)) =>
        s"${worker.readableAddress()}   ${status.name()}   ${Utils.formatTimestamp(time)}"
      }.mkString("\n")
      logWarning(
        s"""
           |Reporting failed workers:
           |$failedWorkersMsg$currentFailedWorkers""".stripMargin)
      failedWorkers.asScala.foreach {
        case (worker, (StatusCode.WORKER_SHUTDOWN, _)) =>
          shuttingWorkers.add(worker)
          removeFromAvailableWorkers(worker)
        case (worker, (statusCode, registerTime)) if !excludedWorkers.containsKey(worker) =>
          excludedWorkers.put(worker, (statusCode, registerTime))
          removeFromAvailableWorkers(worker)
        case (worker, (statusCode, _))
            if statusCode == StatusCode.NO_AVAILABLE_WORKING_DIR ||
              statusCode == StatusCode.RESERVE_SLOTS_FAILED ||
              statusCode == StatusCode.WORKER_UNKNOWN =>
          excludedWorkers.put(worker, (statusCode, excludedWorkers.get(worker)._2))
          removeFromAvailableWorkers(worker)
        case _ => // Not cover
      }
    }
  }

  def removeFromExcludedWorkers(workers: JHashSet[WorkerInfo]): Unit = {
    excludedWorkers.keySet.removeAll(workers)
  }

  private def removeFromAvailableWorkers(worker: WorkerInfo): Unit = {
    availableWorkersWithEndpoint.remove(worker.toUniqueId())
    availableWorkersWithoutEndpoint.remove(worker)
  }

  def addWorkersWithEndpoint(workers: JHashSet[WorkerInfo]): Unit = {
    availableWorkersWithoutEndpoint.removeAll(workers)
    workers.asScala.foreach { workerInfo =>
      availableWorkersWithEndpoint.put(workerInfo.toUniqueId(), workerInfo)
    }
  }

  def handleHeartbeatResponse(res: HeartbeatFromApplicationResponse): Unit = {
    if (res.statusCode == StatusCode.SUCCESS) {
      logDebug(s"Received Worker status from Primary, excluded workers: ${res.excludedWorkers} " +
        s"unknown workers: ${res.unknownWorkers}, shutdown workers: ${res.shuttingWorkers}, available workers from heartbeat: ${res.availableWorkers}")
      val current = System.currentTimeMillis()
      var statusChanged = false

      excludedWorkers.asScala.foreach {
        case (workerInfo: WorkerInfo, (statusCode, registerTime)) =>
          statusCode match {
            case StatusCode.WORKER_UNKNOWN |
                StatusCode.NO_AVAILABLE_WORKING_DIR |
                StatusCode.RESERVE_SLOTS_FAILED |
                StatusCode.PUSH_DATA_CREATE_CONNECTION_FAIL_PRIMARY |
                StatusCode.PUSH_DATA_CREATE_CONNECTION_FAIL_REPLICA |
                StatusCode.PUSH_DATA_CONNECTION_EXCEPTION_PRIMARY |
                StatusCode.PUSH_DATA_CONNECTION_EXCEPTION_REPLICA |
                StatusCode.PUSH_DATA_TIMEOUT_PRIMARY |
                StatusCode.PUSH_DATA_TIMEOUT_REPLICA
                if current - registerTime < excludedWorkerExpireTimeout => // reserve
            case _ =>
              if (!res.excludedWorkers.contains(workerInfo) &&
                !res.shuttingWorkers.contains(workerInfo) &&
                !res.unknownWorkers.contains(workerInfo)) {
                excludedWorkers.remove(workerInfo)
                statusChanged = true
              }
          }
      }
      for (worker <- res.excludedWorkers.asScala) {
        if (!excludedWorkers.containsKey(worker)) {
          excludedWorkers.put(worker, (StatusCode.WORKER_EXCLUDED, current))
          statusChanged = true
        }
      }
      for (worker <- res.unknownWorkers.asScala) {
        if (!excludedWorkers.containsKey(worker)) {
          excludedWorkers.put(worker, (StatusCode.WORKER_UNKNOWN, current))
          statusChanged = true
        }
      }

      val retainShuttingWorkersResult = shuttingWorkers.retainAll(res.shuttingWorkers)
      val addShuttingWorkersResult = shuttingWorkers.addAll(res.shuttingWorkers)

      if (clientShuffleDynamicResourceEnabled) {
        // AvailableWorkers filter Client excludedWorkers and shuttingWorkers.
        // AvailableWorkers already filtered res.excludedWorkers and res.shuttingWorkers.
        val resAvailableWorkers: JSet[WorkerInfo] = new JHashSet[WorkerInfo](res.availableWorkers)
        // update availableWorkers
        // availableWorkers wont filter excludedWorkers.
        // So before using them we hava to filter excludedWorkers.
        availableWorkersWithoutEndpoint.retainAll(resAvailableWorkers)
        availableWorkersWithEndpoint.keySet().retainAll(
          resAvailableWorkers.asScala.map(_.toUniqueId()).asJava)
        resAvailableWorkers.asScala.foreach { workerInfo: WorkerInfo =>
          if (!availableWorkersWithEndpoint.keySet.contains(workerInfo.toUniqueId())) {
            availableWorkersWithoutEndpoint.add(workerInfo)
          } else {
            if (availableWorkersWithoutEndpoint.contains(workerInfo)) {
              availableWorkersWithoutEndpoint.remove(workerInfo)
            }
          }
        }
      }

      statusChanged =
        statusChanged || retainShuttingWorkersResult || addShuttingWorkersResult
      // Always trigger commit files for shutting down workers from HeartbeatFromApplicationResponse
      // See details in CELEBORN-696
      if (!res.unknownWorkers.isEmpty || !res.shuttingWorkers.isEmpty) {
        val workerStatus = new WorkersStatus(res.unknownWorkers, res.shuttingWorkers)
        workerStatusListeners.asScala.foreach { listener =>
          try {
            listener.notifyChangedWorkersStatus(workerStatus)
          } catch {
            case t: Throwable =>
              logError("Error while notify listener", t)
          }
        }
      }
      if (statusChanged) {
        logWarning(
          s"Worker status changed from application heartbeat response.$currentFailedWorkers")
      }
    }
  }

  private def currentFailedWorkers: String = {
    val excludedWorkersMsg =
      excludedWorkers.asScala.groupBy(_._2._1).map { case (status, workers) =>
        (
          status,
          workers.map { case (worker, (_, time)) =>
            s"${worker.readableAddress()}   ${Utils.formatTimestamp(time)}"
          }.mkString("\n"))
      }
    val shutdownWorkersMsg = shuttingWorkers.asScala.map(_.readableAddress()).mkString("\n")
    var failedWorkersMsg = ""
    if (excludedWorkersMsg.contains(StatusCode.WORKER_EXCLUDED)) {
      failedWorkersMsg +=
        s"""
           |Current excluded workers:
           |${excludedWorkersMsg(StatusCode.WORKER_EXCLUDED)}""".stripMargin
    }
    if (excludedWorkersMsg.contains(StatusCode.WORKER_UNKNOWN)) {
      failedWorkersMsg +=
        s"""
           |Current unknown workers:
           |${excludedWorkersMsg(StatusCode.WORKER_UNKNOWN)}""".stripMargin
    }
    if (shutdownWorkersMsg.nonEmpty) {
      failedWorkersMsg +=
        s"""
           |Current shutdown workers:
           |$shutdownWorkersMsg""".stripMargin
    }
    failedWorkersMsg
  }
}
