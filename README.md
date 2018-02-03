SPARQL test runner
==================

SPARQL can be used to validate assumptions made when curating data.
This project goes through a given 'tests' directory looking for files
ending in .sparql, and runs the queries against a given RDF dataset or
datasets.

The results of each query should either be empty - for SELECT style
queries, or TRUE for ASK style queries.  More complex results can be
checked by creating a file with the expected results and giving it a
.expected suffix.  The actual results are checked against the expected
results - note that the output is in CSV format, so line endings are
as for DOS (carriage return, linefeed).

The result of running the SPARQL test runner will be a testresults.xml
file in the current directory, in the style of jUnit XML.  Errors are
also output to stdout/stderr for humans.  The exit code of the test
runner will be 0 for success, and 1 if there are any errors.

The project essentially bundles the Apache Jena ARQ libraries along
with a simple test runner.

The project can be built as a standalone "fat JAR", containing all
dependencies such that it can be used directly without having to
install anything other than Java.  Java version 7 up is supported.

The resulting standalone file can also be used to simply run any
SPARQL 1.1 query on any local or remote data, including using the
SERVICE keyword to mix together external SPARQL endpoints with local
data, etc.

Building
--------

Build using the Scala Build Tool [1].  To build the fat JAR, run 'sbt
assembly'.  This will result in a single executable file under
target/scala-2.11/sparql, which can be copied wherever needed.  The
file has a prolog invoking Bash to run Java on the embedded JAR.

Running
-------

Running the 'sparql' executable is the same as running Apache Jena's
sparql command, but just includes all dependent JARs and classes.

The test runner can be run as follows:

    java -cp sparql uk.org.floop.sparqlTestRunner.Run

and will describe usage:

    Usage: sparql-testrunner [options] <file>...

      -t <dir> | --testdir <dir>
            location of SPARQL queries to run, defaults to tests/sparql
      <file>...
            data to run the queries against

The SPARQL queries are expected to live under the tests/sparql
directory, which will be recursed into looking for files ending with
.sparql.

The RDF data can be in any format recognizable by Apache Jena,
including the quads formats.  To keep things simple, quads are treated
as triples so that the queries range over the union of all graphs.

[1] http://www.scala-sbt.org/
