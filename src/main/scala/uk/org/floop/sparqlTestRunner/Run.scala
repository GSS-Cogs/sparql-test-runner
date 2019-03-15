/*
 * Copyright 2018 Alex Tucker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.org.floop.sparqlTestRunner

import java.io._
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.apache.http.auth.{AuthScope, UsernamePasswordCredentials}
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.client.utils.URIUtils
import org.apache.http.impl.auth.BasicScheme
import org.apache.http.impl.client.{BasicAuthCache, BasicCredentialsProvider, HttpClients}
import org.apache.jena.query._
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.riot.system.RiotLib

import scala.io.Source
import scala.xml.{NodeSeq, PrettyPrinter}

case class Config(dir: File = new File("tests/sparql"),
                  report: File = new File("reports/TESTS-sparql-test-runner.xml"),
                  ignoreFail: Boolean = false, endpoint: Option[URI] = None, auth: Option[String] = None,
                  params: Map[String, String] = Map.empty, data: Seq[File] = Seq())

object Run extends App {
  val packageVersion: String = getClass.getPackage.getImplementationVersion
  val parser = new scopt.OptionParser[Config]("sparql-testrunner") {
    head("sparql-testrunner", packageVersion)
    opt[File]('t', "testdir") optional() valueName "<dir>" action { (x, c) =>
      c.copy(dir = x)
    } text "location of SPARQL queries to run, defaults to tests/sparql"
    opt[File]('r', "report") optional() valueName "<report>" action { (x, c) =>
      c.copy(report = x)
    } text "file to output XML test report, defaults to reports/TESTS-sparql-test-runner.xml"
    opt[Unit]('i', "ignorefail") optional() action { (_, c) =>
      c.copy(ignoreFail = true)
    } text "exit with success even if there are reported failures"
    opt[URI]('s', name= "service") optional() valueName "<HTTP URI>" action { (x, c) =>
      c.copy(endpoint = Some(x))
    } text "SPARQL endpoint to run the queries against"
    opt[String]('a', "auth") optional() valueName "<user:pass>" action { (x, c) =>
      c.copy(auth = Some(x))
    } text "basic authentication username:password"
    opt[Map[String,String]]('p', name="param") optional() valueName "l=\"somelabel\"@en,n=<some-uri>" action {
      (x, c) => c.copy(params = x)
    } text "variables to replace in query"
    arg[File]("<file>...") unbounded() optional() action { (x, c) =>
      c.copy(data = c.data :+ x) } text "data to run the queries against"
    checkConfig( c =>
      if (!c.dir.exists || !c.dir.isDirectory) {
        failure("Tests directory (" + c.dir.toString + ") doesn't exist or isn't a directory.")
      } else if (c.endpoint.isDefined && c.data.nonEmpty) {
        failure("Specify either a SPARQL endpoint or data files, not both.")
      } else if (c.endpoint.isEmpty && c.data.isEmpty) {
        failure("Must specify either a SPARQL endpoint or some data files.")
      } else success
    )
  }
  parser.parse(args, Config()) match {
    case Some(config) =>
      val queryExecution: Query => QueryExecution = config.endpoint match {
        case Some(uri) =>
          // Querying a remote endpoint; if authentication is required, need to set up pre-emptive auth,
          // see https://hc.apache.org/httpcomponents-client-ga/tutorial/html/authentication.html
          config.auth match {
            case Some(userpass) =>
              val target = URIUtils.extractHost(uri) // new HttpHost(uri.getHost, uri.getPort)
              val credsProvider = new BasicCredentialsProvider()
              credsProvider.setCredentials(
                new AuthScope(target.getHostName, target.getPort),
                new UsernamePasswordCredentials(userpass))
              val authCache = new BasicAuthCache()
              authCache.put(target, new BasicScheme())
              val context = HttpClientContext.create()
              context.setCredentialsProvider(credsProvider)
              context.setAuthCache(authCache)
              val client = HttpClients.custom.build()
              (query: Query) => QueryExecutionFactory.sparqlService(uri.toString, query, client, context)
            case None =>
              (query: Query) => QueryExecutionFactory.sparqlService(uri.toString, query)
          }
        case None =>
          val dataset = DatasetFactory.create
          for (d <- config.data) {
            RDFDataMgr.read(dataset, d.toString)
          }
          val union = ModelFactory.createDefaultModel
          union.add(dataset.getDefaultModel)
          union.add(dataset.getUnionModel)
          dataset.close()
          (query: Query) => QueryExecutionFactory.create(query, union)
      }
      val (error, results) = runTestsUnder(config.dir, config.params, queryExecution, config.dir.toPath)
      for (dir <- Option(config.report.getParentFile)) {
        dir.mkdirs
      }
      val pp = new PrettyPrinter(80, 2)
      val pw = new PrintWriter(config.report)
      pw.write(pp.format(<testsuites>{results}</testsuites>))
      pw.close()
      System.exit(if (error && !config.ignoreFail) 1 else 0)
    case None =>
  }

  def runTestsUnder(dir: File, params: Map[String, String],
                    queryExecution: Query => QueryExecution, root: Path): (Boolean, NodeSeq) = {
    var testSuites = NodeSeq.Empty
    var testCases = NodeSeq.Empty
    var overallError = false
    var errors = 0
    var skipped = 0
    var tests = 0
    val timeSuiteStart = System.currentTimeMillis()
    var subSuiteTimes = 0L
    for (f <- dir.listFiles) yield {
      if (f.isDirectory) {
        val subSuiteStart = System.currentTimeMillis()
        val (error, subSuites) = runTestsUnder(f, params, queryExecution, root)
        testSuites ++= subSuites
        overallError |= error
        subSuiteTimes += (System.currentTimeMillis() - subSuiteStart)
      } else if (f.isFile && f.getName.endsWith(".sparql")) {
        val timeTestStart = System.currentTimeMillis()
        val relativePath = root.relativize(f.toPath).toString
        val className = relativePath.substring(0, relativePath.lastIndexOf('.')).replace(File.pathSeparatorChar, '.')
        val comment = {
          val queryLines = Source.fromFile(f).getLines()
          if (queryLines.hasNext) {
            val line = queryLines.next()
            if (line.startsWith("# "))
              line.substring(2)
            else
              line
          } else
            className
        }
        tests += 1
        val rawQuery = new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
        val query = if (params.isEmpty) {
          QueryFactory.create(rawQuery)
        } else {
          val parameterizedSparqlString = new ParameterizedSparqlString(rawQuery)
          for ( (k, v) <- params ) {
            parameterizedSparqlString.setParam(k, RiotLib.parse(v))
          }
          parameterizedSparqlString.asQuery
        }
        val exec = queryExecution(query)
        try {
          if (query.isSelectType) {
            var results = exec.execSelect()
            val nonEmptyResults = results.hasNext
            val timeTaken = (System.currentTimeMillis() - timeTestStart).toFloat / 1000
            testCases = testCases ++ <testcase name={comment} class={className} time={timeTaken.toString}>
              {
                val out = new ByteArrayOutputStream
                ResultSetFormatter.outputAsCSV(out, results)
                val actualResults = out.toString("utf-8")
                val expect = new File(f.getPath.substring(0, f.getPath.lastIndexOf('.')) + ".expected")
                if (expect.exists && expect.isFile) {
                  val expectedResults = new String(Files.readAllBytes(expect.toPath), StandardCharsets.UTF_8)
                  if (actualResults != expectedResults) {
                    errors += 1
                    System.err.println(s"Testcase $comment\nExpected:\n$expectedResults\nActual:\n$actualResults")
                      <error message={"Expected: \n" + expectedResults + "\nGot: \n" + actualResults}/>
                  }
                } else {
                  // assume there should be no results
                  if (nonEmptyResults) {
                    errors += 1
                    System.err.println(s"Testcase $comment\nExpected empty result set, got:\n$actualResults")
                      <failure message={s"Expected empty result set, got:\n$actualResults"}/>
                  }
                }
              }
            </testcase>
          } else if (query.isAskType) {
            val result = exec.execAsk()
            val timeTaken = (System.currentTimeMillis() - timeTestStart).toFloat / 1000
            testCases = testCases ++ <testcase name={comment} class={className} time={timeTaken.toString}>{
              if (result) {
                errors += 1
                System.err.println(s"Testcase $comment\nExpected ASK query to return FALSE")
                <failure message={"Constraint violated"}/>
              }}</testcase>
          } else {
            skipped += 1
            System.out.println(s"Skipped testcase $comment")
            testCases = testCases ++ <testcase name={comment} class={className}>
              <skipped/>
            </testcase>
          }
        } catch {
          case e: Exception =>
            testCases = testCases ++
              <testcase name={comment} class={className}
                        time={((System.currentTimeMillis() - timeTestStart).toFloat / 1000).toString}>
                <error message={"Exception running query: " + e.getMessage}/>
            </testcase>
        }
      }
    }
    if (errors > 0) {
      overallError = true
    }
    val testSuiteTime = (System.currentTimeMillis() - timeSuiteStart - subSuiteTimes).toFloat / 1000
    val suiteName = {
      val relativeName = root.toAbsolutePath.getParent.relativize(dir.toPath.toAbsolutePath).toString
      if (relativeName.length == 0) {
        "root"
      } else {
        relativeName
      }
    }
    (overallError, testSuites ++ <testsuite errors={errors.toString} tests={tests.toString} time={testSuiteTime.toString}
                             name={suiteName} skipped={skipped.toString}>
      {testCases}
    </testsuite>)
  }
}
