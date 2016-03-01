package com.idibon.ml.train.alloy

import com.idibon.ml.alloy.{HasTrainingSummary, BaseAlloy, Alloy}
import com.idibon.ml.common.Engine
import com.idibon.ml.feature.Buildable
import com.idibon.ml.predict.ml.{TrainingSummary}
import com.idibon.ml.predict.ml.metrics._
import com.idibon.ml.predict.{PredictOptions, Classification}
import com.typesafe.scalalogging.StrictLogging
import org.apache.spark.mllib.evaluation.MulticlassMetrics
import org.json4s.JsonAST.{JBool, JObject, JArray, JString}
import org.json4s._

import scala.collection.JavaConversions._
import scala.util.Random

/**
  * This creates learning curves, by creating the correct training data partitions and sending that
  * to the trainer that this wraps.
  *
  * @author "Stefan Krawczyk <stefan@idibon.com>" on 2/16/16.
  */
class LearningCurveTrainer(builder: LearningCurveTrainerBuilder)
  extends AlloyTrainer with KFoldDataSetCreator with MetricHelper with StrictLogging {
  val engine: Engine = builder.engine
  val trainer: AlloyTrainer = builder.trainerBuilder.build(builder.engine)
  val numFolds: Int = builder.numFolds
  val portions: Array[Double] = builder.portions
  val foldSeed: Long = builder.foldSeed
  val notMarker: String = "__!"


  /**
    * @param name          - a user-friendly name for the Alloy
    * @param docs          - a callback function returning a traversable sequence
    *                      of JSON training documents, such as those generated by export_training_to_idiml.rb
    * @param labelsAndRules a callback function returning a traversable sequence
    *                      of JSON Config. Should only be one line,   generated by export_training_to_idiml.rb.
    * @param config        training configuration parameters. Optional.
    * @return an Alloy with the trained model
    */
  override def trainAlloy(name: String,
                          docs: () => TraversableOnce[JObject],
                          labelsAndRules: JObject,
                          config: Option[JObject]): Alloy[Classification] = {
    implicit val formats = org.json4s.DefaultFormats
    val rules = (labelsAndRules \ "rules").extract[JArray]
    val uuidTolabel = (labelsAndRules \ "uuid_to_label").extract[JObject]
    val taskType = (labelsAndRules \ "task_type").extract[String]
    // Create uuidsByLabel
    val labels = uuidToLabelGenerator(uuidTolabel)
    //FIXME: handle small data sets, e.g. if not many positive/neg then possibility for single polarity in data set is possible.
    // create map of fold -> (holdout, [(portion, trainingSet)])
    val foldToDataset = createFoldDataSets(docs, numFolds, portions, foldSeed)
    // create alloys: fold -> Map(portion -> alloy)
    val foldAlloys = createFoldAlloys(labelsAndRules, config, foldToDataset)
    val defaultThreshold = taskType match {
      case "classification.single" => 1.0f/labels.size.toFloat // this will be multinomial or k-binary
      case "classification.multiple" => 0.5f // this will be k-binary always
    }
    // perform predictions
    val foldPredictions = getFoldPortionPredictions(foldAlloys, defaultThreshold)
    // Create nice list of ResultTuples to allow for simpler aggregation
    val tuples = createResultsForAggregation(foldPredictions)
    // compute metrics
    val perLabelPortionMetrics = createPerLabelLCMetrics(tuples)
    // create summaries
    val perLabelPortionSummaries = createPerLabelPortionSummaries(perLabelPortionMetrics)
    // info statement
    perLabelPortionSummaries.foreach(x => logger.info(x.toString()))
    // create alloy
    new BaseAlloy[Classification](name, labels, Map()) with HasTrainingSummary {
      override def getTrainingSummaries = Some(perLabelPortionSummaries.toSeq)
    }
  }

  /**
    * Creates per label per portion training summaries.
    *
    * @param perLabelPortionMetrics crazy data structure of (label, portion), [(label, portion, metrics)]
    * @return training summaries that correspond to a (label, portion).
    */
  def createPerLabelPortionSummaries(perLabelPortionMetrics: Map[String, Seq[Metric with Buildable[_, _]]]):
  Iterable[TrainingSummary] = {
    perLabelPortionMetrics
      .map({ case (label, metrics) =>
        new TrainingSummary(s"$label", metrics)
      })
  }

  /**
    * Takes flat list of ResulTuples representing all predictions and creates a map of
    * label -> Seq[Learning Curve Metrics]
    *
    * @param tuples
    * @return
    */
  def createPerLabelLCMetrics(tuples: Seq[ResultTuple]):
    Map[String, Seq[Metric with Buildable[_, _]]] = {
    // remove fold and flatten
    val perLabelPortionMetricValues: Seq[LabelPortionMetricTuple] = createLabelPortionMetricTuples(tuples)
    // reduce to singular portion metrics by grouping and then averaging them values.
    val perLabelPortionAvgMetrics = averageAcrossFolds(perLabelPortionMetricValues)
    // create label -> [learning curve metrics]
    createLearningCurveMetrics(perLabelPortionAvgMetrics)
  }

  /**
    * Averages across folds -- i.e. grouped by lable, portion and metric, averages the values.
    *
    * @param perLabelPortionMetricValues
    * @return
    */
  def averageAcrossFolds(perLabelPortionMetricValues: Seq[LabelPortionMetricTuple]):
  Iterable[LabelPortionMetricTuple] = {
    perLabelPortionMetricValues
      // group common label, portion and metrics together
      .groupBy(tup => (tup.label, tup.portion, tup.metric))
      // now average and create single value
      .map({ case ((label, portion, mType), grouped) =>
        // we want to average the values here.
        val floats = grouped.map(tup => tup.value)
        val size = floats.size
        new LabelPortionMetricTuple(label, portion, mType, floats.sum / size.toFloat)
      })
  }

  /**
    * Creates the learning curve metrics from an iterable list of LabelPortionMetricTuples.
    *
    * i.e. it groups together the portions for under a (label, metric type) pair and uses them
    * to produce a learning curve metric so that points can be plotted.
    *
    * @param perLabelFlatMetrics
    * @return
    */
  def createLearningCurveMetrics(perLabelFlatMetrics: Iterable[LabelPortionMetricTuple]):
  Map[String, Seq[Metric with Buildable[_, _]]] = {
    // group by label & metric -- so all portions are grouped together
    val perLabelMetric = perLabelFlatMetrics.groupBy(tup => (tup.label, tup.metric))
    perLabelMetric.map({ case ((label, metric), grouped) =>
      // from all portions create learning curve metric
      val newMetric: Metric with Buildable[_, _] = metric match {
        case MetricTypes.LabelF1 => {
          extractLabeledPointsMetric(MetricTypes.LearningCurveLabelF1, label, grouped.toSeq)
        }
        case MetricTypes.LabelPrecision => {
          extractLabeledPointsMetric(MetricTypes.LearningCurveLabelPrecision, label, grouped.toSeq)
        }
        case MetricTypes.LabelRecall => {
          extractLabeledPointsMetric(MetricTypes.LearningCurveLabelRecall, label, grouped.toSeq)
        }
        case MetricTypes.F1 => {
          val points = grouped.map(tup => (tup.portion.toFloat, tup.value)).toSeq
          new PointsMetric(MetricTypes.LearningCurveF1, MetricClass.Binary, points)
        }
        case other => throw new IllegalStateException(s"Error; should not have encountered ${other}.")
      }
      (label, metric, newMetric)
      // now group by label
    }).toSeq.groupBy({ case (label, metric, newMetric) => label })
      // and simplify the grouped to just be a sequence of metrics for that label
      .map({ case (label, grouped) => (label, grouped.map(x => x._3)) })
  }

  /**
    * Removes fold and flattens to a sequence of LabelPortionMetricTuple.
    *
    * Each LabelPortionMetricTuple object is comprised of label, portion, metricType, and value.
    *
    * @param tuples
    * @return
    */
  def createLabelPortionMetricTuples(tuples: Seq[ResultTuple]): Seq[LabelPortionMetricTuple] = {
    val perLabelPortionMetricValues = tuples.groupBy(tup => (tup.fold, tup.label, tup.portion))
      .flatMap({ case ((fold, label, portion), grouped) =>
        val portionMetrics: MulticlassMetrics = getPortionMetrics(grouped)
        // create binary metrics, since that's what they are...
        val idibonMetrics = createMultiClassMetrics(portionMetrics,
          Map(1.0 -> label, 0.0 -> s"$notMarker${label}"), MetricClass.Binary)
        val filteredMetrics: Seq[Metric with Buildable[_, _]] = filterMetrics(label, idibonMetrics)
        //flatten the metrics into tuples -- extract values to make it easier to transform
        filteredMetrics.map(m => {
          val floatValue = m match {
            case lf: LabelFloatMetric => lf.float
            case fm: FloatMetric => fm.float
          }
          new LabelPortionMetricTuple(label, portion, m.metricType, floatValue)
        })
      })
    perLabelPortionMetricValues.toSeq
  }

  /**
    * From the created metrics, filter to the ones we're interested in for learning curves.
    *
    * @param label
    * @param idibonMetrics
    * @return
    */
  def filterMetrics(label: String, idibonMetrics: Seq[Metric with Buildable[_, _]]):
  Seq[Metric with Buildable[_, _]] = {
    val filteredMetrics = idibonMetrics
      // only get metrics we want
      .filter(m => m.metricType == MetricTypes.LabelF1 ||
      m.metricType == MetricTypes.LabelPrecision ||
      m.metricType == MetricTypes.LabelRecall ||
      m.metricType == MetricTypes.F1)
      // remove "not" labels
      .filter(m => m match {
      case lf: LabelFloatMetric => lf.label.equals(label)
      case other => true
    })
    filteredMetrics
  }

  /**
    * Creates RDD for ingestion into the spark multi-class metrics class, which we use to get the
    * relevant metrics from.
    *
    * @param grouped
    * @return
    */
  def getPortionMetrics(grouped: Seq[ResultTuple]): MulticlassMetrics = {
    val doubles: Seq[(Double, Double)] = createDataForRDD(grouped)
    val portionMetrics = new MulticlassMetrics(engine.sparkContext.parallelize(doubles.toSeq))
    portionMetrics
  }

  /**
    * Helper method to create (prediction, gold) data that will become an RDD for
    * input into spark metrics creation.
    *
    * @param grouped
    * @return
    */
  def createDataForRDD(grouped: Seq[ResultTuple]): Seq[(Double, Double)] = {
    val doubles = grouped.map(tup => {
      val predictedLabel = if (tup.predicted) 1.0 else 0.0
      val goldLabel = if (tup.gold) 1.0 else 0.0
      (predictedLabel, goldLabel)
    })
    doubles
  }

  /**
    * Creates labeled point metrics.
    *
    * This assumes the input is wanting to map portions to a float value. So
    * we have a sequence of tuples that has these pairs and all we need to do
    * is pull them out.
    *
    * @param newMetricType
    * @param label
    * @param grouped
    * @return
    */
  def extractLabeledPointsMetric(newMetricType: MetricTypes,
                                 label: String,
                                 grouped: Seq[LabelPortionMetricTuple]): LabelPointsMetric = {
    val points = grouped.map(tup => (tup.portion.toFloat, tup.value))
    new LabelPointsMetric(newMetricType, MetricClass.Binary, label, points)
  }

  /**
    * Creates a flat list of tuples from predictions, i.e. a denormalized data set.
    *
    * Rather than having a nested dataset by fold, label, partion, etc, have a flat sequence of
    * results with all dimensions. This helps in later grouping the output and producing
    * the right results.
    *
    * @param foldPredictions
    * @return
    */
  def createResultsForAggregation(foldPredictions: Seq[(Fold, Stream[Seq[PortionPredictions]])]):
  Seq[ResultTuple] = {
    foldPredictions.par.flatMap({ case (fold, predictions) =>
      predictions.par.flatMap({ case (portionPredictions) =>
        portionPredictions.flatMap(portionPrediction => {
          portionPrediction.predictions.map(prediction => {
            new ResultTuple(prediction.label, fold.fold, portionPrediction.portion, prediction.predicted, prediction.gold)
          })
        })
      })
    }).toList.toSeq
  }

  /**
    * For each fold holdout set, and for each portion, get predictions for the alloys corresponding
    * to each portion.
    *
    * Converts the predictions to more manageable data type of (label, "do we predict this label?",
    * "what does gold say?").
    *
    * @param foldAlloys
    * @return
    */
  def getFoldPortionPredictions(foldAlloys: Seq[(Fold, Seq[(Double, Alloy[Classification])])],
                                defaultThreshold: Float): Seq[(Fold, Stream[Seq[PortionPredictions]])] = {
    foldAlloys.par.map({case (fold, portionAlloys) =>
      val foldPrediction = fold.holdout.map(jsValue => {
        val goldMapping: Map[String, Boolean] = createGoldLabels(jsValue)
        // for each "portion" - grab model to predict on
        val portionPredictions = portionAlloys.map({case (portion, alloy) =>
          (portion, alloy.predict(jsValue, PredictOptions.DEFAULT), alloy.getSuggestedThresholds())
        })
        // Array[(Double, List[Classification])]
        val portionLabelPredictions = portionPredictions.map({ case (portion, alloyPredictions, labelThresholds) =>
          val labelPredictions = alloyPredictions.map(c => {
            val threshold = labelThresholds.getOrDefault(c.label, defaultThreshold)
            // create nice tuple of (label, whether we predicted that label, and what gold says)
            val isOverThreshold = c.probability >= threshold
            new Prediction(c.label, isOverThreshold, goldMapping.getOrElse(c.label, false))
          })
          new PortionPredictions(portion, labelPredictions.toSeq)
        })
        portionLabelPredictions
      })
      (fold, foldPrediction)
    }).toList.toSeq
  }

  /**
    * Takes a data example in and produces a map of label -> boolean.
    *
    * This indicates whether the label is considered to have positive polarity
    * or not.
    *
    * @param jsValue
    * @return
    */
  def createGoldLabels(jsValue: JObject): Map[String, Boolean] = {
    implicit val formats = org.json4s.DefaultFormats
    val annotations = (jsValue \ "annotations").extract[JArray]
    val goldMapping = annotations.arr.map { value =>
      val JString(label) = value \ "label" \ "name"
      val JBool(isPositive) = value \ "isPositive"
      (label, isPositive)
    }.toMap
    goldMapping
  }

  /**
    * For each fold, for each portion, trains an Alloy.
    *
    * @param labelsAndRules
    * @param config
    * @param foldToDataset
    * @return
    */
  def createFoldAlloys(labelsAndRules: JObject,
                       config: Option[JObject],
                       foldToDataset: Seq[Fold]): Seq[(Fold, Seq[(Double, Alloy[Classification])])] = {
    foldToDataset.par.map { fold => {
      val portionAlloys = fold.trainingStreams.map(tStream=> {
        val portionName: String = s"P-${tStream.portion}"
        (tStream.portion, trainer.trainAlloy(portionName, () => tStream.stream, labelsAndRules, config))
      }).toSeq
      (fold, portionAlloys)
    }}.toList.toSeq
  }
}

/**
  * Object to hold a set of raw results for a label, fold, portion.
  *
  * @param label
  * @param fold
  * @param portion
  * @param predicted
  * @param gold
  */
private[alloy] case class ResultTuple(label: String,
                                      fold: Int,
                                      portion: Double,
                                      predicted: Boolean,
                                      gold: Boolean)

/**
  * Object to hold a metric value for a label, portion and metric type.
  *
  * @param label
  * @param portion
  * @param metric
  * @param value
  */
private[alloy] case class LabelPortionMetricTuple(label: String,
                                                  portion: Double,
                                                  metric: MetricTypes,
                                                  value: Float)

/**
  * Holds a prediction for a label.
  *
  * @param label
  * @param predicted
  * @param gold
  */
private[alloy] case class Prediction(label: String, predicted: Boolean, gold: Boolean)

/**
  * Holds a sequence of predictions for a portion.
  *
  * @param portion
  * @param predictions
  */
private[alloy] case class PortionPredictions(portion: Double, predictions: Seq[Prediction])
