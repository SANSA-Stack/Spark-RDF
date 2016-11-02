package net.sansa_stack.owl.common.parsing

import com.typesafe.scalalogging.Logger
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxOWLParserFactory
import org.semanticweb.owlapi.io.{OWLParserException, StringDocumentSource}
import org.semanticweb.owlapi.model.OWLAxiom

import scala.collection.mutable
import scala.io.{BufferedSource, Source}


trait FunctionalSyntaxParsing {
  private val logger = Logger(classOf[FunctionalSyntaxParsing])

  private def parser = new OWLFunctionalSyntaxOWLParserFactory().createParser()
  private def man = OWLManager.createOWLOntologyManager()
  private def ontConf = man.getOntologyLoaderConfiguration

  /**
    * Builds a snipped conforming to the OWL functional syntax which then can
    * be parsed by the OWLAPI functional syntax parser. A single expression,
    * e.g.
    *
    * Declaration(Class(bar:Cls2))
    *
    * has thus to be wrapped into an ontology declaration as follows
    *
    * Ontology( <http://the.ontology.uri#>
    * Declaration(Class(bar:Cls2))
    * )
    *
    * @param expression A String containing an expression in OWL functional
    *                   syntax, e.g. Declaration(Class(bar:Cls2))
    * @return The parsed axiom or null in case something went wrong during parsing
    */
  @throws(classOf[OWLParserException])
  def makeAxiom(expression: String): OWLAxiom = {
//    val ontStr = "Ontology(<" + ontURI + ">\n"
    val ontStr = "Ontology(<http://example.com/dummy>\n"
    val axStr = ontStr + expression + "\n)"

    val ont = man.createOntology()

    parser.parse(new StringDocumentSource(axStr), ont, ontConf)

    val it = ont.axioms().iterator()

    if (it.hasNext) {
      it.next()
    } else {
      logger.warn("No axiom was created for expression " + expression)
      null
    }
  }
}


/**
  * Iterator which
  * - reads the next functional syntax axiom definition (taking care of
  *   line breaks in case of multi-line string literals)
  * - skips the read axiom if it is not a multiple of the split number
  *   (i.e. axiomNumber % numPartitions != split.index)
  * - skips all comments and empty lines
  * - yields a string containing the next axiom (with
  *   axiomNumber % numPartitions == split.index)
  */
class FunctionalSyntaxInputFileIterator(filePath: String,
                                        numPartitions: Int,
                                        partitionIndex: Int) extends Iterator[String] {

  val inputFileIt = Source.fromFile(filePath)
  var ontURI: String = null
  /**
    * the axiom counter to only return axioms with
    * axiomNumber % numPartitions == split.index */
  var axiomNumber = 0
  /**
    * Map containing all read prefixes; required to expand namespace
    * shortcuts in the returned (functional syntax) axiom string */
  var prefixes = new mutable.HashMap[String, String]()
  /**
    * The extraction of the next functional syntax axiom expression is
    * triggered by the hasNext method. If there is a functional syntax
    * axiom expression that could be extracted, it is held in
    * lastExpression */
  var lastExpression: String = null
  // TODO: refine
  val prefixPattern = "Prefix\\(([a-zA-Z]*)\\:=<(.*)>\\)".r
  val ontologyPattern = "Ontology\\(<(.*)>".r
  /** marker used to store default namespace */
  val _empty = "_EMPTY_"

  override def hasNext: Boolean = {
    lastExpression = nextExpression()
    if (lastExpression == null) inputFileIt.close()
    lastExpression != null
  }

  override def next(): String = lastExpression

  /**
    * Tries to extract the next expression from the buffered file input
    * iterator.
    *
    * Main approach:
    * 1) read until next newline is reached
    *    - if the quotes read so far are un-balanced, there was a multi-
    *      line string literal --> read on until next newline is reached
    *      and check again
    *    - else expression string is read
    * 2) check whether the read expression
    *    a) is an empty line --> skip and go on with 1)
    *    b) is a prefix declaration --> add it this.prefixes and go on
    *       with 1)
    *    c) is an ontology declaration --> get ontology URI and go on
    *       with 1)
    *    d) belongs to this partition, i.e.
    *       axiomNumber % numPartitions == split.index --> yield
    *       expression; else go on with 1)
    *
    * TODO: check parentheses: nested parentheses are not checked so far
    */
  private def nextExpression() = {
    var expression: String = null

    // 1)
    // we're done if 2d) check is positive or we reached the end of file
    var notDone = true
    while (notDone) {
      expression = ""
      var foundExpressionEnd = false
      var nrOfReadQuotesIsBalanced = true

      while (!foundExpressionEnd) {
        if (!inputFileIt.hasNext)
          foundExpressionEnd = true

        else {
          var readChar = inputFileIt.next()

          // keep track of quotes read so far
          if (readChar == '"') nrOfReadQuotesIsBalanced = !nrOfReadQuotesIsBalanced

          foundExpressionEnd =
            (readChar == '\n' && nrOfReadQuotesIsBalanced) || !inputFileIt.hasNext

          expression += readChar
        }
      }

      // 2) Now one expression is read (possibly with multi-line string literal)
      // `--> check if the read expression
      //        a) is an empty line or a comment
      //        b) is a prefix declaration
      //        c) is an ontology declaration
      //        d) belongs to this partition

      // a) empty line or a comment
      var skipLine =
        expression.trim() == "" || // empty line
          expression.trim.startsWith("#") || // comment
          expression.trim.startsWith(")") // last, outermost closing parenthesis

      if (!skipLine) {
        if (expression.trim.startsWith("Prefix")) {
          // b) prefix declaration
          val (k, v) = parsePrefix(expression.trim())
          prefixes.put(k, v)
          skipLine = true

        } else if (expression.trim.startsWith("Ontology")) {
          // c) ontology declaration
          ontURI = parseOntologyURI(expression)
          skipLine = true

        } else if (expression.trim.startsWith("<http")) {
          // e.g. <http://purl.obolibrary.org/obo/pato/releases/2016-05-22/pato.owl>
          // as part of an Ontology(...) declaration
          skipLine = true
        }
      }

      // d) check whether expression belongs to this partition
      if ((axiomNumber % numPartitions == partitionIndex && !skipLine) || !inputFileIt.hasNext) {
        notDone = false
      }

      if (!skipLine) axiomNumber += 1
      else expression = null
    }

    if (expression != null) {
      // Expand prefix abbreviations: foo:Bar --> http://foo.com/somePath#Bar
      for (prefix <- prefixes.keys) {
        val p = prefix + ":"

        if (expression.contains(p)) {
          val v: String = "<" + prefixes.get(prefix).get
          // TODO: refine regex
          val pattern = (p + "([a-zA-Z][0-9a-zA-Z_-]*)").r

          pattern.findAllIn(expression).foreach(hit => {
            if (!expression.contains(hit + ">")) {
              expression = expression.replace(hit, hit + ">")
            }
          })
          expression = expression.replace(p.toCharArray, v.toCharArray)
        }
      }

      // handle default prefix e.g. :Bar --> http://foo.com/defaultPath#Bar
      // TODO: refine regex
      val pattern = ":[^/][a-zA-Z][0-9a-zA-Z_-]*".r
      val v: String = "<" + prefixes.get(_empty).get

      if (prefixes.contains(_empty)) {
        pattern.findAllIn(expression).foreach(hit => {
          val full = hit.replace(":".toCharArray, v.toCharArray)
          expression = expression.replace(hit, full + ">")
        })
      }

      expression.trim()

    } else {
      expression  // == null
    }
  }

  /**
    * Parses the prefix declaration of a namespce URI and returns the
    * pair (prefix, namespace URI)
    *
    * @param prefixLine Sth like
    *                   Prefix(:=<http://purl.obolibrary.org/obo/pato.owl#>) or
    *                   Prefix(dc:=<http://purl.org/dc/elements/1.1/>)
    */
  private def parsePrefix(prefixLine: String) = {
    var prefix, uri: String = null

    prefixLine.trim match {
      case prefixPattern(p, u) => {
        prefix = p
        uri = u
      }
    }

    if (prefix.isEmpty) prefix = _empty

    (prefix, uri)
  }

  /**
    * Parses and returns the URI of an ontology definition line.
    *
    * @param ontologyLine Sth like
    *                     'Ontology(<http://purl.obolibrary.org/obo/pato.owl>'
    * @return The parsed string containing ontology URI or null of nothing
    *         was found
    */
  private def parseOntologyURI(ontologyLine: String): String = {
    ontologyLine.trim match {
      case ontologyPattern(uri) => uri
    }
  }
}
