# 화면을 빌드해 서버 jar에 넣고, 실행 이미지에는 jar만 남긴다.
FROM node:20.18-alpine AS web
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend ./
ARG VITE_KAKAO_JS_KEY=""
ENV VITE_KAKAO_JS_KEY=$VITE_KAKAO_JS_KEY
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY matching-engine matching-engine
COPY server server
COPY --from=web /src/server/src/main/resources/static server/src/main/resources/static
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app && mkdir -p /app/uploads && chown -R app:app /app
WORKDIR /app
COPY --from=build /src/server/target/server-0.1.0.jar app.jar
USER app
ENV UPLOAD_DIR=/app/uploads
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
