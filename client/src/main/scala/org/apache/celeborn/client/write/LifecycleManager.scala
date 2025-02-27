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

package org.apache.celeborn.client.write

import java.util
import java.util.{List => JList}
import java.util.concurrent.{ConcurrentHashMap, ScheduledFuture, TimeUnit}
import java.util.concurrent.atomic.LongAdder

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Random

import org.roaringbitmap.RoaringBitmap

import org.apache.celeborn.common.RssConf
import org.apache.celeborn.common.haclient.RssHARetryClient
import org.apache.celeborn.common.identity.IdentityProvider
import org.apache.celeborn.common.internal.Logging
import org.apache.celeborn.common.meta.{PartitionLocationInfo, WorkerInfo}
import org.apache.celeborn.common.protocol.{PartitionLocation, PartitionType, RpcNameConstants, StorageInfo}
import org.apache.celeborn.common.protocol.RpcNameConstants.WORKER_EP
import org.apache.celeborn.common.protocol.message.ControlMessages._
import org.apache.celeborn.common.protocol.message.StatusCode
import org.apache.celeborn.common.rpc._
import org.apache.celeborn.common.util.{ThreadUtils, Utils}

class LifecycleManager(appId: String, val conf: RssConf) extends RpcEndpoint with Logging {

  private val lifecycleHost = Utils.localHostName()

  private val RemoveShuffleDelayMs = RssConf.removeShuffleDelayMs(conf)
  private val GetBlacklistDelayMs = RssConf.getBlacklistDelayMs(conf)
  private val ShouldReplicate = RssConf.replicate(conf)
  private val splitThreshold = RssConf.partitionSplitThreshold(conf)
  private val splitMode = RssConf.partitionSplitMode(conf)
  private val partitionType = RssConf.partitionType(conf)
  private val rangeReadFilter = RssConf.rangeReadFilterEnabled(conf)
  private val unregisterShuffleTime = new ConcurrentHashMap[Int, Long]()
  private val stageEndTimeout = RssConf.stageEndTimeout(conf)

  private val registeredShuffle = ConcurrentHashMap.newKeySet[Int]()
  private val shuffleMapperAttempts = new ConcurrentHashMap[Int, Array[Int]]()
  private val reducerFileGroupsMap =
    new ConcurrentHashMap[Int, Array[Array[PartitionLocation]]]()
  private val dataLostShuffleSet = ConcurrentHashMap.newKeySet[Int]()
  private val stageEndShuffleSet = ConcurrentHashMap.newKeySet[Int]()
  private val inProcessStageEndShuffleSet = ConcurrentHashMap.newKeySet[Int]()
  // maintain each shuffle's map relation of WorkerInfo and partition location
  private val shuffleAllocatedWorkers = {
    new ConcurrentHashMap[Int, ConcurrentHashMap[WorkerInfo, PartitionLocationInfo]]()
  }
  // shuffle id -> (partitionId -> newest PartitionLocation)
  private val latestPartitionLocation =
    new ConcurrentHashMap[Int, ConcurrentHashMap[Int, PartitionLocation]]()
  private val userIdentifier: UserIdentifier = IdentityProvider.instantiate(conf).provide()

  private def workerSnapshots(shuffleId: Int): util.Map[WorkerInfo, PartitionLocationInfo] =
    shuffleAllocatedWorkers.get(shuffleId)

  val newMapFunc =
    new util.function.Function[Int, ConcurrentHashMap[Int, PartitionLocation]]() {
      override def apply(s: Int): ConcurrentHashMap[Int, PartitionLocation] = {
        new ConcurrentHashMap[Int, PartitionLocation]()
      }
    }
  private def updateLatestPartitionLocations(
      shuffleId: Int,
      locations: util.List[PartitionLocation]) = {
    val map = latestPartitionLocation.computeIfAbsent(shuffleId, newMapFunc)
    locations.asScala.foreach { case location => map.put(location.getId, location) }
  }

  // shuffleId -> (partitionId -> set)
  private val changePartitionRequests =
    new ConcurrentHashMap[Int, ConcurrentHashMap[Integer, util.Set[RpcCallContext]]]()

  // register shuffle request waiting for response
  private val registeringShuffleRequest = new ConcurrentHashMap[Int, util.Set[RpcCallContext]]()

  // blacklist
  private val blacklist = ConcurrentHashMap.newKeySet[WorkerInfo]()

  // Threads
  private val forwardMessageThread =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("master-forward-message-thread")
  private var checkForShuffleRemoval: ScheduledFuture[_] = _
  private var getBlacklist: ScheduledFuture[_] = _

  // Use independent app heartbeat threads to avoid being blocked by other operations.
  private val heartbeatIntervalMs = RssConf.applicationHeatbeatIntervalMs(conf)
  private val heartbeatThread = ThreadUtils.newDaemonSingleThreadScheduledExecutor("app-heartbeat")
  private var appHeartbeat: ScheduledFuture[_] = _
  private val responseCheckerThread =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("rss-master-resp-checker")

  // init driver rss meta rpc service
  override val rpcEnv: RpcEnv = RpcEnv.create(
    RpcNameConstants.RSS_METASERVICE_SYS,
    lifecycleHost,
    RssConf.driverMetaServicePort(conf),
    conf)
  rpcEnv.setupEndpoint(RpcNameConstants.RSS_METASERVICE_EP, this)

  logInfo(s"Starting LifecycleManager on ${rpcEnv.address}")

  private val rssHARetryClient = new RssHARetryClient(rpcEnv, conf)
  private val totalWritten = new LongAdder
  private val fileCount = new LongAdder

  // Since method `onStart` is executed when `rpcEnv.setupEndpoint` is executed, and
  // `rssHARetryClient` is initialized after `rpcEnv` is initialized, if method `onStart` contains
  // a reference to `rssHARetryClient`, there may be cases where `rssHARetryClient` is null when
  // `rssHARetryClient` is called. Therefore, it's necessary to uniformly execute the initialization
  // method at the end of the construction of the class to perform the initialization operations.
  private def initialize(): Unit = {
    appHeartbeat = heartbeatThread.scheduleAtFixedRate(
      new Runnable {
        override def run(): Unit = {
          try {
            require(rssHARetryClient != null, "When sending a heartbeat, client shouldn't be null.")
            val tmpTotalWritten = totalWritten.sumThenReset()
            val tmpFileCount = fileCount.sumThenReset()
            logDebug(s"Send app heartbeat with $tmpTotalWritten $tmpFileCount")
            val appHeartbeat =
              HeartbeatFromApplication(appId, tmpTotalWritten, tmpFileCount, ZERO_UUID)
            rssHARetryClient.send(appHeartbeat)
            logDebug("Successfully send app heartbeat.")
          } catch {
            case it: InterruptedException =>
              logWarning("Interrupted while sending app heartbeat.")
              Thread.currentThread().interrupt()
              throw it
            case t: Throwable =>
              logError("Error while send heartbeat", t)
          }
        }
      },
      0,
      heartbeatIntervalMs,
      TimeUnit.MILLISECONDS)
  }

  override def onStart(): Unit = {
    checkForShuffleRemoval = forwardMessageThread.scheduleAtFixedRate(
      new Runnable {
        override def run(): Unit = Utils.tryLogNonFatalError {
          self.send(RemoveExpiredShuffle)
        }
      },
      RemoveShuffleDelayMs,
      RemoveShuffleDelayMs,
      TimeUnit.MILLISECONDS)

    getBlacklist = forwardMessageThread.scheduleAtFixedRate(
      new Runnable {
        override def run(): Unit = Utils.tryLogNonFatalError {
          self.send(GetBlacklist(new util.ArrayList[WorkerInfo](blacklist)))
        }
      },
      GetBlacklistDelayMs,
      GetBlacklistDelayMs,
      TimeUnit.MILLISECONDS)
  }

  override def onStop(): Unit = {
    import scala.concurrent.duration._

    checkForShuffleRemoval.cancel(true)
    getBlacklist.cancel(true)
    ThreadUtils.shutdown(forwardMessageThread, 800.millis)

    appHeartbeat.cancel(true)
    ThreadUtils.shutdown(heartbeatThread, 800.millis)

    ThreadUtils.shutdown(responseCheckerThread, 800.millis)

    rssHARetryClient.close()
    if (rpcEnv != null) {
      rpcEnv.shutdown()
      rpcEnv.awaitTermination()
    }
  }

  def getUserIdentifier(): UserIdentifier = {
    userIdentifier
  }

  def getRssMetaServiceHost: String = {
    lifecycleHost
  }

  def getRssMetaServicePort: Int = {
    rpcEnv.address.port
  }

  override def receive: PartialFunction[Any, Unit] = {
    case RemoveExpiredShuffle =>
      removeExpiredShuffle()
    case msg: GetBlacklist =>
      handleGetBlacklist(msg)
    case StageEnd(applicationId, shuffleId) =>
      logInfo(s"Received StageEnd request, ${Utils.makeShuffleKey(applicationId, shuffleId)}.")
      handleStageEnd(applicationId, shuffleId)
    case UnregisterShuffle(applicationId, shuffleId, _) =>
      logDebug(s"Received UnregisterShuffle request," +
        s"${Utils.makeShuffleKey(applicationId, shuffleId)}.")
      handleUnregisterShuffle(applicationId, shuffleId)
  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case RegisterShuffle(applicationId, shuffleId, numMappers, numPartitions) =>
      logDebug(s"Received RegisterShuffle request, " +
        s"$applicationId, $shuffleId, $numMappers, $numPartitions.")
      handleRegisterShuffle(context, applicationId, shuffleId, numMappers, numPartitions)

    case Revive(
          applicationId,
          shuffleId,
          mapId,
          attemptId,
          partitionId,
          epoch,
          oldPartition,
          cause) =>
      logTrace(s"Received Revive request, " +
        s"$applicationId, $shuffleId, $mapId, $attemptId, ,$partitionId," +
        s" $epoch, $oldPartition, $cause.")
      handleRevive(
        context,
        applicationId,
        shuffleId,
        mapId,
        attemptId,
        partitionId,
        epoch,
        oldPartition,
        cause)

    case PartitionSplit(applicationId, shuffleId, partitionId, epoch, oldPartition) =>
      logTrace(s"Received split request, " +
        s"$applicationId, $shuffleId, $partitionId, $epoch, $oldPartition")
      handleChangePartitionLocation(
        context,
        applicationId,
        shuffleId,
        partitionId,
        epoch,
        oldPartition)

    case MapperEnd(applicationId, shuffleId, mapId, attemptId, numMappers) =>
      logTrace(s"Received MapperEnd request, " +
        s"${Utils.makeMapKey(applicationId, shuffleId, mapId, attemptId)}.")
      handleMapperEnd(context, applicationId, shuffleId, mapId, attemptId, numMappers)

    case GetReducerFileGroup(applicationId: String, shuffleId: Int) =>
      logDebug(s"Received GetShuffleFileGroup request," +
        s"${Utils.makeShuffleKey(applicationId, shuffleId)}.")
      handleGetReducerFileGroup(context, shuffleId)
  }

  /* ========================================================== *
   |        START OF EVENT HANDLER                              |
   * ========================================================== */

  private def handleRegisterShuffle(
      context: RpcCallContext,
      applicationId: String,
      shuffleId: Int,
      numMappers: Int,
      numReducers: Int): Unit = {
    registeringShuffleRequest.synchronized {
      if (registeringShuffleRequest.containsKey(shuffleId)) {
        // If same request already exists in the registering request list for the same shuffle,
        // just register and return.
        logDebug("[handleRegisterShuffle] request for same shuffleKey exists, just register")
        registeringShuffleRequest.get(shuffleId).add(context)
        return
      } else {
        // If shuffle is registered, reply this shuffle's partition location and return.
        // Else add this request to registeringShuffleRequest.
        if (registeredShuffle.contains(shuffleId)) {
          val initialLocs = workerSnapshots(shuffleId)
            .values()
            .asScala
            .flatMap(_.getAllMasterLocationsWithMinEpoch(shuffleId.toString).asScala)
            .filter(_.getEpoch == 0)
            .toList
            .asJava
          context.reply(RegisterShuffleResponse(StatusCode.SUCCESS, initialLocs))
          return
        }
        logInfo(s"New shuffle request, shuffleId $shuffleId, partitionType: $partitionType " +
          s"numMappers: $numMappers, numReducers: $numReducers.")
        val set = new util.HashSet[RpcCallContext]()
        set.add(context)
        registeringShuffleRequest.put(shuffleId, set)
      }
    }

    // Reply to all RegisterShuffle request for current shuffle id.
    def reply(response: RegisterShuffleResponse): Unit = {
      registeringShuffleRequest.synchronized {
        registeringShuffleRequest.asScala
          .get(shuffleId)
          .foreach(_.asScala.foreach(_.reply(response)))
        registeringShuffleRequest.remove(shuffleId)
      }
    }

    // First, request to get allocated slots from Master
    val ids = new util.ArrayList[Integer]
    val numPartitions: Int = partitionType match {
      case PartitionType.REDUCE_PARTITION => numReducers
      case PartitionType.MAP_PARTITION => numMappers
    }
    (0 until numPartitions).foreach(idx => ids.add(new Integer(idx)))
    val res = requestSlotsWithRetry(applicationId, shuffleId, ids)

    res.status match {
      case StatusCode.FAILED =>
        logError(s"OfferSlots RPC request failed for $shuffleId!")
        reply(RegisterShuffleResponse(StatusCode.FAILED, List.empty.asJava))
        return
      case StatusCode.SLOT_NOT_AVAILABLE =>
        logError(s"OfferSlots for $shuffleId failed!")
        reply(RegisterShuffleResponse(StatusCode.SLOT_NOT_AVAILABLE, List.empty.asJava))
        return
      case StatusCode.SUCCESS =>
        logInfo(s"OfferSlots for ${Utils.makeShuffleKey(applicationId, shuffleId)} Success!")
        logDebug(s" Slots Info: ${res.workerResource}")
      case _ => // won't happen
        throw new UnsupportedOperationException()
    }

    // Reserve slots for each PartitionLocation. When response status is SUCCESS, WorkerResource
    // won't be empty since master will reply SlotNotAvailable status when reserved slots is empty.
    val slots = res.workerResource
    val candidatesWorkers = new util.HashSet(slots.keySet())
    val connectFailedWorkers = ConcurrentHashMap.newKeySet[WorkerInfo]()

    // Second, for each worker, try to initialize the endpoint.
    val parallelism = Math.min(Math.max(1, slots.size()), RssConf.rpcMaxParallelism(conf))
    ThreadUtils.parmap(slots.asScala.to, "InitWorkerRef", parallelism) { case (workerInfo, _) =>
      try {
        workerInfo.endpoint =
          rpcEnv.setupEndpointRef(RpcAddress.apply(workerInfo.host, workerInfo.rpcPort), WORKER_EP)
      } catch {
        case t: Throwable =>
          logError(s"Init rpc client for $workerInfo failed", t)
          connectFailedWorkers.add(workerInfo)
      }
    }

    candidatesWorkers.removeAll(connectFailedWorkers)
    recordWorkerFailure(new util.ArrayList[WorkerInfo](connectFailedWorkers))

    // Third, for each slot, LifecycleManager should ask Worker to reserve the slot
    // and prepare the pushing data env.
    val reserveSlotsSuccess =
      reserveSlotsWithRetry(applicationId, shuffleId, candidatesWorkers.asScala.toList, slots)

    // If reserve slots failed, clear allocated resources, reply ReserveSlotFailed and return.
    if (!reserveSlotsSuccess) {
      logError(s"reserve buffer for $shuffleId failed, reply to all.")
      reply(RegisterShuffleResponse(StatusCode.RESERVE_SLOTS_FAILED, List.empty.asJava))
      // tell Master to release slots
      requestReleaseSlots(
        rssHARetryClient,
        ReleaseSlots(applicationId, shuffleId, List.empty.asJava, List.empty.asJava))
    } else {
      logInfo(s"ReserveSlots for ${Utils.makeShuffleKey(applicationId, shuffleId)} success!")
      logDebug(s"Allocated Slots: $slots")
      // Forth, register shuffle success, update status
      val allocatedWorkers = new ConcurrentHashMap[WorkerInfo, PartitionLocationInfo]()
      slots.asScala.foreach { case (workerInfo, (masterLocations, slaveLocations)) =>
        val partitionLocationInfo = new PartitionLocationInfo()
        partitionLocationInfo.addMasterPartitions(shuffleId.toString, masterLocations)
        updateLatestPartitionLocations(shuffleId, masterLocations)
        partitionLocationInfo.addSlavePartitions(shuffleId.toString, slaveLocations)
        allocatedWorkers.put(workerInfo, partitionLocationInfo)
      }
      shuffleAllocatedWorkers.put(shuffleId, allocatedWorkers)
      registeredShuffle.add(shuffleId)

      shuffleMapperAttempts.synchronized {
        if (!shuffleMapperAttempts.containsKey(shuffleId)) {
          val attempts = new Array[Int](numMappers)
          0 until numMappers foreach (idx => attempts(idx) = -1)
          shuffleMapperAttempts.synchronized {
            shuffleMapperAttempts.put(shuffleId, attempts)
          }
        }
      }

      reducerFileGroupsMap.put(shuffleId, new Array[Array[PartitionLocation]](numReducers))

      // Fifth, reply the allocated partition location to ShuffleClient.
      logInfo(s"Handle RegisterShuffle Success for $shuffleId.")
      val allMasterPartitionLocations = slots.asScala.flatMap(_._2._1.asScala).toList
      reply(RegisterShuffleResponse(StatusCode.SUCCESS, allMasterPartitionLocations.asJava))
    }
  }

  private def blacklistPartition(
      shuffleId: Int,
      oldPartition: PartitionLocation,
      cause: StatusCode): Unit = {
    // only blacklist if cause is PushDataFailMain
    val failedWorker = new util.ArrayList[WorkerInfo]()
    if (cause == StatusCode.PUSH_DATA_FAIL_MAIN && oldPartition != null) {
      val tmpWorker = oldPartition.getWorker
      val worker = workerSnapshots(shuffleId).keySet().asScala
        .find(_.equals(tmpWorker))
      if (worker.isDefined) {
        failedWorker.add(worker.get)
      }
    }
    if (!failedWorker.isEmpty) {
      recordWorkerFailure(failedWorker)
    }
  }

  private def handleRevive(
      context: RpcCallContext,
      applicationId: String,
      shuffleId: Int,
      mapId: Int,
      attemptId: Int,
      partitionId: Int,
      oldEpoch: Int,
      oldPartition: PartitionLocation,
      cause: StatusCode): Unit = {
    // If shuffle not registered, reply ShuffleNotRegistered and return
    if (!registeredShuffle.contains(shuffleId)) {
      logError(s"[handleRevive] shuffle $shuffleId not registered!")
      context.reply(ChangeLocationResponse(StatusCode.SHUFFLE_NOT_REGISTERED, null))
      return
    }

    // If shuffle registered and corresponding map finished, reply MapEnd and return.
    if (shuffleMapperAttempts.containsKey(shuffleId)
      && shuffleMapperAttempts.get(shuffleId)(mapId) != -1) {
      logWarning(s"[handleRevive] Mapper ended, mapId $mapId, current attemptId $attemptId, " +
        s"ended attemptId ${shuffleMapperAttempts.get(shuffleId)(mapId)}, shuffleId $shuffleId.")
      context.reply(ChangeLocationResponse(StatusCode.MAP_ENDED, null))
      return
    }

    logWarning(s"Do Revive for shuffle ${Utils.makeShuffleKey(applicationId, shuffleId)}, " +
      s"oldPartition: $oldPartition, cause: $cause")

    handleChangePartitionLocation(
      context,
      applicationId,
      shuffleId,
      partitionId,
      oldEpoch,
      oldPartition,
      Some(cause))
  }

  private val rpcContextRegisterFunc =
    new util.function.Function[Int, ConcurrentHashMap[Integer, util.Set[RpcCallContext]]]() {
      override def apply(s: Int): ConcurrentHashMap[Integer, util.Set[RpcCallContext]] =
        new ConcurrentHashMap()
    }

  private def handleChangePartitionLocation(
      context: RpcCallContext,
      applicationId: String,
      shuffleId: Int,
      partitionId: Int,
      oldEpoch: Int,
      oldPartition: PartitionLocation,
      cause: Option[StatusCode] = None): Unit = {

    // check if there exists request for the partition, if do just register
    val requests = changePartitionRequests.computeIfAbsent(shuffleId, rpcContextRegisterFunc)
    requests.synchronized {
      if (requests.containsKey(partitionId)) {
        requests.get(partitionId).add(context)
        logTrace(s"[handleChangePartitionLocation] For $shuffleId, request for same partition" +
          s"$partitionId-$oldEpoch exists, register context.")
        return
      } else {
        // If new slot for the partition has been allocated, reply and return.
        // Else register and allocate for it.
        val latestLoc = getLatestPartition(shuffleId, partitionId, oldEpoch)
        if (latestLoc != null) {
          context.reply(ChangeLocationResponse(StatusCode.SUCCESS, latestLoc))
          logDebug(s"New partition found, old partition $partitionId-$oldEpoch return it." +
            s" shuffleId: $shuffleId $latestLoc")
          return
        }
        val set = new util.HashSet[RpcCallContext]()
        set.add(context)
        requests.put(partitionId, set)
      }
    }

    if (cause.isDefined) {
      blacklistPartition(shuffleId, oldPartition, cause.get)
    }

    def reply(response: ChangeLocationResponse): Unit = {
      requests.synchronized {
        requests.remove(partitionId)
      }.asScala.foreach(_.reply(response))
    }

    val candidates = workersNotBlacklisted(shuffleId)
    if (candidates.size < 1 || (ShouldReplicate && candidates.size < 2)) {
      logError("[Update partition] failed for not enough candidates for revive.")
      reply(ChangeLocationResponse(StatusCode.SLOT_NOT_AVAILABLE, null))
      return null
    }

    val newlyAllocatedLocation =
      if (oldPartition != null) {
        reallocateSlotsFromCandidates(List(oldPartition), candidates)
      } else {
        reallocateForNonExistPartitionLocationFromCandidates(partitionId, oldEpoch, candidates)
      }

    if (!reserveSlotsWithRetry(applicationId, shuffleId, candidates, newlyAllocatedLocation)) {
      logError(s"[Update partition] failed for $shuffleId.")
      reply(ChangeLocationResponse(StatusCode.RESERVE_SLOTS_FAILED, null))
      return
    }

    // Add all re-allocated slots to worker snapshots.
    newlyAllocatedLocation.asScala.foreach { case (workInfo, (masterLocations, slaveLocations)) =>
      workerSnapshots(shuffleId).asScala.get(workInfo).map { partitionLocationInfo =>
        partitionLocationInfo.addMasterPartitions(shuffleId.toString, masterLocations)
        updateLatestPartitionLocations(shuffleId, masterLocations)
        partitionLocationInfo.addSlavePartitions(shuffleId.toString, slaveLocations)
      }
    }
    val (masterLocations, slavePartitions) = newlyAllocatedLocation.asScala.head._2
    // reply the master location of this partition.
    val newMasterLocation =
      if (masterLocations != null && masterLocations.size() > 0) {
        masterLocations.asScala.head
      } else {
        slavePartitions.asScala.head.getPeer
      }

    reply(ChangeLocationResponse(StatusCode.SUCCESS, newMasterLocation))
    logDebug(s"Renew $shuffleId $partitionId" +
      "$oldEpoch->${newMasterLocation.getEpoch} partition success.")
  }

  private def getLatestPartition(
      shuffleId: Int,
      partitionId: Int,
      epoch: Int): PartitionLocation = {
    val map = latestPartitionLocation.get(shuffleId)
    if (map != null) {
      val loc = map.get(partitionId)
      if (loc != null && loc.getEpoch > epoch) {
        return loc
      }
    }
    null
  }

  private def handleMapperEnd(
      context: RpcCallContext,
      applicationId: String,
      shuffleId: Int,
      mapId: Int,
      attemptId: Int,
      numMappers: Int): Unit = {
    var askStageEnd: Boolean = false
    // update max attemptId
    shuffleMapperAttempts.synchronized {
      var attempts = shuffleMapperAttempts.get(shuffleId)
      // it would happen when task with no shuffle data called MapperEnd first
      if (attempts == null) {
        logDebug(s"[handleMapperEnd] $shuffleId not registered, create one.")
        attempts = new Array[Int](numMappers)
        0 until numMappers foreach (idx => attempts(idx) = -1)
        shuffleMapperAttempts.put(shuffleId, attempts)
      }

      if (attempts(mapId) < 0) {
        attempts(mapId) = attemptId
      } else {
        // Mapper with another attemptId called, skip this request
        context.reply(MapperEndResponse(StatusCode.SUCCESS))
        return
      }

      if (!attempts.exists(_ < 0)) {
        askStageEnd = true
      }
    }

    if (askStageEnd) {
      // last mapper finished. call mapper end
      logInfo(s"Last MapperEnd, call StageEnd with shuffleKey:" +
        s"${Utils.makeShuffleKey(applicationId, shuffleId)}.")
      self.send(StageEnd(applicationId, shuffleId))
    }

    // reply success
    context.reply(MapperEndResponse(StatusCode.SUCCESS))
  }

  private def handleGetReducerFileGroup(
      context: RpcCallContext,
      shuffleId: Int): Unit = {
    var timeout = stageEndTimeout
    val delta = 100
    while (!stageEndShuffleSet.contains(shuffleId)) {
      Thread.sleep(delta)
      if (timeout <= 0) {
        logError(s"[handleGetReducerFileGroup] Wait for handleStageEnd Timeout! $shuffleId.")
        context.reply(
          GetReducerFileGroupResponse(StatusCode.STAGE_END_TIME_OUT, Array.empty, Array.empty))
        return
      }
      timeout = timeout - delta
    }
    logDebug("[handleGetReducerFileGroup] Wait for handleStageEnd complete cost" +
      s" ${stageEndTimeout - timeout}ms")

    if (dataLostShuffleSet.contains(shuffleId)) {
      context.reply(
        GetReducerFileGroupResponse(StatusCode.SHUFFLE_DATA_LOST, Array.empty, Array.empty))
    } else {
      context.reply(GetReducerFileGroupResponse(
        StatusCode.SUCCESS,
        reducerFileGroupsMap.getOrDefault(shuffleId, Array.empty),
        shuffleMapperAttempts.getOrDefault(shuffleId, Array.empty)))
    }
  }

  private def handleStageEnd(applicationId: String, shuffleId: Int): Unit = {
    // check whether shuffle has registered
    if (!registeredShuffle.contains(shuffleId)) {
      logInfo(s"[handleStageEnd]" +
        s"$shuffleId not registered, maybe no shuffle data within this stage.")
      // record in stageEndShuffleSet
      stageEndShuffleSet.add(shuffleId)
      return
    }
    if (stageEndShuffleSet.contains(shuffleId)) {
      logInfo(s"[handleStageEnd] Shuffle $shuffleId already ended!")
      return
    }
    inProcessStageEndShuffleSet.synchronized {
      if (inProcessStageEndShuffleSet.contains(shuffleId)) {
        logWarning(s"[handleStageEnd] Shuffle $shuffleId is in process!")
        return
      }
      inProcessStageEndShuffleSet.add(shuffleId)
    }

    // ask allLocations workers holding partitions to commit files
    val masterPartMap = new ConcurrentHashMap[String, PartitionLocation]
    val slavePartMap = new ConcurrentHashMap[String, PartitionLocation]
    val committedMasterIds = ConcurrentHashMap.newKeySet[String]()
    val committedSlaveIds = ConcurrentHashMap.newKeySet[String]()
    val committedMasterStorageInfos = new ConcurrentHashMap[String, StorageInfo]()
    val committedSlaveStorageInfos = new ConcurrentHashMap[String, StorageInfo]()
    val committedMapIdBitmap = new ConcurrentHashMap[String, RoaringBitmap]()
    val failedMasterIds = ConcurrentHashMap.newKeySet[String]()
    val failedSlaveIds = ConcurrentHashMap.newKeySet[String]()

    val allocatedWorkers = shuffleAllocatedWorkers.get(shuffleId)
    val commitFilesFailedWorkers = ConcurrentHashMap.newKeySet[WorkerInfo]()

    val currentShuffleFileCount = new LongAdder
    val commitFileStartTime = System.nanoTime()

    val parallelism = Math.min(workerSnapshots(shuffleId).size(), RssConf.rpcMaxParallelism(conf))
    ThreadUtils.parmap(
      allocatedWorkers.asScala.to,
      "CommitFiles",
      parallelism) { case (worker, partitionLocationInfo) =>
      if (partitionLocationInfo.containsShuffle(shuffleId.toString)) {
        val masterParts = partitionLocationInfo.getAllMasterLocations(shuffleId.toString)
        val slaveParts = partitionLocationInfo.getAllSlaveLocations(shuffleId.toString)
        masterParts.asScala.foreach { p =>
          val partition = new PartitionLocation(p)
          partition.setFetchPort(worker.fetchPort)
          partition.setPeer(null)
          masterPartMap.put(partition.getUniqueId, partition)
        }
        slaveParts.asScala.foreach { p =>
          val partition = new PartitionLocation(p)
          partition.setFetchPort(worker.fetchPort)
          partition.setPeer(null)
          slavePartMap.put(partition.getUniqueId, partition)
        }

        val masterIds = masterParts.asScala.map(_.getUniqueId).asJava
        val slaveIds = slaveParts.asScala.map(_.getUniqueId).asJava

        val commitFiles = CommitFiles(
          applicationId,
          shuffleId,
          masterIds,
          slaveIds,
          shuffleMapperAttempts.get(shuffleId))
        val res = requestCommitFiles(worker.endpoint, commitFiles)

        res.status match {
          case StatusCode.SUCCESS => // do nothing
          case StatusCode.PARTIAL_SUCCESS | StatusCode.SHUFFLE_NOT_REGISTERED | StatusCode.FAILED =>
            logDebug(s"Request $commitFiles return ${res.status} for " +
              s"${Utils.makeShuffleKey(applicationId, shuffleId)}")
            commitFilesFailedWorkers.add(worker)
          case _ => // won't happen
        }

        // record committed partitionIds
        committedMasterIds.addAll(res.committedMasterIds)
        committedSlaveIds.addAll(res.committedSlaveIds)

        // record committed partitions storage hint and disk hint
        committedMasterStorageInfos.putAll(res.committedMasterStorageInfos)
        committedSlaveStorageInfos.putAll(res.committedSlaveStorageInfos)

        // record failed partitions
        failedMasterIds.addAll(res.failedMasterIds)
        failedSlaveIds.addAll(res.failedSlaveIds)

        if (!res.committedMapIdBitMap.isEmpty) {
          committedMapIdBitmap.putAll(res.committedMapIdBitMap)
        }

        totalWritten.add(res.totalWritten)
        fileCount.add(res.fileCount)
        currentShuffleFileCount.add(res.fileCount)
      }
    }

    recordWorkerFailure(new util.ArrayList[WorkerInfo](commitFilesFailedWorkers))
    // release resources and clear worker info
    workerSnapshots(shuffleId).asScala.foreach { case (_, partitionLocationInfo) =>
      partitionLocationInfo.removeMasterPartitions(shuffleId.toString)
      partitionLocationInfo.removeSlavePartitions(shuffleId.toString)
    }
    requestReleaseSlots(
      rssHARetryClient,
      ReleaseSlots(applicationId, shuffleId, List.empty.asJava, List.empty.asJava))

    def hasCommitFailedIds: Boolean = {
      if (!ShouldReplicate && failedMasterIds.size() != 0) {
        return true
      }
      failedMasterIds.asScala.foreach { id =>
        if (failedSlaveIds.contains(id)) {
          logError(s"For $shuffleId partition $id: data lost.")
          return true
        }
      }
      false
    }

    val dataLost = hasCommitFailedIds

    if (!dataLost) {
      val committedPartitions = new util.HashMap[String, PartitionLocation]
      committedMasterIds.asScala.foreach { id =>
        if (committedMasterStorageInfos.get(id) == null) {
          logDebug(s"$applicationId-$shuffleId $id storage hint was not returned")
        } else {
          masterPartMap.get(id).setStorageInfo(committedMasterStorageInfos.get(id))
          masterPartMap.get(id).setMapIdBitMap(committedMapIdBitmap.get(id))
          committedPartitions.put(id, masterPartMap.get(id))
        }
      }

      committedSlaveIds.asScala.foreach { id =>
        val slavePartition = slavePartMap.get(id)
        if (committedSlaveStorageInfos.get(id) == null) {
          logDebug(s"$applicationId-$shuffleId $id storage hint was not returned")
        } else {
          slavePartition.setStorageInfo(committedSlaveStorageInfos.get(id))
          slavePartition.setMapIdBitMap(committedMapIdBitmap.get(id))
          val masterPartition = committedPartitions.get(id)
          if (masterPartition ne null) {
            masterPartition.setPeer(slavePartition)
            slavePartition.setPeer(masterPartition)
          } else {
            logInfo(s"Shuffle $shuffleId partition $id: master lost, " +
              s"use slave $slavePartition.")
            committedPartitions.put(id, slavePartition)
          }
        }
      }

      val fileGroups = reducerFileGroupsMap.get(shuffleId)
      val sets = Array.fill(fileGroups.length)(new util.HashSet[PartitionLocation]())
      committedPartitions.values().asScala.foreach { partition =>
        sets(partition.getId).add(partition)
      }
      var i = 0
      while (i < fileGroups.length) {
        fileGroups(i) = sets(i).toArray(new Array[PartitionLocation](0))
        i += 1
      }

      logInfo(s"Shuffle $shuffleId " +
        s"commit files complete. File count ${currentShuffleFileCount.sum()} " +
        s"using ${(System.nanoTime() - commitFileStartTime) / 1000000} ms")
    }

    // reply
    if (!dataLost) {
      logInfo(s"Succeed to handle stageEnd for $shuffleId.")
      // record in stageEndShuffleSet
      stageEndShuffleSet.add(shuffleId)
    } else {
      logError(s"Failed to handle stageEnd for $shuffleId, lost file!")
      dataLostShuffleSet.add(shuffleId)
      // record in stageEndShuffleSet
      stageEndShuffleSet.add(shuffleId)
    }
    inProcessStageEndShuffleSet.remove(shuffleId)
  }

  private def handleUnregisterShuffle(
      appId: String,
      shuffleId: Int): Unit = {
    // if StageEnd has not been handled, trigger StageEnd
    if (!stageEndShuffleSet.contains(shuffleId)) {
      logInfo(s"Call StageEnd before Unregister Shuffle $shuffleId.")
      handleStageEnd(appId, shuffleId)
      var timeout = stageEndTimeout
      val delta = 100
      while (!stageEndShuffleSet.contains(shuffleId) && timeout > 0) {
        Thread.sleep(delta)
        timeout = timeout - delta
      }
      if (timeout <= 0) {
        logError(s"StageEnd Timeout! $shuffleId.")
      } else {
        logInfo("[handleUnregisterShuffle] Wait for handleStageEnd complete cost" +
          s" ${stageEndTimeout - timeout}ms")
      }
    }

    if (partitionExists(shuffleId)) {
      logWarning(s"Partition exists for shuffle $shuffleId, " +
        "maybe caused by task rerun or speculative.")
      workerSnapshots(shuffleId).asScala.foreach { case (_, partitionLocationInfo) =>
        partitionLocationInfo.removeMasterPartitions(shuffleId.toString)
        partitionLocationInfo.removeSlavePartitions(shuffleId.toString)
      }
      requestReleaseSlots(
        rssHARetryClient,
        ReleaseSlots(appId, shuffleId, List.empty.asJava, List.empty.asJava))
    }

    // add shuffleKey to delay shuffle removal set
    unregisterShuffleTime.put(shuffleId, System.currentTimeMillis())

    logInfo(s"Unregister for $shuffleId success.")
  }

  /* ========================================================== *
   |        END OF EVENT HANDLER                                |
   * ========================================================== */

  /**
   * After getting WorkerResource, LifecycleManger needs to ask each Worker to
   * reserve corresponding slot and prepare push data env in Worker side.
   *
   * @param applicationId Application ID
   * @param shuffleId     Application shuffle id
   * @param slots         WorkerResource to reserve slots
   * @return List of reserving slot failed workers
   */
  private def reserveSlots(
      applicationId: String,
      shuffleId: Int,
      slots: WorkerResource): util.List[WorkerInfo] = {
    val reserveSlotFailedWorkers = ConcurrentHashMap.newKeySet[WorkerInfo]()
    val parallelism = Math.min(Math.max(1, slots.size()), RssConf.rpcMaxParallelism(conf))
    ThreadUtils.parmap(slots.asScala.to, "ReserveSlot", parallelism) {
      case (workerInfo, (masterLocations, slaveLocations)) =>
        val res = requestReserveSlots(
          workerInfo.endpoint,
          ReserveSlots(
            applicationId,
            shuffleId,
            masterLocations,
            slaveLocations,
            splitThreshold,
            splitMode,
            partitionType,
            rangeReadFilter,
            userIdentifier))
        if (res.status.equals(StatusCode.SUCCESS)) {
          logDebug(s"Successfully allocated " +
            s"partitions buffer for ${Utils.makeShuffleKey(applicationId, shuffleId)}" +
            s" from worker ${workerInfo.readableAddress()}.")
        } else {
          logError(s"[reserveSlots] Failed to" +
            s" reserve buffers for ${Utils.makeShuffleKey(applicationId, shuffleId)}" +
            s" from worker ${workerInfo.readableAddress()}. Reason: ${res.reason}")
          reserveSlotFailedWorkers.add(workerInfo)
        }
    }

    val failedWorkerList = new util.ArrayList[WorkerInfo](reserveSlotFailedWorkers)
    recordWorkerFailure(failedWorkerList)
    failedWorkerList
  }

  /**
   * When enabling replicate, if one of the partition location reserve slots failed,
   * LifecycleManager also needs to release another corresponding partition location.
   * To release the corresponding partition location, LifecycleManager should:
   *   1. Remove the peer partition location of failed partition location from slots.
   *   2. Request the Worker to destroy the slot's FileWriter.
   *   3. Request the Master to release the worker slots status.
   *
   * @param applicationId            application id
   * @param shuffleId                shuffle id
   * @param slots                    allocated WorkerResource
   * @param failedPartitionLocations reserve slot failed partition location
   */
  private def releasePeerPartitionLocation(
      applicationId: String,
      shuffleId: Int,
      slots: WorkerResource,
      failedPartitionLocations: mutable.HashMap[Int, PartitionLocation]) = {
    val destroyResource = new WorkerResource
    failedPartitionLocations.values
      .flatMap { partition => Option(partition.getPeer) }
      .foreach { partition =>
        var destroyWorkerInfo = partition.getWorker
        val workerInfoWithRpcRef = slots.keySet().asScala.find(_.equals(destroyWorkerInfo))
          .getOrElse {
            logWarning(s"Cannot find workInfo from previous success workResource:" +
              s" ${destroyWorkerInfo.readableAddress()}, init according to partition info")
            try {
              destroyWorkerInfo.endpoint = rpcEnv.setupEndpointRef(
                RpcAddress.apply(destroyWorkerInfo.host, destroyWorkerInfo.rpcPort),
                WORKER_EP)
            } catch {
              case t: Throwable =>
                logError(s"Init rpc client failed for ${destroyWorkerInfo.readableAddress()}", t)
                destroyWorkerInfo = null
            }
            destroyWorkerInfo
          }
        if (slots.containsKey(workerInfoWithRpcRef)) {
          val (masterPartitionLocations, slavePartitionLocations) = slots.get(workerInfoWithRpcRef)
          partition.getMode match {
            case PartitionLocation.Mode.MASTER =>
              masterPartitionLocations.remove(partition)
              destroyResource.computeIfAbsent(workerInfoWithRpcRef, newLocationFunc)
                ._1.add(partition)
            case PartitionLocation.Mode.SLAVE =>
              slavePartitionLocations.remove(partition)
              destroyResource.computeIfAbsent(workerInfoWithRpcRef, newLocationFunc)
                ._2.add(partition)
          }
          if (masterPartitionLocations.isEmpty && slavePartitionLocations.isEmpty) {
            slots.remove(workerInfoWithRpcRef)
          }
        }
      }
    if (!destroyResource.isEmpty) {
      destroySlotsWithRetry(applicationId, shuffleId, destroyResource)
      logInfo(s"Destroyed peer partitions for reserve buffer failed workers " +
        s"${Utils.makeShuffleKey(applicationId, shuffleId)}, $destroyResource")

      val workerIds = new util.ArrayList[String]()
      val workerSlotsPerDisk = new util.ArrayList[util.Map[String, Integer]]()
      Utils.getSlotsPerDisk(destroyResource).asScala.foreach {
        case (workerInfo, slotsPerDisk) =>
          workerIds.add(workerInfo.toUniqueId())
          workerSlotsPerDisk.add(slotsPerDisk)
      }
      val msg = ReleaseSlots(applicationId, shuffleId, workerIds, workerSlotsPerDisk)
      requestReleaseSlots(rssHARetryClient, msg)
      logInfo(s"Released slots for reserve buffer failed workers " +
        s"${workerIds.asScala.mkString(",")}" + s"${slots.asScala.mkString(",")}" +
        s"${Utils.makeShuffleKey(applicationId, shuffleId)}, ")
    }
  }

  /**
   * Collect all allocated partition locations on reserving slot failed workers
   * and remove failed worker's partition locations from total slots.
   * For each reduce id, we only need to maintain one of the pair locations
   * even if enabling replicate. If RSS wants to release the failed partition location,
   * the corresponding peers will be handled in [[releasePeerPartitionLocation]]
   *
   * @param reserveFailedWorkers reserve slot failed WorkerInfo list of slots
   * @param slots                the slots tried to reserve a slot
   * @return reserving slot failed partition locations
   */
  def getFailedPartitionLocations(
      reserveFailedWorkers: util.List[WorkerInfo],
      slots: WorkerResource): mutable.HashMap[Int, PartitionLocation] = {
    val failedPartitionLocations = new mutable.HashMap[Int, PartitionLocation]()
    reserveFailedWorkers.asScala.foreach { workerInfo =>
      val (failedMasterLocations, failedSlaveLocations) = slots.remove(workerInfo)
      if (null != failedMasterLocations) {
        failedMasterLocations.asScala.foreach { failedMasterLocation =>
          failedPartitionLocations += (failedMasterLocation.getId -> failedMasterLocation)
        }
      }
      if (null != failedSlaveLocations) {
        failedSlaveLocations.asScala.foreach { failedSlaveLocation =>
          val partitionId = failedSlaveLocation.getId
          if (!failedPartitionLocations.contains(partitionId)) {
            failedPartitionLocations += (partitionId -> failedSlaveLocation)
          }
        }
      }
    }
    failedPartitionLocations
  }

  /**
   * Reserve buffers with retry, retry on another node will cause slots to be inconsistent.
   *
   * @param applicationId application id
   * @param shuffleId     shuffle id
   * @param candidates    working worker list
   * @param slots         the total allocated worker resources that need to be applied for the slot
   * @return If reserve all slots success
   */
  private def reserveSlotsWithRetry(
      applicationId: String,
      shuffleId: Int,
      candidates: List[WorkerInfo],
      slots: WorkerResource): Boolean = {
    var requestSlots = slots
    val maxRetryTimes = RssConf.reserveSlotsMaxRetry(conf)
    val retryWaitInterval = RssConf.reserveSlotsRetryWait(conf)
    var retryTimes = 1
    var noAvailableSlots = false
    var success = false
    while (retryTimes <= maxRetryTimes && !success && !noAvailableSlots) {
      if (retryTimes > 1) {
        Thread.sleep(retryWaitInterval)
      }
      // reserve buffers
      logInfo(s"Try reserve slots for ${Utils.makeShuffleKey(applicationId, shuffleId)} " +
        s"for $retryTimes times.")
      val reserveFailedWorkers = reserveSlots(applicationId, shuffleId, requestSlots)
      if (reserveFailedWorkers.isEmpty) {
        success = true
      } else {
        // Find out all failed partition locations and remove failed worker's partition location
        // from slots.
        val failedPartitionLocations = getFailedPartitionLocations(reserveFailedWorkers, slots)
        // When enable replicate, if one of the partition location reserve slots failed, we also
        // need to release another corresponding partition location and remove it from slots.
        if (ShouldReplicate && failedPartitionLocations.nonEmpty && !slots.isEmpty) {
          releasePeerPartitionLocation(applicationId, shuffleId, slots, failedPartitionLocations)
        }
        if (retryTimes < maxRetryTimes) {
          // get retryCandidates resource and retry reserve buffer
          val retryCandidates = new util.HashSet(slots.keySet())
          // add candidates to avoid revive action passed in slots only 2 worker
          retryCandidates.addAll(candidates.asJava)
          // remove blacklist from retryCandidates
          retryCandidates.removeAll(blacklist)
          if (retryCandidates.size < 1 || (ShouldReplicate && retryCandidates.size < 2)) {
            logError("Retry reserve slots failed caused by not enough slots.")
            noAvailableSlots = true
          } else {
            // Only when the LifecycleManager needs to retry reserve slots again, re-allocate slots
            // and put the new allocated slots to the total slots, the re-allocated slots won't be
            // duplicated with existing partition locations.
            requestSlots = reallocateSlotsFromCandidates(
              failedPartitionLocations.values.toList,
              retryCandidates.asScala.toList)
            requestSlots.asScala.foreach { case (workerInfo, (retryMasterLocs, retrySlaveLocs)) =>
              val (masterPartitionLocations, slavePartitionLocations) =
                slots.computeIfAbsent(workerInfo, newLocationFunc)
              masterPartitionLocations.addAll(retryMasterLocs)
              slavePartitionLocations.addAll(retrySlaveLocs)
            }
          }
        } else {
          logError(s"Try reserve slots failed after $maxRetryTimes retry.")
        }
      }
      retryTimes += 1
    }
    // if failed after retry, destroy all allocated buffers
    if (!success) {
      // Reserve slot failed workers' partition location and corresponding peer partition location
      // has been removed from slots by call [[getFailedPartitionLocations]] and
      // [[releasePeerPartitionLocation]]. Now in the slots are all the successful partition
      // locations.
      logWarning(s"Reserve buffers $shuffleId still fail after retrying, clear buffers.")
      destroySlotsWithRetry(applicationId, shuffleId, slots)
    } else {
      logInfo(s"Reserve buffer success for ${Utils.makeShuffleKey(applicationId, shuffleId)}")
    }
    success
  }

  private val newLocationFunc =
    new util.function.Function[WorkerInfo, (JList[PartitionLocation], JList[PartitionLocation])] {
      override def apply(w: WorkerInfo): (JList[PartitionLocation], JList[PartitionLocation]) =
        (new util.LinkedList[PartitionLocation](), new util.LinkedList[PartitionLocation]())
    }

  /**
   * Allocate a new master/slave PartitionLocation pair from the current WorkerInfo list.
   *
   * @param oldEpochId Current partition reduce location last epoch id
   * @param candidates WorkerInfo list can be used to offer worker slots
   * @param slots      Current WorkerResource
   */
  private def allocateFromCandidates(
      id: Int,
      oldEpochId: Int,
      candidates: List[WorkerInfo],
      slots: WorkerResource): Unit = {
    val masterIndex = Random.nextInt(candidates.size)
    val masterLocation = new PartitionLocation(
      id,
      oldEpochId + 1,
      candidates(masterIndex).host,
      candidates(masterIndex).rpcPort,
      candidates(masterIndex).pushPort,
      candidates(masterIndex).fetchPort,
      candidates(masterIndex).replicatePort,
      PartitionLocation.Mode.MASTER)

    if (ShouldReplicate) {
      val slaveIndex = (masterIndex + 1) % candidates.size
      val slaveLocation = new PartitionLocation(
        id,
        oldEpochId + 1,
        candidates(slaveIndex).host,
        candidates(slaveIndex).rpcPort,
        candidates(slaveIndex).pushPort,
        candidates(slaveIndex).fetchPort,
        candidates(slaveIndex).replicatePort,
        PartitionLocation.Mode.SLAVE,
        masterLocation)
      masterLocation.setPeer(slaveLocation)
      val masterAndSlavePairs = slots.computeIfAbsent(candidates(slaveIndex), newLocationFunc)
      masterAndSlavePairs._2.add(slaveLocation)
    }

    val masterAndSlavePairs = slots.computeIfAbsent(candidates(masterIndex), newLocationFunc)
    masterAndSlavePairs._1.add(masterLocation)
  }

  private def reallocateForNonExistPartitionLocationFromCandidates(
      partitionId: Int,
      oldEpochId: Int,
      candidates: List[WorkerInfo]): WorkerResource = {
    val slots = new WorkerResource()
    allocateFromCandidates(partitionId, oldEpochId, candidates, slots)
    slots
  }

  private def reallocateSlotsFromCandidates(
      oldPartitions: List[PartitionLocation],
      candidates: List[WorkerInfo]): WorkerResource = {
    val slots = new WorkerResource()
    oldPartitions.foreach { partition =>
      allocateFromCandidates(partition.getId, partition.getEpoch, candidates, slots)
    }
    slots
  }

  /**
   * For the slots that need to be destroyed, LifecycleManager will ask the corresponding worker
   * to destroy related FileWriter.
   *
   * @param applicationId  application id
   * @param shuffleId      shuffle id
   * @param slotsToDestroy worker resource to be destroyed
   * @return destroy failed master and slave location unique id
   */
  private def destroySlotsWithRetry(
      applicationId: String,
      shuffleId: Int,
      slotsToDestroy: WorkerResource): Unit = {
    val shuffleKey = Utils.makeShuffleKey(applicationId, shuffleId)
    slotsToDestroy.asScala.foreach { case (workerInfo, (masterLocations, slaveLocations)) =>
      val destroy = Destroy(
        shuffleKey,
        masterLocations.asScala.map(_.getUniqueId).asJava,
        slaveLocations.asScala.map(_.getUniqueId).asJava)
      var res = requestDestroy(workerInfo.endpoint, destroy)
      if (res.status != StatusCode.SUCCESS) {
        logDebug(s"Request $destroy return ${res.status} for " +
          s"${Utils.makeShuffleKey(applicationId, shuffleId)}")
        res = requestDestroy(
          workerInfo.endpoint,
          Destroy(shuffleKey, res.failedMasters, res.failedSlaves))
      }
    }
  }

  private def removeExpiredShuffle(): Unit = {
    val currentTime = System.currentTimeMillis()
    val keys = unregisterShuffleTime.keys().asScala.toList
    keys.foreach { key =>
      if (unregisterShuffleTime.get(key) < currentTime - RemoveShuffleDelayMs) {
        logInfo(s"Clear shuffle $key.")
        // clear for the shuffle
        registeredShuffle.remove(key)
        registeringShuffleRequest.remove(key)
        reducerFileGroupsMap.remove(key)
        dataLostShuffleSet.remove(key)
        shuffleMapperAttempts.remove(key)
        stageEndShuffleSet.remove(key)
        changePartitionRequests.remove(key)
        unregisterShuffleTime.remove(key)
        shuffleAllocatedWorkers.remove(key)
        latestPartitionLocation.remove(key)

        requestUnregisterShuffle(rssHARetryClient, UnregisterShuffle(appId, key))
      }
    }
  }

  private def handleGetBlacklist(msg: GetBlacklist): Unit = {
    val res = requestGetBlacklist(rssHARetryClient, msg)
    if (res.statusCode == StatusCode.SUCCESS) {
      logInfo(s"Received Blacklist from Master, blacklist: ${res.blacklist} " +
        s"unknown workers: ${res.unknownWorkers}")
      val initFailedWorker = ConcurrentHashMap.newKeySet[WorkerInfo]()
      initFailedWorker.addAll(blacklist.asScala.filter(_.endpoint == null).asJava)
      blacklist.clear()
      blacklist.addAll(initFailedWorker)
      blacklist.addAll(res.blacklist)
      blacklist.addAll(res.unknownWorkers)
    }
  }

  private def requestSlotsWithRetry(
      applicationId: String,
      shuffleId: Int,
      ids: util.ArrayList[Integer]): RequestSlotsResponse = {
    val req =
      RequestSlots(applicationId, shuffleId, ids, lifecycleHost, ShouldReplicate, userIdentifier)
    val res = requestRequestSlots(rssHARetryClient, req)
    if (res.status != StatusCode.SUCCESS) {
      requestRequestSlots(rssHARetryClient, req)
    } else {
      res
    }
  }

  private def requestRequestSlots(
      rssHARetryClient: RssHARetryClient,
      message: RequestSlots): RequestSlotsResponse = {
    val shuffleKey = Utils.makeShuffleKey(message.applicationId, message.shuffleId)
    try {
      rssHARetryClient.askSync[RequestSlotsResponse](message, classOf[RequestSlotsResponse])
    } catch {
      case e: Exception =>
        logError(s"AskSync RegisterShuffle for $shuffleKey failed.", e)
        RequestSlotsResponse(StatusCode.FAILED, new WorkerResource())
    }
  }

  private def requestReserveSlots(
      endpoint: RpcEndpointRef,
      message: ReserveSlots): ReserveSlotsResponse = {
    val shuffleKey = Utils.makeShuffleKey(message.applicationId, message.shuffleId)
    try {
      endpoint.askSync[ReserveSlotsResponse](message)
    } catch {
      case e: Exception =>
        val msg = s"Exception when askSync ReserveSlots for $shuffleKey " +
          s"on worker ${endpoint.address}."
        logError(msg, e)
        ReserveSlotsResponse(StatusCode.FAILED, msg + s" ${e.getMessage}")
    }
  }

  private def requestDestroy(endpoint: RpcEndpointRef, message: Destroy): DestroyResponse = {
    try {
      endpoint.askSync[DestroyResponse](message)
    } catch {
      case e: Exception =>
        logError(s"AskSync Destroy for ${message.shuffleKey} failed.", e)
        DestroyResponse(StatusCode.FAILED, message.masterLocations, message.slaveLocations)
    }
  }

  private def requestCommitFiles(
      endpoint: RpcEndpointRef,
      message: CommitFiles): CommitFilesResponse = {
    try {
      endpoint.askSync[CommitFilesResponse](message)
    } catch {
      case e: Exception =>
        logError(s"AskSync CommitFiles for ${message.shuffleId} failed.", e)
        CommitFilesResponse(
          StatusCode.FAILED,
          List.empty.asJava,
          List.empty.asJava,
          message.masterIds,
          message.slaveIds)
    }
  }

  private def requestReleaseSlots(
      rssHARetryClient: RssHARetryClient,
      message: ReleaseSlots): ReleaseSlotsResponse = {
    try {
      rssHARetryClient.askSync[ReleaseSlotsResponse](message, classOf[ReleaseSlotsResponse])
    } catch {
      case e: Exception =>
        logError(s"AskSync ReleaseSlots for ${message.shuffleId} failed.", e)
        ReleaseSlotsResponse(StatusCode.FAILED)
    }
  }

  private def requestUnregisterShuffle(
      rssHARetryClient: RssHARetryClient,
      message: UnregisterShuffle): UnregisterShuffleResponse = {
    try {
      rssHARetryClient.askSync[UnregisterShuffleResponse](
        message,
        classOf[UnregisterShuffleResponse])
    } catch {
      case e: Exception =>
        logError(s"AskSync UnregisterShuffle for ${message.shuffleId} failed.", e)
        UnregisterShuffleResponse(StatusCode.FAILED)
    }
  }

  private def requestGetBlacklist(
      rssHARetryClient: RssHARetryClient,
      message: GetBlacklist): GetBlacklistResponse = {
    try {
      rssHARetryClient.askSync[GetBlacklistResponse](message, classOf[GetBlacklistResponse])
    } catch {
      case e: Exception =>
        logError(s"AskSync GetBlacklist failed.", e)
        GetBlacklistResponse(StatusCode.FAILED, List.empty.asJava, List.empty.asJava)
    }
  }

  private def recordWorkerFailure(failures: util.List[WorkerInfo]): Unit = {
    val failedWorker = new util.ArrayList[WorkerInfo](failures)
    logInfo(s"Report Worker Failure: ${failedWorker.asScala}, current blacklist $blacklist")
    blacklist.addAll(failedWorker)
  }

  def checkQuota(): Boolean = {
    try {
      rssHARetryClient.askSync[CheckQuotaResponse](
        CheckQuota(userIdentifier),
        classOf[CheckQuotaResponse]).isAvailable
    } catch {
      case e: Exception =>
        logError(s"AskSync Cluster Load Status failed.", e)
        false
    }
  }

  private def partitionExists(shuffleId: Int): Boolean = {
    val workers = workerSnapshots(shuffleId)
    if (workers == null || workers.isEmpty) {
      false
    } else {
      workers.values().asScala.exists(_.containsShuffle(shuffleId.toString))
    }
  }

  private def workersNotBlacklisted(shuffleId: Int): List[WorkerInfo] = {
    workerSnapshots(shuffleId)
      .keySet()
      .asScala
      .filter(w => !blacklist.contains(w))
      .toList
  }

  // Initialize at the end of LifecycleManager construction.
  initialize()
}
