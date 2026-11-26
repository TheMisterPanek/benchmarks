FROM docker.io/library/maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src

COPY pom.xml ./
RUN mvn -B -q dependency:resolve

COPY src ./src
RUN mvn -B -q package -DskipTests

FROM docker.io/library/eclipse-temurin:25-jre
WORKDIR /app

COPY --from=build /src/target/java-blazing-app-1.0.0.jar /app/app.jar
COPY --from=build /src/target/lib /app/lib
COPY config.yaml /app/config.yaml

EXPOSE 8080
ENTRYPOINT ["java", \
  "-server", \
  "-Xms64m", \
  "-Xmx256m", \
  "-XX:+UseParallelGC", \
  "-XX:+AlwaysPreTouch", \
  "-XX:+TieredCompilation", \
  "-XX:CompileThresholdScaling=0.5", \
  "--enable-native-access=ALL-UNNAMED", \
  "-Dvertx.disableMetrics=true", \
  "-Dvertx.disableH2c=true", \
  "-Dvertx.disableWebsockets=true", \
  "-Dvertx.disableHttpHeadersValidation=true", \
  "-Dio.netty.buffer.checkBounds=false", \
  "-Dio.netty.buffer.checkAccessible=false", \
  "-Dio.netty.leakDetection.level=disabled", \
  "-cp", "/app/app.jar:/app/lib/*", \
  "app.Main"]
