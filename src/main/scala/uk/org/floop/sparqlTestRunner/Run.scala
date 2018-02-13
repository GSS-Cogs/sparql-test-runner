package uk.org.floop.sparqlTestRunner

import java.io._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.apache.jena.query.{DatasetFactory, QueryExecutionFactory, QueryFactory, ResultSetFormatter}
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.riot.RDFDataMgr

import scala.io.Source
import scala.xml.{NodeSeq, PrettyPrinter}

case class Config(dir: File = new File("tests/sparql"),
                  report: File = new File("reports/TESTS-sparql-test-runner.xml"),
                  ignoreFail: Boolean = false,
                  data: Seq[File] = Seq())

object Run extends App {
  val packageVersion: String = getClass.getPackage.getImplementationVersion()
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
    arg[File]("<file>...") unbounded() required() action { (x, c) =>
      c.copy(data = c.data :+ x) } text "data to run the queries against"
  }
  parser.parse(args, Config()) match {
    case Some(config) =>
      val dataset = DatasetFactory.create
      for (d <- config.data) {
        RDFDataMgr.read(dataset, d.toString)
      }
      val union = ModelFactory.createDefaultModel
      union.add(dataset.getDefaultModel)
      union.add(dataset.getUnionModel)
      dataset.close()
      val (error, results) = runTestsUnder(config.dir, union, config.dir.toPath)
      for (dir <- Option(config.report.getParentFile)) {
        dir.mkdirs
      }
      val pp = new PrettyPrinter(80, 2)
      val pw = new PrintWriter(config.report)
      pw.write(pp.format(<testsuites>{results}</testsuites>))
      pw.close
      System.exit((error && !config.ignoreFail) match {
        case true => 1
        case false => 0
      })
    case None =>
  }

  def runTestsUnder(dir: File, model: Model, root: Path): (Boolean, NodeSeq) = {
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
        val (error, subSuites) = runTestsUnder(f, model, root)
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
        val query = QueryFactory.create(new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8))
        val exec = QueryExecutionFactory.create(query, model)
        if (query.isSelectType) {
          var results = exec.execSelect()
          var nonEmptyResults = results.hasNext()
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
                  System.err.println(s"Testcase $comment\nExpected:\n${expectedResults}\nActual:\n${actualResults}")
                    <error message={"Expected: \n" + expectedResults + "\nGot: \n" + actualResults}/>
                }
              } else {
                // assume there should be no results
                if (nonEmptyResults) {
                  errors += 1
                  System.err.println(s"Testcase $comment\nExpected empty result set, got:\n${actualResults}")
                    <failure message={s"Expected empty result set, got:\n${actualResults}"}/>
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
      }
    }
    if (errors > 0) {
      overallError = true
    }
    val testSuiteTime = (System.currentTimeMillis() - timeSuiteStart - subSuiteTimes).toFloat / 1000
    val suiteName = {
      val relativeName = root.getParent.relativize(dir.toPath).toString
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
