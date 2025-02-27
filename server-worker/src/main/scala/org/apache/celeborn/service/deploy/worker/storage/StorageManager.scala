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

package org.apache.celeborn.service.deploy.worker.storage

import java.io.{File, IOException}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util
import java.util.concurrent.{ConcurrentHashMap, Executors, ThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.IntUnaryOperator

import scala.collection.JavaConverters._
import scala.concurrent.duration._

import com.google.common.util.concurrent.ThreadFactoryBuilder
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.fs.permission.FsPermission
import org.iq80.leveldb.DB

import org.apache.celeborn.common.RssConf
import org.apache.celeborn.common.exception.RssException
import org.apache.celeborn.common.internal.Logging
import org.apache.celeborn.common.meta.{DeviceInfo, DiskInfo, DiskStatus, FileInfo}
import org.apache.celeborn.common.metrics.source.AbstractSource
import org.apache.celeborn.common.network.server.MemoryTracker.MemoryTrackerListener
import org.apache.celeborn.common.protocol.{PartitionLocation, PartitionSplitMode, PartitionType}
import org.apache.celeborn.common.protocol.message.ControlMessages.{ResourceConsumption, UserIdentifier}
import org.apache.celeborn.common.util.{PbSerDeUtils, ThreadUtils, Utils}
import org.apache.celeborn.service.deploy.worker._
import org.apache.celeborn.service.deploy.worker.storage.StorageManager.hdfsFs

final private[worker] class StorageManager(conf: RssConf, workerSource: AbstractSource)
  extends ShuffleRecoverHelper with DeviceObserver with Logging with MemoryTrackerListener {
  // mount point -> filewriter
  val workingDirWriters = new ConcurrentHashMap[File, util.ArrayList[FileWriter]]()
  // userIdentifier -> shuffleKey
  val userShuffleKeys = new ConcurrentHashMap[UserIdentifier, util.Set[String]]()

  val (deviceInfos, diskInfos) = {
    val workingDirInfos =
      RssConf.workerBaseDirs(conf).map { case (workdir, maxSpace, flusherThread, storageType) =>
        (new File(workdir, RssConf.workingDirName(conf)), maxSpace, flusherThread, storageType)
      }

    if (workingDirInfos.size <= 0) {
      throw new IOException("Empty working directory configuration!")
    }

    DeviceInfo.getDeviceAndDiskInfos(workingDirInfos)
  }
  val mountPoints = new util.HashSet[String](diskInfos.keySet())

  def disksSnapshot(): List[DiskInfo] = {
    diskInfos.synchronized {
      val disks = new util.ArrayList[DiskInfo](diskInfos.values())
      disks.asScala.toList
    }
  }

  def healthyWorkingDirs(): List[File] =
    disksSnapshot().filter(_.status == DiskStatus.HEALTHY).flatMap(_.dirs)

  private val diskOperators: ConcurrentHashMap[String, ThreadPoolExecutor] = {
    val cleaners = new ConcurrentHashMap[String, ThreadPoolExecutor]()
    disksSnapshot().foreach {
      diskInfo =>
        cleaners.put(
          diskInfo.mountPoint,
          ThreadUtils.newDaemonCachedThreadPool(s"Disk-cleaner-${diskInfo.mountPoint}", 1))
    }
    cleaners
  }

  val tmpDiskInfos = new ConcurrentHashMap[String, DiskInfo]()
  disksSnapshot().foreach { diskInfo =>
    tmpDiskInfos.put(diskInfo.mountPoint, diskInfo)
  }
  private val deviceMonitor =
    DeviceMonitor.createDeviceMonitor(conf, this, deviceInfos, tmpDiskInfos)

  // (mountPoint -> LocalFlusher)
  private val localFlushers: ConcurrentHashMap[String, LocalFlusher] = {
    val flushers = new ConcurrentHashMap[String, LocalFlusher]()
    disksSnapshot().foreach { diskInfo =>
      if (!flushers.containsKey(diskInfo.mountPoint)) {
        val flusher = new LocalFlusher(
          workerSource,
          deviceMonitor,
          diskInfo.threadCount,
          diskInfo.mountPoint,
          RssConf.flushAvgTimeWindow(conf),
          RssConf.flushAvgTimeMinimumCount(conf),
          diskInfo.storageType)
        flushers.put(diskInfo.mountPoint, flusher)
      }
    }
    flushers
  }

  private val actionService = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
    .setNameFormat("StorageManager-action-thread").build)

  deviceMonitor.startCheck()

  val hdfsDir = RssConf.hdfsDir(conf)
  val hdfsPermission = FsPermission.createImmutable(755)
  val hdfsWriters = new util.ArrayList[FileWriter]()
  val hdfsFlusher =
    if (!hdfsDir.isEmpty) {
      val hdfsConfiguration = new Configuration
      hdfsConfiguration.set("fs.defaultFS", hdfsDir)
      hdfsConfiguration.set("dfs.replication", "2")
      StorageManager.hdfsFs = FileSystem.get(hdfsConfiguration)
      Some(new HdfsFlusher(
        workerSource,
        RssConf.hdfsFlusherThreadCount(conf),
        RssConf.flushAvgTimeWindow(conf),
        RssConf.flushAvgTimeMinimumCount(conf)))
    } else {
      None
    }

  override def notifyError(mountPoint: String, diskStatus: DiskStatus): Unit = this.synchronized {
    if (diskStatus == DiskStatus.IO_HANG) {
      logInfo("IoHang, remove disk operator")
      val operator = diskOperators.remove(mountPoint)
      if (operator != null) {
        operator.shutdown()
      }
    }
  }

  override def notifyHealthy(mountPoint: String): Unit = this.synchronized {
    if (!diskOperators.containsKey(mountPoint)) {
      diskOperators.put(
        mountPoint,
        ThreadUtils.newDaemonCachedThreadPool(s"Disk-cleaner-${mountPoint}", 1))
    }
  }

  private val counter = new AtomicInteger()
  private val counterOperator = new IntUnaryOperator() {
    override def applyAsInt(operand: Int): Int = {
      val dirs = healthyWorkingDirs()
      if (dirs.length > 0) {
        (operand + 1) % dirs.length
      } else 0
    }
  }

  // shuffleKey -> (fileName -> file info)
  private val fileInfos =
    new ConcurrentHashMap[String, ConcurrentHashMap[String, FileInfo]]()
  private val RECOVERY_FILE_INFOS_FILE_NAME = "fileInfos.ldb"
  private var fileInfosDb: DB = null
  // ShuffleClient can fetch data from a restarted worker only
  // when the worker's fetching port is stable.
  if (RssConf.workerGracefulShutdown(conf)) {
    try {
      val recoverFile = new File(RssConf.workerRecoverPath(conf), RECOVERY_FILE_INFOS_FILE_NAME)
      this.fileInfosDb = LevelDBProvider.initLevelDB(recoverFile, CURRENT_VERSION)
      reloadAndCleanFileInfos(this.fileInfosDb)
    } catch {
      case e: Exception =>
        logError("Init level DB failed:", e)
        this.fileInfosDb = null
    }
  }
  cleanupExpiredAppDirs(System.currentTimeMillis(), RssConf.workerGracefulShutdown(conf))
  if (!checkIfWorkingDirCleaned) {
    logWarning(
      "Worker still has residual files in the working directory before registering with Master, " +
        "please refer to the configuration document to increase rss.worker.checkFileCleanRetryTimes or " +
        "rss.worker.checkFileCleanTimeoutMs .")
  } else {
    logInfo("Successfully remove all files under working directory.")
  }

  private def reloadAndCleanFileInfos(db: DB): Unit = {
    if (db != null) {
      val itr = db.iterator
      itr.seek(SHUFFLE_KEY_PREFIX.getBytes(StandardCharsets.UTF_8))
      while (itr.hasNext) {
        val entry = itr.next
        val key = new String(entry.getKey, StandardCharsets.UTF_8)
        if (key.startsWith(SHUFFLE_KEY_PREFIX)) {
          val shuffleKey = parseDbShuffleKey(key)
          try {
            val files = PbSerDeUtils.fromPbFileInfoMap(entry.getValue)
            logDebug("Reload DB: " + shuffleKey + " -> " + files)
            fileInfos.put(shuffleKey, files)
            fileInfosDb.delete(entry.getKey)
          } catch {
            case exception: Exception =>
              logError("Reload DB: " + shuffleKey + " failed.", exception);
          }
        }
      }
    }
  }

  def updateFileInfosInDB(): Unit = {
    fileInfos.asScala.foreach { case (shuffleKey, files) =>
      try {
        fileInfosDb.put(dbShuffleKey(shuffleKey), PbSerDeUtils.toPbFileInfoMap(files))
        logDebug("Update DB: " + shuffleKey + " -> " + files)
      } catch {
        case exception: Exception =>
          logError("Update DB: " + shuffleKey + " failed.", exception)
      }
    }
  }

  private def getNextIndex() = counter.getAndUpdate(counterOperator)

  private val newMapFunc =
    new java.util.function.Function[String, ConcurrentHashMap[String, FileInfo]]() {
      override def apply(key: String): ConcurrentHashMap[String, FileInfo] =
        new ConcurrentHashMap()
    }

  private val workingDirWriterListFunc =
    new java.util.function.Function[File, util.ArrayList[FileWriter]]() {
      override def apply(t: File): util.ArrayList[FileWriter] = new util.ArrayList[FileWriter]()
    }

  @throws[IOException]
  def createWriter(
      appId: String,
      shuffleId: Int,
      location: PartitionLocation,
      splitThreshold: Long,
      splitMode: PartitionSplitMode,
      partitionType: PartitionType,
      rangeReadFilter: Boolean): FileWriter = {
    if (healthyWorkingDirs().size <= 0) {
      throw new IOException("No available working dirs!")
    }

    val fileName = location.getFileName
    var retryCount = 0
    var exception: IOException = null
    val suggestedMountPoint = location.getStorageInfo.getMountPoint
    while (retryCount < RssConf.createFileWriterRetryCount(conf)) {
      val diskInfo = diskInfos.get(suggestedMountPoint)
      val dirs =
        if (diskInfo != null && diskInfo.status.equals(DiskStatus.HEALTHY)) {
          diskInfo.dirs
        } else {
          logWarning(s"Disk unavailable for $suggestedMountPoint, return all healthy" +
            s" working dirs. diskInfo $diskInfo")
          healthyWorkingDirs()
        }
      if (dirs.isEmpty && hdfsFlusher.isEmpty) {
        throw new IOException(s"No available disks! suggested mountPoint $suggestedMountPoint")
      }
      val shuffleKey = Utils.makeShuffleKey(appId, shuffleId)
      if (dirs.isEmpty) {
        val shuffleDir =
          new Path(new Path(hdfsDir, RssConf.workingDirName(conf)), s"$appId/$shuffleId")
        FileSystem.mkdirs(StorageManager.hdfsFs, shuffleDir, hdfsPermission)
        val fileInfo = new FileInfo(new Path(shuffleDir, fileName).toString)
        val hdfsWriter = new FileWriter(
          fileInfo,
          hdfsFlusher.get,
          workerSource,
          conf,
          deviceMonitor,
          splitThreshold,
          splitMode,
          partitionType,
          rangeReadFilter)
        fileInfos.computeIfAbsent(shuffleKey, newMapFunc).put(fileName, fileInfo)
        hdfsWriters.synchronized {
          hdfsWriters.add(hdfsWriter)
        }
        hdfsWriter.registerDestroyHook(hdfsWriters)
        return hdfsWriter
      } else {
        val dir = dirs(getNextIndex() % dirs.size)
        val mountPoint = DeviceInfo.getMountPoint(dir.getAbsolutePath, mountPoints)
        val shuffleDir = new File(dir, s"$appId/$shuffleId")
        val file = new File(shuffleDir, fileName)
        try {
          shuffleDir.mkdirs()
          val createFileSuccess = file.createNewFile()
          if (!createFileSuccess) {
            throw new RssException("create app shuffle data dir or file failed!" +
              s"${file.getAbsolutePath}")
          }
          val fileInfo = new FileInfo(file.getAbsolutePath)
          val fileWriter = new FileWriter(
            fileInfo,
            localFlushers.get(mountPoint),
            workerSource,
            conf,
            deviceMonitor,
            splitThreshold,
            splitMode,
            partitionType,
            rangeReadFilter)
          deviceMonitor.registerFileWriter(fileWriter)
          val list = workingDirWriters.computeIfAbsent(dir, workingDirWriterListFunc)
          list.synchronized {
            list.add(fileWriter)
          }
          fileWriter.registerDestroyHook(list)
          fileInfos.computeIfAbsent(shuffleKey, newMapFunc).put(fileName, fileInfo)
          location.getStorageInfo.setMountPoint(mountPoint)
          logDebug(s"location $location set disk hint to ${location.getStorageInfo} ")
          return fileWriter
        } catch {
          case t: Throwable =>
            logError(
              s"Create FileWriter in path $file of mount $mountPoint failed, " +
                s"report to DeviceMonitor",
              t)
            exception = new IOException(t)
            deviceMonitor.reportDeviceError(mountPoint, exception, DiskStatus.READ_OR_WRITE_FAILURE)
        }
      }
      retryCount += 1
    }

    throw exception
  }

  def getFileInfo(shuffleKey: String, fileName: String): FileInfo = {
    val shuffleMap = fileInfos.get(shuffleKey)
    if (shuffleMap ne null) {
      shuffleMap.get(fileName)
    } else {
      null
    }
  }

  def shuffleKeySet(): util.HashSet[String] = {
    val hashSet = new util.HashSet[String]()
    hashSet.addAll(fileInfos.keySet())
    hashSet
  }

  def cleanupExpiredShuffleKey(expiredShuffleKeys: util.HashSet[String]): Unit = {
    expiredShuffleKeys.asScala.foreach { shuffleKey =>
      logInfo(s"Cleanup expired shuffle $shuffleKey.")
      val hdfsInfos = fileInfos.remove(shuffleKey).asScala.filter(_._2.isHdfs)
      val (appId, shuffleId) = Utils.splitShuffleKey(shuffleKey)
      disksSnapshot().filter(_.status != DiskStatus.IO_HANG).foreach { diskInfo =>
        diskInfo.dirs.foreach { dir =>
          val file = new File(dir, s"$appId/$shuffleId")
          deleteDirectory(file, diskOperators.get(diskInfo.mountPoint))
        }
      }
      hdfsInfos.foreach(item => item._2.deleteAllFiles(StorageManager.hdfsFs))
    }
  }

  private val noneEmptyDirExpireDurationMs = RssConf.appExpireDurationMs(conf)
  private val storageScheduler =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("storage-scheduler")

  storageScheduler.scheduleAtFixedRate(
    new Runnable {
      override def run(): Unit = {
        try {
          // Clean up dirs which has not been modified
          // in the past {{noneEmptyExpireDurationsMs}}.
          cleanupExpiredAppDirs(System.currentTimeMillis() - noneEmptyDirExpireDurationMs)
        } catch {
          case exception: Exception =>
            logWarning(s"Cleanup expired shuffle data exception: ${exception.getMessage}")
        }
      }
    },
    0,
    30,
    TimeUnit.MINUTES)

  private def cleanupExpiredAppDirs(expireTime: Long, isGracefulShutdown: Boolean = false): Unit = {
    val appIds = shuffleKeySet().asScala.map(key => Utils.splitShuffleKey(key)._1)
    disksSnapshot().filter(_.status != DiskStatus.IO_HANG).foreach { diskInfo =>
      diskInfo.dirs.foreach {
        case workingDir if workingDir.exists() =>
          workingDir.listFiles().foreach { appDir =>
            // Don't delete shuffleKey's data recovered from levelDB when restart with graceful shutdown
            if (!(isGracefulShutdown && appIds.contains(appDir.getName))) {
              if (appDir.lastModified() < expireTime) {
                val threadPool = diskOperators.get(diskInfo.mountPoint)
                deleteDirectory(appDir, threadPool)
                logInfo(s"Delete expired app dir $appDir.")
              }
            }
          }
        // workingDir not exist when initializing worker on new disk
        case _ => // do nothing
      }
    }

    if (hdfsFs != null) {
      val hdfsWorkPath = new Path(hdfsDir, RssConf.workingDirName(conf))
      if (hdfsFs.exists(hdfsWorkPath)) {
        val iter = hdfsFs.listFiles(hdfsWorkPath, false)
        while (iter.hasNext) {
          val fileStatus = iter.next()
          if (fileStatus.getModificationTime < expireTime) {
            hdfsFs.delete(fileStatus.getPath, true)
          }
        }
      }
    }
  }

  private def deleteDirectory(dir: File, threadPool: ThreadPoolExecutor): Unit = {
    val allContents = dir.listFiles
    if (allContents != null) {
      for (file <- allContents) {
        deleteDirectory(file, threadPool)
      }
    }
    threadPool.submit(new Runnable {
      override def run(): Unit = {
        deleteFileWithRetry(dir)
      }
    })
  }

  private def deleteFileWithRetry(file: File): Unit = {
    if (file.exists()) {
      var retryCount = 0
      var deleteSuccess = false
      while (!deleteSuccess && retryCount <= 3) {
        deleteSuccess = file.delete()
        retryCount = retryCount + 1
        if (!deleteSuccess) {
          Thread.sleep(200 * retryCount)
        }
      }
      if (deleteSuccess) {
        logDebug(s"Deleted expired shuffle file $file.")
      } else {
        logWarning(s"Failed to delete expired shuffle file $file.")
      }
    }
  }

  private def checkIfWorkingDirCleaned: Boolean = {
    var retryTimes = 0
    val awaitTimeout = RssConf.checkFileCleanTimeoutMs(conf)
    val appIds = shuffleKeySet().asScala.map(key => Utils.splitShuffleKey(key)._1)
    while (retryTimes < RssConf.checkFileCleanRetryTimes(conf)) {
      val localCleaned =
        !disksSnapshot().filter(_.status != DiskStatus.IO_HANG).exists { diskInfo =>
          diskInfo.dirs.exists {
            case workingDir if workingDir.exists() =>
              // Don't check appDirs that store information in the fileInfos
              workingDir.listFiles().exists(appDir => !appIds.contains(appDir.getName))
            case _ =>
              false
          }
        }

      val hdfsCleaned = hdfsFs match {
        case hdfs: FileSystem =>
          val hdfsWorkPath = new Path(hdfsDir, RssConf.workingDirName(conf))
          // hdfs path not exist when first time initialize
          if (hdfs.exists(hdfsWorkPath)) {
            !hdfs.listFiles(hdfsWorkPath, false).hasNext
          } else {
            true
          }
        case _ =>
          true
      }

      if (localCleaned && hdfsCleaned) {
        return true
      }
      retryTimes += 1
      if (retryTimes < RssConf.checkFileCleanRetryTimes(conf)) {
        logInfo(s"Working directory's files have not been cleaned up completely, " +
          s"will start ${retryTimes + 1}th attempt after ${awaitTimeout} milliseconds.")
      }
      Thread.sleep(awaitTimeout)
    }
    false
  }

  def close(): Unit = {
    if (fileInfosDb != null) {
      try {
        updateFileInfosInDB();
        fileInfosDb.close();
      } catch {
        case exception: Exception =>
          logError("Store recover data to LevelDB failed.", exception);
      }
    }
    if (null != diskOperators) {
      cleanupExpiredShuffleKey(shuffleKeySet())
      ThreadUtils.parmap(
        diskOperators.asScala.toMap,
        "ShutdownDiskOperators",
        diskOperators.size()) { entry =>
        ThreadUtils.shutdown(
          entry._2,
          RssConf.workerDiskFlusherShutdownTimeoutMs(conf).milliseconds)
      }
    }
    storageScheduler.shutdownNow()
    if (null != deviceMonitor) {
      deviceMonitor.close()
    }
  }

  private def flushFileWriters(): Unit = {
    val allWriters = new util.HashSet[FileWriter]()
    workingDirWriters.asScala.foreach { case (_, writers) =>
      writers.synchronized {
        allWriters.addAll(writers)
      }
    }
    allWriters.asScala.foreach { writer =>
      writer.flushOnMemoryPressure()
    }
  }

  override def onPause(moduleName: String): Unit = {}

  override def onResume(moduleName: String): Unit = {}

  override def onTrim(): Unit = {
    actionService.submit(new Runnable {
      override def run(): Unit = {
        flushFileWriters()
      }
    })
  }

  def updateDiskInfos(): Unit = this.synchronized {
    disksSnapshot().filter(_.status != DiskStatus.IO_HANG).foreach { diskInfo =>
      val totalUsage = diskInfo.dirs.map { dir =>
        val writers = workingDirWriters.get(dir)
        if (writers != null) {
          writers.synchronized {
            writers.asScala.map(_.getFileInfo.getFileLength).sum
          }
        } else {
          0
        }
      }.sum
      val fileSystemReportedUsableSpace = Files.getFileStore(
        Paths.get(diskInfo.mountPoint)).getUsableSpace
      val workingDirUsableSpace =
        Math.min(diskInfo.configuredUsableSpace - totalUsage, fileSystemReportedUsableSpace)
      val flushTimeAverage = localFlushers.get(diskInfo.mountPoint).averageFlushTime()
      diskInfo.setUsableSpace(workingDirUsableSpace)
      diskInfo.setFlushTime(flushTimeAverage)
    }
    logInfo(s"Updated diskInfos: ${disksSnapshot()}")
  }

  def userResourceConsumptionSnapshot(): Map[UserIdentifier, ResourceConsumption] = {
    userShuffleKeys.synchronized {
      userShuffleKeys.asScala.map { case (userIdentifier, shuffleKeys) =>
        val resourceMetrics = shuffleKeys.asScala.map { shuffleKey =>
          val userFileInfos = fileInfos.get(shuffleKey).values().asScala.toSeq
          val diskFileInfos = userFileInfos.filter(!_.isHdfs)
          val hdfsFileInfos = userFileInfos.filter(_.isHdfs)

          val diskBytesWritten = diskFileInfos.map(_.getFileLength).sum
          val diskFileCount = diskFileInfos.size
          val hdfsBytesWritten = hdfsFileInfos.map(_.getFileLength).sum
          val hdfsFileCount = hdfsFileInfos.size
          (diskBytesWritten, diskFileCount, hdfsBytesWritten, hdfsFileCount)
        }
        val resourceConsumption = ResourceConsumption(
          resourceMetrics.map(_._1).sum,
          resourceMetrics.map(_._2).sum,
          resourceMetrics.map(_._3).sum,
          resourceMetrics.map(_._4).sum)
        (userIdentifier, resourceConsumption)
      }.toMap
    }
  }
}

object StorageManager {
  var hdfsFs: FileSystem = _
}
