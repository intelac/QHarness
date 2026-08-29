# Deploying NEXUM

Target: `<your-server>` (a cloud VM) — the same host that runs
TangoMaster.

## Why the jar is built locally

That host has 1.6GB of RAM, two cores, and five containers already on it, and
its load average sits above 4. Building this project there means compiling and
running 300 tests, two of which start real FIX engines on real sockets. There
is a documented incident on this machine where a parallel build pushed load to
16.8 and it stopped answering SSH.

So: **build the jar on your machine, ship the jar.** The Dockerfile is runtime
only — it copies a 5MB jar into a JRE image, which costs the server nothing.

```bash
mvn package                      # runs all 300 tests

# The jar must land in build/target/, because that is the path the Dockerfile
# copies from. Sending it flat into build/ fails with "target/nexum.jar: not
# found", which reads as a missing file rather than a misplaced one.
ssh root@<your-server> "mkdir -p /root/nexum/build/target"

# rsync, not scp. The jar is ~5.6MB and this link is slow enough that scp
# regularly runs past a two-minute timeout and is killed part-way, leaving a
# truncated file of plausible size. --partial resumes instead of restarting.
rsync -z --partial --timeout=300 target/nexum.jar \
    root@<your-server>:/root/nexum/build/target/nexum.jar
scp Dockerfile docker-compose.prod.yml root@<your-server>:/root/nexum/build/
```

## First-time setup on the server

```bash
ssh root@<your-server>
mkdir -p /root/nexum
cp /root/nexum/build/docker-compose.prod.yml /root/nexum/

# The configuration carries the logon password and is NOT in git.
#
# It goes in /etc/nexum, not /root: the container runs as uid 10001, and /root
# is 750 root-only, so a bind mount from there cannot be traversed — the
# container restart-loops on "cannot read configuration", which reads like a
# missing file rather than a permission one.
mkdir -p /etc/nexum
cp <this repo>/deploy/nexum.yaml.example /etc/nexum/nexum.yaml
vi /etc/nexum/nexum.yaml           # set security.password

# Readable by the container's user and nobody else — it holds the password.
chown 10001:10001 /etc/nexum/nexum.yaml
chmod 600 /etc/nexum/nexum.yaml
chmod 755 /etc/nexum
```

Then build and start:

```bash
cd /root/nexum/build && docker build -t nexum:0.1.0 .
cd /root/nexum && docker compose -f docker-compose.prod.yml up -d
```

## Releasing a change

```bash
# locally
mvn package
rsync -z --partial --timeout=300 target/nexum.jar \
    root@<your-server>:/root/nexum/build/target/nexum.jar

# Verify the transfer before building. A truncated or interrupted scp leaves a
# jar of plausible size, and the container then fails with
# "Could not find or load main class io.nexum.app.Main" — which looks like a
# packaging bug rather than an incomplete copy. This happened.
shasum -a 256 target/nexum.jar
ssh root@<your-server> "sha256sum /root/nexum/build/target/nexum.jar"

# on the server, once the two match
ssh root@<your-server> "cd /root/nexum/build && docker build -t nexum:0.1.0 . \
  && cd /root/nexum && docker compose -f docker-compose.prod.yml up -d"
```

If the container will not start, the first thing to check is what is actually
inside the image:

```bash
ssh root@<your-server> "docker run --rm --entrypoint sh nexum:0.1.0 \
  -c 'jar tf /app/nexum.jar | grep io/nexum/app/Main'"
```

`unzip` is not in the JRE base image, so `unzip -l` returning nothing there
means the tool is missing, not the class.

Restarting logs the FIX sessions out cleanly — the JVM is PID 1 and its
shutdown hook runs on SIGTERM. A session killed without logging out leaves the
counterparty arguing about sequence numbers on the next logon.

## Ports

| port | exposure | what |
|---|---|---|
| 9880 | `0.0.0.0` | FIX acceptor — counterparties connect here |
| 8080 | `127.0.0.1` | monitor API, reached through nginx only |

### The FIX port is public

**Open to the internet as of 2026-08-25.** Verified from outside: a logon with
no password and a logon with the wrong password are both refused with
`35=5 / 58=logon refused`; the right password establishes a session, and orders
sent from off-host complete the whole path.

CompIDs are in the counterparty's onboarding document. They are not a
credential. Two things stand between this port and anyone who reads one:

1. **`security.password` in `nexum.yaml`** — checked against Password(554) on
   Logon, in constant time. Without this block the acceptor admits anyone.
2. **The 阿里云 security group** — currently open. Restricting 9880 to the
   counterparty's addresses is the second layer, and `security.allowFrom` does
   the same check in the application; both is better than either. Until one of
   them is narrowed, the password is the only thing standing there, so it is
   worth rotating if it has ever been sent anywhere it should not have been.

The policy applies to **acceptor sessions only**. A venue this system dials
answers our Logon with one of its own, and checking that against our own
password refused every destination — configuring a password and an outbound
session together took the whole outbound leg down. `LogonPolicyTest` covers it.

A refused logon emits `LOGON_REFUSED` with the real reason. What goes back on
the wire says only "logon refused": telling a caller which of its guesses was
wrong is how it learns to guess.

## Checking it is up

```bash
ssh root@<your-server> "docker compose -f /root/nexum/docker-compose.prod.yml ps"
ssh root@<your-server> "curl -s http://127.0.0.1:8080/api/orders"
ssh root@<your-server> "ss -tln | grep 9880"
```

## State

The order journal lives in the `nexum_data` volume at `/var/lib/nexum`. It is
what open orders are recovered from after a restart — an order the venue knows
about and this system has forgotten cannot be recovered. Do not prune that
volume.

```bash
ssh root@<your-server> "docker run --rm -v nexum_nexum_data:/d alpine ls -la /d/journal"
```

## The monitor's URL

The monitor is proxied by TangoMaster's nginx under `/nexum/`:

```
http://<your-server>/nexum/
```

It is mounted under a prefix rather than at the root because `/api/` in that
server block already belongs to TangoMaster's backend. The page uses relative
paths and a `<base href="./">` tag so it works from any prefix — which is also
why the trailing slash matters, and why there is a redirect for the bare
`/nexum`.

nginx reaches it over the docker bridge gateway (`172.17.0.1:8080`) rather than
by container name: nginx is on `tangomaster_default` and NEXUM has its own
network, and joining them would couple two applications that have no reason to
know about each other. That is why `docker-compose.prod.yml` binds the monitor
to `172.17.0.1` and not `127.0.0.1` — a container cannot reach the host's
loopback.

To add it, append `deploy/nginx-nexum.conf.snippet` inside the `server { }`
block of `/root/tangomaster/deploy/nginx.http-only.conf`, then:

```bash
ssh root@<your-server> "docker exec tangomaster-nginx-1 nginx -t \
  && docker exec tangomaster-nginx-1 nginx -s reload"
```

`nginx -t` first, always: a bad config that gets reloaded takes TangoMaster
down with it.

### gzip is not optional here

The link to this host runs at roughly 15KB/s. Uncompressed, the grid's 1.6MB
script took over 90 seconds and never finished a `curl` with a 90s timeout;
with `gzip on` in the server block it transfers 353KB in about 5 seconds.

That directive was added to `nginx.http-only.conf` alongside the `/nexum/`
location. `deploy/nginx.tangomaster-with-nexum.conf` is a copy of the file as it
actually runs, in case the server's copy is ever lost.

### Verifying the page really renders

`curl` proves the assets are reachable; it does not prove the grid drew. Load
`http://<your-server>/nexum/` in a browser and check that the column headers
(Order, Client ID, Symbol, Side, Qty, Filled, Leaves, State, Destination) and
"No Rows To Show" are present. A 404 for `/favicon.ico` in the console is
expected and harmless — the page does not ship one, so the request falls through
to the application at the root.

## What is here

| Path | What it is |
|---|---|
| `README.md` | This file: the deployment procedure. |
| `aliyun-update.sh` | Update a running engine to a new build. |
| `nexum.yaml.example` | Engine configuration, with the password left as CHANGE-ME. |
| `mcp-block.yaml` | The agent interface, added to the live configuration. |
| `docker/` | Dockerfile and compose file, including the published ports. |
| `nginx/` | Reverse-proxy configuration for a host shared with other services. |
| `scenarios.md` | Scenarios exercised against a deployment. |

Nothing here names a host, a key, or a password. A deployment's own values
belong to whoever runs it; every file here carries a placeholder instead.

## Building what gets shipped

The jar is built where you are and shipped up — a small host builds slowly and
competes with whatever else it is running:

```sh
mvn -q package
rsync -P nexum/target/nexum.jar root@<your-server>:/root/nexum/nexum.jar
```

`rsync` rather than `scp`: a truncated upload has reported success here, and a
short jar builds an image that fails at runtime rather than at build time.
Compare the checksum on both sides before building anything from it.
