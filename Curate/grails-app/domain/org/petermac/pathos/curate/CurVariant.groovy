/*
 * Copyright (c) 2013. PathOS Variant Curation System. All rights reserved.
 *
 * Organisation: Peter MacCallum Cancer Centre
 * Author: doig ken
 */

package org.petermac.pathos.curate

import grails.persistence.Entity

@Entity
class CurVariant implements Taggable
{
    //  CurVariant should be unique by grpVariant + clinContext
    //
    GrpVariant      grpVariant                  //  Group Variant: genomic key to this variant
    ClinContext     clinContext                 //  Optional clinical context for this variant: may be null


    //  Embedded evidence Class encapsulating eveidence data
    //
    Evidence        evidence                    //  Evidence justifiying this curation

    String	        variant                     //  Todo: deprecated - in GrpVariant
    String	        ens_variant                 //  Ensembl formatted variant
    String	        gene                        //  Gene
    String	        gene_type                   //  Gene type: Oncogene or TSG (Tumour suprressor)
    String	        gene_pathway                //  Gene Pathway, from Vogelstein et al
    String	        gene_process                //  Gene Process, from Vogelstein et al
    String	        hgvsc                       //  Todo: deprecated - in GrpVariant
    String	        hgvsp                       //  Todo: deprecated - in GrpVariant
    String	        hgvsg                       //  Todo: deprecated - in GrpVariant
    String	        consequence                 //  Ensembl variant consequences
    String	        chr                         //  Todo: deprecated - in GrpVariant
    String	        pos                         //  Todo: deprecated - in GrpVariant
    String	        exon                        //  Todo: deprecated - in GrpVariant
    String	        pmClass = "Unclassified"    //  Pathogenicity currently C1: Benign .. C5: Pathogenic
    String          classOverrideReason         //  Reason for overriding the pmClass
    String          ampClass = "Unclassified"
    String          ampReason
    String          overallClass = "Unclassified"
    String          overallReason
    String	        reportDesc                  //  Clinical report text
    AuthUser	    classified                  //  User who classified
    AuthUser	    authorised                  //  User who authorised
    Boolean         authorisedFlag = false      //  True if authorised
    Date            lastAuthorised = null       //  Date of latest authorisation
    Date	        dateCreated = new Date()    //  Creation date
    Date	        lastUpdated = new Date()    //  Updated date
    String	        alamutClass                 //  Todo: deprecated
    String          siftCat                     //  Todo: deprecated - in AnoVariant
    String          polyphenCat                 //  Todo: deprecated - in AnoVariant
    String	        cosmic                      //  Todo: deprecated - in AnoVariant
    String	        dbsnp                       //  Todo: deprecated - in AnoVariant
    SeqVariant      originating
//    AcmgEvidence    acmgEvidence

    static embedded = ['evidence', 'grpVariant'] //  Embedded Classes

    static hasMany	= [ tags: Tag ]

    static mappedBy = [ originating: SeqVariant ]

    static belongsTo =  [ SeqVariant ]

    static constraints =
    {
        variant                     ( nullable: false )
        hgvsc                       ( nullable: false )
        hgvsp                       ( nullable: true )
        ens_variant                 ( nullable: true )
        gene                        ( nullable: false )
        gene_type                   ( nullable: true )
        consequence                 ( nullable: true )
        pmClass                     ( inList: [
                                        "Unclassified",
                                        "C1: Not pathogenic",
                                        "C2: Unlikely pathogenic",
                                        "C3: Unknown pathogenicity (Level C)",
                                        "C3: Unknown pathogenicity (Level B)",
                                        "C3: Unknown pathogenicity",
                                        "C3: Unknown pathogenicity (Level A)",
                                        "C4: Likely pathogenic",
                                        "C5: Pathogenic"
                                    ], blank: false )
        classOverrideReason         ( nullable: true )
        ampClass                    ( nullable: true )
        ampReason                   ( nullable: true )
        overallClass                ( nullable: true )
        overallReason               ( nullable: true )
        alamutClass                 ( nullable: true )
        siftCat                     ( nullable: true )
        polyphenCat                 ( nullable: true )
        gene_pathway                ( nullable: true )
        gene_process                ( nullable: true )
        chr                         ( nullable: true )
        pos                         ( nullable: true )
        exon                        ( nullable: true )
        cosmic                      ( nullable: true )
        dbsnp                       ( nullable: true )
        reportDesc                  ( nullable: true )
        evidence                    ( nullable: true )
        classified                  ( nullable: true )
        authorisedFlag              ( nullable: false )
        lastAuthorised              ( nullable: true )
        authorised                  ( nullable: true )
        dateCreated                 ( nullable: true )
        lastUpdated                 ( nullable: false )
        clinContext                 ( nullable: false ) //( nullable: true  ) need to run: UPDATE cur_variant SET clin_context_id=(SELECT id FROM clin_context WHERE code='NONE')
        grpVariant(
                nullable: true,
                unique: ['grpVariant', 'clinContext']
        )
        originating( nullable: true )
//        acmgEvidence                ( nullable: true )

     }


    static mapping =
    {
        sort alamutClass: "desc"
        variant     index: 'variant_idx'
        clinContext   index: 'comb_index', unique: true
        grpVariant   index: 'gv_idx'
        reportDesc          ( type: 'text' )
        classOverrideReason        ( type: 'text' )
        ampReason                   ( type: 'text' )
        overallReason               ( type: 'text' )
        cosmic                     ( type: 'text' )
    }

    static  searchable =
    {
        except = [ 'originating' ]
        evidence    component: true
        tags        component: true
    }

    String	toString()
    {
         "${gene}:${hgvsc} ${hgvsp} ${(authorisedFlag && pmClass) ? pmClass : 'NotAuth'} ${clinContext?clinContext:'No context'}"
    }



    ArrayList<CurVariant> allSeqVariants(){
        return SeqVariant.executeQuery("from SeqVariant sv where sv.hgvsg=:accession",[accession: this.grpVariant.accession])
    }

    ArrayList<CurVariant> allSeqVariantsFromSample(SeqSample ss){
        return SeqVariant.executeQuery("from SeqVariant sv where sv.hgvsg=:accession and sv.seqSample=:ss",[accession: this.grpVariant.accession,ss:ss])
    }

    //  get all seqSamples that have a SeqVariant linked to this
    ArrayList<SeqSample> allSeqSamples(){
        return SeqVariant.executeQuery("select sv.seqSample from SeqVariant sv where sv.hgvsg=:accession group by sv.seqSample",[accession: this.grpVariant.accession])
    }

    /**
     * Return a list of all the CurVariants with the same grpVariant as this one
     * @return
     */
    ArrayList<CurVariant> allCurVariants() {
        return CurVariant.executeQuery("from CurVariant cv where cv.grpVariant.accession=:accession", [accession: this.grpVariant.accession])

    }

    AcmgEvidence fetchAcmgEvidence() {
        return AcmgEvidence.findByCurVariant(this)
    }

    AmpEvidence fetchAmpEvidence() {
        return AmpEvidence.findByCurVariant(this)
    }

}




