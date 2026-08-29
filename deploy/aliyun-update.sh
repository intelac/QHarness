#!/usr/bin/env bash
# Update the deployed NEXUM to the current build, and give the agent an
# interface to it. Run on the server as root.
#
# The jar is uploaded and checksum-verified beforehand; this only rebuilds the
# image around it, adds the mcp block to the live configuration, and restarts.
# The configuration is backed up first, and the rollback is printed at the end.
set -euo pipefail

EXPECTED_SHA=a6cb8c87d186c887
EXPECTED_SIZE=5805660
CONFIG=/srv/nexum/nexum.yaml
COMPOSE=/root/nexum/docker-compose.prod.yml

cd /root/nexum

echo "== 1. the jar is the one that was built =="
actual_sha=$(sha256sum nexum.jar | cut -c1-16)
actual_size=$(stat -c%s nexum.jar)
echo "   ${actual_sha}  ${actual_size} bytes"
if [ "$actual_sha" != "$EXPECTED_SHA" ] || [ "$actual_size" != "$EXPECTED_SIZE" ]; then
  echo "   the jar does not match what was built — upload it again before continuing"
  exit 1
fi

echo "== 2. back up the configuration =="
backup="${CONFIG}.bak-$(date +%Y%m%d-%H%M%S)"
cp "$CONFIG" "$backup"
echo "   $backup"

echo "== 3. rebuild the image =="
mkdir -p target && cp nexum.jar target/nexum.jar
docker build -t nexum:0.1.1 .

echo "== 4. give the agent an interface =="
if grep -q '^mcp:' "$CONFIG"; then
  echo "   already configured, leaving it alone"
else
  cat /root/nexum/mcp-block.yaml >> "$CONFIG"
  echo "   added"
fi

echo "== 5. point the deployment at the new image =="
sed -i 's|image: nexum:0.1.0|image: nexum:0.1.1|' "$COMPOSE"
# The MCP port is published to the docker bridge only. These tools place and
# amend real orders; unlike the FIX acceptor on 9880, this one is not for the
# public internet, and an agent elsewhere should come through an SSH tunnel.
if ! grep -q '8090:8090' "$COMPOSE"; then
  sed -i 's|      - "172.17.0.1:8080:8080"|      - "172.17.0.1:8080:8080"\n      - "172.17.0.1:8090:8090"|' "$COMPOSE"
fi

echo "== 6. restart =="
docker compose -f "$COMPOSE" up -d

echo "== 7. check =="
sleep 20
docker ps --filter name=nexum --format '   {{.Names}}\t{{.Status}}\t{{.Image}}'
echo "   sessions:"
curl -s -m 5 http://172.17.0.1:8080/api/sessions | head -c 300; echo
echo "   tools:"
curl -s -m 8 -X POST http://172.17.0.1:8090/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  | python3 -c 'import json,sys; n=[t["name"] for t in json.load(sys.stdin)["result"]["tools"]]; print("  ", len(n), "tools,", len([x for x in n if x.startswith("harness_")]), "of them harness")' \
  2>/dev/null || echo "   MCP did not answer — see: docker logs nexum-nexum-1"

cat <<ROLLBACK

To undo:
  docker compose -f $COMPOSE down
  cp $backup $CONFIG
  sed -i 's|nexum:0.1.1|nexum:0.1.0|' $COMPOSE
  docker compose -f $COMPOSE up -d
ROLLBACK
