package com.qubole.sparklens.UIanalyzer
import com.qubole
import com.qubole.sparklens.chart.{Graph, Point}
import com.qubole.sparklens.common.AppContext
import com.qubole.sparklens.scheduler.{CompletionEstimator, DelayedExecutors}
import com.qubole.sparklens.timespan.JobTimeSpan
import org.apache.spark.SparkConf

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class AutoscaleUIAnalyzer(conf: SparkConf = new SparkConf) extends AppUIAnalyzer {
  val random = scala.util.Random
  val maxDelay: Int = qubole.sparklens.maxDelayInContainerLaunch(conf)

  override def analyze(ac: AppContext, startTime: Long, endTime: Long): Map[String,Map[String,Any]] = {
    val dimensions = qubole.sparklens.autoscaleGraphDimensions(conf)
    val coresPerExecutor = ac.executorMap.values.map(x => x.cores).sum / ac.executorMap.size

    val originalGraph = createGraphs(dimensions, ac, coresPerExecutor)
    var actualexecutors = new ListBuffer[Map[String,Long]]
    var idealexecutors = new ListBuffer[Map[String,Long]]
    originalGraph.pointsSet('o').map(p => {
      actualexecutors += Map("x" -> p.x.toLong, "y" -> p.y.toLong)
    })
    originalGraph.pointsSet('*').map(p => {
      idealexecutors += Map("x" -> p.x.toLong, "y" -> p.y.toLong)
    })

    var m1 = Map[String, ListBuffer[Map[String,Long]]]()
    m1 += ("Actual Executors" -> actualexecutors)
    m1 += ("Ideal Executors" -> idealexecutors)
    var Ewa = Map[String, Map[String, ListBuffer[Map[String,Long]]]]()
    Ewa += ("modelexecutorlinechartData" -> m1)
    Ewa
    
    
    //    qubole.sparklens.simulateExecutors(conf) match {
    //      // create one extra graph when no simulation is requested
    //      case emptyList if emptyList.isEmpty =>
    //        val minExecutorsForSameLatency = originalGraph.getMaxForChar('*')
    //        println(s"\n\nSimulating another graph which has same latency but max executors " +
    //          s"= ${minExecutorsForSameLatency} for comparison")
    //        println("Use --conf spark.sparklens.autoscale.graph.simulate.executors=10,50,100 for " +
    //          "simulating your run with 10, 50 and 100 max-executors")
    //  //      graphWithNumExec(minExecutorsForSameLatency, dimensions, ac, coresPerExecutor)
    //      case simulationExecutors: Seq[Int] => simulationExecutors.foreach(graphWithNumExec(_, dimensions, ac, coresPerExecutor))
    //    }
    //    println("\n\n=======================================================")

    //""
  }

  private def createGraphs(dimensions: List[Int], ac: AppContext, coresPerExecutor: Int): Graph = {

    val graph = new Graph(dimensions.head, dimensions.last)

    createActualExecutorGraph(ac, graph, 'o')

    createIdealPerJob(ac, graph, '*', coresPerExecutor)
    //    val autoscaleSimulationRuntime = createAutoscaleSimulation(ac, graph, '`', coresPerExecutor) - ac.appInfo.startTime

    val realDuration = ac.appInfo.endTime - ac.appInfo.startTime
    println(s"\n\nTotal app duration = ${pd(realDuration)}")
    //    println(s"Autoscale Simulation Runtime = ${pd(autoscaleSimulationRuntime)}. Difference from " +
    //      f"ideal = ${((autoscaleSimulationRuntime - realDuration).toDouble * 100) /
    //        realDuration}%3.2f%%")
    println(s"Maximum concurrent executors = ${graph.getMaxY()}")
    println(s"coresPerExecutor = ${coresPerExecutor}")
    println(s"\n\nIndex:\noooooo --> Actual number of executors")
    println("****** --> Ideal number of executors which would give same timelines")
    //    println("`````` --> Autoscale Simulation")

    //    println("/// --> Resources used")
    //    println("empty spaces between actual and ideal --> Resouces wasted")
    //graph.plot('o', '*')
    graph
  }

  private def checkForJobOverLap(spans: Seq[JobTimeSpan]): Unit = {
    var lastEnd: Long = 0
    var lastJobId: Long = -1
    spans.foreach(span => {
      if (span.startTime < lastEnd) {
        println(s"job ${lastJobId} overlap with ${span.jobID}")
        lastEnd = span.endTime
        lastJobId = span.jobID
      }
    })
  }

  private def graphWithNumExec(num: Int, dimensions: List[Int], ac: AppContext,
                               coresPerExecutor: Int)
  : Unit
  = {
    val graph = new Graph(dimensions.head, dimensions.last)

    val jobSpans = ac.jobMap.values.toSeq.sortWith(_.startTime < _.startTime)


    var lastJobCompletionTime = ac.appInfo.startTime
    var simulationRuntime = ac.appInfo.startTime
    jobSpans.foreach(jobSpan => {
      val timeBetweenJobs = jobSpan.startTime - lastJobCompletionTime

      simulationRuntime += timeBetweenJobs


      val timeToRun = CompletionEstimator.estimateJobWallClockTime(jobSpan, num, coresPerExecutor)
      val numExecutorsNeeded = minExecutorsForTime(jobSpan, timeToRun, coresPerExecutor, num)

      // 2 points for increased time
      graph.addPoint(Point(simulationRuntime, numExecutorsNeeded, '*'))
      simulationRuntime += timeToRun
      graph.addPoint(Point(simulationRuntime, numExecutorsNeeded, '*'))


      lastJobCompletionTime = jobSpan.endTime

    })
    // account for last driver time
    val lastDriverTime = ac.appInfo.endTime - lastJobCompletionTime
    graph.addPoint(Point(simulationRuntime, 0, '*'))
    simulationRuntime += lastDriverTime
    graph.addPoint(Point(simulationRuntime, 0, '*'))


    // add straight line for num executors
    graph.addPoint(Point(ac.appInfo.startTime, num + 1, 'o'))
    graph.addPoint(Point(simulationRuntime, num + 1, 'o'))


    println(s"\n\nSimulated Graph for ${num} number of executors:")
    println(s"Total app duration = ${pd(simulationRuntime - ac.appInfo.startTime)}")

    println(s"Index:\noooooo --> No Autoscaling")
    println("****** --> Ideal autoscale")
    println("/// --> Resources used")
    println("empty spaces between actual and ideal --> Resouces wasted")



    graph.plot('o', '*')
  }

  private def minExecutorsForTime(jobSpan: JobTimeSpan, timeToRun: Long, coresPerExecutor: Int,
                                  maxExecutors: Int)
  : Int = {

    var low = 1
    var high = maxExecutors
    while(low != high) {
      val mid = (low + high) / 2
      if (CompletionEstimator.estimateJobWallClockTime(jobSpan, mid, coresPerExecutor) > timeToRun) {
        low = mid + 1
      } else high = mid
    }
    low
  }

  private def createActualExecutorGraph(appContext: AppContext, graph: Graph, graphIndex: Char)
  : Unit = {
    val sorted = AppContext.getSortedMap(appContext.executorMap, appContext)
    graph.addPoint(Point(appContext.appInfo.startTime, 0, graphIndex)) // start point
    var count: Int = 0
    sorted.map(x => {
      count += x._2.asInstanceOf[Int]
      graph.addPoint(Point(x._1, count, graphIndex))
    })
    graph.addPoint(Point(appContext.appInfo.endTime, 0, graphIndex))
  }

  private def createAutoscaleSimulation(ac: AppContext, graph: Graph, graphIndex: Char,
                                        coresPerExecutor: Int): Long = {
    val maxConcurrentExecutors = AppContext.getMaxConcurrent(ac.executorMap, ac)
    var delayedExecutors = List[DelayedExecutors]()


    // initialize previousExecutors with number of executors available before 1st job
    val firstJobTime = ac.jobMap.values.toSeq.map(_.startTime).min
    var previousExecutor = ac.executorMap.values.toSeq.map(_.startTime).filter(_ < firstJobTime)
      .size
    var lastJobStarTime = firstJobTime // for keeping track of getting delayed executors

    var lastJobCompletionTime = ac.appInfo.startTime // for keeping track of driver time
    var simulationRuntime: Long = ac.appInfo.startTime
    graph.addPoint(Point(ac.appInfo.startTime, 0, graphIndex))

    ac.jobMap.values.toSeq.sortWith(_.startTime < _.startTime).foreach(jobTimeSpan => {
      //println(s"starting job = ${jobTimeSpan.jobID}")
      val optimalExecutors = optimumNumExecutorsForJob(coresPerExecutor, maxConcurrentExecutors
        .asInstanceOf[Int],
        jobTimeSpan)

      //println(s"optimal executors = ${optimalExecutors}")

      val timeBetweenJobs = jobTimeSpan.startTime - lastJobCompletionTime
      simulationRuntime += timeBetweenJobs
      //println(s"time between previous = ${timeBetweenJobs}")

      val timeToRun = if (timeBetweenJobs > AutoscaleAnalyzer.discardDelayedExecutorTime) {
        //println("discarding previous requests")
        graph.addPoint(Point(simulationRuntime - timeBetweenJobs, 0, graphIndex))
        graph.addPoint(Point(simulationRuntime, 0, graphIndex))

        delayedExecutors = List(DelayedExecutors(random.nextInt(maxDelay)
          .toLong,
          optimalExecutors))
        //println(s"Adding new delay of ${delayedExecutors.head.time}")
        // adding 1 executor, otherwise current scheduling simulation would not proceed
        val execToStartJobWith = 1
        val runTime = CompletionEstimator.estimateJobWallClockTime(jobTimeSpan,
          execToStartJobWith, coresPerExecutor, delayedExecutors)
        previousExecutor = execToStartJobWith + delayedExecutors.filter(_.time < runTime).map(_
          .numExecutors).sum

        graph.addPoint(Point(simulationRuntime, execToStartJobWith, graphIndex))
        var graphPointExecs = execToStartJobWith
        delayedExecutors.filter(_.time < runTime).foreach(delayed => {
          graph.addPoint(Point(delayed.time + simulationRuntime, delayed.numExecutors + graphPointExecs,
            graphIndex))
          graphPointExecs += delayed.numExecutors
        })
        graph.addPoint(Point(simulationRuntime + runTime, previousExecutor, graphIndex))


        runTime

      } else if (previousExecutor >= optimalExecutors) {

        //println("already executors available")
        delayedExecutors = List[DelayedExecutors]()
        previousExecutor = optimalExecutors
        val runTime = CompletionEstimator.estimateJobWallClockTime(jobTimeSpan, optimalExecutors,
          coresPerExecutor)
        graph.addPoint(Point(simulationRuntime, optimalExecutors, graphIndex))
        graph.addPoint(Point(simulationRuntime + runTime, optimalExecutors, graphIndex))

        runTime
      }
      else {
        //val totalDelayed = delayedExecutors.sortBy(_.time)
        var diff = optimalExecutors - previousExecutor
        var execToStartJobWith = previousExecutor
        //println(s"previous delay = ${delayedExecutors}")
        delayedExecutors = delayedExecutors.flatMap(delay => {
          if (diff - delay.numExecutors >= 0) {
            val execToAdd = math.min(diff, delay.numExecutors)
            val newDelayTime = delay.time - (simulationRuntime - lastJobStarTime)
            diff -= execToAdd
            if (newDelayTime > 0) {
              Some(DelayedExecutors(newDelayTime, execToAdd))
            } else {
              execToStartJobWith += execToAdd
              None
            }
          } else None
        })

        // if delayed execs have not been enough, add more execs
        if (diff > 0) {
          delayedExecutors = (DelayedExecutors(random.nextInt(maxDelay).toLong
            , diff)
            :: delayedExecutors).sortWith(_.time < _.time)
        }

        //        println(s"new delay ${delayedExecutors}")


        val runTime = CompletionEstimator.estimateJobWallClockTime(jobTimeSpan,
          execToStartJobWith, coresPerExecutor, delayedExecutors)

        previousExecutor = execToStartJobWith + delayedExecutors.filter(_.time < runTime).map(_
          .numExecutors).sum

        graph.addPoint(Point(simulationRuntime, execToStartJobWith, graphIndex))
        var graphPointExecs = execToStartJobWith
        delayedExecutors.filter(_.time < runTime).foreach(delayed => {
          graph.addPoint(Point(delayed.time + simulationRuntime, delayed.numExecutors + graphPointExecs,
            graphIndex))
          graphPointExecs += delayed.numExecutors
        })
        graph.addPoint(Point(simulationRuntime + runTime, previousExecutor, graphIndex))

        runTime
      }

      //println(s"Autoscale: job: ${jobTimeSpan.jobID} took ${pd(timeToRun)}")
      lastJobStarTime = simulationRuntime
      simulationRuntime += timeToRun
      lastJobCompletionTime = jobTimeSpan.endTime
    })

    // account for last driver time
    val lastDriverTime = ac.appInfo.endTime - lastJobCompletionTime
    graph.addPoint(Point(simulationRuntime, 0, graphIndex))
    simulationRuntime += lastDriverTime
    graph.addPoint(Point(simulationRuntime, 0, graphIndex))
    simulationRuntime
  }

  private def createIdealPerJob(ac: AppContext, graph: Graph, graphIndex: Char,
                                coresPerExecutor: Int): Unit = {
    val maxConcurrentExecutors = AppContext.getMaxConcurrent(ac.executorMap, ac)

    graph.addPoint(Point(ac.appInfo.startTime, 0, graphIndex))
    var lastJobEndTime = ac.appInfo.startTime // remove ... only for debugging

    ac.jobMap.values.toSeq.sortWith(_.startTime < _.startTime).foreach(jobTimeSpan => {
      val optimalExecutors = optimumNumExecutorsForJob(coresPerExecutor, maxConcurrentExecutors
        .asInstanceOf[Int],
        jobTimeSpan)

      // first job starting
      if (lastJobEndTime == ac.appInfo.startTime) graph.addPoint(Point(jobTimeSpan.startTime, 0,
        graphIndex))
      graph.addPoint(Point(jobTimeSpan.startTime, optimalExecutors, graphIndex))
      graph.addPoint(Point(jobTimeSpan.endTime, optimalExecutors, graphIndex))
      //      println(s"job = ${jobTimeSpan.jobID} took ${pd(jobTimeSpan.endTime - jobTimeSpan.startTime)
      //      } with ${optimalExecutors} execs, and starting at ${pd(jobTimeSpan.startTime - ac.appInfo
      //        .startTime)}. Driver time between this and previous = ${pd(jobTimeSpan.startTime - lastJobEndTime)}")
      lastJobEndTime = jobTimeSpan.endTime
    })
    //val lastJobCompletionTime = ac.jobMap.values.toSeq.map(_.endTime).max
    graph.addPoint(Point(lastJobEndTime, 0, graphIndex))
    graph.addPoint(Point(ac.appInfo.endTime, 0, graphIndex))
  }


  private def optimumNumExecutorsForJob(coresPerExecutor: Int, maxConcurrent: Int,
                                        jobTimeSpan: JobTimeSpan): Int = {
    val realTime = jobTimeSpan.endTime - jobTimeSpan.startTime
    var high = maxConcurrent
    var low = 1
    while(high != low) {
      val mid = (low + high) / 2

      val simulationTime = CompletionEstimator.estimateJobWallClockTime(jobTimeSpan, mid, coresPerExecutor)
      if (simulationTime <= realTime) {
        high = mid
      } else low = mid + 1
    }
    low
  }

}
object AutoscaleAnalyzer {
  val discardDelayedExecutorTime: Long = 60 * 1000 // 1 minute
  //val maxDelay = 2 * 60 * 1000 // 3 minutes

}
