package net.sansa_stack.rdf.spark.qualityassessment.metrics.availability

import org.apache.spark.rdd.RDD
import org.apache.jena.graph.{ Triple, Node }
import net.sansa_stack.rdf.spark.qualityassessment.utils.NodeUtils._

/*
 * Dereferenceability of the URI.
 * @author Gezim Sejdiu
 */
object DereferenceableUris {
  implicit class DereferenceableUrisFunctions(dataset: RDD[Triple]) extends Serializable {

    val totalURIs = dataset.filter(_.getSubject.isURI())
      .union(dataset.filter(_.getPredicate.isURI()))
      .union(dataset.filter(_.getObject.isURI()))
      .distinct().count().toDouble
    // check object if URI and local
    val objects = dataset.filter(f =>
      f.getObject.isURI() && isInternal(f.getObject) && !isBroken(f.getObject))

    // check subject, if local and not a blank node
    val subjects = dataset.filter(f =>
      f.getSubject.isURI() && isInternal(f.getSubject) && !isBroken(f.getSubject))

    // check predicate if local
    val predicates = dataset.filter(f =>
      f.getPredicate.isURI() && isInternal(f.getPredicate) && !isBroken(f.getPredicate))

    /**
     * This metric calculates the number of valid redirects of URI.
     * It computes the ratio between the number of all valid redirects (subject + predicates + objects)
     * a.k.a dereferencedURIS and the total number of URIs on the dataset.
     */
    def assessDereferenceableUris() = {

      val dereferencedURIs = subjects.count().toDouble + predicates.count().toDouble + objects.count().toDouble

      val value = if (totalURIs > 0.0)
        dereferencedURIs / totalURIs
      else 0

      value

    }
    /**
     * This metric measures the extent to which a resource includes all triples from the dataset that have
     * the resource's URI as the object.
     * The ratio computed is the number of objects that are "back-links" (are part of the resource's URI)
     * and the total number of objects.
     */
    def assessDereferenceableBackLinks() = {
      val backLinks = objects.map(f => getParentURI(f.getObject)).count().toDouble
      if (totalURIs > 0.0) backLinks / totalURIs else 0
    }

    /**
     * This metric measures the extent to which a resource includes all triples from the dataset that have
     * the resource's URI as the subject.
     * The ratio computed is the number of subjects that are "forward-links" (are part of the resource's URI)
     * and the total number of subjects.
     */
    def assessDereferenceableForwardLinks() = {
      val forwardLinks = subjects.map(f => getParentURI(f.getObject)).count().toDouble
      if (totalURIs > 0.0) forwardLinks / totalURIs else 0
    }
  }
}