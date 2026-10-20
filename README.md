# db-console: a MySQL query console on OpenShift

A small Spring Boot + Thymeleaf web app where you type SQL into a page, it runs against MySQL, and you see the rows (or the error). The point is not the console itself but to learn **how a microservice running on OpenShift (OCP) gets connected to a database**: where the connection settings live, how credentials reach the pod without being in git, and how traffic reaches the app.

## How the pieces connect

```
 Browser
   │  https://db-console-<project>.apps.<cluster>
   ▼
 Route (route.yaml)            ← public URL, TLS terminated at the OpenShift router
   │
   ▼
 Service (service.yaml)        ← stable name "db-console:8080", load-balances across pods
   │
   ▼
 Pods x2 (deployment.yaml)     ← Spring Boot app; page header shows which pod answered
   │   env DB_HOST/PORT/NAME   ← from ConfigMap  db-console-config   (configmap.yaml)
   │   env DB_USERNAME/PASSWORD← from Secret     db-console-secret   (created with `oc create secret`)
   │
   │  JDBC over TCP 3306 (HikariCP pool, max 5 connections per pod)
   ▼
 MySQL 63.142.240.31:3306 / schema ocp_connect   ← outside the cluster
```

Step by step, what happens when you click **Run query**:

1. The browser posts the SQL to the Route. The OpenShift router forwards it to the `db-console` Service.
2. The Service picks one of the pods (selected by the label `app=db-console`). Refresh a few times and the "served by pod" label in the header changes.
3. Inside the pod, `QueryController` hands the SQL to `QueryService`.
4. `QueryService` borrows a connection from the HikariCP pool. Spring Boot built that pool at startup from `application.properties`:
   ```properties
   spring.datasource.url=jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?...
   spring.datasource.username=${DB_USERNAME}
   spring.datasource.password=${DB_PASSWORD}
   ```
   Those `${...}` placeholders are environment variables, and the Deployment fills them from the ConfigMap and the Secret.
5. The driver (`mysql-connector-j`) opens TCP from the pod, out of the cluster's egress, to `63.142.240.31:3306`. MySQL checks the user, password and the **source IP** (which is the cluster's egress IP, not your laptop's).
6. Rows or the error come back and Thymeleaf renders them into `index.html`.

### Why ConfigMap *and* Secret?

| What | Where | Why |
|---|---|---|
| Host, port, schema | ConfigMap `db-console-config` | Not sensitive. Change it to point at another DB without rebuilding. |
| Username, password | Secret `db-console-secret` | Sensitive. Access controlled by RBAC, kept out of the image and out of git. |
| JDBC URL template, pool size | `application.properties` (baked into the image) | Same for every environment. |

The same image can therefore run in dev, test and prod; only the ConfigMap and Secret differ.

## Run it on OpenShift

You need the `oc` CLI and to be logged in (`oc login ...`).

```bash
# 1. Make (or pick) a project
oc new-project db-console-demo

# 2. Create the Secret with the DB credentials.
#    Use SINGLE quotes so characters like @ # $ ! in the password are passed as-is.
oc create secret generic db-console-secret \
  --from-literal=DB_USERNAME='<your-db-username>' \
  --from-literal=DB_PASSWORD='<your-db-password>'

# 3. Create the ConfigMap, ImageStream, BuildConfig, Deployment, Service and Route
oc apply -k openshift/

# 4. Build the image inside OpenShift from this folder (uses the Dockerfile)
oc start-build db-console --from-dir=. --follow
#    The Deployment rolls out automatically when the build pushes the image.

# 5. Watch the pods come up, then open the URL
oc get pods -l app=db-console -w
oc get route db-console -o jsonpath='https://{.spec.host}{"\n"}'
```

On the page, click **Test connection** first. It shows the JDBC URL, the DB user, and the MySQL server version, or the exact connection error.

### Useful commands while learning

```bash
oc logs -f deploy/db-console                   # app + HikariCP logs
oc exec deploy/db-console -- env | grep DB_    # see the injected env vars (password included, so be careful)
oc get configmap db-console-config -o yaml
oc get secret db-console-secret -o jsonpath='{.data.DB_USERNAME}' | base64 -d
oc set env deploy/db-console --list            # where each env var comes from
oc scale deploy/db-console --replicas=3        # more pods behind the same Service
oc rollout restart deploy/db-console           # pick up a changed Secret/ConfigMap
```

Changing a ConfigMap or Secret does **not** restart pods on its own. Env vars are read at container start, so run `oc rollout restart deploy/db-console` afterwards.

### If it doesn't connect

| Error on the page | Likely cause |
|---|---|
| `Communications link failure` / `connect timed out` | The cluster can't reach `63.142.240.31:3306`: an egress firewall or `EgressFirewall`/`NetworkPolicy` is blocking it, or MySQL isn't listening on the public interface. Test with `oc exec deploy/db-console -- bash -c "timeout 5 bash -c '</dev/tcp/63.142.240.31/3306' && echo open"`. `Connection refused` means nothing accepts connections on that port: see the SSH tunnel section below. |
| `Access denied for user 'x'@'<ip>'` | Wrong password, or the MySQL user isn't allowed from the cluster's egress IP. On MySQL: `CREATE USER 'x'@'%' ...` or grant for that IP. |
| `Unknown database 'ocp_connect'` | Schema name in the ConfigMap is wrong or not created. |
| `Public Key Retrieval is not allowed` | Already handled with `allowPublicKeyRetrieval=true` in the JDBC URL. |
| Pod in `CreateContainerConfigError` | The Secret `db-console-secret` doesn't exist or a key name is misspelled. |

## Reaching MySQL through an SSH tunnel

If MySQL's port 3306 is closed to the internet (only SSH on port 22 is open, or MySQL listens on the server's `127.0.0.1` only), use the tunnel overlay. It adds a **sidecar** container to the pod that holds an SSH tunnel open:

```
 Pod db-console
 ┌───────────────────────────────────────────────────────────────┐
 │  db-console container            ssh-tunnel container         │
 │  JDBC -> 127.0.0.1:3306 ──────▶  listens on 127.0.0.1:3306    │
 │  (same network namespace)        ssh -N -L 3306:127.0.0.1:3306│
 └───────────────────────────────────────────┬───────────────────┘
                                             │ SSH, TCP 22 (encrypted)
                                             ▼
                          63.142.240.31  sshd ──▶ MySQL on its 127.0.0.1:3306
```

Containers in one pod share `localhost`, so the app just connects to `127.0.0.1:3306` and doesn't know a tunnel exists. The overlay overrides `DB_HOST`/`DB_PORT` for the app container; everything else (schema, DB user and password) stays the same.

Setup (Windows Command Prompt shown; on Linux/Mac use `/` paths):

```bat
:: 1. Put your SSH key (and known_hosts, so the server's identity is checked) in a Secret
oc create secret generic db-ssh-key --from-file=id_ed25519=D:\tunnel-key\id_ed25519 --from-file=known_hosts=D:\tunnel-key\known_hosts

:: 2. Set your SSH login name: edit SSH_USER in openshift\tunnel\configmap.yaml (and SSH_PORT if not 22)

:: 3. Apply the overlay (app + tunnel sidecar), then build the sidecar image
oc apply -k openshift/tunnel/
oc start-build ssh-tunnel --from-dir=tunnel --follow

:: 4. Check both containers are up (READY 2/2) and read the tunnel's log
oc get pods -l app=db-console
oc logs deploy/db-console -c ssh-tunnel
```

To go back to a direct connection, run `oc apply -k openshift/`. That removes the sidecar and the `127.0.0.1` override again.

Tunnel errors, as shown by `oc logs deploy/db-console -c ssh-tunnel`:

| Log line | Meaning |
|---|---|
| `Permission denied (publickey)` | Wrong `SSH_USER`, or the public key (`id_ed25519.pub`) isn't in `~/.ssh/authorized_keys` for that user on the server. |
| `Host key verification failed` | `known_hosts` doesn't have an entry for `63.142.240.31` (or for `[63.142.240.31]:port` when SSH isn't on 22). Re-create the Secret without `known_hosts` to trust on first use. |
| `Connection refused` / `timed out` on port 22 | SSH isn't reachable from the cluster either. |
| `channel 2: open failed: connect failed` | Tunnel is up, but MySQL isn't on `REMOTE_DB_HOST:REMOTE_DB_PORT` as seen from the SSH server. |

Running locally, do the same by hand: `ssh -N -L 3306:127.0.0.1:3306 <user>@63.142.240.31 -i D:\tunnel-key\id_ed25519`, then start the app with `DB_HOST=127.0.0.1`.

## Run it locally

```bash
export DB_HOST=63.142.240.31 DB_PORT=3306 DB_NAME=ocp_connect
export DB_USERNAME='<your-db-username>' DB_PASSWORD='<your-db-password>'
mvn spring-boot:run
# open http://localhost:8080
```

Or with a container:

```bash
podman build -t db-console .
podman run --rm -p 8080:8080 \
  -e DB_HOST=63.142.240.31 -e DB_NAME=ocp_connect \
  -e DB_USERNAME='<your-db-username>' -e DB_PASSWORD='<your-db-password>' \
  db-console
```

Tests use an in-memory H2 database, so `mvn test` needs no MySQL.

## Project layout

```
src/main/java/com/example/dbconsole/
  DbConsoleApplication.java   Spring Boot entry point
  QueryController.java        GET /, POST /query, GET /connection
  QueryService.java           runs SQL through the pooled DataSource
  QueryResult.java            rows / update count / error
src/main/resources/
  application.properties      datasource from env vars, pool + probe settings
  templates/index.html        Thymeleaf page
openshift/
  kustomization.yaml          `oc apply -k openshift/`  (direct connection)
  secret-template.yaml        placeholder only; real Secret is made with `oc create secret`
  base/
    configmap.yaml            DB_HOST, DB_PORT, DB_NAME
    build.yaml                ImageStream + BuildConfig (Docker strategy, binary source)
    deployment.yaml           2 replicas, env from ConfigMap + Secret, health probes
    service.yaml              ClusterIP service on 8080
    route.yaml                edge-TLS public route
  tunnel/                     `oc apply -k openshift/tunnel/`  (via SSH tunnel sidecar)
    configmap.yaml            SSH_HOST, SSH_PORT, SSH_USER
    build.yaml                ImageStream + BuildConfig for the sidecar
    deployment-patch.yaml     adds the ssh-tunnel container, points the app at 127.0.0.1
tunnel/
  Dockerfile, entrypoint.sh   UBI minimal + OpenSSH client that holds the tunnel open
Dockerfile                    Maven build stage + UBI OpenJDK 17 runtime
```

## Safety notes

* This console runs **any** SQL you type, including `DROP TABLE`, with the permissions of the DB user. It's for learning. Use a user that only has rights on `ocp_connect`, and don't expose the Route publicly for longer than you need (delete it with `oc delete route db-console`, then use `oc port-forward svc/db-console 8080` instead).
* Never commit real credentials. `.gitignore` already ignores `openshift/*secret*.local.yaml` in case you make a filled-in copy of the template.
* If a password was ever pasted into chat, a ticket, or a commit, rotate it on the MySQL server.
* Results are capped at 500 rows and queries time out after 30 seconds (`console.max-rows`, `console.query-timeout-seconds`).
