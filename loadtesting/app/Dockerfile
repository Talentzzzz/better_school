# 多阶段构建：构建阶段用 maven 镜像，应用服务器必须能访问 maven 中央仓库。
# 如果应用机不能联网，用同目录的 Dockerfile.runtime（先在本机 mvn package，再只装 JRE）。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# 单独拉依赖，利用镜像层缓存；个别插件的元数据拉不到不影响后续 package，所以容错
RUN mvn -B -q -DskipTests dependency:go-offline || true
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app

WORKDIR /app
COPY --from=build /build/target/inventory-loadtest.jar /app/app.jar

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms2g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:+AlwaysPreTouch -Duser.timezone=Asia/Shanghai"

EXPOSE 8080
USER app

# 用 busybox 的 wget 探活，避免为了 curl 再装包
HEALTHCHECK --interval=10s --timeout=3s --retries=30 --start-period=60s \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
