package timeusage

import java.nio.file.Paths

import org.apache.spark.sql._
import org.apache.spark.sql.types._

/** Main class */
object TimeUsage {

  import org.apache.spark.sql.SparkSession
  import org.apache.spark.sql.functions._

  val spark: SparkSession =
    SparkSession
      .builder()
      .appName("Time Usage")
      .config("spark.master", "local")
      .getOrCreate()

  // For implicit conversions like converting RDDs to DataFrames
  import spark.implicits._

  /** Main function */
  def main(args: Array[String]): Unit = {
    timeUsageByLifePeriod()
  }

  def timeUsageByLifePeriod(): Unit = {
    val (columns, initDf) = read("/timeusage/atussum.csv")
    val (primaryNeedsColumns, workColumns, otherColumns) = classifiedColumns(columns)
    val summaryDf = timeUsageSummary(primaryNeedsColumns, workColumns, otherColumns, initDf)
    //val summaryDfTyped = timeUsageSummaryTyped(summaryDf)
    val finalDf = timeUsageGroupedSql(summaryDf)
    finalDf.show()
  }

  /** @return The read DataFrame along with its column names. */
  def read(resource: String): (List[String], DataFrame) = {
    val rdd = spark.sparkContext.textFile(fsPath(resource))

    val headerColumns = rdd.first().split(",").to[List]
    // Compute the schema based on the first line of the CSV file
    val schema = dfSchema(headerColumns)

    val data =
      rdd
        .mapPartitionsWithIndex((i, it) => if (i == 0) it.drop(1) else it) // skip the header line
        .map(_.split(",").to[List])
        .map(row)

    val dataFrame =
      spark.createDataFrame(data, schema)

    (headerColumns, dataFrame)
  }

  /** @return The filesystem path of the given resource */
  def fsPath(resource: String): String =
    Paths.get(getClass.getResource(resource).toURI).toString

  /** @return The schema of the DataFrame, assuming that the first given column has type String and all the others
    *         have type Double. None of the fields are nullable.
    * @param columnNames Column names of the DataFrame
    */
  def dfSchema(columnNames: List[String]): StructType = {
    /** The constructing process of StructType is in the lecture slides. */
    val sfList: List[StructField] = for {
      i <- (0 until columnNames.size).toList
      sf = if (i == 0) StructField(columnNames(i), StringType, false) else
        StructField(columnNames(i), DoubleType, false)
    } yield sf
    StructType(sfList)
  }


  /** @return An RDD Row compatible with the schema produced by `dfSchema`
    * @param line Raw fields
    */
  def row(line: List[String]): Row = {
    /** In the schema, the first column is String type,
      * the following columns are double type. So use type case.
      */
    val lineCasted: Seq[Any] = for {
      i <- (0 until line.size).toList
      field = if (i == 0) line(i) else line(i).toDouble
    } yield field
    Row.fromSeq(lineCasted)
  }

  /** @return The initial data frame columns partitioned in three groups: primary needs (sleeping, eating, etc.),
    *         work and other (leisure activities)
    *
    * @see https://www.kaggle.com/bls/american-time-use-survey
    *
    * The dataset contains the daily time (in minutes) people spent in various activities. For instance, the column
    * “t010101” contains the time spent sleeping, the column “t110101” contains the time spent eating and drinking, etc.
    *
    * This method groups related columns together:
    * 1. “primary needs” activities (sleeping, eating, etc.). These are the columns starting with “t01”, “t03”, “t11”,
    *    “t1801” and “t1803”.
    * 2. working activities. These are the columns starting with “t05” and “t1805”.
    * 3. other activities (leisure). These are the columns starting with “t02”, “t04”, “t06”, “t07”, “t08”, “t09”,
    *    “t10”, “t12”, “t13”, “t14”, “t15”, “t16” and “t18” (those which are not part of the previous groups only).
    */
  def classifiedColumns(columnNames: List[String]): (List[Column], List[Column], List[Column]) = {
    /** Use for comprehension and filter to generate the column */
    val primNeedsCols = for {
      cn <- columnNames
      if cn.startsWith("t01") || cn.startsWith("t03") || cn.startsWith("t11") ||
         cn.startsWith("t1801") || cn.startsWith("t1803")
    } yield col(cn)

    val workActiCols = for {
      cn <- columnNames
      if cn.startsWith("t05") || cn.startsWith("t1805")
    } yield col(cn)

    val othActiCols = for {
      cn <- columnNames
      if cn.startsWith("t02") || cn.startsWith("t04") || cn.startsWith("t06") ||
         cn.startsWith("t07") || cn.startsWith("t08") || cn.startsWith("t09") ||
         cn.startsWith("t10") || cn.startsWith("t12") || cn.startsWith("t13") ||
         cn.startsWith("t14") || cn.startsWith("t15") || cn.startsWith("t16") ||
         cn.startsWith("t18") && !cn.startsWith("t1801") && !cn.startsWith("t1803") &&
         !cn.startsWith("t1805")
    } yield col(cn)

    (primNeedsCols, workActiCols, othActiCols)
  }

  /** @return a projection of the initial DataFrame such that all columns containing hours spent on primary needs
    *         are summed together in a single column (and same for work and leisure). The “teage” column is also
    *         projected to three values: "young", "active", "elder".
    *
    * @param primaryNeedsColumns List of columns containing time spent on “primary needs”
    * @param workColumns List of columns containing time spent working
    * @param otherColumns List of columns containing time spent doing other activities
    * @param df DataFrame whose schema matches the given column lists
    *
    * This methods builds an intermediate DataFrame that sums up all the columns of each group of activity into
    * a single column.
    *
    * The resulting DataFrame should have the following columns:
    * - working: value computed from the “telfs” column of the given DataFrame:
    *   - "working" if 1 <= telfs < 3
    *   - "not working" otherwise
    * - sex: value computed from the “tesex” column of the given DataFrame:
    *   - "male" if tesex = 1, "female" otherwise
    * - age: value computed from the “teage” column of the given DataFrame:
    *   - "young" if 15 <= teage <= 22,
    *   - "active" if 23 <= teage <= 55,
    *   - "elder" otherwise
    * - primaryNeeds: sum of all the `primaryNeedsColumns`, in hours
    * - work: sum of all the `workColumns`, in hours
    * - other: sum of all the `otherColumns`, in hours
    *
    * Finally, the resulting DataFrame should exclude people that are not employable (ie telfs = 5).
    *
    * Note that the initial DataFrame contains time in ''minutes''. You have to convert it into ''hours''.
    */
  def timeUsageSummary(
    primaryNeedsColumns: List[Column],
    workColumns: List[Column],
    otherColumns: List[Column],
    df: DataFrame
  ): DataFrame = {
    /** Refer to the API doc for when and otherwise methods, note that when can only
      * be used after a column applied with 'when' method or as the first method.
      * So the as method should be placed at the end.
      */
    val workingStatusProjection: Column =
      when($"telfs" >= 1 && $"telfs" < 3, "working").otherwise("not working").as("working")
    val sexProjection: Column =
      when($"tesex" === 1, "male").otherwise("female").as("sex")
    val ageProjection: Column =
      when($"teage" >= 15 && $"teage" <= 22, "young")
        .when($"teage" >= 23 && $"teage" <= 55, "active")
        .otherwise("elder")
        .as("age")

    /** lit(60) is Column type. In scala, Class Column has numeric operators. */
    val primaryNeedsProjection: Column = (primaryNeedsColumns.reduce(_+_) / lit(60)).as("primaryNeeds")
    val workProjection: Column = (workColumns.reduce(_+_) / lit(60)).as("work")
    val otherProjection: Column = (otherColumns.reduce(_+_) / lit(60)).as("other")
    df
      .select(workingStatusProjection, sexProjection, ageProjection, primaryNeedsProjection, workProjection, otherProjection)
      .where($"telfs" <= 4) // Discard people who are not in labor force
  }

  /** @return the average daily time (in hours) spent in primary needs, working or leisure, grouped by the different
    *         ages of life (young, active or elder), sex and working status.
    * @param summed DataFrame returned by `timeUsageSumByClass`
    *
    * The resulting DataFrame should have the following columns:
    * - working: the “working” column of the `summed` DataFrame,
    * - sex: the “sex” column of the `summed` DataFrame,
    * - age: the “age” column of the `summed` DataFrame,
    * - primaryNeeds: the average value of the “primaryNeeds” columns of all the people that have the same working
    *   status, sex and age, rounded with a scale of 1 (using the `round` function),
    * - work: the average value of the “work” columns of all the people that have the same working status, sex
    *   and age, rounded with a scale of 1 (using the `round` function),
    * - other: the average value of the “other” columns all the people that have the same working status, sex and
    *   age, rounded with a scale of 1 (using the `round` function).
    *
    * Finally, the resulting DataFrame should be sorted by working status, sex and age.
    */
  def timeUsageGrouped(summed: DataFrame): DataFrame = {
    /** Note the usage of 'avg' method, 'round' method, 'agg' method and 'groupBy' method.
      * If change the order in the 'groupBy' method, the order of columns in the result dataframe
      * will change as well.
      */
    summed
      .groupBy($"working", $"sex", $"age")
      .agg(round(avg($"primaryNeeds"), 1).as("primaryNeeds"),
        round(avg($"work"), 1).as("work"),
        round(avg($"other"), 1).as("other"))
      .orderBy($"working", $"sex", $"age")
  }

  /**
    * @return Same as `timeUsageGrouped`, but using a plain SQL query instead
    * @param summed DataFrame returned by `timeUsageSumByClass`
    */
  def timeUsageGroupedSql(summed: DataFrame): DataFrame = {
    val viewName = s"summed"
    summed.createOrReplaceTempView(viewName)
    spark.sql(timeUsageGroupedSqlQuery(viewName))
  }

  /** @return SQL query equivalent to the transformation implemented in `timeUsageGrouped`
    * @param viewName Name of the SQL view to use
    */
  def timeUsageGroupedSqlQuery(viewName: String): String =
  /** Standard SQL code. */
    """SELECT working, sex, age, ROUND(AVG(primaryNeeds),1) AS primaryNeeds,
       ROUND(AVG(work),1) AS work,
       ROUND(AVG(other),1) AS other
       FROM summed
       GROUP BY age, sex, working
       ORDER BY working, sex, age
    """.stripMargin

  /**
    * @return A `Dataset[TimeUsageRow]` from the “untyped” `DataFrame`
    * @param timeUsageSummaryDf `DataFrame` returned by the `timeUsageSummary` method
    *
    * Hint: you should use the `getAs` method of `Row` to look up columns and
    * cast them at the same time.
    */
  def timeUsageSummaryTyped(timeUsageSummaryDf: DataFrame): Dataset[TimeUsageRow] =
    timeUsageSummaryDf map {
      /** From the API doc, we can see Row can be pattern matched. */
      case Row(working: String, sex: String, age: String,
               primaryNeeds: Double, work: Double, other: Double)
        => TimeUsageRow(working, sex, age, primaryNeeds, work, other)
    }

  /**
    * @return Same as `timeUsageGrouped`, but using the typed API when possible
    * @param summed Dataset returned by the `timeUsageSummaryTyped` method
    *
    * Note that, though they have the same type (`Dataset[TimeUsageRow]`), the input
    * dataset contains one element per respondent, whereas the resulting dataset
    * contains one element per group (whose time spent on each activity kind has
    * been aggregated).
    *
    * Hint: you should use the `groupByKey` and `typed.avg` methods.
    */
  def timeUsageGroupedTyped(summed: Dataset[TimeUsageRow]): Dataset[TimeUsageRow] = {
    /** After groupByKey which takes a tuple as argument, the dataset becomes the pattern
      * int the map case, so the map case can transform the dataset row to the desired one.
      */
    import org.apache.spark.sql.expressions.scalalang.typed
    summed
          .groupByKey(timeUsageRow => (timeUsageRow.age, timeUsageRow.sex, timeUsageRow.working))
          .agg(round(avg($"primaryNeeds"), 1).as[Double],
            round(avg($"work"), 1).as[Double],
            round(avg($"other"), 1).as[Double])
          .map {
            case ((age, sex, working), primaryNeeds, work, other)
              => TimeUsageRow(working, sex, age, primaryNeeds, work, other)
          }
          .orderBy($"working", $"sex", $"age")
  }
}

/**
  * Models a row of the summarized data set
  * @param working Working status (either "working" or "not working")
  * @param sex Sex (either "male" or "female")
  * @param age Age (either "young", "active" or "elder")
  * @param primaryNeeds Number of daily hours spent on primary needs
  * @param work Number of daily hours spent on work
  * @param other Number of daily hours spent on other activities
  */
case class TimeUsageRow(
  working: String,
  sex: String,
  age: String,
  primaryNeeds: Double,
  work: Double,
  other: Double
)