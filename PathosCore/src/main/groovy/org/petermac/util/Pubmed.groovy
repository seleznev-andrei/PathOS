/*
 * Copyright (c) 2015. PathOS Variant Curation System. All rights reserved.
 *
 * Organisation: Peter MacCallum Cancer Centre
 * Author: doig ken
 */



package org.petermac.util

import groovy.json.JsonSlurper
import groovy.util.logging.Log4j
import org.apache.commons.lang.StringUtils
import org.apache.log4j.Level
import org.apache.log4j.Logger

/**
 * Pubmed REST API utilities
 *
 * Description:
 *
 * Methods to retrieve components of a Pubmed entry or
 * search for references
 *
 * Author:  Ken Doig
 * Date:    28-Oct-2015
 */

@Log4j
class Pubmed
{
    static JsonSlurper slurper = new JsonSlurper()

    // NCBI API Key, this one belongs to DKGM.
    // Visit https://www.ncbi.nlm.nih.gov/account/ to generate a new one.
    // See: https://www.ncbi.nlm.nih.gov/books/NBK25497/ for more info.
    private static final def api_key = '222700615c07e3765fdd531360ec7a2a5108'
    private static final def pmidURL = 'https://eutils.ncbi.nlm.nih.gov/entrez/eutils/'

    /**
     * Main execution thread
     *
     * @param args  CLI arguments
     */
    static void main(args)
    {
        def cli = new CliBuilder(
                usage: 'Pubmed [options] pmid.in out.tsv',
                header: '\nAvailable options (use -h for help):\n',
                footer: '\nBulk upload a list of pubmed IDs into Pubmed table\n')

        cli.with
                {
                    h(longOpt:  'help',    'Usage Information',    required: false)
                    d(longOpt:  'debug',   'turn on debugging' )
                }

        def opt = cli.parse(args)

        if ( ! opt ) return
        List argin = opt.arguments()
        if ( opt.h || argin.size() != 2)
        {
            cli.usage()
            return
        }

        //  Debugging needed
        //
        if ( opt.debug ) Logger.getRootLogger().setLevel(Level.DEBUG)
        log.debug( "Debugging turned on!" )

        //  Run the program
        //
        log.info("Pubmed " + args )

        // Validate the file
        //
        def inf = new File( argin[0] as String )
        if( ! inf.exists())
            throw new RuntimeException('ERROR in [FILE]:' + inf + ' does not exist !')

        def outf = new File( argin[1] as String )
        outf.delete()

        def pmids = inf.readLines()
        log.info( "Found ${pmids.size()} pmids in ${inf}")

        if ( ! ping())
        {
            log.fatal( "Couldn't contact pubmed server at ${pmidURL}")
            return
        }

        String header = "pmid\tdoi\tdate\tjournal\tabbreviation\tvolume\tissue\tpages\ttitle\tauthors\taffiliations\tabstract\tkeywords\n"
        int nart = 0

        for ( pmid in pmids )
        {
            Map m = fetchArticle( pmid )

            if ( ! m )
            {
                log.warn( "No record for pmid=${pmid}")
                continue
            }

            if ( header )
            {
                outf << header
                header = null
            }

            List article = []
            article << m.pmid
            article << m.doi
            article << m.date
            article << m.journal
            article << m.abbreviation
            article << m.volume
            article << m.issue
            article << m.pages
            article << m.title
            article << (m.authors?.name)?.join(',')
            article << (m.authors?.affiliation)?.join(',')
            article << m.abstract
            article << m.keywords?.join(',')

            outf << article.join('\t') + '\n'

            if ((++nart % 10) == 0 ) log.info( "Processed ${nart} lines")
        }

        log.info("Done, processed ${nart} lines")
    }

    /**
     * Check server is up
     * To test use % curl 'https://eutils.ncbi.nlm.nih.gov/entrez/eutils/einfo.fcgi?retmode=json'
     *
     * @return      true if server available
     */
    static Boolean ping()
    {
        def url = pmidURL + "einfo.fcgi?retmode=json&api_key=${api_key}"
        def res
        try
        {
            def ret = url.toURL()
            res = ret.text
        }

        catch( Exception ex )
        {
            log.fatal( "Exception when trying to connect to NCBI: " + ex )
            return false
        }

        //  Unpack return JSON
        //
        res = slurper.parseText(res)

        log.debug("result=${res}")

        return res.header.type == 'einfo'
    }

    /**
     * Retrieve article details for a pubmed ID
     *
     * @param   id  ID to lookup, optionally prefixed by 'pmid:'
     * @return      Map of article [id:, authors:, title:, abstract:, journal: ]
     */
    static Map fetchArticle( String id )
    {
        //  Strip off optional pmid:
        //
        if ( id.toLowerCase().startsWith('pmid:'))
        {
            id = id[5..-1]
        }

        //  Construct URL returning XML
        //
        def url = pmidURL + "efetch.fcgi?api_key=${api_key}&db=pubmed&retmode=xml&rettype=abstract&id=${id}"
        def res = getUrl( url )
        if ( ! res ) return [:]

        //  Unpack return XML
        //
        def parser = new XmlParser()
        parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
        parser.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Node node = parser.parseText( res )

        //  Check if we got anything
        //
        def articles = node.children()
        if ( articles.size() < 1 ) return [:]
        def article = articles[0]

        //  Find DOI
        //
        def ids = article.PubmedData.ArticleIdList.ArticleId
        def doi = ids.find{ Node it -> it.'@IdType' == 'doi' }?.text()

        //  Find Pubmed ID
        //
        def pmid = article.MedlineCitation.PMID.text()
        assert pmid == id

        //  Find Journal
        //
        def journal = article.MedlineCitation.Article.Journal.Title.text()

        //  Find Journal Abbreviation
        def abbreviation = article.MedlineCitation.Article.Journal.ISOAbbreviation.text()

        //  Find Title
        //
        def title = article.MedlineCitation.Article.ArticleTitle.text()

        //  Find Page Nos
        //
        def pages = article.MedlineCitation.Article.Pagination.MedlinePgn.text()

        //  Volume
        //
        def volume = article.MedlineCitation.Article.Journal.JournalIssue.Volume.text()

        //  Issue
        //
        def issue = article.MedlineCitation.Article.Journal.JournalIssue.Issue.text()

        //  Find Abstract
        //
        def abstrct = article.MedlineCitation.Article.Abstract.AbstractText.text()

        //  Get Author List
        //
        def authors = article.MedlineCitation.Article.AuthorList.Author
        List authorList = []
        for ( author in authors )
        {
            Map authorMap = [:]
            authorMap << [name: "${author.LastName.text()} ${author.Initials.text()}"]
            authorMap << [affiliation: "${author.AffiliationInfo.Affiliation.text()}"]
            authorList << authorMap
        }

        //  Get Date
        //
        String date = null

        try {
            String year = article?.MedlineCitation?.Article?.Journal?.JournalIssue?.PubDate?.Year?.text()
            String month = article?.MedlineCitation?.Article?.Journal?.JournalIssue?.PubDate?.Month?.text()
            String day = article?.MedlineCitation?.Article?.Journal?.JournalIssue?.PubDate?.Day?.text()

            if(day) {
                if (StringUtils.isNumeric(month)) {
                    date = new Date().parse("yyyy-MM-dd", "${year}-${month}-${day}").format("yyyy-MM-dd")
                } else {
                    date = new Date().parse("yyyy-MMM-dd", "${year}-${month}-${day}").format("yyyy-MM-dd")
                }
            } else if (month) {
                if (StringUtils.isNumeric(month)) {
                    date = new Date().parse("yyyy-MM", "${year}-${month}").format("yyyy-MM-dd")
                } else {
                    date = new Date().parse("yyyy-MMM", "${year}-${month}").format("yyyy-MM-dd")
                }
            } else if (year) {
                date = new Date().parse("yyyy", "${year}").format("yyyy-MM-dd")
            }
        } catch (e) {
            log.error(e)
        }

        //  Get Keyword list
        //
        def chemicals = article.MedlineCitation.ChemicalList.Chemical
        ArrayList keywords = []
        for ( chemical in chemicals )
        {
            keywords << chemical.NameOfSubstance.text()
        }

        def meshs = article.MedlineCitation.MeshHeadingList.MeshHeading
        for ( mesh in meshs )
        {
            keywords << mesh.DescriptorName.text()
        }

        return [    pmid: id, authors: authorList , title: title ,
                    abstract: abstrct, volume: volume, issue: issue,
                    journal: journal, abbreviation: abbreviation, pages: pages,
                    doi: doi, date: date, keywords: keywords.unique() ]
    }

    static String getUrl( String url )
    {
        try
        {
            def ret = url.toURL()
            return ret.text
        }
        catch( Exception ex )
        {
            log.warn( "Exception when retrieving URL[${url}]: " + ex )
            return null
        }

        return null
    }
}
