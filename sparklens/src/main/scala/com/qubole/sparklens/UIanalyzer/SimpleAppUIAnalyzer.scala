
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

import com.qubole.sparklens.common.AppContext

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/*
 * Created by rohitk on 21/09/17.
 */
class SimpleAppUIAnalyzer extends AppUIAnalyzer {

  def analyze(appContext: AppContext, startTime: Long, endTime: Long): Map[String,Map[String,Any]] = {
    val ac = appContext.filterByStartAndEndTime(startTime, endTime)

    var table_information= Map[String, Any]()
    var table_headers = new ListBuffer[String]()
    var table_data = new ListBuffer[ListBuffer[String]]
    table_headers += ("NAME","SUM","MIN","MAX","MEAN")
    table_data = ac.appMetrics.printJson()
    table_information += ("Table Headers" -> table_headers)
    table_information += ("Table Data" -> table_data)
    println(table_information)
    return Map[String,Map[String,Any]]("AggregateMetrics" -> table_information)
  }
}
