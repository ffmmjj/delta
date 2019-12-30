/*
 * Copyright 2019 Databricks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta

import java.nio.file.{Files, Paths}
import java.nio.file.attribute.FileTime
import java.time.{Duration, Instant}

import org.apache.spark.sql.delta.actions.{CommitInfo, Metadata}
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.delta.test.DeltaSQLCommandTest
import org.apache.spark.sql.execution.streaming.MemoryStream
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql._
import org.apache.spark.unsafe.types.CalendarInterval
import org.apache.spark.util.Utils
import org.scalatest.Tag

trait DescribeDeltaHistorySuiteBase
  extends QueryTest
  with SharedSparkSession {

  import testImplicits._

  protected val evolvabilityResource = "src/test/resources/delta/history/delta-0.2.0"

  protected val evolvabilityLastOp = Seq("STREAMING UPDATE", null, null)

  protected def testWithFlag(name: String, tags: Tag*)(f: => Unit): Unit = {
    test(name, tags: _*) {
      withSQLConf(DeltaSQLConf.DELTA_COMMIT_INFO_ENABLED.key -> "true") {
        f
      }
    }
  }

  protected def checkLastOperation(
      basePath: String,
      expected: Seq[String],
      columns: Seq[Column] = Seq($"operation", $"operationParameters.mode")): Unit = {
    val df = getHistory(basePath, Some(1))
    checkAnswer(
      df.select(columns: _*),
      Seq(Row(expected: _*))
    )
  }

  def getHistory(path: String, limit: Option[Int] = None): DataFrame = {
    val deltaTable = io.delta.tables.DeltaTable.forPath(spark, path)
    if (limit.isDefined) {
      deltaTable.history(limit.get)
    } else {
      deltaTable.history()
    }
  }

  testWithFlag("logging and limit") {
    val tempDir = Utils.createTempDir().toString
    Seq(1, 2, 3).toDF().write.format("delta").save(tempDir)
    Seq(4, 5, 6).toDF().write.format("delta").mode("overwrite").save(tempDir)
    assert(getHistory(tempDir).count === 2)
    checkLastOperation(tempDir, Seq("WRITE", "Overwrite"))
  }

  testWithFlag("operations - insert append with partition columns") {
    val tempDir = Utils.createTempDir().toString
    Seq((1, "a"), (2, "3")).toDF("id", "data")
      .write
      .format("delta")
      .mode("append")
      .partitionBy("id")
      .save(tempDir)

    checkLastOperation(
      tempDir,
      Seq("WRITE", "Append", """["id"]"""),
      Seq($"operation", $"operationParameters.mode", $"operationParameters.partitionBy"))
  }

  testWithFlag("operations - insert append without partition columns") {
    val tempDir = Utils.createTempDir().toString
    Seq((1, "a"), (2, "3")).toDF("id", "data").write.format("delta").save(tempDir)
    checkLastOperation(
      tempDir,
      Seq("WRITE", "ErrorIfExists", """[]"""),
      Seq($"operation", $"operationParameters.mode", $"operationParameters.partitionBy"))
  }

  testWithFlag("operations - insert error if exists with partitions") {
    val tempDir = Utils.createTempDir().toString
    Seq((1, "a"), (2, "3")).toDF("id", "data")
        .write
        .format("delta")
        .partitionBy("id")
        .mode("errorIfExists")
        .save(tempDir)
    checkLastOperation(
      tempDir,
      Seq("WRITE", "ErrorIfExists", """["id"]"""),
      Seq($"operation", $"operationParameters.mode", $"operationParameters.partitionBy"))
  }

  testWithFlag("operations - insert error if exists without partitions") {
    val tempDir = Utils.createTempDir().toString
    Seq((1, "a"), (2, "3")).toDF("id", "data")
        .write
        .format("delta")
        .mode("errorIfExists")
        .save(tempDir)
    checkLastOperation(
      tempDir,
      Seq("WRITE", "ErrorIfExists", """[]"""),
      Seq($"operation", $"operationParameters.mode", $"operationParameters.partitionBy"))
  }

  test("operations - streaming append with transaction ids") {
    val tempDir = Utils.createTempDir().toString
    val checkpoint = Utils.createTempDir().toString

    val data = MemoryStream[Int]
    data.addData(1, 2, 3)
    val stream = data.toDF()
      .writeStream
      .format("delta")
      .option("checkpointLocation", checkpoint)
      .start(tempDir)
    stream.processAllAvailable()
    stream.stop()

    checkLastOperation(
      tempDir,
      Seq("STREAMING UPDATE", "Append", "0"),
      Seq($"operation", $"operationParameters.outputMode", $"operationParameters.epochId"))
  }

  testWithFlag("operations - insert overwrite with predicate") {
    val tempDir = Utils.createTempDir().toString
    Seq((1, "a"), (2, "3")).toDF("id", "data").write.format("delta").partitionBy("id").save(tempDir)

    Seq((1, "b")).toDF("id", "data").write
      .format("delta")
      .mode("overwrite")
      .option(DeltaOptions.REPLACE_WHERE_OPTION, "id = 1")
      .save(tempDir)

    checkLastOperation(
      tempDir,
      Seq("WRITE", "Overwrite", """id = 1"""),
      Seq($"operation", $"operationParameters.mode", $"operationParameters.predicate"))
  }

  testWithFlag("operations - delete with predicate") {
    val tempDir = Utils.createTempDir().toString
    Seq((1, "a"), (2, "3")).toDF("id", "data").write.format("delta").partitionBy("id").save(tempDir)
    val deltaLog = DeltaLog.forTable(spark, tempDir)
    val deltaTable = io.delta.tables.DeltaTable.forPath(spark, deltaLog.dataPath.toString)
    deltaTable.delete("id = 1")

    checkLastOperation(
      tempDir,
      Seq("DELETE", """["(`id` = 1)"]"""),
      Seq($"operation", $"operationParameters.predicate"))
  }

  testWithFlag("old and new writers") {
    val tempDir = Utils.createTempDir().toString
    withSQLConf(DeltaSQLConf.DELTA_COMMIT_INFO_ENABLED.key -> "false") {
      Seq(1, 2, 3).toDF().write.format("delta").save(tempDir.toString)
    }

    checkLastOperation(tempDir, Seq(null, null))
    withSQLConf(DeltaSQLConf.DELTA_COMMIT_INFO_ENABLED.key -> "true") {
      Seq(1, 2, 3).toDF().write.format("delta").mode("append").save(tempDir.toString)
    }

    assert(getHistory(tempDir).count() === 2)
    checkLastOperation(tempDir, Seq("WRITE", "Append"))
  }

  testWithFlag("order history by version") {
    val tempDir = Utils.createTempDir().toString

    withSQLConf(DeltaSQLConf.DELTA_COMMIT_INFO_ENABLED.key -> "false") {
      Seq(0).toDF().write.format("delta").save(tempDir)
      Seq(1).toDF().write.format("delta").mode("overwrite").save(tempDir)
    }
    withSQLConf(DeltaSQLConf.DELTA_COMMIT_INFO_ENABLED.key -> "true") {
      Seq(2).toDF().write.format("delta").mode("append").save(tempDir)
      Seq(3).toDF().write.format("delta").mode("overwrite").save(tempDir)
    }
    withSQLConf(DeltaSQLConf.DELTA_COMMIT_INFO_ENABLED.key -> "false") {
      Seq(4).toDF().write.format("delta").mode("overwrite").save(tempDir)
    }

    val ans = getHistory(tempDir).as[CommitInfo].collect()
    assert(ans.map(_.version) === Seq(Some(4), Some(3), Some(2), Some(1), Some(0)))
  }

  test("read version") {
    val tempDir = Utils.createTempDir().toString

    withSQLConf(DeltaSQLConf.DELTA_COMMIT_INFO_ENABLED.key -> "true") {
      Seq(0).toDF().write.format("delta").save(tempDir) // readVersion = None as first commit
      Seq(1).toDF().write.format("delta").mode("overwrite").save(tempDir) // readVersion = Some(0)
    }

    val log = DeltaLog.forTable(spark, tempDir)
    val txn = log.startTransaction()   // should read snapshot version 1

    withSQLConf(DeltaSQLConf.DELTA_COMMIT_INFO_ENABLED.key -> "true") {
      Seq(2).toDF().write.format("delta").mode("append").save(tempDir)  // readVersion = Some(0)
      Seq(3).toDF().write.format("delta").mode("append").save(tempDir)  // readVersion = Some(2)
    }

    txn.commit(Seq.empty, DeltaOperations.Truncate())  // readVersion = Some(1)

    withSQLConf(DeltaSQLConf.DELTA_COMMIT_INFO_ENABLED.key -> "false") {
      Seq(5).toDF().write.format("delta").mode("append").save(tempDir)   // readVersion = None
    }
    val ans = getHistory(tempDir).as[CommitInfo].collect()
    assert(ans.map(x => x.version.get -> x.readVersion) ===
      Seq(5 -> None, 4 -> Some(1), 3 -> Some(2), 2 -> Some(1), 1 -> Some(0), 0 -> None))
  }

  testWithFlag("evolvability test") {
    checkLastOperation(
      evolvabilityResource,
      evolvabilityLastOp,
      Seq($"operation", $"operationParameters.mode", $"operationParameters.partitionBy"))
  }

  testWithFlag("describe history with delta table identifier") {
    val tempDir = Utils.createTempDir().toString
    Seq(1, 2, 3).toDF().write.format("delta").save(tempDir)
    Seq(4, 5, 6).toDF().write.format("delta").mode("overwrite").save(tempDir)
    val df = sql(s"DESCRIBE HISTORY delta.`$tempDir` LIMIT 1")
    checkAnswer(df.select("operation", "operationParameters.mode"),
      Seq(Row("WRITE", "Overwrite")))
  }

  test("using on non delta") {
    withTempDir { basePath =>
      val e = intercept[AnalysisException] {
        sql(s"describe history '$basePath'").collect()
      }
      assert(Seq("supported", "Delta").forall(e.getMessage.contains))
    }
  }

  test("describe history a non-existent path and a non Delta table") {
    def assertNotADeltaTableException(path: String): Unit = {
      for (table <- Seq(s"'$path'", s"delta.`$path`")) {
        val e = intercept[AnalysisException] {
          sql(s"describe history $table").show()
        }
        Seq("DESCRIBE HISTORY", "only supported for Delta tables").foreach { msg =>
          assert(e.getMessage.contains(msg))
        }
      }
    }
    withTempPath { tempDir =>
      assert(!tempDir.exists())
      assertNotADeltaTableException(tempDir.getCanonicalPath)
    }
    withTempPath { tempDir =>
      spark.range(1, 10).write.parquet(tempDir.getCanonicalPath)
      assertNotADeltaTableException(tempDir.getCanonicalPath)
    }
  }

  test("operation metrics - write metrics") {
    withSQLConf(DeltaSQLConf.DELTA_HISTORY_METRICS_ENABLED.key -> "true") {
      withTempDir { tempDir =>
        // create table
        spark.range(100).repartition(5).write.format("delta").save(tempDir.getAbsolutePath)
        val deltaTable = io.delta.tables.DeltaTable.forPath(tempDir.getAbsolutePath)

        // get last command history
        val lastCmd = deltaTable.history(1)

        // Check if operation metrics from history are accurate
        assert(lastCmd.select("operationMetrics.numFiles").take(1).head.getString(0).toLong
          == 5)

        assert(lastCmd.select("operationMetrics.numOutputBytes").take(1).head.getString(0).toLong
          > 0)

        assert(lastCmd.select("operationMetrics.numOutputRows").take(1).head.getString(0).toLong
          == 100)
      }
    }
  }

  test("operation metrics - merge") {
    withSQLConf(DeltaSQLConf.DELTA_HISTORY_METRICS_ENABLED.key -> "true") {
      withTempDir { tempDir =>
        // create target
        spark.range(100).write.format("delta").save(tempDir.getAbsolutePath)
        val deltaTable = io.delta.tables.DeltaTable.forPath(tempDir.getAbsolutePath)

        // run merge
        deltaTable.as("t")
          .merge(spark.range(50, 150).toDF().as("s"), "s.id = t.id")
          .whenMatched()
          .updateAll()
          .whenNotMatched()
          .insertAll()
          .execute()

        // Get operation metrics
        val lastCommand = deltaTable.history(1)
        val operationMetrics: Map[String, String] = lastCommand.select("operationMetrics")
          .take(1)
          .head
          .getMap(0)
          .asInstanceOf[Map[String, String]]

        assert(operationMetrics("numTargetRowsInserted") == "50")
        assert(operationMetrics("numTargetRowsUpdated") == "50")
        assert(operationMetrics("numOutputRows") == "100")
        assert(operationMetrics("numSourceRows") == "100")
      }
    }
  }

  testWithFlag("does not return non-reproducible versions") {
    withSQLConf("spark.databricks.delta.properties.defaults.checkpointInterval" -> "2") {
      val tempDir = Utils.createTempDir().toString
      // Create versions 0 and 1
      Seq(1, 2, 3).toDF().write.format("delta").mode("overwrite").save(tempDir)
      Seq(1, 2, 3).toDF().write.format("delta").mode("overwrite").save(tempDir)

      // Set v0 last modified date to more than DeltaConfigs.LOG_RETENTION days ago so that
      // it gets deleted when the checkpoint is created
      val zerothDeltaFilePath = Paths.get(tempDir, "_delta_log").toFile
        .listFiles()
        .filter(_.getName.matches("0+\\.json"))
        .head
        .toPath
      val currentLogRetentionPeriodInDays =
        calendarIntervalToDays(DeltaConfigs.LOG_RETENTION.fromMetaData(Metadata()))
      val enoughDaysToExpireLogs = currentLogRetentionPeriodInDays + 1
      val expiredDate: Instant = Files.getLastModifiedTime(zerothDeltaFilePath).toInstant
        .minus(Duration.ofDays(enoughDaysToExpireLogs))
      Files.setLastModifiedTime(zerothDeltaFilePath, FileTime.from(expiredDate))

      // Force creation of checkpoint by creating versions 2 and 3
      Seq(1, 2, 3).toDF().write.format("delta").mode("overwrite").save(tempDir)
      Seq(1, 2, 3).toDF().write.format("delta").mode("overwrite").save(tempDir)

      val historyVersions = getHistory(tempDir).select("version").collect().map(_.getLong(0)).toSet
      assert(historyVersions === Set(2, 3))
    }
  }

  private def calendarIntervalToDays(currentLogRetentionPeriod: CalendarInterval) = {
    currentLogRetentionPeriod.microseconds / CalendarInterval.MICROS_PER_DAY
  }
}

class DescribeDeltaHistorySuite
  extends DescribeDeltaHistorySuiteBase with DeltaSQLCommandTest

