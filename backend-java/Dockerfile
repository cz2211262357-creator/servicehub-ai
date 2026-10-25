ARG BUILDER_IMAGE=mirror.gcr.io/library/maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237
ARG RUNTIME_IMAGE=mirror.gcr.io/library/eclipse-temurin:21-jre-jammy@sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0
FROM ${BUILDER_IMAGE} AS build
WORKDIR /project
ENV SERVICEHUB_WORKSPACE_ROOT=/project MAVEN_USER_HOME=/project/.cache/maven
COPY backend-java/pom.xml backend-java/pom.xml
COPY backend-java/.mvn backend-java/.mvn
COPY backend-java/src/main backend-java/src/main
RUN --mount=type=cache,id=servicehub-java-m2,target=/project/.cache/m2 \
    mvn -B -ntp -f backend-java/pom.xml --settings backend-java/.mvn/settings.xml \
    -Dmaven.repo.local=/project/.cache/m2 -Dmaven.test.skip=true package

FROM ${RUNTIME_IMAGE}
RUN groupadd --gid 10001 servicehub && useradd --uid 10001 --gid servicehub --no-create-home servicehub \
    && mkdir -p /workspace/.data/java/uploads /workspace/.cache/java-tmp \
    && chown -R servicehub:servicehub /workspace
WORKDIR /workspace
COPY --from=build --chown=servicehub:servicehub /project/backend-java/target/servicehub-backend-0.1.0.jar /workspace/servicehub.jar
ENV SERVICEHUB_WORKSPACE_ROOT=/workspace \
    JAVA_TOOL_OPTIONS="-Duser.timezone=UTC -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/workspace/.cache/java-tmp"
USER servicehub
EXPOSE 8001
ENTRYPOINT ["java","-jar","/workspace/servicehub.jar"]
