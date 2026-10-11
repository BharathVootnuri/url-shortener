# Deploy the backend to Kubernetes

Backend only. Build the image, point it at PostgreSQL, then apply `k8s/`.

## 1. Build and push the image

```bash
docker build -t YOUR_REGISTRY/url-shortener:0.0.1 .
docker push YOUR_REGISTRY/url-shortener:0.0.1
```

On Windows PowerShell the commands are the same if Docker Desktop is running.

Edit `kustomization.yaml` so `images.newName` is `YOUR_REGISTRY/url-shortener`.

## 2. Fill in config and secrets

In `configmap.yaml`:

- `APP_BASE_URL` — the public origin clients will use (same host as the Ingress)
- optional `CORS_ALLOWED_ORIGINS`

In `secret.yaml` (or create the Secret with `kubectl`, do not commit real passwords):

- `DB_URL` — `jdbc:postgresql://HOST:5432/urlshortener`
- `DB_USERNAME` / `DB_PASSWORD`

Use a managed database in the cloud when you can. `postgres.yaml` is only a demo database inside the cluster. If you apply it, set:

```
DB_URL=jdbc:postgresql://postgres.url-shortener.svc.cluster.local:5432/urlshortener
```

and use the same user and password as in that file.

## 3. Apply

```bash
kubectl apply -k k8s
# optional demo database:
# kubectl apply -f k8s/postgres.yaml
```

The image defaults to `SPRING_PROFILES_ACTIVE=prod`. Flyway creates the schema on first start.

## 4. Check

```bash
kubectl -n url-shortener get pods,svc,ingress
kubectl -n url-shortener logs deploy/url-shortener
curl -s https://short.example.com/actuator/health
```

Readiness is `/actuator/health/readiness` (fails if PostgreSQL is down). Liveness is `/actuator/health/liveness`.

## Notes

- Rate limits and the click-recording queue are **per pod**. Two replicas roughly double the rate-limit budget; clicks still in a pod's queue are lost if that pod is killed.
- `server.forward-headers-strategy=framework` is on in the prod profile so rate limits use the client IP from `X-Forwarded-For` (trust your Ingress).
- Graceful shutdown waits up to 20s so queued clicks can flush; `terminationGracePeriodSeconds` is 40.
- Swagger UI and `/v3/api-docs` are off in prod. The contract is `docs/openapi.json`.
