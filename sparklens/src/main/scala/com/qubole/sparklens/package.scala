package com.qubole

import org.apache.spark.SparkConf

package object sparklens {

  private [qubole] def getDumpDirectory(conf: SparkConf): String = {
    conf.get("spark.sparklens.data.dir", "/tmp/sparklens/")
  }

  private [qubole] def asyncReportingEnabled(conf: SparkConf): Boolean = {
    // This will dump info to `getDumpDirectory()` and not run reporting
    conf.getBoolean("spark.sparklens.reporting.disabled", false)
  }

  private [qubole] def dumpDataEnabled(conf: SparkConf): Boolean = {
    /* Even if reporting is in app, we can still dump sparklens data which could be used later */
    conf.getBoolean("spark.sparklens.save.data", true)
  }

  private [qubole] def sparklensPreviousDump(conf: SparkConf): Option[String] = {
    conf.getOption("spark.sparklens.previous.data.file")
  }

  // Width and length of autoscale graph
  private[qubole] def autoscaleGraphDimensions(conf: SparkConf): List[Int] = {
    conf.getOption("spark.sparklens.autoscale.graph.dimensions") match {
      case Some(dimensions) => dimensions.split("x").toList.map(_.toInt)
      case _ => List(100, 30)
    }
  }

  // Simulate autoscale with number of executors
  private[qubole] def simulateExecutors(conf: SparkConf): Seq[Int] = {
    try {
      conf.getOption("spark.sparklens.autoscale.graph.simulate.executors") match {
        case (Some(simulations)) => simulations.split(",").map(_.toInt).toSeq
      }
    } catch {
      case _: Throwable => Seq.empty[Int]
    }
  }

  private[qubole] def maxDelayInContainerLaunch(conf: SparkConf): Int = {
    conf.getOption("spark.sparklens.autoscale.max.delay.container.launch") match {
      case Some(delayString) =>
        val delay = delayString.toInt
        if (delay <= 0) 1 // minimum 1 millisecond delay
        else delay
      case None => 2 * 60 * 1000 // default 2 minute delay
    }
  }
}
