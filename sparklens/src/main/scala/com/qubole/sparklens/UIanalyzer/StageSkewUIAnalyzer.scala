
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
package com.qubole.sparklens.UIanalyzer

import java.util.Locale

import com.qubole.sparklens.common.{AggregateMetrics, AppContext}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/*
 * Created by rohitk on 21/09/17.
 */
class StageSkewUIAnalyzer extends AppUIAnalyzer {


  def analyze(appContext: AppContext, startTime: Long, endTime: Long): Map[String,Map[String,Any]] = {
    val ac = appContext.filterByStartAndEndTime(startTime, endTime)
    val out = new mutable.StringBuilder()
    val (table_headers1,table_data1) = computePerStageEfficiencyStatistics(ac, out)
    val (table_headers2,table_data2) = checkForGCOrShuffleService(ac, out)
    var table_information= Map[String, Any]()
    var table_headers = table_headers1 ++ table_headers2
    var table_data = new ListBuffer[ListBuffer[String]]()
    for((x1,x2) <- (table_data1 zip table_data2))
    {
      table_data += x1 ++ x2   
    }
    
    table_information += ("Table Headers" -> table_headers)
    table_information += ("Table Data" -> table_data)
    return Map[String,Map[String,Any]]("modeltableData" -> table_information)
  }


  def bytesToString(size: Long): String = {
    val TB = 1L << 40
    val GB = 1L << 30
    val MB = 1L << 20
    val KB = 1L << 10

    val (value, unit) = {
      if (Math.abs(size) >= 1*TB) {
        (size.asInstanceOf[Double] / TB, "TB")
      } else if (Math.abs(size) >= 1*GB) {
        (size.asInstanceOf[Double] / GB, "GB")
      } else if (Math.abs(size) >= 1*MB) {
        (size.asInstanceOf[Double] / MB, "MB")
      } else {
        (size.asInstanceOf[Double] / KB, "KB")
      }
    }
    "%.1f %s".formatLocal(Locale.US, value, unit)
  }

  def computePerStageEfficiencyStatistics(ac: AppContext, out: mutable.StringBuilder):Tuple2[ListBuffer[String],ListBuffer[ListBuffer[String]]] = {

    val totalTasks = ac.stageMap.map(x => x._2.taskExecutionTimes.length).sum
    var table_headers = new ListBuffer[String]
    table_headers += ("Stage-ID","WallClock%","Task Runtime%","Task Count","IO%","Input","Output","Shuffle-Input","Suffle-Output","WallClockTime Measured","WallClockTime Ideal","OneCoreComputeHoursAvailable","OneCoreComputeHoursAvailable Used%","OneCoreComputeHoursAvailable Wasted%","MaxTaskMem")
    var table_data = new ListBuffer[ListBuffer[String]] 
    val maxExecutors = AppContext.getMaxConcurrent(ac.executorMap, ac)
    val totalCores = ac.executorMap.values.last.cores * maxExecutors
    val totalMillis = ac.stageMap.map(x =>
      x._2.duration().getOrElse(0L)
    ).sum * totalCores

    val totalRuntime = ac.stageMap.map(x =>
      x._2.stageMetrics.map(AggregateMetrics.executorRuntime).value).sum

    val totalExecutors = ac.executorMap.size
    val totalIOBytes   = ac.jobMap.values.map ( x => (  x.jobMetrics.map(AggregateMetrics.inputBytesRead).value
      + x.jobMetrics.map(AggregateMetrics.outputBytesWritten).value
      + x.jobMetrics.map(AggregateMetrics.shuffleWriteBytesWritten).value
      + x.jobMetrics.map(AggregateMetrics.shuffleReadBytesRead).value)
    ).sum

    ac.stageMap.keySet
      .toBuffer
      .sortWith( _ < _ )
      .filter( x => ac.stageMap.get(x).get.endTime != 0)
      .foreach(x => {
        val sts = ac.stageMap.get(x).get
        val duration = sts.duration().get
        val available = totalCores * duration
        val stagePercent = (available * 100 / totalMillis.toFloat).toInt
        val used = sts.stageMetrics.map(AggregateMetrics.executorRuntime).value
        val wasted = available - used
        val usedPercent = (used * 100) / available.toFloat
        val wastedPercent = (wasted * 100) / available.toFloat
        val executorCores = totalCores / totalExecutors
        val stageBytes = sts.stageMetrics.map(AggregateMetrics.inputBytesRead).value
        +sts.stageMetrics.map(AggregateMetrics.outputBytesWritten).value
        +sts.stageMetrics.map(AggregateMetrics.shuffleWriteBytesWritten).value
        +sts.stageMetrics.map(AggregateMetrics.shuffleReadBytesRead).value
        val maxTaskMemory = sts.taskPeakMemoryUsage.take(executorCores.toInt).sum // this could
        // be at different times?
        //val maxTaskMemoryUtilization = (maxTaskMemory*100)/executorMemory
        val IOPercent = (stageBytes * 100) / totalIOBytes.toFloat
        val taskRuntimePercent = (sts.stageMetrics.map(AggregateMetrics.executorRuntime).value * 100) / totalRuntime.toFloat
        val idealWallClock = sts.stageMetrics.map(AggregateMetrics.executorRuntime).value / (totalExecutors * executorCores)

        var temp = new ListBuffer[String]()
        temp += (f"${x}", f"${stagePercent}%5.2f", f"${taskRuntimePercent}%5.2f", f"${sts.taskExecutionTimes.length}",
          f"${IOPercent}%5.1f", f"${bytesToString(sts.stageMetrics.map(AggregateMetrics.inputBytesRead).value)}",
          f"${bytesToString(sts.stageMetrics.map(AggregateMetrics.outputBytesWritten).value)}",
          f"${bytesToString(sts.stageMetrics.map(AggregateMetrics.shuffleReadBytesRead).value)}",
          f" ${bytesToString(sts.stageMetrics.map(AggregateMetrics.shuffleWriteBytesWritten).value)}",
          f"${pd(duration)}", f"${pd(idealWallClock)}", f"${pcm(available)}", f"$usedPercent%5.1f", f"$wastedPercent%5.1f", f"${bytesToString(maxTaskMemory)}")
        table_data += temp
      })
    return new Tuple2(table_headers,table_data)
  }

  def checkForGCOrShuffleService(ac: AppContext, out: mutable.StringBuilder): Tuple2[ListBuffer[String],ListBuffer[ListBuffer[String]]] = {
    val maxExecutors = AppContext.getMaxConcurrent(ac.executorMap, ac)
    val totalCores = ac.executorMap.values.last.cores * maxExecutors
    val totalMillis = ac.stageMap.filter(x => x._2.endTime > 0).map(x => x._2.duration().get).sum * totalCores


    var table_headers = new ListBuffer[String]()
    table_headers += ("WallClock Stage%", "OneCoreComputeHours","TaskCount","PRatio","TaskSkew","Task StageSkew","OIRatio","ShuffleWrite%","ReadFetch%","GC%")
    var table_data = new ListBuffer[ListBuffer[String]]()

    ac.stageMap.keySet.toBuffer.sortWith( _ < _ )
      .filter( x => ac.stageMap(x).endTime > 0)
      .foreach(x => {
        val sts =  ac.stageMap(x)
        val totalExecutorTime     = sts.stageMetrics.map(AggregateMetrics.executorRuntime).value
        //shuffleWriteTime is in nanoSeconds
        val writeTimePercent:Float = (sts.stageMetrics.map(AggregateMetrics.shuffleWriteTime).value.toFloat * 100)/totalExecutorTime/(1000*1000)
        val readFetchPercent:Float  = (sts.stageMetrics.map(AggregateMetrics.shuffleReadFetchWaitTime).value.toFloat * 100)/ totalExecutorTime
        val gcPercent:Float        = (sts.stageMetrics.map(AggregateMetrics.jvmGCTime).value.toFloat * 100) / totalExecutorTime

        val available = totalCores * ac.stageMap.get(x).get.duration.get
        val stagePercent:Float = (available.toFloat*100/totalMillis)
        val parallelismRatio:Float  = sts.stageMetrics.count.toFloat/totalCores
        val maxTaskTime = sts.taskExecutionTimes.max
        val meanTaskTime = if (sts.taskExecutionTimes.length == 0) {
          0
        }else if (sts.taskExecutionTimes.length == 1) {
          sts.taskExecutionTimes(0)
        }else {
          sts.taskExecutionTimes.sortWith(_ < _ )(sts.taskExecutionTimes.length/2)
        }

        val taskSkew:Float  = if (meanTaskTime > 0) {
          maxTaskTime.toFloat / meanTaskTime
        }else {
          0
        }
        val duration = sts.duration().get
        val taskStageSkew: Float = if (duration > 0) {
          maxTaskTime.toFloat/duration
        } else {
          0
        }


        val totalInput = sts.stageMetrics.map(AggregateMetrics.inputBytesRead).value + sts.stageMetrics.map(AggregateMetrics.shuffleReadBytesRead).value
        val totalOutput = sts.stageMetrics.map(AggregateMetrics.outputBytesWritten).value+ sts.stageMetrics.map(AggregateMetrics.shuffleWriteBytesWritten).value
        val oiRatio:Float = if (totalInput == 0) {
          0
        }else {
          totalOutput.toFloat/totalInput
        }
        val temp = new ListBuffer[String]()
        temp += (f"${stagePercent}%.2f",f"${pcm(totalExecutorTime)}",f"${sts.taskExecutionTimes.length}",f"$parallelismRatio%.2f",f"$taskSkew%.2f",f"$taskStageSkew%.2f",f"$oiRatio%.2f ",f"${writeTimePercent}%.2f",f"${readFetchPercent}%.2f",f"${gcPercent}%.2f")
        table_data += temp
      })
    return Tuple2(table_headers,table_data)
  }
}
