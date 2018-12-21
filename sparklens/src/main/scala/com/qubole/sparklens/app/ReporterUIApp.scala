package com.qubole.sparklens.app

import java.io.{BufferedInputStream, InputStream}
import java.net.URI

import com.ning.compress.lzf.LZFInputStream
import com.qubole.sparklens.QuboleJobListener
import net.jpountz.lz4.LZ4BlockInputStream
import com.qubole.sparklens.analyzer.AppAnalyzer
import com.qubole.sparklens.UIanalyzer.AppUIAnalyzer
import com.qubole.sparklens.common.AppContext
import com.sun.xml.internal.ws.developer.Serialization
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.SparkConf
import org.json4s.DefaultFormats
import org.json4s.JsonAST.JValue
import org.json4s.jackson.JsonMethods.parse
import org.xerial.snappy.SnappyInputStream
import org.json4s.DefaultFormats
import org.json4s.JsonAST.JValue
import org.json4s.jackson.{Serialization => JsonSerialization}
import java.io._


object ReporterUIApp extends App {

  val usage = "Need to specify sparklens data file\n" +
    "Of specify event-history file and also add \"source=history\" or \"source=sparklens\".\n" +
    "If \"source\" is not specified, sparklens is chosen by default."

  checkArgs()
  parseInput()

  private def checkArgs(): Unit = {
    args.size match {
      case x if x < 1 => throw new IllegalArgumentException(usage)
      case _ => // Do nothing
    }
  }

  def startAnalysersFromString(json: String,output_json_path:String): Unit = {

    implicit val formats = DefaultFormats
    val map = parse(json).extract[JValue]

    val appContext = AppContext.getContext(map)
    val output = startAnalysersFromAppContext(appContext)
    
    //converting the output ot json and writing it in a folder
    val chart_info_json = JsonSerialization.writePretty(output)
    val writer = new PrintWriter(new File(output_json_path+".out"))
    writer.write(chart_info_json.toString)
    writer.close()
  }

  private def startAnalysersFromAppContext(appContext: AppContext): Map[String,Map[String,Any]] = {
    AppUIAnalyzer.startAnalyzers(appContext)
  }


  private def parseInput(): Unit = {

    getSource match {
      case "sparklens" => reportFromSparklensDump(args(0))
      case _ => new EventHistoryReporter(args(0)) // event files
    }
  }

  private def getSource: String = {
    args.foreach(arg => {
      val splits = arg.split("=")
      if (splits.size == 2) {
        if ("source".equalsIgnoreCase(splits(0))) {
          if ("history".equalsIgnoreCase(splits(1))) {
            return "history"
          } else return "sparklens"
        } else new IllegalArgumentException(usage)
      }
    })
    return "sparklens"

  }

  private def reportFromSparklensDump(file: String): Unit = {
    val fs = FileSystem.get(new URI(file), new Configuration())
    val path = new Path(file)
    val byteArray = new Array[Byte](fs.getFileStatus(path).getLen.toInt)
    fs.open(path).readFully(byteArray)
    val json = (byteArray.map(_.toChar)).mkString
    startAnalysersFromString(json,path.toString)
  }

  def reportFromEventHistory(file: String): Unit = {
    val busKlass = Class.forName("org.apache.spark.scheduler.ReplayListenerBus")
    val bus = busKlass.newInstance()

    val addListenerMethod = busKlass.getMethod("addListener", classOf[java.lang.Object])

    val conf = new SparkConf()
      .set("spark.sparklens.reporting.disabled", "false")
      .set("spark.sparklens.save.data", "false")

    val listener = new QuboleJobListener(conf)

    addListenerMethod.invoke(bus, listener)


    val replayMethod = busKlass.getMethod("replay", classOf[InputStream], classOf[String],
      classOf[Boolean], classOf[(String) => Boolean])

    replayMethod.invoke(bus, getDecodedInputStream(file, conf), file, boolean2Boolean(false),
      getFilter _)
  }

  // Borrowed from CompressionCodecs in spark
  private def getDecodedInputStream(file: String, conf: SparkConf): InputStream = {

    val fs = FileSystem.get(new URI(file), new Configuration())
    val path = new Path(file)
    val bufStream = new BufferedInputStream(fs.open(path))

    val logName = path.getName.stripSuffix(".inprogress")
    val codecName: Option[String] = logName.split("\\.").tail.lastOption

    codecName.getOrElse("") match {
      case "lz4" => new LZ4BlockInputStream(bufStream)
      case "lzf" => new LZFInputStream(bufStream)
      case "snappy" => new SnappyInputStream(bufStream)
      case _ => bufStream
    }

  }

  private def getFilter(eventString: String): Boolean = {
    implicit val formats = DefaultFormats
    eventFilter.contains(parse(eventString).extract[Map[String, Any]].get("Event")
      .get.asInstanceOf[String])
  }

  private def eventFilter: Set[String] = {
    Set(
      "SparkListenerTaskEnd",
      "SparkListenerApplicationStart",
      "SparkListenerApplicationEnd",
      "SparkListenerExecutorAdded",
      "SparkListenerExecutorRemoved",
      "SparkListenerJobStart",
      "SparkListenerJobEnd",
      "SparkListenerStageSubmitted",
      "SparkListenerStageCompleted"
    )
  }

}

