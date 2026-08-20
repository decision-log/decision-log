# 회의 자리용 — 개발 도구 없이 앱을 띄운다.
# 코드 생성이 빌드 중 컨테이너를 띄우므로(Docker-in-Docker) 여기서 빌드하지 않는다.
# ./gradlew :app:bootJar 로 만든 산출물을 담기만 한다.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY app/build/libs/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
