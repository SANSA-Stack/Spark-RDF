package net.sansa_stack.query.spark.ops.rdd

import net.sansa_stack.query.spark.api.domain.ResultSetSpark
import net.sansa_stack.rdf.spark.utils.DataTypeUtils
import org.aksw.jena_sparql_api.analytics.ResultSetAnalytics
import org.aksw.jena_sparql_api.schema_mapping.{FieldMapping, SchemaMapperImpl, SchemaMapping, TypePromoterImpl}
import org.aksw.jena_sparql_api.utils.NodeUtils
import org.apache.jena.datatypes.TypeMapper
import org.apache.jena.sparql.core.Var
import org.apache.jena.sparql.engine.binding.Binding
import org.apache.jena.vocabulary.XSD
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row, SparkSession}

/**
 * Mapper from SPARQL bindings to DataFrames
 *
 * The actual work is carried out by [[SchemaMapperImpl]] which computes
 * a [[SchemaMapping]] from the provided source schema and its type information
 *
 * The schema mapping holds information about how to compute the values for the target
 * schema by means of SPARQL expressions over the source schema. For example,
 * if a target column 'foo' should carry the languages tags of the language tag of a column 'bar'
 * in the source schema, the definition for 'foo' becomes:
 * ?foo = lang(?bar)
 *
 * Note, that column names are represented as SPARQL variables in order to facilite
 * re-use of SPARQL algebra and expressions as a model (=language) for declarative schema mappings.
 *
 * In addition, the field mapping stores type information for the target column. In this example,
 * the type of bar is xsd:string.
 * Jena's [[TypeMapper]] is used to resolve datatypeIris to [[org.apache.jena.datatypes.RDFDatatype]] instances which in
 * turn give access to the Java class which finally is converted to a spark type via [[DataTypeUtils]].
 *
 *
 */
object RddToDataFrameMapper {
  import net.sansa_stack.query.spark.query._

  import collection.JavaConverters._

  def createSchemaMapping(resultSet: ResultSetSpark): SchemaMapping = {

    val javaResultVars = resultSet.getResultVars.toSet.asJava

    // Gather result set statistics using the analytic functions
    val usedDatatypesAndNulls = resultSet.getBindings.javaCollect(
      ResultSetAnalytics.usedDatatypesAndNullCounts(javaResultVars).asCollector())

    // Provide the stastistics to the schema mapper
    val schemaMapping = SchemaMapperImpl.newInstance
      .setSourceVars(javaResultVars)
      .setSourceVarToDatatypes((v: Var) => usedDatatypesAndNulls.get(v).getKey.elementSet)
      .setSourceVarToNulls((v: Var) => usedDatatypesAndNulls.get(v).getValue)
      .setTypePromotionStrategy(TypePromoterImpl.create)
      .createSchemaMapping

    schemaMapping
  }

  // Convenience method; execution doesn't need the resultVars
  // def applySchemaMapping(resultSet: ResultSetSpark, schemaMapping: SchemaMapping): DataFrame =
  // applySchemaMapping(resultSet.getBindings, schemaMapping)

  def applySchemaMapping(
                          sparkSession: SparkSession,
                          bindings: RDD[Binding],
                          schemaMapping: SchemaMapping): DataFrame = {

    val typeMapper = TypeMapper.getInstance

    val structType = new StructType

    for (v <- schemaMapping.getDefinedVars.asScala) {
      val fieldMapping: FieldMapping = schemaMapping.getFieldMapping.get(v)

      val datatypeIri = fieldMapping.getDatatypeIri

      // Special cases: R2RML IRI and BlankNodes
      val effectiveDatatypeIri = datatypeIri match {
        case NodeUtils.R2RML_IRI => XSD.xstring.getURI
        case NodeUtils.R2RML_BlankNode => XSD.xstring.getURI
        case default => default
      }

      val rdfDatatype = typeMapper.getSafeTypeByName(effectiveDatatypeIri)
      val javaClass = rdfDatatype.getJavaClass

      val name = v.getVarName
      val dataType = DataTypeUtils.getSparkType(javaClass)
      val isNullable = fieldMapping.isNullable

      structType.add(name, dataType, isNullable)
    }

    val rows: RDD[Row] = bindings.map(mapToRow(_, schemaMapping))
    sparkSession.createDataFrame(rows, structType)
  }

  def mapToRow(binding: Binding, schemaMapping: SchemaMapping): Row = {
    val typeMapper = TypeMapper.getInstance

    val seq = schemaMapping.getDefinedVars.asScala.map(v => {
      val fieldMapping = schemaMapping.getFieldMapping.get(v)
      val decisionTreeExpr = fieldMapping.getDefinition

      val node = decisionTreeExpr.eval(binding)
      val javaValue =
        if (node.isURI) node.getURI
        else if (node.isBlank) node.getBlankNodeLabel
        else node.getLiteralValue

      javaValue
    }).toSeq

    Row.fromSeq(seq)
  }
}
