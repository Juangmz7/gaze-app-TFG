# infra/k8s

GitOps deployment of Gaze on Kubernetes (single-node k3s on WSL2 now, AKS later), synced by Argo CD from `main`.

Full documentation: [`docs/k8s.md`](../../docs/k8s.md).

Quick start (inside WSL2 Ubuntu, systemd + Docker Engine; `DB_BIND_IP=0.0.0.0` in `infra/local/.env`):

```bash
docker compose -f infra/local/compose.yaml --env-file infra/local/.env up -d   # databases (out of cluster)
./infra/k8s/bootstrap/bootstrap.sh                                           # k3s + Argo CD + root app
```

Then, from an elevated PowerShell on Windows: `infra/k8s/bootstrap/windows-portproxy.ps1`.

| Want to... | Edit |
|---|---|
| Change a service's config | `services/<service>/values.yaml` / `values-<env>.yaml` |
| Add a service | new folder `services/<service>/` (picked up by the ApplicationSet) |
| Change env infra (gateway, rabbitmq, redis, keycloak) | `environments/<env>/<component>.yaml` |
| Change what every service gets | `charts/microservice/` |
| Enable prod | `enabled: "true"` in `argocd/applicationsets/*.yaml` |
