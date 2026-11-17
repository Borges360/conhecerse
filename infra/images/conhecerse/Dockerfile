# The lab copies this file into platform/conhecerse-dockerfile. Never pass secrets as build args.
FROM node:24.21.0-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS web-deps
RUN corepack enable && corepack prepare pnpm@10.28.2 --activate
WORKDIR /src
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json apps/web/package.json
RUN pnpm install --frozen-lockfile
FROM web-deps AS web-build
COPY apps/web apps/web
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 NODE_OPTIONS=--max-old-space-size=1280
RUN pnpm --dir apps/web build
FROM node:24.21.0-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS web
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 HOSTNAME=0.0.0.0 PORT=3000
# apk upgrade: correções de segurança do Alpine publicadas depois do digest fixado (gate Trivy).
RUN apk upgrade --no-cache \
 && rm -rf /usr/local/lib/node_modules/npm /usr/local/bin/npm /usr/local/bin/npx /opt/yarn-* /usr/local/bin/yarn /usr/local/bin/yarnpkg \
 && addgroup -g 1001 app && adduser -D -u 1001 -G app app
COPY --from=web-build --chown=app:app /src/apps/web/.next/standalone ./
COPY --from=web-build --chown=app:app /src/apps/web/.next/static ./apps/web/.next/static
COPY --from=web-build --chown=app:app /src/apps/web/public ./apps/web/public
USER app
EXPOSE 3000
CMD ["node","apps/web/server.js"]
FROM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS java-build
WORKDIR /src
COPY backend/pom.xml ./pom.xml
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
RUN mvn -B -q -DskipTests package
FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555 AS api
WORKDIR /app
RUN apk upgrade --no-cache && addgroup -g 1001 app && adduser -D -u 1001 -G app app
COPY --from=java-build --chown=app:app /src/target/conhecerse-2.0.0.jar ./app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError" PORT=8080
USER app
EXPOSE 8080
CMD ["java","-jar","/app/app.jar"]
FROM quay.io/keycloak/keycloak:26.8.2@sha256:c68e286cacd87b942ac1939a0a1481461109d6acd3a57dc496969a319a76bbf0 AS iam
# Keycloak 26.8.2 (última) ainda embute guava 33.6.0-jre (CVE-2026-102554, corrigida na 33.7.2).
# O jar corrigido do Maven Central substitui o arquivo no mesmo nome, que o índice do Quarkus referencia;
# remover quando uma versão do Keycloak trouxer guava >= 33.7.2.
ADD --chown=1000:0 --checksum=sha256:b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c \
  https://repo1.maven.org/maven2/com/google/guava/guava/33.7.2-jre/guava-33.7.2-jre.jar \
  /opt/keycloak/lib/lib/main/com.google.guava.guava-33.6.0-jre.jar
COPY infra/keycloak/realm.json /opt/keycloak/data/import/conhecerse-realm.json
COPY infra/keycloak/themes/conhecerse /opt/keycloak/themes/conhecerse
ENV KC_DB=postgres KC_HEALTH_ENABLED=true KC_HTTP_RELATIVE_PATH=/auth
RUN /opt/keycloak/bin/kc.sh build
USER 1000
EXPOSE 8080 9000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start","--optimized","--import-realm"]
FROM clamav/clamav:1.5.4@sha256:638d90201ee7ee6a82d249a2fc23b132e578673b5f1f2766852b127676ca1ef6 AS av
USER root
RUN apk upgrade --no-cache && mkdir -p /run/clamav /var/log/clamav && chown -R clamav:clamav /var/lib/clamav /var/log/clamav /run/clamav /etc/clamav \
 && printf 'TCPSocket 3310\nTCPAddr 0.0.0.0\nForeground yes\nUser clamav\nDatabaseDirectory /var/lib/clamav\nStreamMaxLength 27M\nMaxFileSize 27M\nMaxScanSize 100M\n' > /etc/clamav/clamd.conf
USER clamav
EXPOSE 3310
ENTRYPOINT ["/bin/sh","-c"]
CMD ["freshclam --quiet; freshclam --daemon --quiet & exec clamd --foreground --config-file=/etc/clamav/clamd.conf"]
