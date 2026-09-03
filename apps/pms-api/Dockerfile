ARG MAVEN_BUILD_IMAGE=maven:3.9.9-eclipse-temurin-17
ARG API_RUNTIME_IMAGE=eclipse-temurin:17-jre-alpine

FROM ${MAVEN_BUILD_IMAGE} AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B verify

FROM ${API_RUNTIME_IMAGE}
ARG PMS_SECURITY_REFRESH=manual
RUN echo "security-refresh=${PMS_SECURITY_REFRESH}" \
    && apk upgrade --no-cache \
    && apk add --no-cache --upgrade \
        'libcrypto3>=3.5.8-r0' \
        'libssl3>=3.5.8-r0' \
        'openssl>=3.5.8-r0' \
    && addgroup -S app \
    && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/target/pms-api-*.jar app.jar
USER app
EXPOSE 8088
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
