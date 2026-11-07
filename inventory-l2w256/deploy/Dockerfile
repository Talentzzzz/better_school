# 应用镜像：Spring Boot 3 + JDK21 虚拟线程
#
# 构建上下文是项目根目录（多阶段构建，无需本地先打包）：
#   docker build -f deploy/Dockerfile -t inventory-l2w256:1.0.0 .
#
# 也可以直接用本地已构建的 jar（构建机无网络时更快）：
#   mvn clean package -DskipTests
#   docker build -f deploy/Dockerfile.jar -t inventory-l2w256:1.0.0 .

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# 先只拷 POM 把依赖拉下来，改代码时不必重下依赖（层缓存）
COPY pom.xml .
COPY l2w256-types/pom.xml l2w256-types/
COPY l2w256-api/pom.xml l2w256-api/
COPY l2w256-domain/pom.xml l2w256-domain/
COPY l2w256-case/pom.xml l2w256-case/
COPY l2w256-infrastructure/pom.xml l2w256-infrastructure/
COPY l2w256-trigger/pom.xml l2w256-trigger/
COPY l2w256-app/pom.xml l2w256-app/
RUN mvn -B -q -DskipTests dependency:go-offline || true

COPY . .
RUN mvn -B -q clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre

ENV TZ=Asia/Shanghai \
    JAVA_OPTS=""

WORKDIR /app
COPY --from=build /build/l2w256-app/target/l2w256-app.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
