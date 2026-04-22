# Target Java Microservices Architecture for CICS GenApp Migration

## 1. Context

The legacy application in `cicsdev/cics-genapp` is a CICS insurance demo that manages:

- Customers (create, update, inquire)
- Policies (create, update, delete, inquire)
- Statistics/Reports
- Setup & Web front-end via CICS transactions, BMS maps, COMMAREA data structures, and VSAM/DB2.

We are migrating to a modern Java microservices architecture hosted in `Cobol_migrated_6`, using REST APIs and a relational database.

---

## 2. Service Decomposition

### 2.1 Services

We define the following microservices:

1. **Customer Service**
   - Owns customer data and operations:
     - Create customer (COBOL: `lgacdb01`, `lgacus01`, `lgacvs01`)
     - Update customer (COBOL: `lgucdb01`, `lgucus01`, `lgucvs01`)
     - Inquire customer (COBOL: `lgicdb01`, `lgicus01`, `lgicvs01`)
   - Replaces customer-related COMMAREA and VSAM logic described in `lgcmarea.cpy` and related copybooks.

2. **Policy Service**
   - Owns policy data and operations:
     - Add policy (COBOL: `lgapdb01`, `lgapol01`, `lgapvs01`)
     - Update policy (COBOL: `lgupdb01`, `lgupol01`, `lgupvs01`)
     - Inquire policy (COBOL: `lgipdb01`, `lgipol01`, `lgipvs01`)
     - Delete policy (COBOL: `lgdpol01`, `lgdpvs01`)
   - Data model based on `lgpolicy.cpy`, `polloo2.cpy`, `pollook.cpy`.

3. **Statistics Service**
   - Generates statistics and reports similar to:
     - `lgastat1.cbl`
     - `lgstsq.cbl`
   - Aggregates data from Customer and Policy services via REST APIs and/or read replicas.

4. **Setup/Admin Service**
   - Handles environment/demo setup and maintenance logic from `lgsetup.cbl` and other setup utilities.
   - Provides admin endpoints for loading sample data and resetting the system for demos/tests.

5. **API Gateway / Web Front Service**
   - Replaces the CICS web/BMS front-end:
     - `lgwebst5.cbl` and `ssmap.bms`
     - SOA/web service copybooks (`soa*.cpy`).
   - Responsibilities:
     - Unified REST API façade for external clients.
     - Optional web UI (SPA or server-rendered).
     - Composition of underlying services for end-to-end flows (e.g. “Create customer + policy”).

---

## 3. Technology Stack

### 3.1 Common Stack

Each microservice:

- **Language / Framework**: Java 17+, Spring Boot 3.x
- **Packaging**: Maven, jar, containerized with Docker
- **Build**:
  - Spring Boot starter:
    - `spring-boot-starter-web`
    - `spring-boot-starter-data-jpa`
    - `spring-boot-starter-validation`
- **API Design**:
  - REST/JSON
  - OpenAPI/Swagger via `springdoc-openapi-starter-webmvc-ui`
- **Persistence**:
  - Relational DB (e.g. PostgreSQL/MySQL)
  - Spring Data JPA
  - Liquibase/Flyway for schema migration
- **Config / Discovery (optional for now)**:
  - Initially static configuration (no Eureka/Config Server)
  - Later extension to full Spring Cloud if needed.

---

## 4. Domain Model Mapping (COBOL → Java)

### 4.1 COBOL COMMAREA / Copybooks

Key legacy artifacts:

- `lgcmarea.cpy`: shared COMMAREA (request/response structure)
- `lgpolicy.cpy`: core policy structure
- `polloo2.cpy`, `pollook.cpy`: additional policy details
- SOA copybooks (`soaic01.cpy`, `soaipb1.cpy`, `soaipe1.cpy`, `soaiph1.cpy`, `soaipm1.cpy`,
  `soavcii.cpy`, `soavcio.cpy`, `soavpii.cpy`, `soavpio.cpy`): web service headers and payloads.

In the target architecture:

- Each COMMAREA structure becomes:
  - A JPA `@Entity` for persisted fields.
  - DTOs for requests/responses over REST.
- Groups and PIC clauses in copybooks are mapped to Java types:
  - `PIC X(n)` → `String`
  - `PIC 9(n)` → `Integer`, `Long`, or `BigDecimal` depending on size/scale.
  - Dates and timestamps mapped to `LocalDate` / `LocalDateTime`.

### 4.2 Customer Service Data Model (Conceptual)

Representative fields (final names should reflect copybook contents):

- `Customer` entity
  - `id` (generated database identifier)
  - `customerNumber` (from COMMAREA, primary business key)
  - `firstName`
  - `lastName`
  - `dateOfBirth`
  - `addressLine1`, `addressLine2`, `city`, `postcode`, `country`
  - `email`, `phone`
  - `createdAt`, `updatedAt`

Validation rules (derived from COBOL):
- Mandatory fields (e.g. name, address, customerNumber)
- Length constraints corresponding to PIC sizes
- Format checks for date, email, etc.

### 4.3 Policy Service Data Model (Conceptual)

Based on `lgpolicy.cpy` and related copybooks:

- `Policy` entity
  - `id`
  - `policyNumber`
  - `customerNumber` (foreign key / association)
  - `productCode`
  - `status` (e.g. ACTIVE, LAPSED, CANCELLED)
  - `effectiveDate`, `expiryDate`
  - `premiumAmount`
  - `coverageAmount`
  - Other fields from `polloo2.cpy` / `pollook.cpy`.

Business rules (mirroring COBOL logic):
- Date validity (effectiveDate <= expiryDate)
- Status transitions allowed in update operations
- Required linkage to a valid customer.

---

## 5. APIs (per Microservice)

### 5.1 Customer Service REST API

Base URL: `/api/customers`

Endpoints:

- `POST /api/customers`
  - Create a new customer.
  - Request: `CustomerCreateRequest`
  - Response: `CustomerResponse` (includes generated ID and customerNumber).
- `GET /api/customers/{customerNumber}`
  - Inquire customer (replacement for `lgicdb01`/`lgicus01`).
- `PUT /api/customers/{customerNumber}`
  - Update customer (replacement for `lgucdb01`/`lgucus01`).
- `DELETE /api/customers/{customerNumber}`
  - Optional; if legacy allowed deletion, else logical delete only.
- `GET /api/customers`
  - Search with optional filters (name, city, etc.).

### 5.2 Policy Service REST API

Base URL: `/api/policies`

Endpoints:

- `POST /api/policies`
  - Create policy (replacement for `lgapdb01`/`lgapol01`).
- `GET /api/policies/{policyNumber}`
  - Inquire policy (replacement for `lgipdb01`/`lgipol01`).
- `PUT /api/policies/{policyNumber}`
  - Update policy (replacement for `lgupdb01`/`lgupol01`).
- `DELETE /api/policies/{policyNumber}`
  - Delete policy (replacement for `lgdpol01`).
- `GET /api/policies`
  - Search policies by customerNumber, productCode, status, etc.

The Policy Service will verify that the referenced customer exists via a synchronous call to the Customer Service (or via a denormalized copy depending on design constraints).

### 5.3 Statistics Service REST API

Base URL: `/api/statistics`

Endpoints (examples mirroring `lgastat1` / `lgstsq`):

- `GET /api/statistics/summary`
  - High-level counts (customers, policies, policies by status, etc.).
- `GET /api/statistics/premiums`
  - Aggregations (premium totals per product, per time frame).
- `GET /api/statistics/customer/{customerNumber}`
  - Stats for a single customer (number of policies, aggregated premiums, etc.).

Internally, the Statistics Service queries its own read models or calls Customer and Policy services.

### 5.4 Setup/Admin Service REST API

Base URL: `/api/admin`

Endpoints:

- `POST /api/admin/demo-data`
  - Initialize or re-seed demo data (replacement for `lgsetup.cbl` behaviors).
- `POST /api/admin/reset`
  - Truncate / reset data for clean testing.

### 5.5 API Gateway / Web Front End

Base URL: Externally exposed, e.g. `/api` and `/ui`.

Responsibilities:

- Provide simplified composite operations, e.g.:
  - `POST /api/operations/customer-with-policy`
    - Wraps “create customer” and “create policy” into one call.
- Provide front-end web UI (if required) using:
  - A simple Spring MVC + Thymeleaf UI, or
  - Static SPA (React/Angular/Vue) served by the gateway.

The Gateway translates external contracts (if you need to mimic existing SOA copybooks) into internal REST calls to domain services.

---

## 6. Non-Functional Requirements

### 6.1 Cross-Cutting Concerns

All services:

- **Logging**: Structured logging with correlation IDs (derived from legacy transaction IDs where needed).
- **Error Handling**: Consistent error model, e.g.:
  - `code`, `message`, `details`
- **Security**:
  - Initially simple (basic auth / API key)
  - Evolvable to OAuth2/JWT.
- **Validation**:
  - Bean Validation (`@Valid`, `@NotNull`, etc.).
- **Observability** (optional initial phase):
  - `/actuator` endpoints for health, metrics.
  - Tracing (OpenTelemetry) in later phases.

### 6.2 Deployment

Target deployment model:

- Each microservice packaged as a Docker image.
- Compose them using:
  - Docker Compose for development.
  - Kubernetes (or equivalent) for production.

Example containers:
- `customer-service`
- `policy-service`
- `statistics-service`
- `admin-service`
- `api-gateway`
- `database` (PostgreSQL/MySQL)

---

## 7. Repository Structure (`Cobol_migrated_6`)

Proposed layout:

```text
Cobol_migrated_6/
  docs/
    architecture.md              # This document
  customer-service/
    pom.xml
    src/main/java/...
    src/test/java/...
  policy-service/
    pom.xml
    src/main/java/...
    src/test/java/...
  statistics-service/
    pom.xml
  admin-service/
    pom.xml
  api-gateway/
    pom.xml
  pom.xml                         # Parent Maven POM (multi-module)
  README.md
```

Each module is an independent Spring Boot application, with shared dependencies managed in the parent POM.

---

## 8. Migration Strategy

### 8.1 Stepwise Migration

1. **Customer Service First**
   - Implement basic CRUD based on `lgcmarea.cpy` and customer COBOL programs.
   - Verify equivalence via test scenarios mimicking existing test drivers (`lgtestc1.cbl`).

2. **Policy Service**
   - Implement CRUD including business rules for policy creation/update/deletion.
   - Use tests analogous to `lgtestp*.cbl`.

3. **Statistics Service**
   - Implement based on the queries and aggregates from `lgastat1` and `lgstsq`.

4. **Admin Service**
   - Implement data seeding and reset logic replicating `lgsetup.cbl`.

5. **API Gateway**
   - Implement façade endpoints and any required UI, mapping flows close to `lgwebst5.cbl` and `ssmap.bms`.

### 8.2 Data Migration

- Extract data definitions from `lgpolicy.cpy`, `lgcmarea.cpy`, and any DB/VSAM definitions.
- Design equivalent relational schemas (normalized tables for customers and policies).
- Implement SQL migration scripts (Flyway/Liquibase).
- Provide utilities or scripts to:
  - Bulk-load legacy sample data into the new DB.
  - Validate data correctness via automated tests.

---

## 9. Traceability to COBOL Components

For reference:

- **Customer Service**
  - `lgacdb01.cbl`, `lgacus01.cbl`, `lgacvs01.cbl`
  - `lgicdb01.cbl`, `lgicus01.cbl`, `lgicvs01.cbl`
  - `lgucdb01.cbl`, `lgucus01.cbl`, `lgucvs01.cbl`
  - Copybooks: `lgcmarea.cpy`, relevant sections of `soa*.cpy`.

- **Policy Service**
  - `lgapdb01.cbl`, `lgapol01.cbl`, `lgapvs01.cbl`
  - `lgipdb01.cbl`, `lgipol01.cbl`, `lgipvs01.cbl`
  - `lgupdb01.cbl`, `lgupol01.cbl`, `lgupvs01.cbl`
  - `lgdpol01.cbl`, `lgdpvs01.cbl`
  - Copybooks: `lgpolicy.cpy`, `polloo2.cpy`, `pollook.cpy`, `lgcmarea.cpy`, relevant `soa*.cpy`.

- **Statistics Service**
  - `lgastat1.cbl`, `lgstsq.cbl`.

- **Setup/Admin Service**
  - `lgsetup.cbl`, related JCL/scripts in `base/cntl`, `base/data`, etc.

- **API Gateway / Web Front**
  - `lgwebst5.cbl`, `ssmap.bms`
  - `soa*.cpy` copybooks describing web service mappings.

---

## 10. Open/Unclear Points

Because this is derived from a CICS demo and not a full spec, some details require interpretation:

- Exact mapping of all fields and field semantics from copybooks to Java entities/DTOs.
- Exact validation and error-handling behavior of each COBOL program.
- Any performance/SLA requirements beyond the demos implied usage.

These will be refined while implementing the microservices, guided by the COBOL logic and test programs.
