package uk.org.floop

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._
import uk.org.floop.sparqlTestRunner.SparqlTestRunner.{parseArgs, runTestsUnder}

import java.nio.file.Paths

class ProtocolTests extends AnyFlatSpec with BeforeAndAfterEach {
  private val wireMockServer = new WireMockServer(wireMockConfig().dynamicPort())

  override def beforeEach {
    wireMockServer.start()
  }

  override def afterEach {
    wireMockServer.stop()
  }

  "sparql-test-runner" should "cope with remote failure" in {
    wireMockServer.stubFor(
      get(urlPathMatching("/sparql.*"))
        .willReturn(aResponse()
          .withBody("Server exploded")
          .withStatus(503)))

    val testDir = Paths.get(this.getClass.getResource("examples").toURI)
    val (queryExecution, config, testDirs) = parseArgs(Array(
      "-t", testDir.toString,
      "-s", s"http://localhost:${wireMockServer.port()}/sparql"
    ))
    val (errors, failures, tests, results) = runTestsUnder(
      testDir.toFile, config.params, config.limit, queryExecution, testDir)
    wireMockServer.verify(
      getRequestedFor(urlPathMatching("/sparql.*"))
    )
    assert(errors == 1)
    assert(failures == 0)
    assert(tests == 1)
    val error = results \ "testcase" \ "error"
    error.text should include ("exploded")
  }
}