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

import java.util.Date
import java.util.concurrent.TimeUnit

import com.qubole.sparklens.common.AppContext
import com.sun.xml.internal.ws.developer.Serialization

import scala.collection.mutable.ListBuffer
import org.json4s.DefaultFormats
import org.json4s.JsonAST.JValue

/*
 * Interface for creating new Analyzers
 */

trait AppUIAnalyzer {
  def analyze(ac: AppContext): Map[String,Map[String,Any]] = {
    analyze(ac, ac.appInfo.startTime, ac.appInfo.endTime)
  }

  def analyze(appContext: AppContext, startTime: Long, endTime: Long): Map[String,Map[String,Any]]

  import java.text.SimpleDateFormat
  val DF = new SimpleDateFormat("hh:mm:ss:SSS")
  val MINUTES_DF = new SimpleDateFormat("hh:mm")

  /*
  print time
   */
  def pt(x: Long) : String = {
    DF.format(new  Date(x))
  }
  /*
  print duration
   */
  def pd(millis: Long) : String = {
    "%02dm %02ds".format(
      TimeUnit.MILLISECONDS.toMinutes(millis),
      TimeUnit.MILLISECONDS.toSeconds(millis) -
        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))
    )
  }

  def pcm(millis: Long) : String = {
    val millisForMinutes = millis % (60*60*1000)

    "%02dh %02dm".format(
      TimeUnit.MILLISECONDS.toHours(millis),
      TimeUnit.MILLISECONDS.toMinutes(millisForMinutes))
  }
  
  implicit class PrintlnStringBuilder(sb: StringBuilder) {
    def println(x: Any): StringBuilder = {
      sb.append(x).append("\n")
    }
    def print(x: Any): StringBuilder = {
      sb.append(x)
    }
  }
}

object AppUIAnalyzer {
  def startAnalyzers(appContext: AppContext): Map[String,Map[String,Any]] = {
    var chart_info_map = Map[String, Map[String,Any]]()
    val list = new ListBuffer[AppUIAnalyzer]
    list += new EfficiencyStatisticsUIAnalyzer
    list += new ExecutorWallclockUIAnalyzer
    list += new StageSkewUIAnalyzer
    list += new AutoscaleUIAnalyzer
    list += new SimpleAppUIAnalyzer
    
    list.foreach( x => {
      try {
        val output = x.analyze(appContext)
        chart_info_map = chart_info_map++output 
      } catch {
        case e:Throwable => {
          println(s"Failed in Analyzer ${x.getClass.getSimpleName}")
          e.printStackTrace()
        }
      }
    })
    chart_info_map
  }

}
