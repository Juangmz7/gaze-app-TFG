# Kubernetes (infra/k8s) Documentation

## What it does
`infra/k8s` deploys the whole platform on Kubernetes with GitOps: Argo CD syncs this repository (branch `main`) into the cluster. It runs today on a local single-node **k3s** cluster (WSL2 Ubuntu) and is written to move to **AKS** without restructuring.

```
infra/k8s/
├── bootstrap/            # one-off: k3s install + config, Argo CD install, root app, Windows port proxy, teardown
├── argocd/               # everything Argo CD manages (app of apps)
│   ├── projects.yaml     #   AppProjects: platform / env-infra / apps
│   ├── platform/         #   Applications for cluster-wide controllers + config
│   └── applicationsets/  #   env-infra (component x env), microservices (service x env)
├── platform/             # cluster-wide manifests (kustomize), incl. external-databases
├── charts/
│   ├── microservice/     # ONE generic chart for every microservice
│   ├── gateway/          # per-env Gateway + traffic policies
│   ├── rabbitmq/         # per-env RabbitmqCluster + users/permissions/exchanges
│   ├── redis/            # per-env Redis
│   └── keycloak/         # per-env Keycloak (operator + CR + realm + route)
├── environments/{dev,prod}/<component>.yaml   # values of the env charts
└── services/<service>/values{,-dev,-prod}.yaml # values of the microservice chart
```

### Sync order (Argo CD sync waves)
1. `-20` AppProjects
2. `-10` Argo CD (self-managed)
3. `-8..-5` controllers: Envoy Gateway (+ Gateway API CRDs), cert-manager, KEDA, Keycloak Operator CRDs, Image Updater, RabbitMQ operators, OpenTelemetry Operator
4. `-4..-3` platform config: ClusterIssuers, GatewayClass, rate-limit Redis, external-databases, observability, observability-agent
5. `0` **env-infra** ApplicationSet: `gateway|rabbitmq|redis|keycloak` x `dev|prod`
6. `5` **microservices** ApplicationSet: every folder in `services/` x `dev|prod`
7. `10` ImageUpdater configuration

Every Application uses `automated: {prune: true, selfHeal: true}` and `CreateNamespace=true`; namespaces are labelled by Argo CD (`managedNamespaceMetadata`) with `gaze.app/env`, `gaze.app/tier` and Pod Security Admission levels (`restricted` for services).

### Namespaces (one network per component)
| Namespace | Contents |
|---|---|
| `<service>-<env>` | one microservice (`social-service-dev`, ...) |
| `gateway-<env>` | Gateway + its Envoy proxies (Gateway Namespace Mode) |
| `rabbitmq-<env>`, `redis-<env>`, `keycloak-<env>` | per-env infra |
| `observability` | OTel Collector + Grafana LGTM (shared) |
| `observability-agent` | per-node OTel agent (DaemonSet): pod logs + kubelet metrics. Pod Security `privileged` (hostPath `/var/log/pods`) |
| `external-databases` | no pods: in-cluster names for the databases outside the cluster |
| `argocd`, `envoy-gateway-system`, `cert-manager`, `keda`, `rabbitmq-system`, `opentelemetry-operator-system` | control plane |

Every workload namespace starts with a **default-deny** NetworkPolicy (ingress and egress) and only opens what it needs: DNS, its declared dependencies (by namespace + pod labels + port) and, for databases outside the cluster, a CIDR + port (locally `10.42.0.1/32` on that database's published port only). A compromised pod can't reach other services, the other environment, or infra it doesn't use. Examples: `redis-dev` only accepts `social-service-dev` and `post-command-service-dev` on 6379 and makes no outbound connections; Gateway proxies only reach `gaze.app/tier in (app, identity)` namespaces of their own env.

### Gateway (Envoy Gateway, Gateway API)
- `api.<env-domain>` → `/api/social` (social-service), `/api/posts`, `/api/collabs` (post-command-service). `/api/feed` (post-query-service).
- `auth.<env-domain>` → Keycloak `/realms/`, `/resources/` (+ `/admin/` only in dev).
- HTTP → HTTPS redirect; TLS by cert-manager (local CA in k3s, Let's Encrypt for prod).
- Gateway-wide `BackendTrafficPolicy`: **global per-IP rate limiting** (Redis-backed, `sourceCIDR: Distinct`), **circuit breaking** (connection/pending/parallel request limits), passive health checks (outlier ejection), safe retries (connect-failure, refused-stream) and timeouts. Keycloak's route overrides only the rate limit (stricter) with `mergeType: StrategicMerge`.

### Microservice chart
One chart, configured only through values: Deployment (non-root, read-only root FS, seccomp, dropped capabilities, startup/liveness/readiness probes), Service, HTTPRoute, PDB, NetworkPolicies, ConfigMap (`env`), Secret (`secretEnv`, dev only) or `existingSecret`, KEDA `ScaledObject` and an optional migration Job (Argo CD Sync hook that runs before the new pods, used by recommendation-service for `alembic upgrade head`).

### Autoscaling (KEDA)
`autoscaling.rabbitmq.queues` is a list: each entry becomes a `rabbitmq` trigger (`QueueLength`, value = messages per replica) authenticated with the `RABBITMQ_AMQP_URI` key of the service Secret. HTTP services also scale on CPU. Every queue a service consumes is listed, with a threshold by priority: `20` where a user waits for the result (sign-up, like/share validation), `50`–`100` for read models and counters, `200`–`300` for background work (deletion cleanup, feed signals, recommendation features). The HPA follows the trigger that asks for the most replicas. With `protocol: amqp` KEDA counts ready messages only, so messages already prefetched by a consumer don't count.

### Continuous delivery
- CI (`.github/workflows/<service>-ci.yml`) runs the tests on every push (any branch) and pull request. Only on `main`, and only after the `test` job is green, its `publish` job pushes `docker.io/juangmz7/<service>:latest` and `:<commit sha>` through the reusable `docker-publish.yml` (secrets `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`). The Keycloak image has no tests and is published by `keycloak-image.yml`.
- Manifests track `:latest`. **Argo CD Image Updater** (`digest` strategy) detects the new digest and sets the Helm parameter `image.tag=latest@sha256:...`, which rolls the Deployment. The ApplicationSets ignore `spec.source.helm.parameters` so they don't revert it.

### Secrets
For now the **dev** values (same as each service's `application-dev.yaml`) are in the values files (`secretEnv`, env chart values) and rendered into Kubernetes Secrets. **prod** values reference `existingSecret` names and list the keys they need; they will be created by External Secrets from Vault, so the charts don't change. RabbitMQ has one user per client (5 users), with Keycloak limited to publishing on `x.auth.events`.

### Observability
Services export OTLP to `gaze-collector.observability:4317/4318` (OpenTelemetry Operator). The collector exports to Grafana LGTM; on AKS only the exporter changes.
- **Senders**: the four services (traces, metrics, logs), the Envoy proxies (edge spans, metrics, access logs) and, from Keycloak 26.1, Keycloak traces (`tracing.enabled` in the keycloak chart). The collector accepts OTLP from namespaces with `gaze.app/tier` in `app`, `edge` and `identity`.
- **Scraped**: RabbitMQ (`:15692`, aggregated `/metrics` plus per-queue/per-exchange `/metrics/detailed`) and Keycloak (`:9000/metrics`) through the collector's `prometheus` receiver (pod discovery, dev targets; prod is a `TODO(aks)`).
- **Cluster state**: the collector's `k8s_cluster` receiver (pod phase and status reasons, container restarts/readiness, requests/limits, Deployment/StatefulSet/DaemonSet/Job/HPA replicas, node conditions and allocatable) and `k8s_events` (every Kubernetes Event as a log, `service_name="k8s-events"` in Loki). Both must run in a single collector replica.
- **Node agent** (`platform/observability-agent`, DaemonSet `gaze-agent`): `file_log` ships the stdout of every container on the node (RabbitMQ, Keycloak, Envoy, operators, Argo CD, ...) and `kubelet_stats` the node/pod/container/volume CPU, memory, network and filesystem usage. It sends to the collector's dedicated `otlp/agent` receiver (`:4319`), which skips the collector's `k8s_attributes` (the agent enriches its own data). The microservices' pod logs are **excluded**: they already export logs over OTLP, and some of their loggers (SQL bind values, OAuth2) are stdout-only on purpose. Envoy's stdout access log is dropped too (already exported over OTLP).
- **Kubernetes metadata**: the `k8s_attributes` processor adds `k8s.namespace.name`, `k8s.pod.name`, `k8s.deployment.name`, … (RBAC in `platform/observability/rbac.yaml`). A `deployment.environment.name` set by the SDK wins over the namespace's `gaze.app/env`.
- **Trace propagation**: W3C `traceparent` over HTTP and over RabbitMQ headers (Spring AMQP observation, Watermill middleware in post-query-service, FastStream middleware in recommendation-service), so one request is one trace across services.
- **Dashboards**: `platform/observability/dashboards/*.json`, provisioned into LGTM's Grafana (folder **Gaze**): *Kubernetes cluster* (capacity, usage vs requests/limits, failing pods and workloads, events, error logs) and *RabbitMQ* (brokers, throughput, per-queue and per-exchange state, broker logs). They are generated by `tools/grafana-dashboards/generate.py`: change the generator and re-run it, not the UI (UI saves are disabled).
- **Grafana**: not routed publicly, `kubectl -n observability port-forward svc/lgtm 3000` and open http://localhost:3000.
- **Check**: `tools/otel-check/check.py` reports, per service, whether traces, metrics and logs reach LGTM (same port-forward).

## Packages used
- **k3s** `v1.35.2+k3s1` (single node on WSL2; embedded NetworkPolicy controller, ServiceLB, local-path storage)
- **Argo CD** chart `argo-cd 10.11.6` (Argo CD v3.5) + **Argo CD Image Updater** `v1.4.0`
- **Envoy Gateway** `v1.9.3` (Gateway API, global rate limit, Gateway Namespace Mode)
- **cert-manager** `v1.21.3`
- **KEDA** `2.21.0`
- **RabbitMQ Cluster Operator** `v2.23.1` + **Messaging Topology Operator** `v1.20.4` (RabbitMQ `4.3.7`)
- **Keycloak Operator** `25.0.6` (matches the custom Keycloak image)
- **OpenTelemetry Operator** chart `0.126.2`, **grafana/otel-lgtm** `0.38.0`
- **Redis** `7.4.11-alpine`

## Why
- **GitOps**: the cluster state is the repo; prune + self-heal undo manual drift, and the AppProjects stop a service's values from creating anything outside its namespaces/kinds.
- **One generic chart**: the four services differ only in values, so a fix to probes, security or policies applies to all of them at once, and adding a service is adding a values folder.
- **Operators** for stateful infra (RabbitMQ, Keycloak, OTel): upgrades, users, exchanges and realm imports are declarative instead of init scripts.
- **Namespace-per-component + default deny**: limits lateral movement; dev and prod never share a network path.
- **Databases outside the cluster**: managed services on AKS; locally the existing compose stack (`infra/local`) on the same Linux host as k3s.

## Local databases (out of the cluster)
The compose stack runs with Docker Engine on the same WSL2 Ubuntu as k3s. Pods can't use Docker's DNS, so `platform/external-databases` gives each database a stable name (`social-postgres.external-databases.svc.cluster.local:5432`, ...): a selector-less Service whose EndpointSlice points at the node's pod-network gateway `10.42.0.1` on the database's **published host port** (5433, 5434, 5435, 5436, 7687, 27017, 10000). That only works if those ports listen beyond loopback, hence `DB_BIND_IP=0.0.0.0` in `infra/local/.env` (WSL2's NAT keeps them off the LAN; Windows still reaches them on `localhost`). After the Service DNAT, traffic goes to `10.42.0.1:<host port>`, which is exactly what each service's NetworkPolicy allows. Mongo is reached with `directConnection=true` because its replica set advertises a Docker-only hostname. Argo CD excludes EndpointSlices by default, so `bootstrap/argocd-values.yaml` overrides `resource.exclusions` without them.

## How to run (k3s on WSL2 Ubuntu)
1. In Ubuntu: enable systemd (`/etc/wsl.conf` → `[boot]` `systemd=true`, then `wsl --shutdown` from Windows), install Docker Engine and helm, and disable Docker Desktop's WSL integration for this distro.
2. Start the databases with `DB_BIND_IP=0.0.0.0` in `infra/local/.env`: `docker compose -f infra/local/compose.yaml --env-file infra/local/.env up -d`.
3. The dev database passwords in the values files must stay equal to `infra/local/.env`: the `SPRING_DATASOURCE_PASSWORD` / Neo4j values of each service, the password in `DATABASE_URL` of `services/recommendation-service/values-dev.yaml` (= `RECOMMENDATION_POSTGRES_PASSWORD`) and `database.password` in `environments/dev/keycloak.yaml` (= `POSTGRES_PASSWORD`).
4. `./infra/k8s/bootstrap/bootstrap.sh`: installs k3s `v1.35.2+k3s1` with `bootstrap/k3s-config.yaml` (Traefik off, fixed CIDRs, Secrets encrypted at rest), checks the pod CIDR, installs Argo CD and applies the root app. Argo CD then syncs from `main`.
5. From Windows, an elevated PowerShell: `infra/k8s/bootstrap/windows-portproxy.ps1 -Lan` (again after each WSL restart; see [Access from the LAN](#access-from-the-lan)). It forwards Windows' ports 80/443 to the WSL IP, because k3s exposes the Gateway through iptables, which WSL's localhost forwarding doesn't see. `kubectl` from Windows works with the kubeconfig at `~/.kube/config` in Ubuntu (API on `127.0.0.1:6443`).

To remove the cluster so it stops using resources and doesn't start again on reboot: `./infra/k8s/bootstrap/teardown.sh`. It runs k3s' `k3s-uninstall.sh`, which stops and disables the systemd unit and deletes every workload, PersistentVolume, image and the cluster state, then removes `~/.kube/config` if it is still the copy `bootstrap.sh` made. Afterwards, from an elevated PowerShell, `infra/k8s/bootstrap/windows-portproxy.ps1 -Remove` deletes the port proxy. `bootstrap.sh` recreates everything from scratch.

## Access from the LAN
The dev hostnames are `api.dev.192-168-1-135.sslip.io` and `auth.dev.192-168-1-135.sslip.io`: sslip.io (public wildcard DNS) resolves them to the IP they embed, this PC's LAN IP, so other devices on the same network (phone, laptop) reach the Gateway, and so does this PC. No router port forwarding is involved.

1. Windows, elevated PowerShell: `infra/k8s/bootstrap/windows-portproxy.ps1 -Lan` (again after each WSL restart). Needed on this PC too, since the hostnames resolve to the LAN IP, not to 127.0.0.1. The port proxy listens on `0.0.0.0:80/443` instead of `127.0.0.1` and an inbound firewall rule allows TCP 80/443 from the local subnet on Private/Domain networks. It also opens Azurite's blob port to the LAN: a firewall rule for TCP 10000, plus a port proxy to the WSL IP only when nothing on Windows listens on `0.0.0.0:10000` yet (Docker Desktop already publishes it there, and its own firewall rules only cover Public networks). The network must be **Private**: the script warns when it is Public. Running it without `-Lan` (or with `-Remove`) deletes the rules.
2. Router: reserve this PC's IP in DHCP so it doesn't change.
3. Each device trusts `gaze-local-ca` (or skips TLS verification).
4. Media uploads: post-command-service builds the blob and upload SAS URLs from `AZURE_STORAGE_ACCOUNT_URL` = `http://azurite.dev.192-168-1-135.sslip.io:10000/devstoreaccount1`, so devices upload straight to Azurite through that port. The pod uses the same URL, but not through the LAN IP: in WSL2 NAT mode Windows drops traffic from the WSL switch addressed to its LAN interface's IP, so `platform/external-databases/coredns-custom.yaml` makes CoreDNS answer that name inside the cluster with the `azurite` Service. Azurite runs with `--disableProductStyleUrl`, so the dotted host isn't read as an account name.
5. If a device can't resolve the names, its router drops DNS answers that point at private IPs ("DNS rebinding protection"): use another DNS server on that device (e.g. 1.1.1.1). sslip.io needs internet access.

Why not `*.localhost` or the bare IP: `*.localhost` always resolves to the device itself, and the Gateway routes by hostname (listeners, HTTPRoutes, certificate SANs). If the LAN IP changes, replace `192-168-1-135` everywhere at once, because Keycloak issues tokens for a single hostname and the services check that exact issuer: `environments/dev/gateway.yaml` and `keycloak.yaml`, the HTTPRoute hostnames and issuer in `services/*/values-dev.yaml`, `AZURE_STORAGE_ACCOUNT_URL` in `services/post-command-service/values-dev.yaml` together with `platform/external-databases/coredns-custom.yaml`, `bootstrap/bootstrap.sh` and `client-queries-templates/`. cert-manager reissues the certificate on its own; tokens issued before stop being valid.

## Pending (TODO markers in the files)
- Prod: enable it in both ApplicationSets (`enabled: "true"`), real domain, managed DB hosts, Vault/External Secrets, Workload Identity client id, narrower egress CIDRs (`TODO(aks)`, `TODO(vault)`).
