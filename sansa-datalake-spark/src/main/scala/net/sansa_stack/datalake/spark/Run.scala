package net.sansa_stack.datalake.spark

import java.io.FileNotFoundException

import org.apache.commons.lang.time.StopWatch
import org.apache.log4j.{Level, Logger}
import net.sansa_stack.datalake.spark.utils.Helpers._

import scala.collection.JavaConversions._
import scala.collection.mutable
import org.apache.spark.sql.DataFrame

/**
 * Created by mmami on 26.01.17.
 */
class Run[A](executor: QueryExecutor[A]) {

  private var finalDataSet: A = _

  def application(queryFile: String, mappingsFile: String, configFile: String): DataFrame = {

    Logger.getLogger("ac.biu.nlp.nlp.engineml").setLevel(Level.OFF)
    Logger.getLogger("org.BIU.utils.logging.ExperimentLogger").setLevel(Level.OFF)
    Logger.getRootLogger.setLevel(Level.OFF)

    Logger.getLogger("org").setLevel(Level.ERROR)
    Logger.getLogger("akka").setLevel(Level.ERROR)

    // 1. Read SPARQL query
    println("\n/*******************************************************************/")
    println("/*                         QUERY ANALYSIS                          */")
    println("/*******************************************************************/")

    try {

      val queryString = scala.io.Source.fromFile(queryFile)
      var query = try queryString.mkString finally queryString.close()


      // Transformations
      var transformExist = false
      var trans = ""
      if (query.contains("TRANSFORM")) {
        trans = query.substring(query.indexOf("TRANSFORM") + 9, query.lastIndexOf(")")) // E.g. ?k?a.toInt && ?a?l.r.toInt.scl(_+61)
        query = query.replace("TRANSFORM" + trans + ")", "") // TRANSFORM is not defined in Jena, so remove
        transformExist = true
      }

      // 2. Extract star-shaped BGPs
      val qa = new QueryAnalyser(query)

      val stars = qa.getStars

      // Create a map between the variable and its star and predicate URL [variable -> (star,predicate)]
      // Need e.g. to create the column to 'SQL ORDER BY' from 'SPARQL ORDER BY'
      var variablePredicateStar: Map[String, (String, String)] = Map()
      for (v <- stars._1) {
        val star = v._1
        val predicate_variable_set = v._2
        for (pv <- predicate_variable_set) {
          val predicate = pv._1
          val variable = pv._2

          variablePredicateStar += (variable -> (star, predicate))
        }
      }

      println(s"predicateStar: $variablePredicateStar")

      val prefixes = qa.getPrefixes
      val (select, distinct) = qa.getProject
      val filters = qa.getFilters
      val orderBys = qa.getOrderBy
      val groupBys = qa.getGroupBy(variablePredicateStar, prefixes)

      var limit: Int = 0
      if (qa.hasLimit) limit = qa.getLimit()

      println("\n- Predicates per star:")

      val star_predicate_var = stars._2 // TODO: assuming no (star,predicate) with two vars?
      println("star_predicate_var: " + star_predicate_var)

      // 3. Generate plan of joins
      println("\n/*******************************************************************/")
      println("/*                  PLAN GENERATION & MAPPINGS                     */")
      println("/*******************************************************************/")
      val pl = new Planner(stars._1)
      val pln = pl.generateJoinPlan
      val joins = pln._1
      val joinedToFlag = pln._2
      val joinedFromFlag = pln._3
      val joinPairs = pln._4

      val neededPredicates = pl.getNeededPredicates(star_predicate_var, joins, select)
      val neededPredicatesAll = neededPredicates._1 // all predicates used
      val neededPredicatesSelect = neededPredicates._2 // only projected out predicates

      println("--> Needed predicates all: " + neededPredicatesAll)

      // 4. Check mapping file
      println("---> MAPPING CONSULTATION")

      val mappers = new Mapper(mappingsFile)
      val results = mappers.findDataSources(stars._1, configFile)

      var star_df: Map[String, A] = Map.empty
      var starNbrFilters: Map[String, Integer] = Map()

      var starDataTypesMap: Map[String, mutable.Set[String]] = Map()

      println("\n---> GOING NOW TO JOIN STUFF")
      for (s <- results) {
        val star = s._1
        val datasources = s._2
        val options = s._3

        val dataTypes = datasources.map(d => d._3)

        starDataTypesMap += (star -> dataTypes)

        println("* Getting DF relevant to the star: " + star)

        // Transformations
        var leftJoinTransformations: (String, Array[String]) = null
        var rightJoinTransformations: Array[String] = null
        if (transformExist) {
          val (transmap_left, transmap_right) = qa.getTransformations(trans)

          val str = omitQuestionMark(star)
          if (transmap_left.keySet.contains(str)) {
            // Get with whom there is a join
            val rightOperand = transmap_left(str)._1
            val ops = transmap_left(str)._2

            // Get the predicate of the join
            val joinLeftPredicate = joinPairs((str, rightOperand))
            leftJoinTransformations = (joinLeftPredicate, ops)
            println("Transform (left) on predicate " + joinLeftPredicate + " using " + ops.mkString("_"))
          }

          if (transmap_right.keySet.contains(str)) {
            rightJoinTransformations = transmap_right(str)
            println("Transform (right) ID using " + rightJoinTransformations.mkString("..."))
          }
        }

        if (joinedToFlag.contains(star) || joinedFromFlag.contains(star)) {
          val (ds, numberOfFiltersOfThisStar) = executor.query(datasources, options, true, star, prefixes, select, star_predicate_var, neededPredicatesAll, filters, leftJoinTransformations, rightJoinTransformations, joinPairs)

          star_df += (star -> ds) // DataFrame representing a star

          starNbrFilters += star -> numberOfFiltersOfThisStar

          println("...with DataFrame schema: " + ds)
        } else if (!joinedToFlag.contains(star) && !joinedFromFlag.contains(star)) {
          val (ds, numberOfFiltersOfThisStar) = executor.query(datasources, options, false, star, prefixes, select, star_predicate_var, neededPredicatesAll, filters, leftJoinTransformations, rightJoinTransformations, joinPairs)

          star_df += (star -> ds) // DataFrame representing a star

          starNbrFilters += star -> numberOfFiltersOfThisStar

          println("...with DataFrame schema: " + ds)
        }
      }

      println("\n/*******************************************************************/")
      println("/*                         QUERY EXECUTION                         */")
      println("/*******************************************************************/")
      println(s"- Here are the (Star, ParSet) pairs: \n $star_df")
      println(s"- Here are join pairs: $joins")
      println(s"- Number of predicates per star: $starNbrFilters ")

      val starWeights = pl.sortStarsByWeight(starDataTypesMap, starNbrFilters, configFile)
      println(s"- Stars weighted (performance + nbr of filters): $starWeights \n")

      val sortedScoredJoins = pl.reorder(joins, starDataTypesMap, starNbrFilters, starWeights, configFile)
      println(s"- Sorted scored joins: $sortedScoredJoins")
      val startingJoin = sortedScoredJoins.head

      // Convert starting join to: (leftStar, (rightStar, joinVar)) so we can remove it from $joins
      var firstJoin: (String, (String, String)) = null
      for (j <- joins.entries) {
        if (j.getKey == startingJoin._1._1 && j.getValue._1 == startingJoin._1._2) {
          firstJoin = startingJoin._1._1 -> (startingJoin._1._2, j.getValue._2)
        }
      }
      println(s"- Starting join: $firstJoin \n")

      finalDataSet = executor.join(joins, prefixes, star_df)

      // Project out columns from the final global join results
      var columnNames = Seq[String]()
      println(s"\n--> Needed predicates select: $neededPredicatesSelect")
      for (i <- neededPredicatesSelect) {
        val star = i._1
        val ns_predicate = i._2
        val bits = get_NS_predicate(ns_predicate)

        val selected_predicate = omitQuestionMark(star) + "_" + bits._2 + "_" + prefixes(bits._1)
        columnNames = columnNames :+ selected_predicate
      }

      if (groupBys != null) {
        println(s"groupBys: $groupBys")
        finalDataSet = executor.groupBy(finalDataSet, groupBys)

        // Add aggregation columns to the final project ones
        for (gb <- groupBys._2) {
          println("-> Add to Project list:" + gb._2)
          columnNames = columnNames :+ gb._2 + "(" + gb._1 + ")"
        }
      }

      println(s"SELECTED column names: $columnNames")

      if (orderBys != null) {
        println(s"orderBys: $orderBys")

        var orderByList: Set[(String, String)] = Set()
        for (o <- orderBys) {
          val orderDirection = o._1
          val str = variablePredicateStar(o._2)._1
          val vr = variablePredicateStar(o._2)._2
          val ns_p = get_NS_predicate(vr)
          val column = omitQuestionMark(str) + "_" + ns_p._2 + "_" + prefixes(ns_p._1)
          orderByList += ((column, orderDirection))
        }

        println(s"ORDER BY list: $orderByList (-1 ASC, -2 DESC)")

        for (o <- orderByList) {
          val variable = o._1
          val direction = o._2

          finalDataSet = executor.orderBy(finalDataSet, direction, variable)
        }
      }

      println("|__ Has distinct? " + distinct)
      finalDataSet = executor.project(finalDataSet, columnNames, distinct)

      if (limit > 0) finalDataSet = executor.limit(finalDataSet, limit)

      println("- Final results schema: ")

      val stopwatch: StopWatch = new StopWatch
      stopwatch.start

      executor.run(finalDataSet)

      stopwatch.stop

      val timeTaken = stopwatch.getTime

      println(s"timeTaken: $timeTaken")

      finalDataSet.asInstanceOf[DataFrame]

    } catch {
      case ex : FileNotFoundException =>
        println("ERROR: One of input files ins't found.")
        null

      case ex : org.apache.jena.riot.RiotException =>
        println("ERROR: invalid Mappings, check syntax.")
        null

      case ex : org.apache.spark.SparkException =>
        println("ERROR: invalid Spark Master.")
        null

      case ex : com.fasterxml.jackson.core.JsonParseException =>
        println("ERROR: invalid JSON content in config file.")
        null

      case ex : java.lang.IllegalArgumentException =>
        println("ERROR: invalid mappings.")
        null

      case ex : org.apache.jena.query.QueryParseException =>
        println("ERROR: invalid query.")
        null
    }

  }
}
