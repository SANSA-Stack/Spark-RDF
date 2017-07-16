package net.sansa_stack.rdf.spark.io

import java.io.{ByteArrayInputStream, Closeable, IOException}

import com.typesafe.config.{Config, ConfigFactory}
import net.sansa_stack.rdf.spark.io.ntriples.{JenaTripleToNTripleString, NTriplesStringToJenaTriple}
import net.sansa_stack.rdf.spark.utils.{Logging, Utils}
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat
import org.apache.jena.graph.{Node, Triple}
import org.apache.jena.riot.{Lang, RDFDataMgr}
import org.apache.jena.sparql.util.FmtUtils
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.streaming.DataStreamReader

import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
  * Wrap up implicit classes/methods to read/write RDF data from N-Triples or Turtle files into either [[DataFrame]] or
  * [[RDD]].
  */
package object rdf {

  /**
    * Converts a Jena [[Triple]] to a Spark SQL [[Row]] with three columns.
    * @param triple the triple
    * @return the row
    */
  def toRow(triple: org.apache.jena.graph.Triple): Row = {
    toRow(Seq(triple.getSubject, triple.getPredicate, triple.getObject))
  }

  /**
    * Converts a list Jena [[Node]] objects to a Spark SQL [[Row]].
    * @param nodes the nodes
    * @return the row
    */
  def toRow(nodes: Seq[Node]): Row = {
    // we use the Jena rendering
    Row.fromSeq(nodes.map(n => {
      if (n.isBlank) FmtUtils.stringForNode(n) else n.toString()

    }))
  }



  // the DataFrame methods

  /**
    * Adds methods, `ntriples` and `turtle`, to [[DataFrameWriter]] that allows to write N-Triples files.
    */
  implicit class RDFDataFrameWriter[T](writer: DataFrameWriter[T]) {
    def rdf: String => Unit = writer.format("ntriples").save
    def ntriples: String => Unit = writer.format("ntriples").save
  }

  /**
    * Adds methods, `rdf`, `ntriples` and `turtle`, to [[DataFrameReader]] that allows to read N-Triples and Turtle
    * files.
    */
  implicit class RDFDataFrameReader(reader: DataFrameReader) extends Logging {
    @transient lazy val conf: Config = ConfigFactory.load("rdf_loader")
    /**
      * Load RDF data into a `DataFrame`. Currently, only N-Triples and Turtle syntax are supported
      * @param lang the RDF language (Turtle or N-Triples)
      * @return a [[DataFrame]][(String, String, String)]
      */
    def rdf(lang: Lang): String => DataFrame = lang match {
      case i if lang == Lang.NTRIPLES => ntriples
      case j if lang == Lang.TURTLE => turtle
      case j if lang == Lang.RDFXML => rdfxml
      case _ => throw new IllegalArgumentException(s"${lang.getLabel} syntax not supported yet!")
    }
    /**
      * Load RDF data in N-Triples syntax into a [[DataFrame]] with columns `s`, `p`, and `o`.
      * @return a [[DataFrame]][(String, String, String)]
      */
    def ntriples: String => DataFrame = {
      logDebug(s"Parsing N-Triples with ${conf.getString("rdf.ntriples.parser")} ...")
      reader.format("ntriples").load
    }
    /**
      * Load RDF data in Turtle syntax into a [[DataFrame]] with columns `s`, `p`, and `o`.
      * @return a [[DataFrame]][(String, String, String)]
      */
    def turtle: String => DataFrame = reader.format("turtle").load

    /**
      * Load RDF data in RDF/XML syntax into a [[DataFrame]] with columns `s`, `p`, and `o`.
      * @return a [[DataFrame]][(String, String, String)]
      */
    def rdfxml(path: String): DataFrame = reader.format("rdfxml").load(path)
  }



  // the RDD methods

  /**
    * Adds methods, `ntriples` and `turtle`, to [[SparkContext]] that allows to write N-Triples and Turtle files.
    */
  implicit class RDFWriter[T](triples: RDD[Triple]) {

    val converter = new JenaTripleToNTripleString()

    def saveAsNTriplesFile(path: String, mode: SaveMode = SaveMode.ErrorIfExists, exitOnError: Boolean = false): Unit = {

      val fsPath = new Path(path)
      val fs = fsPath.getFileSystem(triples.sparkContext.hadoopConfiguration)

      val doSave = if (fs.exists(fsPath)) {
        mode match {
          case SaveMode.Append =>
            sys.error(s"Append mode is not supported by ${this.getClass.getCanonicalName} !")
            if (exitOnError) sys.exit(1)
            false
          case SaveMode.Overwrite =>
            fs.delete(fsPath, true)
            true
          case SaveMode.ErrorIfExists =>
            sys.error(s"Given path $path already exists!")
            if (exitOnError) sys.exit(1)
            false
          case SaveMode.Ignore => false
          case _ =>
            throw new IllegalStateException(s"Unsupported save mode $mode ")
        }
      } else {
        true
      }

      // save only if there was no failure with the path before
      if(doSave) {
        triples
          .map(converter) // map to N-Triples string
          .saveAsTextFile(path)
      }


    }

  }

  /**
    * Adds methods, `rdf(lang: Lang)`, `ntriples` and `turtle`, to [[SparkContext]] that allows to read N-Triples and Turtle files.
    */
  implicit class RDFReader(sc: SparkContext) {

    import scala.collection.JavaConverters._

    /**
      * Load RDF data into an [[RDD]][Triple]. Currently, only N-Triples and Turtle syntax are supported.
      * @param lang the RDF language (Turtle or N-Triples)
      * @return the [[RDD]]
      */
    def rdf(lang: Lang, allowBlankLines: Boolean = false): String => RDD[Triple] = lang match {
      case i if lang == Lang.NTRIPLES => ntriples(allowBlankLines)
      case j if lang == Lang.TURTLE => turtle
      case _ => throw new IllegalArgumentException(s"${lang.getLabel} syntax not supported yet!")
    }

    /**
      * Load RDF data in N-Triples syntax into an [[RDD]][Triple].
      * @return the [[RDD]]
      */
    def ntriples(allowBlankLines: Boolean = false): String => RDD[Triple] = path => {
      var rdd = sc.textFile(path, 4) // read the text file

      if(allowBlankLines) rdd = rdd.filter(!_.trim.isEmpty)

      rdd.map(new NTriplesStringToJenaTriple())
    }

    /**
      * Load RDF data in Turtle syntax into an [[RDD]][Triple]
      * @return the [[RDD]]
      */
    def turtle: String => RDD[Triple] = path => {
      val confHadoop = org.apache.hadoop.mapreduce.Job.getInstance().getConfiguration
      confHadoop.set("textinputformat.record.delimiter", ".\n")

      // 1. parse the Turtle file into an RDD[String] with each entry containing a full Turtle snippet
      val turtleRDD = sc.newAPIHadoopFile(
        path, classOf[TextInputFormat], classOf[LongWritable], classOf[Text], confHadoop)
        .filter(!_._2.toString.trim.isEmpty)
        .map{ case (_, v) => v.toString.trim }

//      turtleRDD.collect().foreach(chunk => println("Chunk" + chunk))

      // 2. we need the prefixes - two options:
      // a) assume that all prefixes occur in the beginning of the document
      // b) filter all lines that contain the prefixes
      val prefixes = turtleRDD.filter(_.startsWith("@prefix"))

      // we broadcast the prefixes
      val prefixesBC = sc.broadcast(prefixes.collect())
//      println(prefixesBC.value.mkString(", "))

      turtleRDD.flatMap(ttl => {
        Utils.tryWithResource(new ByteArrayInputStream((prefixesBC.value.mkString("\n") + ttl).getBytes)) {
          is =>
            RDFDataMgr.createIteratorTriples(is, Lang.TURTLE, null).asScala.toSeq
        }
        cleanly(new ByteArrayInputStream((prefixesBC.value.mkString("\n") + ttl).getBytes))(_.close()) { is =>
          // parse the text snippet with Jena and return the triples
          RDFDataMgr.createIteratorTriples(is, Lang.TURTLE, null).asScala.toSeq

        }.get
      })
    }






  def cleanly[A, B](resource: A)(cleanup: A => Unit)(doWork: A => B): Try[B] = {
    try {
      Success(doWork(resource))
    } catch {
      case e: Exception => Failure(e)
    }
    finally {
      try {
        if (resource != null) {
          cleanup(resource)
        }
      } catch {
        case e: Exception => println(e) // should be logged
      }
    }
  }


}