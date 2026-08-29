# Runtime only.
#
# The jar is built on the developer's machine and copied in, deliberately: the
# target server has 1.6GB of RAM, two cores, and five containers already on it.
# Building there — which for this project means compiling and running 289 tests
# including two live FIX engines on real sockets — is how that box stops
# answering SSH. There is a documented incident.
#
#   mvn package && docker build -t nexum:VERSION .
#
FROM eclipse-temurin:21-jre

WORKDIR /app

# Not root: this process listens on a public port.
RUN useradd --system --uid 10001 --create-home nexum

COPY target/nexum.jar /app/nexum.jar

# Journal and FIX logs are state and belong on a volume, not in the layer — an
# order journal lost with the container cannot be recovered, and recovering
# open orders after a restart is what it is for.
RUN mkdir -p /var/lib/nexum && chown -R nexum:nexum /var/lib/nexum /app
VOLUME ["/var/lib/nexum"]

USER nexum

# 8080 monitor, 9880 FIX
EXPOSE 8080 9880

# Small heap: this shares a 1.6GB host with another application.
ENV JAVA_OPTS="-Xmx320m -XX:+ExitOnOutOfMemoryError"

# Exec form so the JVM is PID 1 and receives SIGTERM directly. The shutdown
# hook is what logs the FIX sessions out; a session that dies without logging
# out leaves the counterparty arguing about sequence numbers on the next logon.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dnexum.monitor.port=8080 -jar /app/nexum.jar /etc/nexum/nexum.yaml"]
