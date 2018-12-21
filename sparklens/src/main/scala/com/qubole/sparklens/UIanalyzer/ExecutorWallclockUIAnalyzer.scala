
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

import java.util.concurrent.TimeUnit

import com.qubole.sparklens.scheduler.CompletionEstimator
import com.qubole.sparklens.common.{AggregateMetrics, AppContext}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.ListBuffer

/*
 * Created by rohitk on 21/09/17.
 */
class ExecutorWallclockUIAnalyzer extends AppUIAnalyzer {

  def analyze(appContext: AppContext, startTime: Long, endTime: Long):Map[String,Map[String,Any]]  = {
    val ac = appContext.filterByStartAndEndTime(startTime, endTime)
    val out = new mutable.StringBuilder()

    val coresPerExecutor    =  ac.executorMap.values.map(x => x.cores).sum/ac.executorMap.size
    val appExecutorCount    =  ac.executorMap.size
    val testPercentages     =  Array(10, 20, 50, 80, 100, 110, 120, 150, 200, 300, 400, 500)
    val appRealDuration = endTime - startTime
    printModelError(ac, appRealDuration, out)


    val pool = java.util.concurrent.Executors.newFixedThreadPool(testPercentages.size)
    val results = new mutable.HashMap[Int, String]()
    var clustertime = new ListBuffer[Map[String,Float]]
    var clusterutilization = new ListBuffer[Map[String,Float]]
    for (percent <- testPercentages) {
      pool.execute( new Runnable {
        override def run(): Unit = {
          val executorCount = (appExecutorCount * percent)/100
          out.println(executorCount)
          if (executorCount > 0) {
            val estimatedTime = CompletionEstimator.estimateAppWallClockTime(ac, executorCount, coresPerExecutor, appRealDuration)
            val utilization =  ac.stageMap.map(x => x._2.stageMetrics.map(AggregateMetrics.executorRuntime).value).sum.toDouble*100/(estimatedTime*executorCount*coresPerExecutor)
            clustertime += Map("x" -> executorCount.toFloat, "y" -> estimatedTime.toFloat)
            clusterutilization += Map("x" -> executorCount.toFloat, "y" -> utilization.toFloat)
          }
        }
      })
    }
    
    pool.shutdown()
    if (!pool.awaitTermination(2, TimeUnit.MINUTES)) {
      //we timed out
      out.println (
        s"""
           |WARN: Timed out calculating estimations for various executor counts.
           |WARN: ${results.size} of total ${testPercentages.size} estimates available at this time.
           |WARN: Please share the event log file with Qubole, to help us debug this further.
           |WARN: Apologies for the inconvenience.\n
         """.stripMargin)

    }

    var m1 = Map[String, ListBuffer[Map[String,Float]]]()
    m1 += ("Estimated Wallclock Time" -> clustertime)
    m1 += ("Estimated Cluster Utilization" -> clusterutilization)
    var Ewa = Map[String, Map[String, ListBuffer[Map[String,Float]]]]()
    Ewa += ("modellinechartData" -> m1)
    Ewa
  }

  def printModelError(ac: AppContext, appRealDuration: Long, out: mutable.StringBuilder): Unit = {
    val coresPerExecutor    =  ac.executorMap.values.map(x => x.cores).sum/ac.executorMap.size
    val appExecutorCount    =  ac.executorMap.size
    @volatile var estimatedTime: Long = -1
    val thread = new Thread {
      override def run(): Unit = {
        estimatedTime = CompletionEstimator.estimateAppWallClockTime(ac, appExecutorCount, coresPerExecutor, appRealDuration)
      }
    }
    thread.setDaemon(true)
    thread.start()
    thread.join(60*1000)

    if (estimatedTime < 0) {
      //we timed out
      out.println (
        s"""
           |WARN: Timed out calculating model estimation time.
           |WARN: Please share the event log file with Qubole, to help us debug this further.
           |WARN: Apologies for the inconvenience.
         """.stripMargin)
      return
    }

    out.println (
      s"""
         | Real App Duration ${pd(appRealDuration)}
         | Model Estimation  ${pd(estimatedTime)}
         | Model Error       ${(Math.abs(appRealDuration-estimatedTime)*100)/appRealDuration}%
         |
         | NOTE: 1) Model error could be large when auto-scaling is enabled.
         |       2) Model doesn't handles multiple jobs run via thread-pool. For better insights into
         |          application scalability, please try such jobs one by one without thread-pool.
         |
       """.stripMargin)
  }
}
