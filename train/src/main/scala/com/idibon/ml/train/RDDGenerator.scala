package com.idibon.ml.train

import com.idibon.ml.feature.FeaturePipeline
import com.idibon.ml.common.Engine

import java.io.File
import com.typesafe.scalalogging.StrictLogging
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.json4s._
import org.json4s.native.JsonMethods.parse
import scala.collection.mutable.{HashMap, ListBuffer}
import scala.io.Source


/** RDDGenerator
  *
  * Produces an RDD of LabeledPoints given a list of documents with annotations. This is intended for use with MLlib
  * for performing logistic regression during training.
  *
  */
object RDDGenerator extends StrictLogging {

  /** Produces an RDD of LabeledPoints for each distinct label name.
    *
    * Callers should provide a callback function which returns a traversable
    * list of documents; this function will be called multiple times, and
    * each invocation of the function must return an instance that will
    * traverse over the exact set of documents traversed by previous instances.
    *
    * Traversed documents should match the format generated by
    * idibin.git:/idibin/bin/open_source_integration/export_training_to_idiml.rb
    *
    *   { "content": "Who drives a chevy maliby Would you recommend it?
    *     "metadata": { "iso_639_1": "en" },
    *     "annotations: [{ "label": { "name": "Intent" }, "isPositive": true }]}
    *
    * @param engine: the current idiml engine context
    * @param pipeline: a FeaturePipeline to use for processing documents
    * @param docs: a callback function returning the training documents
    * @return a Map from label name to an RDD of LabeledPoints for that label
    */
  def getLabeledPointRDDs(engine: Engine, pipeline: FeaturePipeline,
      docs: () => TraversableOnce[JObject]): (Map[String, RDD[LabeledPoint]], FeaturePipeline) = {

    implicit val formats = org.json4s.DefaultFormats

    // Prime the index by reading each document from the input file, which assigns an index value to each token
    val fp = pipeline.prime(docs())

    // Iterate over the data one more time now that the index is complete. This ensures that every feature vector
    // will now be the same size
    val perLabelLPs = HashMap[String, ListBuffer[LabeledPoint]]()
    docs().foreach(document => {
      val annotations = (document \ "annotations").extract[JArray]
      // for each annotation, we assume it was provided so we can make a training point out of it.
      for (entry <- annotations.arr) {
        val JString(label) = entry \ "label" \ "name"
        val JBool(isPositive) = entry \ "isPositive"

        // If we haven't seen this label before, instantiate a list
        if (!perLabelLPs.contains(label)) {
          perLabelLPs(label) = new ListBuffer[LabeledPoint]()
        }

        // Assign a number that MLlib understands
        val labelNumeric = if (isPositive) 1.0 else 0.0

        // Run the pipeline to generate the feature vector
        val featureVector = fp(document)

        // Create labeled points
        perLabelLPs(label) += LabeledPoint(labelNumeric, featureVector)
      }
    })

    // Generate the RDDs, given the per-label list of LabeledPoints we just created
    val perLabelRDDs = HashMap[String, RDD[LabeledPoint]]()
    val logLine = perLabelLPs.map{
      case (label, lp) => {
        perLabelRDDs(label) = engine.sparkContext.parallelize(lp)
        val splits = lp.groupBy(x => x.label).map(x => s"Polarity: ${x._1}, Size: ${x._2.size}").toList
        // create some data for logging.
        (label, lp.size, splits)
      }
    }.foldRight(""){
      // create atomic log line.
      case ((label, size, splits), line) => {
        line + s"\nCreated $size data points for $label; with splits $splits"
      }
    }
    logger.info(logLine)

    (perLabelRDDs.toMap, fp)
  }
}