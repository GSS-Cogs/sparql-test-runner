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

import org.apache.http.HttpHeaders
import org.apache.http.auth.{AuthScope, UsernamePasswordCredentials}
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.client.utils.URIUtils
import org.apache.http.impl.auth.BasicScheme
import org.apache.http.impl.client.{BasicAuthCache, BasicCredentialsProvider, HttpClients}
import org.apache.http.message.BasicHeader
import org.apache.jena.query._
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.riot.system.RiotLib
import org.apache.jena.sparql.engine.http.{QueryEngineHTTP, QueryExceptionHTTP}
import org.apache.jena.sparql.mgt.Explain
import scopt.OptionParser

import java.io._
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util
import scala.io.Source
import scala.util.Using
import scala.xml.{NodeSeq, PCData, XML}

case class Config(dirs: List[File] = List.empty,
                  report: File = new File("reports/TESTS-sparql-test-runner.xml"),
                  ignoreError: Boolean = false, endpoint: Option[URI] = None, auth: Option[Either[String, String]] = None,
                  params: Map[String, String] = Map.empty,
                  froms: List[String] = List.empty,
                  limit: Option[Int] = None,
                  data: Seq[File] = Seq())

object SparqlTestRunner {
  val packageVersion: String = getClass.getPackage.getImplementationVersion
  val defaultDir = new File("tests/sparql")
  val parser: OptionParser[Config] = new scopt.OptionParser[Config]("sparql-testrunner") {
    head("sparql-test-runner", packageVersion)
    opt[File]('t', "testdir")
      .optional()
      .unbounded()
      .valueName("<dir>")
      .action((x, c) => c.copy(dirs = c.dirs :+ x))
      .text("location of SPARQL queries to run, defaults to tests/sparql, can be specified multiple times")
    opt[File]('r', "report")
      .optional()
      .valueName("<report>")
      .action((x, c) => c.copy(report = x))
      .text("file to output XML test report, defaults to reports/TESTS-sparql-test-runner.xml")
    opt[Unit]('i', "ignoreerror")
      .optional()
      .action((_, c) => c.copy(ignoreError = true))
      .text("errors (exceptions) are recorded, but don't change the exit status")
    opt[URI]('s', name = "service")
      .optional()
      .valueName("<HTTP URI>")
      .action((x, c) => c.copy(endpoint = Some(x)))
      .text("SPARQL endpoint to run the queries against")
    opt[String]('a', "auth")
      .optional()
      .valueName("<user:pass>")
      .action((x, c) => c.copy(auth = Some(Left(x))))
      .text("basic authentication username:password")
    opt[String]('k', "token")
      .optional()
      .valueName("<user:pass>")
      .action((x, c) => c.copy(auth = Some(Right(x))))
      .text("oAuth token")
    opt[Map[String, String]]('p', name = "param")
      .optional()
      .valueName("l=\"somelabel\"@en,n=<some-uri>")
      .action((x, c) => c.copy(params = x))
      .text("variables to replace in query")
    opt[String]('f', name = "from")
      .optional()
      .unbounded()
      .valueName("<some-uri>")
      .action((x, c) => c.copy(froms = c.froms :+ x))
      .text("graphs to query")
    opt[Int]('l', name = "limit")
      .optional()
      .valueName("<max>")
      .action((x, c) => c.copy(limit = Some(x)))
      .text("limit the number of results (examples of failure) to return")
    arg[File]("<file>...")
      .unbounded()
      .optional()
      .action((x, c) => c.copy(data = c.data :+ x))
      .text("data to run the queries against")
    checkConfig(c => {
      val badDirs = for {
        d <- c.dirs match {
          case Nil => List(defaultDir)
          case _ => c.dirs
        }
        if !d.exists || !d.isDirectory
      } yield d
      badDirs match {
        case List(d) =>
          failure("Tests directory (" + d.toString + ") doesn't exist or isn't a directory.")
        case List(_, _*) =>
          failure("Test directories (" + badDirs.map(_.toString).mkString(", ") + ") don't exist or are not directories.")
        case Nil =>
          if (c.endpoint.isDefined && c.data.nonEmpty) {
            failure("Specify either a SPARQL endpoint or data files, not both.")
          } else if (c.endpoint.isEmpty && c.data.isEmpty) {
            failure("Must specify either a SPARQL endpoint or some data files.")
          } else success
      }
    })
  }

  def parseArgs(args: Array[String]): (Query => QueryExecution, Config, Seq[File]) = {
    parser.parse(args, Config()) match {
      case Some(config) =>
        val queryExecution: Query => QueryExecution = {
          val exec = config.endpoint match {
            case Some(uri) =>
              // Querying a remote endpoint; if authentication is required, need to set up pre-emptive auth,
              // see https://hc.apache.org/httpcomponents-client-ga/tutorial/html/authentication.html
              config.auth match {
                case Some(Left(userpass)) =>
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
                case Some(Right(token)) =>
                  val authHeader = new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                  val headers = new util.ArrayList[BasicHeader]
                  headers.add(authHeader)
                  val client = HttpClients.custom.setDefaultHeaders(headers).build()
                  (query: Query) => QueryExecutionFactory.sparqlService(uri.toString, query, client)
                case None =>
                  (query: Query) => QueryExecutionFactory.sparqlService(uri.toString, query)
              }
            case None =>
              val dataset = DatasetFactory.create
              for (d <- config.data) {
                println(s"Loading $d")
                RDFDataMgr.read(dataset, d.toString)
              }
              (query: Query) => QueryExecutionFactory.create(query, dataset)
          }
          // if this is an HTTP executor, then we can add the FROM graphs as part of the protocol
          exec match {
            case httpExec: QueryEngineHTTP =>
              for (g <- config.froms) {
                httpExec.addDefaultGraph(g)
              }
              httpExec
            case other => other
          }
        }
        (queryExecution, config, config.dirs match {
          case Nil => List(defaultDir)
          case _ => config.dirs
        })
    }
  }

  def main(args: Array[String]) {
    val (queryExecution, config, testDirs) = parseArgs(args)
    val timeSuiteStart = System.currentTimeMillis()
    val (errors, failures, tests, results) = testDirs.foldLeft[(Int, Int, Int, NodeSeq)](0, 0, 0, Nil) { (a, d) =>
      val (e2, f2, t2, r2) = runTestsUnder(d, config.params, config.limit, queryExecution, d.toPath)
      (a._1 + e2, a._2 + f2, a._3 + t2, a._4 ++ r2)
    }
    for (dir <- Option(config.report.getParentFile)) {
      dir.mkdirs
    }
    XML.save(config.report.getPath,
      <testsuites
      errors={errors.toString}
      failures={failures.toString}
      tests={tests.toString}
      time={((System.currentTimeMillis() - timeSuiteStart).toFloat / 1000).toString}>
        {results}
      </testsuites>,
      enc = "UTF-8", xmlDecl = true)
    System.exit(if ((!config.ignoreError && (errors > 0)) || (failures > 0)) 1 else 0)
  }

  def runTestsUnder(dir: File, params: Map[String, String], limit: Option[Int],
                    queryExecution: Query => QueryExecution, root: Path): (Int, Int, Int, NodeSeq) = {
    var testSuites = NodeSeq.Empty
    var testCases = NodeSeq.Empty
    var errors = 0
    var failures = 0
    var skipped = 0
    var tests = 0
    val timeSuiteStart = System.currentTimeMillis()
    for (f <- dir.listFiles) yield {
      if (f.isDirectory) {
        val (subErrors, subFailures, subTests, subSuites) = runTestsUnder(f, params, limit, queryExecution, root)
        testSuites ++= subSuites
        errors += subErrors
        failures += subFailures
        tests += subTests
      } else if (f.isFile && f.getName.endsWith(".sparql")) {
        val timeTestStart = System.currentTimeMillis()
        val relativePath = root.relativize(f.toPath).toString
        val className = relativePath
          .substring(0, relativePath.lastIndexOf('.'))
          .replace(File.pathSeparatorChar, '.')
        val comment = {
          Using.resource(Source.fromFile(f)) { src =>
            src.getLines().nextOption() match {
              case Some(line) =>
                if (line.startsWith("# "))
                  line.substring(2)
                else
                  className
              case None => className
            }
          }
        }
        tests += 1
        val rawQuery = new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
        val query = if (params.isEmpty) {
          QueryFactory.create(rawQuery)
        } else {
          val parameterizedSparqlString = new ParameterizedSparqlString(rawQuery)
          for ((k, v) <- params) {
            parameterizedSparqlString.setParam(k, RiotLib.parse(v))
          }
          parameterizedSparqlString.asQuery
        }
        if (query.isSelectType) {
          limit.foreach(n => query.setLimit(n))
        }
        val exec = queryExecution(query)
        System.out.println(s"Running query $f")
        try {
          if (query.isSelectType) {
            val results = exec.execSelect()
            val nonEmptyResults = results.hasNext
            val timeTaken = (System.currentTimeMillis() - timeTestStart).toFloat / 1000
            testCases = testCases ++ <testcase name={comment} classname={className} time={timeTaken.toString}>
              {val out = new ByteArrayOutputStream
              ResultSetFormatter.outputAsCSV(out, results)
              val actualResults = out.toString("utf-8")
              val expect = new File(f.getPath.substring(0, f.getPath.lastIndexOf('.')) + ".expected")
              if (expect.exists && expect.isFile) {
                val expectedResults = new String(Files.readAllBytes(expect.toPath), StandardCharsets.UTF_8)
                if (actualResults != expectedResults) {
                  failures += 1
                  System.err.println(s"Testcase $comment\nExpected:\n$expectedResults\nActual:\n$actualResults")
                  <failure message="Constraint violated">
                    {PCData(s"Expected:\n$expectedResults\nGot:\n$actualResults")}
                  </failure>
                }
              } else {
                // assume there should be no results
                if (nonEmptyResults) {
                  failures += 1
                  System.err.println(s"Testcase $comment\nExpected empty result set, got:\n$actualResults")
                  <failure message="Constraint violated">
                    {PCData(s"Expected empty result set, got:\n$actualResults")}
                  </failure>
                }
              }}
            </testcase>
          } else if (query.isAskType) {
            val result = exec.execAsk()
            val timeTaken = (System.currentTimeMillis() - timeTestStart).toFloat / 1000
            testCases = testCases ++ <testcase name={comment} classname={className} time={timeTaken.toString}>
              {if (result) {
                failures += 1
                System.err.println(s"Testcase $comment\nExpected ASK query to return FALSE")
                <failure message={"Constraint violated"}>Expected ASK query to return FALSE</failure>
              }}
            </testcase>
          } else {
            skipped += 1
            System.out.println(s"Skipped testcase $comment")
            testCases = testCases ++ <testcase name={comment} classname={className}>
              <skipped/>
            </testcase>
          }
        } catch {
          case httpException: QueryExceptionHTTP =>
            testCases = testCases ++
              <testcase name={comment} classname={className}
                        time={((System.currentTimeMillis() - timeTestStart).toFloat / 1000).toString}>
                <error message={s"Exception running query: ${httpException.getMessage}; ${httpException.getResponse}"}/>
              </testcase>
            errors += 1
          case e: Exception =>
            testCases = testCases ++
              <testcase name={comment} classname={className}
                        time={((System.currentTimeMillis() - timeTestStart).toFloat / 1000).toString}>
                <error message={"Exception running query: " + e.getMessage}>
                  {PCData({
                  val sw = new StringWriter()
                  val pw = new PrintWriter(sw)
                  e.printStackTrace(pw)
                  sw.toString
                })}
                </error>
              </testcase>
            errors += 1
        }
      }
    }
    val testSuiteTime = (System.currentTimeMillis() - timeSuiteStart).toFloat / 1000
    val suiteName = {
      val relativeName = root.toAbsolutePath.getParent.relativize(dir.toPath.toAbsolutePath).toString
      if (relativeName.isEmpty) {
        "root"
      } else {
        relativeName
      }
    }
    (errors, failures, tests, testSuites ++
      <testsuite errors={errors.toString}
                 failures={failures.toString}
                 tests={tests.toString}
                 time={testSuiteTime.toString}
                 name={suiteName}
                 skipped={skipped.toString}>
        {testCases}
      </testsuite>)
  }
}