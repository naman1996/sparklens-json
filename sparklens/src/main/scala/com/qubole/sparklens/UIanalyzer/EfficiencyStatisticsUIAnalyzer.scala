
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

import com.qubole.sparklens.common.{AggregateMetrics, AppContext}
import scala.collection.mutable

/*
 * Created by rohitk on 21/09/17.
 */
class EfficiencyStatisticsUIAnalyzer extends AppUIAnalyzer {

  def analyze(appContext: AppContext, startTime: Long, endTime: Long): Map[String,Map[String,Any]] = {
    val ac = appContext.filterByStartAndEndTime(startTime, endTime)
    // wall clock time, appEnd - appStart
    val appTotalTime = endTime - startTime
    // wall clock time per Job. Aggregated
    val jobTime   = ac.jobMap.values
      .map(x => (x.endTime - x.startTime))
      .sum

    /* sum of cores in all the executors:
     * There are executors coming up and going down.
     * We are taking the max-number of executors running at any point of time, and
     * multiplying it by num-cores per executor (assuming homogenous cluster)
     */
    val maxExecutors = AppContext.getMaxConcurrent(ac.executorMap, ac)
    val totalCores = ac.executorMap.values.last.cores * maxExecutors
    // total compute millis available to the application
    val appComputeMillisAvailable = totalCores * appTotalTime
    val computeMillisFromExecutorLifetime = ac.executorMap.map( x => {
      val ecores = x._2.cores
      val estartTime = Math.max(startTime, x._2.startTime)
      val eendTime  = if (x._2.isFinished()) {
        Math.min(endTime, x._2.endTime)
      }else {
        endTime
      }
      ecores * (eendTime - estartTime)
    }).sum

    // some of the compute millis are lost when driver is doing some work
    // and has not assigned any work to the executors
    // We assume executors are only busy when one of the job is in progress
    val inJobComputeMillisAvailable = totalCores * jobTime
    // Minimum time required to run a job even when we have infinite number
    // of executors, essentially the max time taken by any task in the stage.
    // which is in the critical path. Note that some stages can run in parallel
    // we cannot reduce the job time to less than this number.
    // Aggregating over all jobs, to get the lower bound on this time.
    val criticalPathTime = ac.jobMap.map( x => x._2.computeCriticalTimeForJob()).sum

    //sum of millis used by all tasks of all jobs
    val inJobComputeMillisUsed  = ac.jobMap.values
      .filter(x => x.endTime > 0).map(x =>
      x.jobMetrics.map(AggregateMetrics.executorRuntime).value)
      .sum

    val perfectJobTime  = ac.jobMap.values
      .filter(x => x.endTime > 0)
      .map(x => x.jobMetrics.map(AggregateMetrics.executorRuntime).value).sum/totalCores

    //Enough variables lets print some

    val driverTimeJobBased = appTotalTime - jobTime
    val driverComputeMillisWastedJobBased  = driverTimeJobBased * totalCores
   
    val executorUsedPercent = inJobComputeMillisUsed*100/inJobComputeMillisAvailable.toFloat
    val executorWastedPercent = (inJobComputeMillisAvailable - inJobComputeMillisUsed)*100/inJobComputeMillisAvailable.toFloat
    val driverWastedPercentOverAll = driverComputeMillisWastedJobBased*100/appComputeMillisAvailable.toFloat
    val executorWastedPercentOverAll = (inJobComputeMillisAvailable - inJobComputeMillisUsed)*100 / appComputeMillisAvailable.toFloat



    var Efa = Map[String, Map[String,Any]]()
    var m1,m2= Map[String, Long]()
    var m3 = Map[String,Float]()
    m1+= ("Driver Wallclock Time" -> driverTimeJobBased)
    m1+= ("Executor Wallclock Time" -> jobTime)
    m1+= ("Total Wallclock Time" -> appTotalTime)
    Efa+= ("wallclockData" -> m1)

    m2+= ("Acutal Runtime" -> (appTotalTime))
    m2+= ("Critical path" ->  (driverTimeJobBased + criticalPathTime).toInt)
    m2+= ("Ideal application time" -> (driverTimeJobBased + perfectJobTime).toInt)
    Efa+= ("modelruntimeData" -> m2)
    
    m3+= ("Total OCCH available" -> 100.toFloat)
    m3+= ("Total OCCH wasted" -> (executorWastedPercentOverAll+driverWastedPercentOverAll).toFloat)
    m3+= ("OCCH wasted by executor" -> (executorWastedPercentOverAll).toFloat)
    m3+= ("OCCH wasted by driver" -> (driverWastedPercentOverAll).toFloat)
    
    Efa+= ("modelwastedtimeData" -> m3)
    Efa
  }
}
