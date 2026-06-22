# GitOps (Argo CD)

Declarative deployment of the protogemcouch shim to the Kubernetes cluster. **Git is the source of
truth for what is deployed**; Argo CD reconciles the cluster to this directory. CI builds + publishes
the image; CD (here) only references it.

## Layout

```
gitops/
  bootstrap/root-app.yaml   # app-of-apps root — the only manifest you apply by hand
  apps/                     # Argo Applications (one per environment component)
    couchbase-staging.yaml  shim-staging.yaml    # staging — AUTO-sync
    couchbase-prod.yaml     shim-prod.yaml       # prod    — MANUAL sync (promotion gate)
  couchbase/                # shared single-node Couchbase (dev-grade) applied per namespace
  envs/<env>/values.yaml    # Helm values overlay for the shim per environment
```

## Environments

| Env     | Namespace     | Sync   | Couchbase            |
|---------|---------------|--------|----------------------|
| staging | `pgc-staging` | auto   | in-namespace, 1 node |
| prod    | `pgc-prod`    | manual | in-namespace, 1 node |

The shim chart lives in `charts/protogemcouch`; each env's Application renders it with its
`envs/<env>/values.yaml` (Argo multi-source `$values` ref). Image is **tag-pinned** in the values
file — that tag is the promotion knob (digest pinning + auto-update is a later step).

## Secrets (out of git)

Couchbase credentials are **not** committed. Each namespace needs a Secret `pgc-couchbase-creds`
(keys `cb-username` / `cb-password`); the shim mounts it (`couchbase.existingSecret`) and the Couchbase
init Job reads the same Secret. Create it before first sync:

```sh
kubectl create namespace pgc-staging
kubectl -n pgc-staging create secret generic pgc-couchbase-creds \
  --from-literal=cb-username=Administrator --from-literal=cb-password=password
```

(Future hardening: sealed-secrets or external-secrets so even this is git-managed.)

## Bootstrap

```sh
# Argo CD must already be installed in the argocd namespace.
kubectl apply -f gitops/bootstrap/root-app.yaml
```

The root app creates the four child Applications; staging auto-syncs (Couchbase StatefulSet + init
Job, then the 2-replica shim). Prod stays out-of-sync until you sync it.

## Promote staging → prod

1. Validate the tag in staging.
2. PR bumping `envs/prod/values.yaml` `image.tag`; merge to `main`.
3. Sync the prod Applications (UI **Sync**, or `argocd app sync couchbase-prod shim-prod`).

## Access Argo CD

```sh
kubectl -n argocd port-forward svc/argocd-server 8080:443   # https://localhost:8080, user: admin
```
