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

package uk.org.floop.updateInPlace

import java.io.{File, FileOutputStream, FileWriter}
import java.nio.file.{Files, Path}

import org.apache.jena.query.{Dataset, DatasetFactory, QueryExecutionFactory, QueryFactory}
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.riot.{Lang, RDFDataMgr, RDFLanguages}
import org.apache.jena.update.UpdateAction

import scala.io.Source

case class Config(dir: File = new File("sparql"),
                  data: File = null)

object Run extends App {

  val packageVersion: String = getClass.getPackage.getImplementationVersion()
  val parser = new scopt.OptionParser[Config]("sparql-update-in-place") {
    head("sparql-update-in-place", packageVersion)
    opt[File]('q', "querydir") optional() valueName "<dir>" action { (x, c) =>
      c.copy(dir = x)
    } text "location of SPARQL update queries to run, defaults to sparql"
    arg[File]("<file>") required() action { (x, c) =>
      c.copy(data = x) } text "data to update in-place"
  }
  parser.parse(args, Config()) match {
    case None =>
    case Some(config) =>
      val dataset = DatasetFactory.create
      RDFDataMgr.read(dataset, config.data.toString)
      runQueriesUnder(config.dir, dataset, config.data)
      val out = new FileOutputStream(config.data)
      RDFDataMgr.write(out, dataset.getDefaultModel, RDFLanguages.filenameToLang(config.data.toString))
      out.close
  }

  def runQueriesUnder(dir: File, dataset: Dataset, out: File): Unit = {
    Files.walk(dir.toPath()).
      filter(Files.isRegularFile(_)).
      sorted((p1, p2) => p1.compareTo((p2))).
      forEach { p =>
        UpdateAction.parseExecute(Source.fromFile(p.toFile).mkString, dataset)
      }
  }


}
