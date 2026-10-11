# URL Shortener

Create short links, redirect from a short code, and read click analytics.

- **Backend:** Java 21, Spring Boot 4.1, Flyway, H2 (local) / PostgreSQL (prod)
- **Frontend:** Angular 22 in `frontend/` — a thin UI to exercise the API by hand

PostgreSQL, Docker, and Kubernetes steps are in [`docs/3.Setup-instructions.md`](docs/3.Setup-instructions.md).

---

## Start locally

Needs **JDK 21**. The UI also needs **Node 22.22+ or 24.15+**.

### 1. Backend — http://localhost:8080

From the repository root. Uses in-memory H2; Flyway creates the schema. Data is lost on restart.

```powershell
.\mvnw.cmd spring-boot:run
```

```bash
./mvnw spring-boot:run
```

### 2. Frontend — http://localhost:4200

Second terminal. The UI calls the backend at `http://localhost:8080` (CORS is already allowed in the `local` profile).

```powershell
cd frontend
npm install
npm start
```

Start the backend first. Open http://localhost:4200 to create links, follow them, and view analytics.

| URL | What |
| --- | --- |
| http://localhost:4200 | Angular UI |
| http://localhost:8080/swagger-ui.html | Interactive API docs (`local` only) |
| http://localhost:8080/v3/api-docs | OpenAPI JSON (`local` only) |
| http://localhost:8080/actuator/health | Health |
| http://localhost:8080/h2-console | H2 console. JDBC URL `jdbc:h2:mem:urlshortener`, user `sa`, empty password |

---

## Tests

```powershell
.\mvnw.cmd verify
```

```bash
./mvnw verify
```

See [`docs/4.Testing-approach.md`](docs/4.Testing-approach.md).

---

## More

| Doc | What |
| --- | --- |
| [`docs/3.Setup-instructions.md`](docs/3.Setup-instructions.md) | PostgreSQL, Docker, Kubernetes, troubleshooting |
| [`docs/0.Requirements-analysis.md`](docs/0.Requirements-analysis.md) | Requirements, decisions, acceptance criteria |
| [`docs/1.Architecture.md`](docs/1.Architecture.md) | Backend architecture |
| [`docs/2.Scenarios.md`](docs/2.Scenarios.md) | Greenfield / brownfield notes |
| [`docs/openapi.json`](docs/openapi.json) | Committed HTTP contract |
| [`k8s/README.md`](k8s/README.md) | Cluster deploy notes |
