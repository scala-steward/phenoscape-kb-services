package org.phenoscape.kb

import scala.concurrent.Future
import scala.collection.JavaConversions._
import scala.language.postfixOps
import spray.http._
import spray.httpx._
import spray.httpx.marshalling._
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.phenoscape.kb.Main.system.dispatcher
import org.phenoscape.kb.Term.JSONResultItemsMarshaller
import org.phenoscape.owl.Vocab
import org.phenoscape.owl.Vocab._
import org.phenoscape.kb.KBVocab._
import org.phenoscape.kb.KBVocab.rdfsSubClassOf
import org.phenoscape.scowl._
import org.phenoscape.kb.KBVocab.rdfsLabel
import org.phenoscape.owlet.SPARQLComposer._
import org.phenoscape.owlet.OwletManchesterSyntaxDataType.SerializableClassExpression
import com.hp.hpl.jena.sparql.syntax.ElementSubQuery
import org.semanticweb.owlapi.model.OWLClassExpression
import com.hp.hpl.jena.query.Query
import org.semanticweb.owlapi.model.IRI
import com.hp.hpl.jena.sparql.expr.aggregate.AggCountDistinct
import com.hp.hpl.jena.sparql.core.Var
import com.hp.hpl.jena.query.QuerySolution
import com.hp.hpl.jena.sparql.expr.ExprVar
import com.hp.hpl.jena.sparql.expr.ExprList
import com.hp.hpl.jena.sparql.expr.E_NotOneOf
import com.hp.hpl.jena.sparql.expr.nodevalue.NodeValueNode
import com.hp.hpl.jena.sparql.syntax.ElementFilter

case class GenePhenotypeAnnotation(gene: MinimalTerm, phenotype: MinimalTerm, source: Option[IRI]) extends JSONResultItem {

  def toJSON: JsObject = {
    (Map("gene" -> gene.toJSON,
      "phenotype" -> phenotype.toJSON,
      "source" -> source.map(_.toString).getOrElse("").toJson)).toJson.asJsObject
  }

  override def toString(): String = {
    s"${gene.iri}\t${gene.label}\t${phenotype.iri}\t${phenotype.label}\t${source}"
  }

}

object GenePhenotypeAnnotation {

  def queryAnnotations(entity: Option[OWLClassExpression], quality: Option[OWLClassExpression], inTaxonOpt: Option[IRI], limit: Int = 20, offset: Int = 0): Future[Seq[GenePhenotypeAnnotation]] = for {
    query <- buildGenePhenotypeAnnotationsQuery(entity, quality, inTaxonOpt, limit, offset)
    annotations <- App.executeSPARQLQuery(query, fromQueryResult)
  } yield annotations

  def queryAnnotationsTotal(entity: Option[OWLClassExpression], quality: Option[OWLClassExpression], inTaxonOpt: Option[IRI]): Future[Int] = for {
    query <- buildGenePhenotypeAnnotationsTotalQuery(entity, quality, inTaxonOpt)
    result <- App.executeSPARQLQuery(query)
  } yield ResultCount.count(result)

  def fromQueryResult(result: QuerySolution): GenePhenotypeAnnotation = GenePhenotypeAnnotation(
    MinimalTerm(IRI.create(result.getResource("gene").getURI),
      result.getLiteral("gene_label").getLexicalForm),
    MinimalTerm(IRI.create(result.getResource("phenotype").getURI),
      result.getLiteral("phenotype_label").getLexicalForm),
    Option(result.getResource("source")).map(v => IRI.create(v.getURI)))

  private def buildBasicGenePhenotypeAnnotationsQuery(entity: Option[OWLClassExpression], quality: Option[OWLClassExpression], inTaxonOpt: Option[IRI]): Future[Query] = {
    val phenotypeExpression = (entity, quality) match {
      case (Some(entityTerm), Some(qualityTerm)) => Some((has_part some qualityTerm) and (phenotype_of some entityTerm))
      case (Some(entityTerm), None)              => Some(phenotype_of some entityTerm)
      case (None, Some(qualityTerm))             => Some(has_part some qualityTerm)
      case (None, None)                          => None
    }
    val phenotypeTriple = phenotypeExpression.map(desc => t('phenotype, rdfsSubClassOf, desc.asOMN)).toList
    val taxonPatterns = inTaxonOpt.map(t('taxon, rdfsSubClassOf*, _)).toList
    val query = select_distinct('gene, 'gene_label, 'phenotype, 'phenotype_label, 'source) where (
      bgp(
        App.BigdataAnalyticQuery ::
          t('annotation, rdfType, AnnotatedPhenotype) ::
          t('annotation, associated_with_gene, 'gene) ::
          t('gene, rdfsLabel, 'gene_label) ::
          t('annotation, rdfType, 'phenotype) ::
          t('phenotype, rdfsLabel, 'phenotype_label) ::
          t('annotation, associated_with_taxon, 'taxon) ::
          phenotypeTriple ++
          taxonPatterns: _*),
        optional(bgp(t('annotation, dcSource, 'source))),
        new ElementFilter(new E_NotOneOf(new ExprVar('phenotype), new ExprList(List(
          new NodeValueNode(AnnotatedPhenotype),
          new NodeValueNode(owlNamedIndividual))))))
    App.expandWithOwlet(query)
  }

  def buildGenePhenotypeAnnotationsQuery(entity: Option[OWLClassExpression], quality: Option[OWLClassExpression], inTaxonOpt: Option[IRI], limit: Int = 20, offset: Int = 0): Future[Query] = {
    for {
      rawQuery <- buildBasicGenePhenotypeAnnotationsQuery(entity, quality, inTaxonOpt)
    } yield {
      val query = rawQuery from KBMainGraph
      query.setOffset(offset)
      if (limit > 0) query.setLimit(limit)
      query.addOrderBy('gene_label)
      query.addOrderBy('gene)
      query.addOrderBy('phenotype_label)
      query.addOrderBy('phenotype)
      query
    }
  }

  def buildGenePhenotypeAnnotationsTotalQuery(entity: Option[OWLClassExpression], quality: Option[OWLClassExpression], inTaxonOpt: Option[IRI]): Future[Query] = {
    for {
      rawQuery <- buildBasicGenePhenotypeAnnotationsQuery(entity, quality, inTaxonOpt)
    } yield {
      val query = select() from KBMainGraph where (new ElementSubQuery(rawQuery))
      query.getProject.add(Var.alloc("count"), query.allocAggregate(new AggCountDistinct()))
      query
    }
  }

  val AnnotationTextMarshaller = Marshaller.delegate[Seq[GenePhenotypeAnnotation], String](MediaTypes.`text/plain`, MediaTypes.`text/tab-separated-values`) { annotations =>
    val header = "gene IRI\tgene label\tphenotype IRI\tphenotype label\tsource IRI"
    s"$header\n${annotations.map(_.toString).mkString("\n")}"
  }

  implicit val ComboGenePhenotypeAnnotationsMarshaller = ToResponseMarshaller.oneOf(MediaTypes.`text/plain`, MediaTypes.`text/tab-separated-values`, MediaTypes.`application/json`)(AnnotationTextMarshaller, JSONResultItemsMarshaller)

}