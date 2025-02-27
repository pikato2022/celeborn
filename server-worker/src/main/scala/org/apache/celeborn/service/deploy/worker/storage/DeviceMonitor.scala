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

import java.io._
import java.nio.charset.Charset
import java.util
import java.util.{Set => jSet}
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import scala.collection.JavaConverters._
import scala.io.Source

import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

import org.apache.celeborn.common.RssConf
import org.apache.celeborn.common.RssConf.{deviceMonitorCheckList, diskCheckIntervalMs}
import org.apache.celeborn.common.meta.{DeviceInfo, DiskInfo, DiskStatus}
import org.apache.celeborn.common.util.ThreadUtils
import org.apache.celeborn.common.util.Utils._

trait DeviceMonitor {
  def startCheck() {}
  def registerFileWriter(fileWriter: FileWriter): Unit = {}
  def unregisterFileWriter(fileWriter: FileWriter): Unit = {}
  // Only local flush needs device monitor.
  def registerFlusher(flusher: LocalFlusher): Unit = {}
  def unregisterFlusher(flusher: LocalFlusher): Unit = {}
  def reportDeviceError(mountPoint: String, e: IOException, diskStatus: DiskStatus): Unit = {}
  def close() {}
}

object EmptyDeviceMonitor extends DeviceMonitor

class LocalDeviceMonitor(
    rssConf: RssConf,
    observer: DeviceObserver,
    deviceInfos: util.Map[String, DeviceInfo],
    diskInfos: util.Map[String, DiskInfo]) extends DeviceMonitor {
  val logger = LoggerFactory.getLogger(classOf[LocalDeviceMonitor])

  class ObservedDevice(val deviceInfo: DeviceInfo) {
    val diskInfos = new ConcurrentHashMap[String, DiskInfo]()
    deviceInfo.diskInfos.foreach { case diskInfo =>
      diskInfos.put(diskInfo.mountPoint, diskInfo)
    }
    val observers: jSet[DeviceObserver] = ConcurrentHashMap.newKeySet[DeviceObserver]()

    val sysBlockDir = RssConf.sysBlockDir(rssConf)
    val statFile = new File(s"$sysBlockDir/${deviceInfo.name}/stat")
    val inFlightFile = new File(s"$sysBlockDir/${deviceInfo.name}/inflight")

    var lastReadComplete: Long = -1
    var lastWriteComplete: Long = -1
    var lastReadInflight: Long = -1
    var lastWriteInflight: Long = -1

    def addObserver(observer: DeviceObserver): Unit = {
      observers.add(observer)
    }

    def removeObserver(observer: DeviceObserver): Unit = {
      observers.remove(observer)
    }

    def notifyObserversOnError(mountPoints: List[String], diskStatus: DiskStatus): Unit =
      this.synchronized {
        mountPoints.foreach { case mountPoint =>
          diskInfos.get(mountPoint).setStatus(diskStatus)
        }
        // observer.notifyDeviceError might remove itself from observers,
        // so we need to use tmpObservers
        val tmpObservers = new util.HashSet[DeviceObserver](observers)
        tmpObservers.asScala.foreach(ob => {
          mountPoints.foreach { case mountPoint =>
            ob.notifyError(mountPoint, diskStatus)
          }
        })
      }

    def notifyObserversOnHealthy(mountPoint: String): Unit = this.synchronized {
      diskInfos.get(mountPoint).setStatus(DiskStatus.HEALTHY)
      val tmpObservers = new util.HashSet[DeviceObserver](observers)
      tmpObservers.asScala.foreach(ob => {
        ob.notifyHealthy(mountPoint)
      })
    }

    def notifyObserversOnHighDiskUsage(mountPoint: String): Unit = this.synchronized {
      diskInfos.get(mountPoint).setStatus(DiskStatus.HIGH_DISK_USAGE)
      val tmpObservers = new util.HashSet[DeviceObserver](observers)
      tmpObservers.asScala.foreach(ob => {
        ob.notifyHighDiskUsage(mountPoint)
      })
    }

    /**
     * @return true if device is hang
     */
    def ioHang(): Boolean = {
      if (deviceInfo.deviceStatAvailable) {
        false
      } else {
        var statsSource: Source = null
        var infligtSource: Source = null

        try {
          statsSource = Source.fromFile(statFile)
          infligtSource = Source.fromFile(inFlightFile)
          val stats = statsSource.getLines().next().trim.split("[ \t]+", -1)
          val inflight = infligtSource.getLines().next().trim.split("[ \t]+", -1)
          val readComplete = stats(0).toLong
          val writeComplete = stats(4).toLong
          val readInflight = inflight(0).toLong
          val writeInflight = inflight(1).toLong

          if (lastReadComplete == -1) {
            lastReadComplete = readComplete
            lastWriteComplete = writeComplete
            lastReadInflight = readInflight
            lastWriteInflight = writeInflight
            false
          } else {
            val isReadHang = lastReadComplete == readComplete &&
              readInflight >= lastReadInflight && lastReadInflight > 0
            val isWriteHang = lastWriteComplete == writeComplete &&
              writeInflight >= lastWriteInflight && lastWriteInflight > 0

            if (isReadHang || isWriteHang) {
              logger.info(s"Result of DeviceInfo.checkIoHang, DeviceName: ${deviceInfo.name}" +
                s"($readComplete,$writeComplete,$readInflight,$writeInflight)\t" +
                s"($lastReadComplete,$lastWriteComplete,$lastReadInflight,$lastWriteInflight)\t" +
                s"Observer cnt: ${observers.size()}")
              logger.error(s"IO Hang! ReadHang: $isReadHang, WriteHang: $isWriteHang")
            }

            lastReadComplete = readComplete
            lastWriteComplete = writeComplete
            lastReadInflight = readInflight
            lastWriteInflight = writeInflight

            isReadHang || isWriteHang
          }
        } catch {
          case e: Exception =>
            logger.warn(s"Encounter Exception when check IO hang for device ${deviceInfo.name}", e)
            // we should only return true if we have direct evidence that the device is hang
            false
        } finally {
          if (statsSource != null) {
            statsSource.close()
          }
          if (infligtSource != null) {
            infligtSource.close()
          }
        }
      }
    }

    override def toString: String = {
      s"DeviceName: ${deviceInfo.name}\tMount Infos: ${diskInfos.values().asScala.mkString("\n")}"
    }
  }

  // (deviceName -> ObservedDevice)
  var observedDevices: util.Map[DeviceInfo, ObservedDevice] = _

  val diskCheckInterval = diskCheckIntervalMs(rssConf)

  // we should choose what the device needs to detect
  val monitorCheckList = deviceMonitorCheckList(rssConf)
  val checkIoHang = monitorCheckList.contains("iohang")
  val checkReadWrite = monitorCheckList.contains("readwrite")
  val checkDiskUsage = monitorCheckList.contains("diskusage")
  private val diskChecker =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("worker-disk-checker")

  def init(): Unit = {
    this.observedDevices = new util.HashMap[DeviceInfo, ObservedDevice]()
    deviceInfos.asScala.filter(_._2.deviceStatAvailable).foreach { case (deviceName, _) =>
      logger.warn(s"device monitor may not worker properly " +
        s"because noDevice device $deviceName exists.")
    }
    deviceInfos.asScala.foreach(entry => {
      val observedDevice = new ObservedDevice(entry._2)
      observedDevice.addObserver(observer)
      observedDevices.put(entry._2, observedDevice)
    })
  }

  override def startCheck(): Unit = {
    diskChecker.scheduleAtFixedRate(
      new Runnable {
        override def run(): Unit = {
          logger.debug("Device check start")
          try {
            observedDevices.values().asScala.foreach(device => {
              val mountPoints = device.diskInfos.keySet.asScala.toList

              if (checkIoHang && device.ioHang()) {
                logger.error(s"Encounter device io hang error!" +
                  s"${device.deviceInfo.name}, notify observers")
                device.notifyObserversOnError(mountPoints, DiskStatus.IO_HANG)
              } else {
                device.diskInfos.values().asScala.foreach { case diskInfo =>
                  if (checkDiskUsage && DeviceMonitor.highDiskUsage(rssConf, diskInfo.mountPoint)) {
                    logger.error(s"${diskInfo.mountPoint} high_disk_usage error, notify observers")
                    device.notifyObserversOnHighDiskUsage(diskInfo.mountPoint)
                  } else if (checkReadWrite &&
                    DeviceMonitor.readWriteError(rssConf, diskInfo.dirs.head)) {
                    logger.error(s"${diskInfo.mountPoint} read-write error, notify observers")
                    // We think that if one dir in device has read-write problem, if possible all
                    // dirs in this device have the problem
                    device.notifyObserversOnError(
                      List(diskInfo.mountPoint),
                      DiskStatus.READ_OR_WRITE_FAILURE)
                  } else {
                    device.notifyObserversOnHealthy(diskInfo.mountPoint)
                  }
                }
              }
            })
          } catch {
            case t: Throwable =>
              logger.error("Device check failed.", t)
          }
        }
      },
      diskCheckInterval,
      diskCheckInterval,
      TimeUnit.MILLISECONDS)
  }

  override def registerFileWriter(fileWriter: FileWriter): Unit = {
    val mountPoint = DeviceInfo.getMountPoint(fileWriter.getFile.getAbsolutePath, diskInfos)
    observedDevices.get(diskInfos.get(mountPoint).deviceInfo).addObserver(fileWriter)
  }

  override def unregisterFileWriter(fileWriter: FileWriter): Unit = {
    val mountPoint = DeviceInfo.getMountPoint(fileWriter.getFile.getAbsolutePath, diskInfos)
    observedDevices.get(diskInfos.get(mountPoint).deviceInfo).removeObserver(fileWriter)
  }

  override def registerFlusher(flusher: LocalFlusher): Unit = {
    observedDevices.get(diskInfos.get(flusher.mountPoint).deviceInfo).addObserver(flusher)
  }

  override def unregisterFlusher(flusher: LocalFlusher): Unit = {
    observedDevices.get(diskInfos.get(flusher.mountPoint).deviceInfo).removeObserver(flusher)
  }

  override def reportDeviceError(
      mountPoint: String,
      e: IOException,
      diskStatus: DiskStatus): Unit = {
    logger.error(s"Receive report exception, disk $mountPoint, $e")
    if (diskInfos.containsKey(mountPoint)) {
      observedDevices.get(diskInfos.get(mountPoint).deviceInfo)
        .notifyObserversOnError(List(mountPoint), diskStatus)
    }
  }

  override def close(): Unit = {
    if (null != DeviceMonitor.deviceCheckThreadPool) {
      DeviceMonitor.deviceCheckThreadPool.shutdownNow()
    }
  }
}

object DeviceMonitor {
  val logger = LoggerFactory.getLogger(classOf[DeviceMonitor])
  val deviceCheckThreadPool = ThreadUtils.newDaemonCachedThreadPool("device-check-thread", 5)

  def createDeviceMonitor(
      rssConf: RssConf,
      deviceObserver: DeviceObserver,
      deviceInfos: util.Map[String, DeviceInfo],
      diskInfos: util.Map[String, DiskInfo]): DeviceMonitor = {
    try {
      if (RssConf.deviceMonitorEnabled(rssConf)) {
        val monitor = new LocalDeviceMonitor(rssConf, deviceObserver, deviceInfos, diskInfos)
        monitor.init()
        logger.info("Device monitor init success")
        monitor
      } else {
        EmptyDeviceMonitor
      }
    } catch {
      case t: Throwable =>
        logger.error("Device monitor init failed.", t)
        throw t
    }
  }

  /**
   * check if the disk is high usage
   * @param rssConf conf
   * @param diskRootPath disk root path
   * @return true if high disk usage
   */
  def highDiskUsage(rssConf: RssConf, diskRootPath: String): Boolean = {
    tryWithTimeoutAndCallback({
      val usage = runCommand(s"df -B 1G $diskRootPath").trim.split("[ \t]+")
      val totalSpace = usage(usage.length - 5)
      val freeSpace = usage(usage.length - 3)
      val used_percent = usage(usage.length - 2)

      val status = freeSpace.toLong < RssConf.diskMinimumReserveSize(rssConf) / 1024 / 1024 / 1024
      if (status) {
        logger.warn(s"$diskRootPath usage:{total:$totalSpace GB," +
          s" free:$freeSpace GB, used_percent:$used_percent}")
      }
      status
    })(false)(
      deviceCheckThreadPool,
      RssConf.workerStatusCheckTimeout(rssConf),
      s"Disk: $diskRootPath Usage Check Timeout")
  }

  /**
   * check if the data dir has read-write problem
   * @param rssConf conf
   * @param dataDir one of shuffle data dirs in mount disk
   * @return true if disk has read-write problem
   */
  def readWriteError(rssConf: RssConf, dataDir: File): Boolean = {
    if (null == dataDir || !dataDir.isDirectory) {
      return false
    }

    tryWithTimeoutAndCallback({
      try {
        val file = new File(dataDir, s"_SUCCESS_${System.currentTimeMillis()}")
        if (!file.exists() && !file.createNewFile()) {
          true
        } else {
          FileUtils.write(file, "test", Charset.defaultCharset)
          var fileInputStream: FileInputStream = null
          var inputStreamReader: InputStreamReader = null
          var bufferReader: BufferedReader = null
          try {
            fileInputStream = FileUtils.openInputStream(file)
            inputStreamReader = new InputStreamReader(fileInputStream, Charset.defaultCharset())
            bufferReader = new BufferedReader(inputStreamReader)
            bufferReader.readLine()
          } finally {
            bufferReader.close()
            inputStreamReader.close()
            fileInputStream.close()
          }
          FileUtils.forceDelete(file)
          false
        }
      } catch {
        case t: Throwable =>
          logger.error(s"Disk dir $dataDir cannot read or write", t)
          true
      }
    })(false)(
      deviceCheckThreadPool,
      RssConf.workerStatusCheckTimeout(rssConf),
      s"Disk: $dataDir Read_Write Check Timeout")
  }

  def EmptyMonitor(): DeviceMonitor = EmptyDeviceMonitor
}
