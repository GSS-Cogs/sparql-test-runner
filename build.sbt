import sbtassembly.AssemblyPlugin.defaultShellScript

name := "sparql-test-runner"

version := "1.2"

scalaVersion := "2.12.4"

libraryDependencies ++= Seq(
  "org.apache.jena" % "jena-arq" % "3.6.0",
  "org.apache.jena" % "jena-cmds" % "3.6.0",
  "com.github.scopt" %% "scopt" % "3.7.0",
  "org.scala-lang.modules" %% "scala-xml" % "1.0.6"
)

mainClass in assembly := Some("arq.sparql")

assemblyMergeStrategy in assembly := {
  case PathList("org", "apache", "commons", "logging", xs @ _*)         => MergeStrategy.first
  case PathList("org", "apache", "jena", "tdb", "tdb-properties.xml")   => MergeStrategy.first
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

assemblyOption in assembly := (assemblyOption in assembly).value.copy(prependShellScript = Some(defaultShellScript))

assemblyJarName in assembly := "sparql"
