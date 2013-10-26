/*
 * Copyright (C) 2012 The Regents of The University California.
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package shark.api

import scala.collection.mutable.ArrayBuffer

import shark.SharkEnv
import shark.memstore2.{TablePartitionStats, TablePartition, TablePartitionBuilder}
import shark.util.HiveUtils

import org.apache.spark.rdd.RDD


class RDDTableFunctions(self: RDD[Product], manifests: Seq[ClassManifest[_]]) {

  def saveAsTable(tableName: String, fields: Seq[String]): Boolean = {
    require(fields.size == this.manifests.size,
      "Number of column names != number of fields in the RDD.")

    // Get a local copy of the manifests so we don't need to serialize this object.
    val manifests = this.manifests

    val statsAcc = SharkEnv.sc.accumulableCollection(ArrayBuffer[(Int, TablePartitionStats)]())

    // Create the RDD object.
    val rdd = self.mapPartitionsWithIndex { case(partitionIndex, iter) =>
      val ois = manifests.map(HiveUtils.getJavaPrimitiveObjectInspector)
      val builder = new TablePartitionBuilder(ois, 1000000, shouldCompress = false)

      for (p <- iter) {
        builder.incrementRowCount()
        // TODO: this is not the most efficient code to do the insertion ...
        p.productIterator.zipWithIndex.foreach { case (v, i) =>
          builder.append(i, v.asInstanceOf[Object], ois(i))
        }
      }

      statsAcc += Tuple2(partitionIndex, builder.asInstanceOf[TablePartitionBuilder].stats)
      Iterator(builder.build())
    }.persist()

    var isSucessfulCreateTable = HiveUtils.createTableInHive(tableName, fields, manifests)

    // Put the table in the metastore. Only proceed if the DDL statement is executed successfully.
    if (isSucessfulCreateTable) {
      // Force evaluate to put the data in memory.
      SharkEnv.memoryMetadataManager.put(tableName, rdd)
      try {
        rdd.context.runJob(rdd, (iter: Iterator[TablePartition]) => iter.foreach(_ => Unit))
      } catch {
        case _ => {
          // Intercept the exception thrown by SparkContext#runJob() and handle it silently. The
          // exception message should already be printed to the console by DDLTask#execute().
          HiveUtils.dropTableInHive(tableName)
          // Drop the table entry from MemoryMetadataManager.
          SharkEnv.unpersist(tableName)
          isSucessfulCreateTable = false
        }
      }

      // Gather the partition statistics.
      SharkEnv.memoryMetadataManager.putStats(tableName, statsAcc.value.toMap)
    }
    return isSucessfulCreateTable
  }
}
