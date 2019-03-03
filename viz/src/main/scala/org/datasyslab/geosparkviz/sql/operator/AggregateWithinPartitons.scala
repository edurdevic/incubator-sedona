/**
  * FILE: AggregateWithinPartitons
  * Copyright (c) 2015 - 2019 GeoSpark Development Team
  *
  * MIT License
  *
  * Permission is hereby granted, free of charge, to any person obtaining a copy
  * of this software and associated documentation files (the "Software"), to deal
  * in the Software without restriction, including without limitation the rights
  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  * copies of the Software, and to permit persons to whom the Software is
  * furnished to do so, subject to the following conditions:
  *
  * The above copyright notice and this permission notice shall be included in all
  * copies or substantial portions of the Software.
  *
  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  * SOFTWARE.
  */
package org.datasyslab.geosparkviz.sql.operator

import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, Row}
import org.datasyslab.geosparkviz.sql.utils.Conf
import org.datasyslab.geosparkviz.utils.Pixel

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer


object AggregateWithinPartitons {
  /**
    * Run aggregation within each partition without incurring a data shuffle. Currently support three aggregates, sum, count, avg.
    * If the aggregate func is count, this function doesn't require a value column. If the aggregate func is sum and avg, it will require
    * a value column. They are same as the regular aggregation SQL
    *
    * SELECT pixel, COUNT(*)
    * FROM t GROUP BY pixel
    *
    * SELECT pixel, AVG(weight)
    * FROM t GROUP BY pixel
    *
    * SELECT pixel, AVG(weight)
    * FROM t GROUP BY pixel
    *
    * @param dataFrame
    * @param keyCol GroupBy key
    * @param valueCol Aggregate value
    * @param aggFunc Aggregate function
    * @return
    */
  def apply(dataFrame: DataFrame, keyCol:String, valueCol:String, aggFunc:String): DataFrame = {
    // Keep keycol, valuecol, primary partition id, secondary partition id
    var formattedDf:DataFrame = null
    // If the aggFunc is count, we don't need to make sure the valueCol exists because we just simply count the number of rows
    if (aggFunc.equalsIgnoreCase("count")) formattedDf = dataFrame.select(keyCol, Conf.PrimaryPID, Conf.SecondaryPID).withColumn(valueCol, lit(0.0))
    else formattedDf = dataFrame.select(keyCol, Conf.PrimaryPID, Conf.SecondaryPID, valueCol)

    //formattedDf.show()

    val aggRdd = formattedDf.rdd.mapPartitions(iterator=>{
      var aggregator = new mutable.HashMap[Pixel,Tuple4[Double, Double, String, String]]()
      while (iterator.hasNext) {
        val cursorRow = iterator.next()
        val cursorKey = cursorRow.getAs[Pixel](keyCol)
        val cursorValue = cursorRow.getAs[Double](valueCol)
        val currentAggregate = aggregator.getOrElse(cursorKey,Tuple4(0.0, 0.0, "", ""))
        // Update the aggregator values, partition ids are appended directly
        aggregator.update(cursorKey, Tuple4(currentAggregate._1+cursorValue, currentAggregate._2+1, cursorRow.getAs[String](Conf.PrimaryPID), cursorRow.getAs[String](Conf.SecondaryPID)))
      }
      var result = new ArrayBuffer[Row]()
      aggFunc match {
        case "sum" => aggregator.foreach(f=>{
          result+=Row(f._1, f._2._3, f._2._4, f._2._1)
        })
        case "count" => aggregator.foreach(f=>{
          result+=Row(f._1, f._2._3, f._2._4, f._2._2)
        })
        case "avg" => aggregator.foreach(f=>{
          result+=Row(f._1, f._2._3, f._2._4, f._2._1/f._2._2)
        })
        case _ => throw new IllegalArgumentException("[GeoSparkViz][SQL] Unsupported aggregate func type. Only sum, count, avg are supported.")
      }
      result.iterator
    })
    formattedDf.sparkSession.createDataFrame(aggRdd, formattedDf.schema)
  }
}