FROM maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c AS build
WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY backend ./backend
RUN mvn --batch-mode --no-transfer-progress -f backend/pom.xml \
    -pl resilientpay-service -am -DskipTests package

# The official container tag lags the vendor's Java 25 security release.
# Checksum the fixed Linux/x64 JRE in a separate stage; no download tooling is
# added to the application runtime, and the official CA entrypoint is retained.
FROM eclipse-temurin:25-jre@sha256:dbb43d81099eec91e0800226ed2296d03146ee7857165b95112372ad0d67e87f AS security-jre
ADD --checksum=sha256:6fac98f0c8202efc3135a9e9e42aa61963ec42d1c5cbb40caf231e7b14a24ef6 https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jre_x64_linux_hotspot_25.0.5_8.tar.gz /tmp/openjdk.tar.gz
RUN set -eux; \
    test "$(dpkg --print-architecture)" = amd64; \
    echo '6fac98f0c8202efc3135a9e9e42aa61963ec42d1c5cbb40caf231e7b14a24ef6 */tmp/openjdk.tar.gz' | sha256sum --check --strict; \
    mkdir /verified-jre; \
    tar --extract --file /tmp/openjdk.tar.gz --directory /verified-jre --strip-components 1 --no-same-owner; \
    grep -Fx 'JAVA_VERSION="25.0.5"' /verified-jre/release; \
    grep -Fx 'IMPLEMENTOR="Eclipse Adoptium"' /verified-jre/release; \
    grep -Fx 'OS_ARCH="x86_64"' /verified-jre/release; \
    grep -Fx 'OS_NAME="Linux"' /verified-jre/release; \
    /verified-jre/bin/java -version

FROM eclipse-temurin:25-jre@sha256:dbb43d81099eec91e0800226ed2296d03146ee7857165b95112372ad0d67e87f
RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        libssl3t64=3.5.5-1ubuntu3.7 \
        openssl=3.5.5-1ubuntu3.7 \
        openssl-provider-legacy=3.5.5-1ubuntu3.7 \
    && rm -rf /var/lib/apt/lists/* \
    && rm -f /usr/bin/pebble \
    && rm -rf /opt/java/openjdk
COPY --from=security-jre /verified-jre/ /opt/java/openjdk/
ENV JAVA_VERSION=jdk-25.0.5+8
RUN find "$JAVA_HOME/lib" -name '*.so' -exec dirname '{}' ';' | sort -u > /etc/ld.so.conf.d/docker-openjdk.conf \
    && ldconfig \
    && java -Xshare:dump \
    && java -version
RUN useradd --system --uid 10001 --create-home resilientpay
WORKDIR /app
COPY --from=build /workspace/backend/resilientpay-service/target/resilientpay-service-*.jar /app/application.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["/__cacert_entrypoint.sh", "java", "-XX:MaxRAMPercentage=75", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app/application.jar"]
