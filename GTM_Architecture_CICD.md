# GTM (Global Trade Management) — Architecture & CI/CD Overview

## Project Architecture

### Overview

I worked on a Global Trade Management (GTM) platform — a supply chain application used to manage and track shipments across the globe, moving through multiple transport modes like road, air, and ocean. The application is built on **Java and Spring Boot**, with **Oracle and PostgreSQL** as the underlying databases.

GTM is organized into multiple functional modules — **Import, Export, and Logistics** — each handling a distinct part of the trade lifecycle. I specifically worked on the **Logistics module**, which was responsible for shipment management and real-time shipment tracking.

Since real-time tracking depends on receiving live updates from multiple external and internal systems, integration was a core part of the Logistics module's responsibility.

### Integration Layer — PMS

To handle integrations cleanly, we built a dedicated microservice called **PMS**. Its core responsibility was **message transformation and routing** — converting inbound/outbound messages into the format expected by the target application, and routing them accordingly.

PMS handled two broad categories of integration:
- **Event-based integrations** — getting real-time shipment events/updates
- **Functional integrations** — for example, fetching charges, partner information, and similar data from other applications

We integrated with several applications within our own organization, as well as with an external application called **Shippeo**.

**Outbound flow (GTM → downstream):**
GTM → PMS (synchronous call) → PMS publishes the message to **Watermill** (asynchronous, pub/sub) → downstream services subscribe and consume the message from Watermill.

**Inbound flow (Shippeo → GTM):**
Shippeo publishes events to Watermill → PMS subscribes and consumes those events from Watermill → PMS routes the data to GTM.

So PMS sits on **both sides** of Watermill — it acts as a publisher for outbound flows and a consumer for inbound flows.

**Watermill** itself is a shared, Kafka-like pub/sub platform — but importantly, it isn't something we owned. It was a **centrally managed platform maintained by a separate team**, and used by multiple applications across the organization, not just GTM.

### Legacy Integration Path — Not Everything Goes Through PMS

Not all integrations were modernized to go through PMS — some older integrations still used a legacy approach, built around dedicated shipment processors:

- **MPEngine** — handled inbound data in XML format where the data was **not directly mapped one-to-one** with our DB table/column structure. This required actual parsing logic to transform the XML into our internal shipment data model before persisting it.
- **SEI Processor** — handled inbound data (JSON/XML) where the mapping to our DB structure **was** one-to-one, making the persistence logic more straightforward compared to MPEngine.

Both MPEngine and SEI processing are **part of the GTM application itself** (not separate microservices like PMS). Inbound messages for both flows land on a **common servlet endpoint** in GTM, which then invokes inbound processing code — this code, in turn, routes the message to either MPEngine or SEI processing logic depending on the message type.

### EDI Mappers — Upstream of GTM, Outside PMS

In global supply chain data exchange, the standard format is **EDI**. To bridge EDI with our internal formats, we use external mapping tools — **ecXpert** and **Mercator** — which convert EDI documents into MPE/SEI-compatible XML.

Importantly, these EDI mappers are **completely separate from the PMS/Watermill flow**. They sit upstream, and their output (MPE/SEI XML) is sent **directly to GTM's common servlet endpoint** — the same entry point used for the legacy MPEngine/SEI flow described above. This is a fully independent path that bypasses PMS and Watermill entirely.

### Authentication

Security across the system is **JWT-based**, involving two components:
- **Tenant server** — maintains user credentials, and is responsible for generating the JWT based on those credentials.
- **Central Auth server** — validates the JWT for incoming requests.

### Multi-Tenancy

Each customer runs on a **separate, dedicated GTM instance** — so the deployment model is isolated per customer rather than multi-tenant within a single instance. (Exact load characteristics per instance aren't something I have precise numbers on.)

Watermill, however, breaks from this isolated-per-customer pattern — being a shared platform, it's common infrastructure used across customers and across other applications in the organization, managed independently by another team.

### Other Core Functionalities

Beyond integration and shipment persistence, the Logistics module also handled:
- **Milestone schedule event calculation**
- **Alert triggers**
- **Policy and rules engine** for business rule evaluation

### Observability

Monitoring and troubleshooting relied on **application logs and Elasticsearch**, along with possibly a couple of other tools that aren't fully recalled at this time.

---

## CI/CD Pipeline

### Source Control & Build Trigger

Code changes are pushed to Git. Unlike a typical CI setup that triggers on every check-in, our **Jenkins job was scheduled to run twice a day** rather than being triggered per commit — a deliberate batching approach rather than continuous build-on-push.

### Build Process

1. The Jenkins job **builds a WAR file** as part of its process.
2. **SonarQube** is integrated into the pipeline for code quality analysis — however, it runs in a **non-blocking, reporting-only capacity**; a failing SonarQube scan does not fail the build.
3. Using this pre-built WAR file, the job then builds a **Docker image**, based on our Dockerfile.
4. At container startup, a **`.sh` script** is executed inside the container — this script contains the actual deployment logic:
   - It expects the WAR file (already built by the same Jenkins job) to be available.
   - It deploys this WAR file into a **Tomcat server running inside the Docker container**.

### Artifact Storage & Versioning

- Once the Docker image is built, it is **pushed to JFrog Artifactory**. Artifactory here serves purely as an **artifact storage system** — it is not a deployment environment in itself; it has separate profiles for organizing artifacts (distinct from actual deployment environments like QA).
- Each release follows a **version-based naming convention** — e.g., `26.1`, `26.2` — and the build artifact is named accordingly (e.g., `jar_name.26.1.jar`).

### Deployment

- The image is deployed to the **QA server**, using **Docker Compose** for orchestration.
- Both **image build/push to Artifactory** and the **subsequent deployment to QA** are **fully automated** — no manual approval gate exists between build completion and deployment.

### Notifications

Build and deployment status notifications are sent via **email**.

### Known Gap

- **Rollback strategy** is **not currently defined/established** — if a bad image gets deployed, there isn't a documented or automated process to revert to a previous version.
