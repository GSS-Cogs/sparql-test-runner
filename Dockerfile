FROM openjdk:8-alpine as assembly

# based on https://hub.docker.com/r/hseeberger/scala-sbt/dockerfile

ENV SCALA_VERSION 2.12.8
ENV SBT_VERSION 1.2.8

RUN \
  apk add --no-cache curl bash && \
  curl -fsL https://downloads.lightbend.com/scala/$SCALA_VERSION/scala-$SCALA_VERSION.tgz | tar xfz - -C /usr/local/

ENV SCALA_HOME /usr/local/scala-${SCALA_VERSION}
ENV PATH "${SCALA_HOME}/bin:${PATH}"

RUN \
  curl -fsL https://piccolo.link/sbt-${SBT_VERSION}.tgz | tar xfz - -C /usr/local/ && \
  /usr/local/sbt/bin/sbt sbtVersion

ENV PATH "/usr/local/sbt/bin:${PATH}"

WORKDIR /usr/local/src
COPY build.sbt ./
COPY src ./src
COPY project ./project
RUN sbt assembly

FROM openjdk:8-alpine
COPY --from=assembly /usr/local/src/target/scala-2.12/sparql-test-runner /usr/local/bin/
