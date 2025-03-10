package org.petermac.pathos.curate

import org.petermac.util.JiraNotifier
import org.petermac.util.Locator

import java.text.MessageFormat

class CurateService {

    static def loc = Locator.instance   // file locator
    def grailsApplication
    def utilService

    def SpringSecurityService
    //LinkGenerator grailsLinkGenerator

    def AuditService


    def deleteCurVariant( CurVariant cv ) {

        // Null all JiraIssue links to the CurVariant record
        //
        def variantIssues = JiraIssue.findAllByCurVariant(cv)
        for ( varIssue in variantIssues ) {
            varIssue.setCurVariant(null)
            varIssue.save(flush: true)
        }

        // Null the curVariantReport objects
        CurVariantReport.findAllByCurVariant(cv).each { cvr ->
            cvr.setCurVariant(null)
            cvr.save(flush: true)
        }

        // Delete the ACMG and AMP evidence
        cv.fetchAcmgEvidence()?.delete()
        cv.fetchAmpEvidence()?.delete()

        //  Delete the CurVariant record
        //
        cv.delete(flush: true)

        //  Audit message
        //
        AuditService.audit([
            category    : 'curation',
            variant     : cv.toString(),
            task        : 'variant deletion',
            description : "Deleted CurVariant ${cv.toString()}"
        ])
    }



    /**
     *
     * in v1.3 this replaces CurateService's createVariant
     * Create one or two CurVariant object(s) from a given originating SeqVariant object
     *
     * Called from SeqVariantController / from svlist when a user ticks the 'curate' button
     * for a SV + hits save
     *
     * The expected behaviour is:
     * If there is no CV in the null context, create it.
     * If there is no CV in the current seqsample's context, create it.
     *
     * Return false if we fail to create a curVariant when we should
     * AES 7-December-2016
     *
     * @param sv
     */
    boolean createNewCurVariantsFromSeqVariant ( SeqVariant sv ) {
        ClinContext cc = sv?.seqSample?.clinContext;

        // Return false without doing anything if there is no clinical context.
        if ( !cc ) return false

        def defaultCC = ClinContext.generic()

        //  if this SV does not have a default-clincontext CV, make a null-clincontext CV
        //
        if (!sv.curatedInContext(defaultCC))
        {
            if(!makeCurVariant ( sv, defaultCC ))  return false
        }

        //  if the seqsample of this seqvar has a non-null clincontext, make a CV for that clincontext
        //
        if(cc != defaultCC && !sv.curatedInContext(cc))
        {
            // Try to make a CurVariant for this SV at that CC.
            if(!makeCurVariant( sv, cc ))  return false
        }

        return sv.curatedInContext(cc);
    }

    


    /**
     * Create a new CurVariant object
     * this also makes a VarLink
     * this also alerts molpath.ops on JIRA by making a new issue
     *
     * @param sv to create from
     * @param cc of new cv
     * @return
     */
    CurVariant makeCurVariant(SeqVariant sv, ClinContext cc = ClinContext.generic()) {
        if(!cc) cc = ClinContext.generic()

        CurVariant var = new CurVariant()

        var.variant = sv.variant
        var.grpVariant = new GrpVariant(accession: sv.hgvsg, muttyp: 'SNV')
        var.clinContext = cc
        var.gene = sv.gene
        var.chr = sv.chr
        var.pos = sv.pos
        var.exon = sv.exon
        var.hgvsg = sv.hgvsg
        var.hgvsp = sv.hgvsp
        var.hgvsc = sv.hgvsc
        var.consequence = sv.consequence
        var.siftCat = sv.siftCat
        var.ens_variant = sv.ens_variant
        var.cosmic = sv.cosmic
        var.dbsnp = sv.dbsnp
        var.polyphenCat = sv.polyphenCat
        var.alamutClass = sv.alamutClass
        var.evidence = null

        if ( ! var.save(flush:true))
        {
            log.error("Failure to Save CurVariant when trying to create new CurVariant from SeqVariant id " + sv.id)
            var?.errors?.allErrors?.each {
                log.error(new MessageFormat(it?.defaultMessage)?.format(it?.arguments))
                println (new MessageFormat(it?.defaultMessage)?.format(it?.arguments))
            }


            //  Discard transient object
            //
            var.discard()
            return null
        }
        if(!var?.grpVariant?.accession) {
            log.error("grpVariant Accession is null error when trying to create new CurVariant from SeqVariant id " + sv.id)
            var.discard()
            return null
        }

        AcmgEvidence acmgEvidence = new AcmgEvidence(curVariant: var)
        AmpEvidence ampEvidence = new AmpEvidence(curVariant: var)
        acmgEvidence.save()
        ampEvidence.save()

        //  if there is a null cc CV with this accession, use its originating
        //  we only set originating afresh for brand new curvariants
        def otherVars =  CurVariant.executeQuery("from org.petermac.pathos.curate.CurVariant cv where cv.grpVariant.accession=:hgvsg and cv.clinContext=:default and cv.id !=:id",[hgvsg:sv.hgvsg,id:var.id,default:ClinContext.generic()])
        if(otherVars && otherVars?.size() > 0) {
            var.originating = otherVars[0].originating
        } else {
            var.originating = sv
        }
        var.save()

        //  Create audit message
        //
        AuditService.audit([
            category    : 'curation',
            variant     : var.toString(),
            task        : 'curation',
            description : "Curated ${var.toString()} from SeqVariant ${sv} in ${sv.seqSample.seqrun} ${sv.seqSample} "
        ])


        createJiraIssueForNewCurVariant(var,sv)


        return var
    }


    def createJiraIssueForNewCurVariant(CurVariant var, SeqVariant origSv)
    {
            //  make a JIRA issue: notify MP_OPS that variant needs curation
            //  disabled on lhost
            def env = loc.pathosEnv

            def jnotifier = new JiraNotifier()
            def currentUser = springSecurityService.currentUser as AuthUser

            //we cannot use grailslinkgen because loader uses curateservice. instead we hardcode the links since, for molpath.ops, only
            //production links are ever useful (+ we do pa_dev ones for debugging)

            def cvlink = "(link to ${var})"
            def sslink = "(link to ${origSv.seqSample})"
            if ( env == "pa_prod" ) {
                cvlink = "http://bioinf-pathos:8080${utilService.context()}/curVariant/show/${var.id}"
                sslink = "http://bioinf-pathos:8080${utilService.context()}/seqVariant/svlist/${origSv.seqSampleId}"
            }
            else if ( env == "pa_dev" ) {
                cvlink = "http://bioinf-pathos-test:8080${utilService.context()}/curVariant/show/${var.id}"
                sslink = "http://bioinf-pathos-test:8080${utilService.context()}/seqVariant/svlist/${origSv.seqSampleId}"
            }
            else if ( env == "pa_uat" ) {
                cvlink = "http://vmut-pathos-uat1.unix.petermac.org.au:8080${utilService.context()}/curVariant/show/${var.id}"
                sslink = "http://vmut-pathos-uat1.unix.petermac.org.au:8080${utilService.context()}/seqVariant/svlist/${origSv.seqSampleId}"
            }

            def issueSummary = "New CurVariant needs curation: ${var}"
            def valout =
                    """*Triggered by:* ${currentUser.getDisplayName()} ([~${currentUser.getEmail().split('@')[0]}]) ${currentUser.getEmail()}

            *New Curated Variant:* [${var}|${cvlink}]

            ||Gene||HGVSc||HGVSp||pmClass||Clinical Context||
            |${var.gene}|${var.hgvsc}|${var.hgvsp}|${(var.authorisedFlag && var.pmClass) ? var.pmClass : 'NotAuth'}|${var.clinContext?var.clinContext:'No context'}|

            *Created from:* [SeqSample ${origSv.seqSample}| ${sslink}] SeqVariant ${origSv.toString()}"""

            //  assemble "present in the following seqsamples"
            //

            def seqSs = var.allSeqSamples()

            def size = seqSs.size()
            def message = "This Curated Variant has too many related Sequenced Samples to show here.\nPlease use PathOS to view the *${size} Sequenced Samples*."

            if (size <= 20) {
                message = "It is present in the following Sequenced Samples:\n\n"
                seqSs.sort{ a, b -> b.sampleName <=> a.sampleName }.each {
                    //  CurateService is a service linked by loader and we cannot include Grails code in here, only groovy
                    //  we would use grailsLinkGenerator, but we can't, so we hard-code.
                    //  don't link if not on prod
                    String ssPresentLink = ''
                    if (env == "pa_prod" || true) {
                        ssPresentLink = "http://bioinf-pathos:8080${utilService.context()}/svlist/${it?.id}"
                    }
                    message = message + "* [${it.sampleName}|${ssPresentLink}]\n"
                }
            }
            valout = valout + """

            """

            valout = valout + message

            //
            //  ---

            if ( env != "pa_prod" )
            {
                issueSummary = "TEST! ${issueSummary}"
                valout = "TEST! NOT A REAL ISSUE!\n${valout}"
            }

            def response
            if ( env != "pa_local" ) {
                response = jnotifier.createJiraIssue(issueSummary, valout, "Task", "molpath")
            }

            if (response)
            {
                println response
                log.info("JIRA issue response: ")
                log.info(response)
                if (response.containsKey('errors')) {
                    log.info  "Error creating issue!"
                }

                if (response.containsKey('id') && response.containsKey('key'))
                {
                    log.info "Issue created. Issue ${response['id']} ${response['key']} "

                    int newIssueId = response['id'] as int
                    def jiraIssue = new JiraIssue(triggered_by: currentUser, issueType: 'new_variant', curVariant: var, issueIdentifier: response['key']).save()
                    
                }
            }

    }




}
