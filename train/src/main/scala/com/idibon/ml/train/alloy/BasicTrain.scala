package com.idibon.ml.train.alloy

import scala.io.Source

import com.idibon.ml.alloy.Alloy
import com.idibon.ml.common.Engine
import com.idibon.ml.predict.{PredictResult, Classification}

import org.json4s._
import org.json4s.native.JsonMethods

/** Create static Java methods from the companion object */
class BasicTrain {}

/** Simple top-level interface for training alloys from a set of files */
object BasicTrain {

  /** Reads a JSON object from a text file; assumes the entire text file
    * (possibly multi-line) stores exactly 1 JSON object.
    *
    * @param filename filename to read
    */
  def readJsonFile(filename: String): JObject = {
    val file = Source.fromFile(filename)
    try {
      JsonMethods.parse(file.mkString).asInstanceOf[JObject]
    } finally {
      file.close()
    }
  }

  /** Returns a function that reads a newline-delimited JSON file into
    * a TraversableOnce[JObject].
    */
  def lazyFileReader(filename: String): () => TraversableOnce[JObject] = {
    implicit val formats = org.json4s.DefaultFormats
    () => {
      Source.fromFile(filename).getLines.map(line => {
        JsonMethods.parse(line).extract[JObject]
      })
    }
  }

  /** Trains an Alloy using configuration and data located in local files
    *
    * @param engine The engine context to use for training
    * @param alloyName A user-friendly name to identify the alloy
    * @param trainingDataFile A file containing JSON training data
    * @param taskConfigFile A JSON file containing label and rule configuration
    * @param alloyConfigFile A JSON file containing the trainer parameters
    * @param alloyOutputFile The path where the JAR alloy should be saved
    */
  def trainFromFiles(engine: Engine,
    alloyName: String,
    trainingDataFile: String,
    taskConfigFile: String,
    alloyConfigFile: String): Alloy[Classification] = {

    val alloyConfig = readJsonFile(alloyConfigFile)
    val taskConfig = readJsonFile(taskConfigFile)

    val trainer = AlloyFactory.getTrainer(engine,
      (alloyConfig \ "trainerConfig").asInstanceOf[JObject])

    trainer.trainAlloy(alloyName,
      lazyFileReader(trainingDataFile),
      taskConfig, Some(alloyConfig))
  }
}