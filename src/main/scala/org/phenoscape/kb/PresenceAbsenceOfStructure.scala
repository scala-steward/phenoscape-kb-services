package org.phenoscape.kb

import scala.concurrent.Future

import org.apache.log4j.Logger
import org.phenoscape.owl.Vocab._
import org.phenoscape.owlet.OwletManchesterSyntaxDataType.SerializableClassExpression
import org.phenoscape.owlet.SPARQLComposer._
import org.phenoscape.scowl.OWL._
import org.semanticweb.owlapi.model.IRI
import org.phenoscape.kb.App.withOwlery

import com.hp.hpl.jena.query.Query

object PresenceAbsenceOfStructure {

  def statesEntailingAbsence(taxon: IRI, entity: IRI): Future[Seq[String]] = {
    App.executeSPARQLQuery(buildAbsenceQuery(taxon, entity), _.toString)
  }

  def statesEntailingPresence(taxon: IRI, entity: IRI): Future[Seq[String]] = {
    App.executeSPARQLQuery(buildPresenceQuery(taxon, entity), _.toString)
  }

  private def buildAbsenceQuery(taxonIRI: IRI, entityIRI: IRI): Query = {
    val taxon = Class(taxonIRI)
    val entity = Individual(entityIRI)
    select_distinct('state, 'state_label, 'matrix_label) from "http://kb.phenoscape.org/" where (
      bgp(
        t(taxon, HAS_MEMBER / EXHIBITS / rdfType, 'phenotype),
        t('state, DENOTES_EXHIBITING / rdfType, 'phenotype),
        t('state, dcDescription, 'state_label),
        t('matrix, HAS_CHARACTER, 'matrix_char),
        t('matrix, rdfsLabel, 'matrix_label),
        t('matrix_char, MAY_HAVE_STATE_VALUE, 'state)),
        withOwlery(
          t('phenotype, rdfsSubClassOf, (LacksAllPartsOfType and (TOWARDS value entity) and (inheres_in some MultiCellularOrganism)).asOMN)),
          App.BigdataRunPriorFirst)
  }

  private def buildPresenceQuery(taxonIRI: IRI, entityIRI: IRI): Query = {
    val taxon = Class(taxonIRI)
    val entity = Class(entityIRI)
    select_distinct('state, 'state_label, 'matrix_label) from "http://kb.phenoscape.org/" where (
      bgp(
        t(taxon, HAS_MEMBER / EXHIBITS / rdfType, 'phenotype),
        t('state, DENOTES_EXHIBITING / rdfType, 'phenotype),
        t('state, dcDescription, 'state_label),
        t('matrix, HAS_CHARACTER, 'matrix_char),
        t('matrix, rdfsLabel, 'matrix_label),
        t('matrix_char, MAY_HAVE_STATE_VALUE, 'state)),
        withOwlery(
          t('phenotype, rdfsSubClassOf, (IMPLIES_PRESENCE_OF some entity).asOMN)),
          App.BigdataRunPriorFirst)
  }

  lazy val logger = Logger.getLogger(this.getClass)

}