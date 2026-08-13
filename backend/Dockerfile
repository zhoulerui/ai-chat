# ai-chat 运行时镜像
# 前提:jar 已在本地构建好(mvn -DskipTests clean package),
# 服务器上无需安装 JDK / Maven / Node,镜像只包含 JRE + 应用
#
# 注意:必须用 glibc 系基础镜像(Ubuntu temurin-jre,21 与本机开发 JDK 一致)。
# ONNX Runtime 的 native 库(libonnxruntime.so)是 glibc 编译的,
# Alpine(musl)镜像缺少 libstdc++.so.6,启动会报
# "Error loading shared library libstdc++.so.6",故不能用 -alpine 版本。
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY target/ai-chat.jar app.jar

EXPOSE 8080

# 低内存优化参数(与裸机部署一致),可用 -e JAVA_OPTS="..." 覆盖
ENV JAVA_OPTS="-Xms64m -Xmx192m -XX:MaxMetaspaceSize=96m -XX:+UseSerialGC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
