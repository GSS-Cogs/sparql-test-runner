import sbtassembly.AssemblyPlugin.defaultShellScript

ThisBuild / scalaVersion := "2.13.4"
ThisBuild / organizationName := "Alex Tucker"

lazy val sparqlTestRunner = (project in file("."))
  .enablePlugins(NativeImagePlugin, JavaAppPackaging)
  .settings(
    name := "sparql-test-runner",
    version := "1.4",
    Compile / mainClass := Some("uk.org.floop.sparqlTestRunner.SparqlTestRunner"),
    nativeImageOptions ++= List(
      "-H:+ReportExceptionStackTraces",
      "--no-fallback",
      "--allow-incomplete-classpath",
      "-H:ResourceConfigurationFiles=../../configs/resource-config.json",
      "-H:ReflectionConfigurationFiles=../../configs/reflect-config.json",
      "-H:JNIConfigurationFiles=../../configs/jni-config.json",
      "-H:DynamicProxyConfigurationFiles=../../configs/proxy-config.json",
      "-H:EnableURLProtocols=https"),
    libraryDependencies ++= Seq(
      "org.apache.jena" % "jena-arq" % "3.17.0",
      "org.apache.jena" % "jena-cmds" % "3.17.0",
      "com.github.scopt" %% "scopt" % "4.0.0",
      "org.scala-lang.modules" %% "scala-xml" % "1.3.0",
      "org.slf4j" % "slf4j-simple" % "1.7.30",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
      "xerces" % "xercesImpl" % "2.12.1",
      "org.scalactic" %% "scalactic" % "3.2.5" % Test,
      "org.scalatest" %% "scalatest" % "3.2.5" % Test,
      "org.scalatest" %% "scalatest-flatspec" % "3.2.5" % Test,
      "org.json4s" %% "json4s-native" % "3.6.11" % Test,
      "com.github.tomakehurst" % "wiremock-jre8" % "2.27.2" % Test
    ),
    assemblyMergeStrategy in assembly := {
      case "module-info.class" => MergeStrategy.discard
      case PathList("org", "apache", "commons", "logging", xs @ _*)         => MergeStrategy.first
      case PathList("org", "apache", "jena", "tdb", "tdb-properties.xml")   => MergeStrategy.first
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    },
    mainClass in assembly := Some("uk.org.floop.sparqlTestRunner.SparqlTestRunner"),
    assemblyOption in assembly := (assemblyOption in assembly).value.copy(prependShellScript = Some(defaultShellScript)),
    assemblyJarName in assembly := "sparql-test-runner"
  )
