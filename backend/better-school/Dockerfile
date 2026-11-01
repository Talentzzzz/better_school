# 多阶段构建：maven 编译 → jre 运行
# 构建 context = better-school/ 工程目录（service-restructure 票 02 平移后，不再是 backend/ 根）：
#   docker build -t better-school backend/better-school
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN mvn -q -DskipTests clean package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/bs-app/target/bs-app-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
