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

package org.apache.spark.sql

import org.apache.spark.sql.execution.command.management.CarbonLoadDataCommand
import org.apache.spark.sql.types._
import org.apache.spark.sql.util.CarbonException

import org.apache.carbondata.common.logging.LogServiceFactory
import org.apache.carbondata.core.metadata.datatype.{DataTypes => CarbonType}
import org.apache.carbondata.spark.CarbonOption

class CarbonDataFrameWriter(sqlContext: SQLContext, val dataFrame: DataFrame) {

  private val LOGGER = LogServiceFactory.getLogService(this.getClass.getCanonicalName)

  def saveAsCarbonFile(parameters: Map[String, String] = Map()): Unit = {
    // create a new table using dataframe's schema and write its content into the table
    sqlContext.sparkSession.sql(
      makeCreateTableString(dataFrame.schema, new CarbonOption(parameters)))
    writeToCarbonFile(parameters)
  }

  def appendToCarbonFile(parameters: Map[String, String] = Map()): Unit = {
    writeToCarbonFile(parameters)
  }

  private def writeToCarbonFile(parameters: Map[String, String] = Map()): Unit = {
    val options = new CarbonOption(parameters)
    loadDataFrame(options)
  }
  /**
   * Loading DataFrame directly without saving DataFrame to CSV files.
   * @param options
   */
  private def loadDataFrame(options: CarbonOption): Unit = {
    val header = dataFrame.columns.mkString(",")
    CarbonLoadDataCommand(
      Some(CarbonEnv.getDatabaseName(options.dbName)(sqlContext.sparkSession)),
      options.tableName,
      null,
      Seq(),
      Map("fileheader" -> header) ++ options.toMap,
      isOverwriteTable = false,
      null,
      Some(dataFrame)).run(sqlContext.sparkSession)
  }

  private def convertToCarbonType(sparkType: DataType): String = {
    sparkType match {
      case StringType => CarbonType.STRING.getName
      case IntegerType => CarbonType.INT.getName
      case ShortType => CarbonType.SHORT.getName
      case LongType => CarbonType.LONG.getName
      case FloatType => CarbonType.DOUBLE.getName
      case DoubleType => CarbonType.DOUBLE.getName
      case TimestampType => CarbonType.TIMESTAMP.getName
      case DateType => CarbonType.DATE.getName
      case decimal: DecimalType => s"decimal(${decimal.precision}, ${decimal.scale})"
      case BooleanType => CarbonType.BOOLEAN.getName
      case other => CarbonException.analysisException(s"unsupported type: $other")
    }
  }

  private def makeCreateTableString(schema: StructType, options: CarbonOption): String = {
    val carbonSchema = schema.map { field =>
      s"${ field.name } ${ convertToCarbonType(field.dataType) }"
    }

    val property = Map(
      "SORT_COLUMNS" -> options.sortColumns,
      "DICTIONARY_INCLUDE" -> options.dictionaryInclude,
      "DICTIONARY_EXCLUDE" -> options.dictionaryExclude,
      "TABLE_BLOCKSIZE" -> options.tableBlockSize,
      "STREAMING" -> Option(options.isStreaming.toString)
    ).filter(_._2.isDefined)
      .map(property => s"'${property._1}' = '${property._2.get}'").mkString(",")

    val dbName = CarbonEnv.getDatabaseName(options.dbName)(sqlContext.sparkSession)

    s"""
       | CREATE TABLE IF NOT EXISTS $dbName.${options.tableName}
       | (${ carbonSchema.mkString(", ") })
       | STORED BY 'carbondata'
       | ${ if (options.tablePath.nonEmpty) s"LOCATION '${options.tablePath.get}'" else ""}
       |  ${ if (property.nonEmpty) "TBLPROPERTIES (" + property + ")" else "" }
       |
     """.stripMargin
  }

}
