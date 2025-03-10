/*
 * Copyright (c) 2015. PathOS Variant Curation System. All rights reserved.
 *
 * Organisation: Peter MacCallum Cancer Centre
 * Author: doig ken
 */


//
//	GenSeqrun.groovy
//
//	Create a sample list for pipeline processing
//
//  Take a legacy LIMS_<seqrun>.xml file and generate a sample list as input to a bpipe pipeline
//
//	01	kdoig	23-Ocy-2015     Initial build
//

package org.petermac.pathos.pipeline

import groovy.io.FileVisitResult
import groovy.util.logging.Log4j
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.petermac.util.Locator

@Log4j
class GenSeqrun
{
    static def loc = Locator.instance      // Locator class for file locations

    static void main( args )
	{
		def cli = new CliBuilder(   usage: 'GenSeqrun [options]',
                                    header: '\nAvailable options (use -h for help):\n',
                                    footer: '\nGenerate a list of samples for pipeline processing\n')
		
		//	CLI options
        //
        cli.with
        {
            h(  longOpt: 'help',     'this help message' )
            r(  longOpt: 'seqrun',   args: 1, 'seqrun file to process' )
            o(  longOpt: 'output',   args: 1, 'output file [Seqrun.tsv]' )
            s(  longOpt: 'sample',   args: 1, 'single sample to process' )
            l(  longOpt: 'limsxml',  args: 1, 'lims XML file to process [/pipeline/Runs/*/<seqrun>/LIMS_<seqrun>.xml]' )
            p(  longOpt: 'pipeline', args: 1, 'name of pipeline to run [mp_Amplicon]' )
            b(  longOpt: 'base',     args: 1, 'base directory for pipeline output [/pathology/NGS/Samples/Testing]' )
            d(  longOpt: 'debug',    'Turn on debugging' )
        }

		def opt = cli.parse( args )
		if ( ! opt ) return 
		if ( opt.h )
        {
            cli.usage()
            return
        }

        //  Debug ?
        //
        if ( opt.debug ) Logger.getRootLogger().setLevel(Level.DEBUG)
        log.debug( "Debugging turned on!" )

        //  Load LIMS_<sr>.xml file
        //
        if ( ! opt.seqrun && ! opt.limsxml )
        {
            log.fatal( "Must supply either --seqrun or --limsxml option")
            return
        }

        String seqrun = opt.seqrun ?: null
        File lf = findLimsXml( seqrun, opt.limsxml ?: null )
        if ( ! lf ) return

		log.info( "Starting: GenSeqrun " + args )

        boolean ok = processSeqrun( seqrun, opt.sample ?: null, lf, opt.pipeline ?: 'mp_Amplicon', opt.base ?: '/pathology/NGS/Samples/Testing', opt.output ?: 'Seqrun.tsv'  )

        if ( ok )
            log.info( "Finished: GenSeqrun " )
	}

    /**
     * Generate a pipeline file from the supplied paramaters
     * @param seqrun
     * @param sample
     * @param limsxml
     * @param pipeline
     * @return
     */
    static boolean processSeqrun( String seqrun, String sample, File limsxml, String pipeline, String baseDir, String outfile )
    {
        File of = new File( outfile )
        of.delete()

        //  print TSV headers
        //
        List headers = []
        headers.addAll( 'seqrun sample panel pipeline pipein outdir'.split(' '))
        of << "##  Created by GenSeqrun.groovy\n##\n#"
        of << headers.join("\t") + "\n"

        //  parse the LIMS XML file
        //
        Map srm = new SeqrunLims().parseLims( limsxml.absolutePath )
        if ( ! srm ) return false
        if ( seqrun && seqrun != srm.seqrun )
        {
            log.warn( "Supplied seqrun ${seqrun} doesn't match LIMS_*.xml seqrun ${srm.seqrun}: Using ${seqrun}")
        }
        else
        {
            seqrun = srm.seqrun
        }

        //  Process all sequenced samples
        //
        List<Map> sams = srm.samples
        for ( sam in sams )
        {
            log.debug( "Processing sample ${sam.sample} ${sam.analysis}")

            if ( sample && sam.sample != sample ) continue
            if ( ! sam.analysis.startsWith("Pathology"))
            {
                log.warn( "Non Pathology analysis type: ${sam.analysis}")
            }

            //  Construct fields in order
            //
            List flds = []

            //  Set the usual directory of FASTQ files
            //
            String fastqDir = samplePath( limsxml.parentFile, sam.sample, "${baseDir}/${seqrun}/${sam.sample}/FASTQ")
            if ( pipeline == 'mp_vcfAmplicon' )
            {
                fastqDir = "${baseDir}/${seqrun}/${sam.sample}/${sam.sample}.vcf"
            }

            flds << seqrun                                  //  Seqrun
            flds << sam.sample                              //  Sample
            flds << sam.reference                           //  Panel
            flds << pipeline                                //  Pipeline name eg mp_dualAmplicon
            flds << fastqDir                                //  FASTQ file
            flds << "${baseDir}/${seqrun}/${sam.sample}"    //  Output data directory

            //  Output TSV row to dump file
            //
            of << flds.join("\t") + "\n"
        }

        return true
    }

    /**
     * Find the LIMS XML file for a seqrun
     *
     * @param seqrun    Seqrun to search for
     * @param limsxml   Optional LIMS file to use if supplied
     * @return          List of directories to process
     */
    static File findLimsXml( String seqrun, String limsxml )
    {
        File limsfile

        if ( limsxml )
        {
            limsfile = new File( limsxml )
            if ( limsfile.exists()) return limsfile

            log.warn( "LIMS XML file doesn't exist: ${limsxml}")
            return null
        }

        //  Loop through sequencing platforms
        //
        for ( pf in ['NextSeq','MiSeq','HiSeq'] )
        {
            //  loop through repositories
            //
            for ( repos in ['Runs','Archives','Testing'])
            {
                limsfile = new File( "/pipeline/${repos}/${pf}/${seqrun}/LIMS_${seqrun}.xml" )
                log.debug( "Checking for LIMS.xml in ${limsfile.absolutePath} ${limsfile.exists()}")
                if ( limsfile.exists()) return limsfile
            }
        }

        log.error( "LIMS XML file wasn't found for ${seqrun}")
        return null
    }

    /**
     * Find the full path to a sample's FASTQ files
     *
     * @param root     File of base directory for seqrun
     * @param sample   Sample dir to find
     * @return         Path to sample FASTQ files
     */
    static String samplePath( File root, String sample, String fastqDefaultDir )
    {
        String ret  = null

        if ( ! root )
        {
            return fastqDefaultDir
        }

        File base = new File( root, "ProjectFolders")
        if ( base.exists())
        {
            base.traverse
            {
                file ->
                if ( file.directory && (file.name == 'Sample_'+sample ))
                {
                    ret = file.absolutePath
                    FileVisitResult.TERMINATE
                }
                else
                    FileVisitResult.CONTINUE
            }
        }

        if ( ! ret )
        {
            log.error( "Sample ${sample} not found under ${root}")
            return fastqDefaultDir
        }

        return ret
    }

    /**
     * Extract full Seqrun details from LIMS meta data file
     *
     * @param seqrun   Name of seqrun
     * @return         Map of run meta data
     */
    static Map seqrunLimsMap( String seqrun )
    {
        File lf = findLimsXml( seqrun, null )

        //  If we have a valid LIMS file return a seqrun Map
        //
        if ( ! lf ) return [:]

        Map srm = SeqrunLims.parseLims( lf.absolutePath )

        //  Load in Illumina [Rr]unParameters details
        //
        String rpFile = "${lf.parent}/runParameters.xml"
        if ( seqrun =~ /.*_NS.*/ ) rpFile = "${lf.parent}/RunParameters.xml"   // Illumina changed the name for NextSeq !!

        //  Parse the file
        //
        Map runp = SeqrunLims.parseRunParameters( rpFile )
        if ( ! runp )
        {
            log.warn( "Couldn't parse RunParameters ${rpFile}")
            return srm
        }

        //  Add meta data to seqrun Map
        //
        srm.experiment = runp.experiment
        srm.scanner    = runp.scanner
        srm.readlen    = runp.readlan

        return srm
    }
}

