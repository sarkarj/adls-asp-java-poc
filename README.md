# ADLS Gen2 ↔ Azure App Service File Transfer PoC
### Java 21 · Spring Boot 3.3 · Managed Identity · OIDC · Zero Secrets · Zero Hardcoded IDs

![Build](https://img.shields.io/badge/build-passing-brightgreen)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green)
![Azure](https://img.shields.io/badge/Azure-App%20Service%20B1%20Linux-blue)
![Auth](https://img.shields.io/badge/Auth-Managed%20Identity-green)
![Secrets](https://img.shields.io/badge/Secrets%20in%20Code-Zero-brightgreen)

---

## ⚡ 30-Second Read

| What | Detail |
|---|---|
| **Goal** | Bidirectional file transfer between Azure Data Lake Storage Gen2 and Azure App Service |
| **Auth** | System-Assigned Managed Identity — zero credentials, zero secrets anywhere |
| **Deploy** | GitHub Actions with OIDC federated credentials — no service principal secrets |
| **Language** | Java 21 — records, virtual threads, fail-fast validation |
| **Framework** | Spring Boot 3.3 + Spring MVC — production-grade from day one |
| **Build** | GitHub Actions builds + deploys — no Java or Maven needed locally |
| **Rebuild Time** | < 15 minutes from scratch (repo + Java code retained) |
| **Cost** | ~$0.018/hr on B1 — teardown stops all billing instantly |

> **Proven:** Full ADLS Gen2 ↔ App Service bidirectional transfer, MSI auth,
> parameterized read/write, Spring Boot Actuator, production-grade CI/CD pipeline.
> Safe to make repo public — zero IDs, zero secrets hardcoded anywhere.

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        GitHub Actions                           │
│                                                                 │
│  on: push to main                                               │
│       │                                                         │
│       ├── Build JAR (Java 21 / Maven / Spring Boot)            │
│       │                                                         │
│       └── OIDC Token Exchange ──► Azure AD                     │
│               (no secrets)              │                       │
│                                         ▼                       │
│                              Deploy JAR to App Service          │
└─────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│              Azure App Service (B1, Linux, centralus)           │
│                                                                 │
│   webapp-adls-poc.azurewebsites.net                             │
│                                                                 │
│   GET  /actuator/health        → liveness probe (Actuator)      │
│   GET  /read?file={fileName}   → ADLS Gen2 → App Service        │
│   POST /write?file={fileName}  → ASP generates content → ADLS  │
│                                                                 │
│   App Settings (non-sensitive config only):                     │
│     AZURE_STORAGE_ACCOUNT_NAME   = adlspocstore001              │
│     AZURE_STORAGE_CONTAINER_NAME = raw-data                     │
│                                                                 │
│   System-Assigned Managed Identity ──────────────────────────┐  │
│   (Azure manages token, rotates automatically)               │  │
└──────────────────────────────────────────────────────────────┼──┘
                                                               │
                  RBAC: Storage Blob Data Contributor          │
                  Scope: adlspocstore001 only                  │
                  Network: App Service IPs only                │
                                                               ▼
┌─────────────────────────────────────────────────────────────────┐
│              ADLS Gen2 (Standard LRS, eastus)                   │
│                                                                 │
│   adlspocstore001                                               │
│   └── raw-data  (container)                                     │
│       ├── sample.txt  ◄── read (ADLS → ASP)                     │
│       └── output.txt  ◄── write (ASP → ADLS)                    │
│                                                                 │
│   Security:                                                     │
│     ✅ HTTPS only · TLS 1.2 minimum                             │
│     ✅ No public blob access                                     │
│     ✅ No shared key access — AAD only                          │
│     ✅ Network firewall — App Service IPs only                  │
│     ✅ Blob soft delete (7 days)                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🎯 Objective

Prove bidirectional secure file transfer between **Azure Data Lake Storage Gen2**
and **Azure App Service** using:

- **Managed Identity (MSI)** — eliminates all credential management
- **Java 21** — modern language features, production-grade patterns
- **Spring Boot 3.3 + Spring MVC** — production-grade REST API
- **GitHub Actions + OIDC** — zero-secret CI/CD pipeline
- **Principle of Least Privilege** — Storage Blob Data Contributor (minimum for read+write)
- **Zero hardcoded IDs** — all values derived dynamically at runtime

---

## 🔐 Security Architecture

### Authentication Chain

```
GitHub Actions
    │
    │  OIDC JWT Token (ephemeral, per-run)
    ▼
Azure Active Directory
    │  Validates: issuer + subject + audience
    │  subject = repo:<owner>/<repo>:ref:refs/heads/main
    ▼
Temporary Access Token (deploy scope only)
    │
    ▼
App Service Deployment

App Service Runtime
    │
    │  System-Assigned MSI
    │  Azure manages token lifecycle automatically
    ▼
ADLS Gen2 (Storage Blob Data Contributor)
    ├── Read: raw-data/* → App Service
    └── Write: App Service → raw-data/*
```

### Security Decisions

| Control | Implementation | Why |
|---|---|---|
| Zero secrets in code | `@Value` config injection | Prevents accidental exposure |
| Zero secrets in GitHub | OIDC — IDs only, no passwords | No blast radius if repo leaked |
| Zero secrets in App Settings | MSI handles auth entirely | No rotation risk |
| Zero hardcoded IDs in README | All derived from `az login` | Safe to make repo public |
| ManagedIdentityCredential direct | Skips 6-provider chain | Faster cold start on App Service |
| Spring-managed ADLS client | Singleton @Bean | Eliminates race condition |
| Log injection prevention | `LogUtils.sanitize()` on all user-controlled log values | Blocks CWE-117 newline injection |
| Path traversal prevention | Strict filename regex allowlist | Rejects `..`, `/`, `\`, null bytes |
| File size cap on read | 10MB hard limit in service | Guards against OOM |
| POST body size cap on write | 10MB via Spring properties | Guards against large payload attacks |
| TLS 1.2 minimum | Enforced at storage account level | Blocks obsolete protocols |
| No shared key access | `--allow-shared-key-access false` | Forces AAD auth only |
| No public blob access | `--allow-blob-public-access false` | Zero anonymous access |
| FTP disabled | `--ftps-state Disabled` | Reduces attack surface |
| SCM basic auth disabled | `basicPublishingCredentialsPolicies` | Kudu via AAD/portal only |
| ADLS network firewall | App Service outbound IPs only | No other source can reach ADLS |
| RBAC tightly scoped | Contributor on single storage account | Least privilege for read+write |
| Blob soft delete | 7-day recovery window | Accidental write recovery |
| Error sanitization | Bearer/SAS tokens stripped from logs | Prevents token leakage |
| `@ExceptionHandler` | No stack traces in responses | Prevents internal exposure |
| `show-details=never` | Actuator returns UP/DOWN only | No internal details exposed |
| OWASP dependency scan | `dependency-check-maven` in pipeline | Catches CVEs in dependencies before deploy |

---

## 📋 Prerequisites

```
✅ Azure CLI     → brew install azure-cli
✅ GitHub CLI    → brew install gh
✅ Git           → pre-installed on Mac
✅ Azure Pay-As-You-Go account  → portal.azure.com
✅ GitHub repo cloned           → github.com/<your-username>/adls-asp-java-poc
✅ gh authenticated             → gh auth login
✅ az authenticated             → az login
```

> Java 21 and Maven are NOT required locally.
> GitHub Actions builds and deploys everything on ubuntu-latest runners.

---

## ⚠️ Rebuild Considerations

> Read this section before starting if rebuilding after a teardown.

### Service Principal Already Exists
After `az group delete`, the SP `sp-github-deploy-poc` remains in Azure AD.
`az ad sp create-for-rbac` will fail on rebuild. Phase 7 handles this automatically.

### Resource Providers Persist
`Microsoft.Storage`, `Microsoft.Web`, `Microsoft.ManagedIdentity` stay registered
after teardown. Phase 1 includes a safety check regardless.

### B1 Linux Quota Persists
Quota approved at subscription level — survives teardown.
`centralus` remains available.

### GitHub Secrets — AZURE_CLIENT_ID Must Update
After SP delete + recreate, `AZURE_CLIENT_ID` must be updated — new SP = new App ID.
Phase 8 always updates all secrets. Do not skip.

### App Settings Display Behavior
`az webapp config appsettings set` always shows `null` values in its response —
this is normal Azure CLI behavior for security. Always verify with
`az webapp config appsettings list` to confirm actual values.

### @Configuration Class and proxyBeanMethods
Spring Boot's CGLIB proxy requires `@Configuration` classes to be subclassable.
`AdlsConfig.java` uses `@Configuration(proxyBeanMethods = false)` which allows
the `final` security modifier while satisfying Spring's proxy requirement.
Do NOT remove `proxyBeanMethods = false` from `AdlsConfig.java` — it is required
for the application to start.

---

## ⏱️ 15-Minute Rebuild Guide

> **Assumption:** GitHub repo + Java code + workflow retained.
> Only Azure infrastructure needs to be recreated.

---

### Phase 1 · Azure Login + Set Variables (1 min)

```bash
az login

# Derive ALL IDs from login context — zero hardcoding
export TENANT_ID=$(az account show --query tenantId -o tsv)
export SUBSCRIPTION_ID=$(az account show --query id -o tsv)
export ACCOUNT_EMAIL=$(az account show --query user.name -o tsv)
export RESOURCE_GROUP="rg-adls-asp-poc"
export STORAGE_ACCOUNT="adlspocstore001"
export CONTAINER_NAME="raw-data"
export WEBAPP_NAME="webapp-adls-poc"
export ASP_NAME="asp-adls-poc"
export SP_NAME="sp-github-deploy-poc"
export GITHUB_REPO="sarkarj/adls-asp-java-poc"

az account show --query "{Name:name, State:state}" -o table
echo "TENANT_ID       : $TENANT_ID"
echo "SUBSCRIPTION_ID : $SUBSCRIPTION_ID"

# Provider safety check
az provider register --namespace Microsoft.Storage
az provider register --namespace Microsoft.Web
az provider register --namespace Microsoft.ManagedIdentity

az provider show --namespace Microsoft.Storage --query "registrationState" -o tsv
az provider show --namespace Microsoft.Web --query "registrationState" -o tsv
az provider show --namespace Microsoft.ManagedIdentity --query "registrationState" -o tsv
```

---

### Phase 2 · Resource Group (30 sec)

```bash
az group create \
  --name $RESOURCE_GROUP \
  --location eastus
```

---

### Phase 3 · ADLS Gen2 + Container + File (2 min)

```bash
az storage account create \
  --name $STORAGE_ACCOUNT \
  --resource-group $RESOURCE_GROUP \
  --location eastus \
  --sku Standard_LRS \
  --kind StorageV2 \
  --hns true \
  --https-only true \
  --min-tls-version TLS1_2 \
  --allow-blob-public-access false \
  --allow-shared-key-access false

az storage fs create \
  --name $CONTAINER_NAME \
  --account-name $STORAGE_ACCOUNT \
  --auth-mode login

echo "ADLS to ASP Transfer PoC — $(date)" > sample.txt

az storage fs file upload \
  --source sample.txt \
  --path sample.txt \
  --file-system $CONTAINER_NAME \
  --account-name $STORAGE_ACCOUNT \
  --auth-mode login

az storage fs file list \
  --file-system $CONTAINER_NAME \
  --account-name $STORAGE_ACCOUNT \
  --auth-mode login \
  --query "[].{Name:name, Size:contentLength}" \
  -o table
```

---

### Phase 4 · App Service Plan + Web App (2 min)

```bash
# centralus — eastus has Linux B1 capacity issues on new subscriptions
az appservice plan create \
  --name $ASP_NAME \
  --resource-group $RESOURCE_GROUP \
  --location centralus \
  --sku B1 \
  --is-linux

az webapp create \
  --name $WEBAPP_NAME \
  --resource-group $RESOURCE_GROUP \
  --plan $ASP_NAME \
  --runtime "JAVA:21-java21"

az webapp update \
  --name $WEBAPP_NAME \
  --resource-group $RESOURCE_GROUP \
  --https-only true

# ⚠️ App Service renames ALL deployed JARs to app.jar automatically
az webapp config set \
  --name $WEBAPP_NAME \
  --resource-group $RESOURCE_GROUP \
  --ftps-state Disabled \
  --min-tls-version 1.2 \
  --startup-file "java -jar /home/site/wwwroot/app.jar"
```

---

### Phase 5 · App Settings (30 sec)

```bash
az webapp config appsettings set \
  --name $WEBAPP_NAME \
  --resource-group $RESOURCE_GROUP \
  --settings \
    AZURE_STORAGE_ACCOUNT_NAME="$STORAGE_ACCOUNT" \
    AZURE_STORAGE_CONTAINER_NAME="$CONTAINER_NAME" \
    SPRING_PROFILES_ACTIVE="production" \
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE="health" \
    MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS="never" \
    LOGGING_LEVEL_COM_POC_ADLS="INFO" \
    LOGGING_LEVEL_COM_AZURE="WARN" \
    SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE="10MB" \
    SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE="10MB"

# Verify — list shows real values (set response always shows null)
az webapp config appsettings list \
  --name $WEBAPP_NAME \
  --resource-group $RESOURCE_GROUP \
  --query "[].{Key:name, Value:value}" \
  -o table
```

---

### Phase 6 · Managed Identity + RBAC (2 min)

```bash
az webapp identity assign \
  --name $WEBAPP_NAME \
  --resource-group $RESOURCE_GROUP

# ⚠️ MSI takes 30s to propagate to Azure AD — RBAC assignment fails without this wait
echo "Waiting 30s for MSI propagation to Azure AD..."
sleep 30

export PRINCIPAL_ID=$(az webapp identity show \
  --name $WEBAPP_NAME \
  --resource-group $RESOURCE_GROUP \
  --query principalId -o tsv)

echo "Principal ID: $PRINCIPAL_ID"

# Storage Blob Data Contributor — minimum role for read + write
az role assignment create \
  --assignee $PRINCIPAL_ID \
  --role "Storage Blob Data Contributor" \
  --scope /subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$STORAGE_ACCOUNT

# ⚠️ Use --scope for reliable results — list without scope may return empty
az role assignment list \
  --assignee $PRINCIPAL_ID \
  --scope /subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$STORAGE_ACCOUNT \
  --query "[].{Role:roleDefinitionName, Principal:principalType}" \
  -o table
```

Expected:
```
Role                           Principal
-----------------------------  ----------------
Storage Blob Data Contributor  ServicePrincipal
```

---

### Phase 7 · Service Principal + OIDC Federated Credentials (2 min)

```bash
# Auto-detect and delete existing SP before recreate
SP_EXISTS=$(az ad sp list \
  --display-name $SP_NAME \
  --query "[0].appId" -o tsv)

if [ -n "$SP_EXISTS" ]; then
  echo "Existing SP found — deleting before recreate..."
  az ad app delete --id $SP_EXISTS
  echo "Deleted — proceeding"
else
  echo "No existing SP — proceeding to create"
fi

az ad sp create-for-rbac \
  --name $SP_NAME \
  --role contributor \
  --scopes /subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP

export APP_ID=$(az ad sp list \
  --display-name $SP_NAME \
  --query "[0].appId" -o tsv)

echo "App ID: $APP_ID"

# ⚠️ Use ${GITHUB_REPO} with braces — plain $GITHUB_REPO:ref is misread
# by zsh as the :r string modifier, silently corrupting the subject
az ad app federated-credential create \
  --id $APP_ID \
  --parameters "{
    \"name\": \"github-actions-main\",
    \"issuer\": \"https://token.actions.githubusercontent.com\",
    \"subject\": \"repo:${GITHUB_REPO}:ref:refs/heads/main\",
    \"audiences\": [\"api://AzureADTokenExchange\"],
    \"description\": \"GitHub Actions OIDC for main branch\"
  }"

az ad app federated-credential create \
  --id $APP_ID \
  --parameters "{
    \"name\": \"github-actions-dispatch\",
    \"issuer\": \"https://token.actions.githubusercontent.com\",
    \"subject\": \"repo:${GITHUB_REPO}:workflow_dispatch\",
    \"audiences\": [\"api://AzureADTokenExchange\"],
    \"description\": \"GitHub Actions OIDC for manual dispatch\"
  }"

# ⚠️ Verify before Phase 8 — subjects must be exact
az ad app federated-credential list \
  --id $APP_ID \
  --query "[].{Name:name, Subject:subject}" \
  -o table
```

Expected — verify before proceeding:
```
Name                     Subject
-----------------------  --------------------------------------------------
github-actions-main      repo:sarkarj/adls-asp-java-poc:ref:refs/heads/main
github-actions-dispatch  repo:sarkarj/adls-asp-java-poc:workflow_dispatch
```

If subject is wrong → delete and recreate:
```bash
az ad app federated-credential delete \
  --id $APP_ID \
  --federated-credential-id github-actions-main
```

---

### Phase 8 · GitHub Secrets (1 min)

```bash
gh auth status || gh auth login

# ⚠️ AZURE_CLIENT_ID must always be updated after SP delete + recreate
# New SP generates new App ID — old value causes OIDC login failure
gh secret set AZURE_CLIENT_ID \
  --body "$APP_ID" \
  --repo $GITHUB_REPO

gh secret set AZURE_TENANT_ID \
  --body "$TENANT_ID" \
  --repo $GITHUB_REPO

gh secret set AZURE_SUBSCRIPTION_ID \
  --body "$SUBSCRIPTION_ID" \
  --repo $GITHUB_REPO

gh secret set AZURE_STORAGE_ACCOUNT_NAME \
  --body "$STORAGE_ACCOUNT" \
  --repo $GITHUB_REPO

gh secret set AZURE_STORAGE_CONTAINER_NAME \
  --body "$CONTAINER_NAME" \
  --repo $GITHUB_REPO

# Verify — 5 secrets
gh secret list --repo $GITHUB_REPO
```

---

### Phase 9 · Security Hardening (2 min)

```bash
# C2 — ADLS network firewall: App Service outbound IPs only
export OUTBOUND_IPS=$(az webapp show \
  --name $WEBAPP_NAME \
  --resource-group $RESOURCE_GROUP \
  --query "outboundIpAddresses" -o tsv)

az storage account update \
  --name $STORAGE_ACCOUNT \
  --resource-group $RESOURCE_GROUP \
  --default-action Deny \
  --bypass AzureServices

for IP in $(echo $OUTBOUND_IPS | tr ',' ' '); do
  az storage account network-rule add \
    --account-name $STORAGE_ACCOUNT \
    --resource-group $RESOURCE_GROUP \
    --ip-address $IP
  echo "Allowed IP: $IP"
done

# C3 — Disable SCM basic auth (Kudu still accessible via Azure portal / AAD)
az resource update \
  --resource-group $RESOURCE_GROUP \
  --name scm \
  --namespace Microsoft.Web \
  --resource-type basicPublishingCredentialsPolicies \
  --parent sites/$WEBAPP_NAME \
  --set properties.allow=false

az resource update \
  --resource-group $RESOURCE_GROUP \
  --name ftp \
  --namespace Microsoft.Web \
  --resource-type basicPublishingCredentialsPolicies \
  --parent sites/$WEBAPP_NAME \
  --set properties.allow=false

# C4 — Health check path (Spring Actuator)
az webapp config set \
  --name $WEBAPP_NAME \
  --resource-group $RESOURCE_GROUP \
  --generic-configurations '{"healthCheckPath": "/actuator/health"}'

# H1 — Diagnostic logging
az webapp log config \
  --name $WEBAPP_NAME \
  --resource-group $RESOURCE_GROUP \
  --application-logging filesystem \
  --level information \
  --detailed-error-messages true \
  --web-server-logging filesystem

# H2 — Blob soft delete (7 day recovery)
az storage blob service-properties delete-policy update \
  --account-name $STORAGE_ACCOUNT \
  --auth-mode login \
  --enable true \
  --days-retained 7

# H3 — Blob versioning SKIPPED
# ⚠️ Not supported on ADLS Gen2 HNS-enabled accounts (FeatureNotSupportedForAccount)
# Soft delete (H2) provides recovery capability as alternative
```

---

### Phase 10 · Monitoring — Application Insights (3 min)

> ⚠️ Run each step separately and verify before proceeding to the next.
> `az webapp config appsettings set` always shows `null` in its response —
> this is normal. Always verify with `list`.

#### Step 10A · Register Required Providers

```bash
az provider register --namespace microsoft.operationalinsights
az provider register --namespace microsoft.insights

# ⚠️ Wait until BOTH show Registered before proceeding — takes 1-2 min
az provider show \
  --namespace microsoft.operationalinsights \
  --query "registrationState" -o tsv

az provider show \
  --namespace microsoft.insights \
  --query "registrationState" -o tsv
```

Expected — do NOT proceed until both show:
```
Registered
Registered
```

---

#### Step 10B · Create Application Insights

```bash
az monitor app-insights component create \
  --app adls-poc-insights \
  --location centralus \
  --resource-group $RESOURCE_GROUP \
  --application-type web

# Verify creation before capturing key
az monitor app-insights component show \
  --app adls-poc-insights \
  --resource-group $RESOURCE_GROUP \
  --query "{Name:name, State:provisioningState}" \
  -o table
```

Expected:
```
Name              State
----------------  ---------
adls-poc-insights Succeeded
```

---

#### Step 10C · Capture Key + Wire to App Service

```bash
export APPINSIGHTS_KEY=$(az monitor app-insights component show \
  --app adls-poc-insights \
  --resource-group $RESOURCE_GROUP \
  --query instrumentationKey -o tsv)

echo "Key captured: ${APPINSIGHTS_KEY:0:8}..."

az webapp config appsettings set \
  --name $WEBAPP_NAME \
  --resource-group $RESOURCE_GROUP \
  --settings \
    APPLICATIONINSIGHTS_CONNECTION_STRING="InstrumentationKey=$APPINSIGHTS_KEY" \
    ApplicationInsightsAgent_EXTENSION_VERSION="~3"

# Verify key wired correctly (list shows real values)
az webapp config appsettings list \
  --name $WEBAPP_NAME \
  --resource-group $RESOURCE_GROUP \
  --query "[?name=='APPLICATIONINSIGHTS_CONNECTION_STRING'].value" \
  -o tsv
```

Expected:
```
InstrumentationKey=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx;IngestionEndpoint=...
```

---

### Phase 11 · Deploy (2 min)

```bash
gh workflow run deploy.yml --repo $GITHUB_REPO

sleep 3
export RUN_ID=$(gh run list \
  --repo $GITHUB_REPO \
  --workflow deploy.yml \
  --limit 1 \
  --json databaseId \
  --jq ".[0].databaseId")

echo "Pipeline Run ID: $RUN_ID"
gh run watch $RUN_ID --repo $GITHUB_REPO
```

Pipeline stages:
```
✅ Build JAR       (~20s) — Java 21 + Spring Boot Maven Plugin
✅ OWASP Scan      (~60s) — CVE check on all dependencies
✅ Azure Login     (~5s)  — OIDC token exchange, zero secrets
✅ Set Startup     (~5s)  — java -jar /home/site/wwwroot/app.jar
✅ Deploy JAR      (~40s) — ~55MB Spring Boot fat JAR
```

---

### Phase 12 · Verify All Endpoints (1 min)

```bash
# Wait 60s after deploy — Spring Boot cold start on B1

# Liveness probe (Spring Actuator)
curl https://${WEBAPP_NAME}.azurewebsites.net/actuator/health

# Read: ADLS Gen2 → App Service
curl "https://${WEBAPP_NAME}.azurewebsites.net/read?file=sample.txt"

# Write: App Service generates content → ADLS Gen2 (no body needed)
curl -X POST \
  "https://${WEBAPP_NAME}.azurewebsites.net/write?file=output.txt"

# Verify write: read the file we just wrote
curl "https://${WEBAPP_NAME}.azurewebsites.net/read?file=output.txt"
```

Expected:
```
{"status":"UP","groups":["liveness","readiness"]}

╔══════════════════════════════════════════╗
║        READ SUCCESSFUL  ✅  (ADLS→ASP)   ║
╚══════════════════════════════════════════╝
File      : sample.txt
Bytes     : 79
Timestamp : <timestamp>
Content   : ADLS to ASP Transfer PoC — ...

╔══════════════════════════════════════════╗
║       WRITE SUCCESSFUL  ✅  (ASP→ADLS)   ║
╚══════════════════════════════════════════╝
File      : output.txt
Bytes     : 661
Timestamp : <timestamp>

╔══════════════════════════════════════════╗
║        READ SUCCESSFUL  ✅  (ADLS→ASP)   ║
╚══════════════════════════════════════════╝
File      : output.txt
Content   : Generated by Azure App Service ...
```

---

## 💡 Key Concept — App Service is Stateless

Files are **NOT** stored in App Service. App Service is a stateless compute layer — it has no persistent disk.

### Read (ADLS → ASP)

```
curl GET /read?file=sample.txt
        │
        ▼
App Service reads sample.txt FROM ADLS into memory
        │
        ▼
Returns content to caller as HTTP response
        │
        ▼
Memory discarded — file never touches App Service disk
```

> `sample.txt` lives permanently in ADLS Gen2 only.
> App Service is a read-through proxy — stateless by design.

### Write (ASP → ADLS)

```
curl POST /write?file=output.txt
        │
        ▼
App Service generates content from its own environment
(hostname, instance ID, region, timestamp)
        │
        ▼
Writes generated content directly to ADLS Gen2
        │
        ▼
App Service retains nothing — ADLS is the system of record
```

> `output.txt` is created permanently in ADLS Gen2.
> App Service only originates the content — never holds it.

### Where Files Actually Live

```
ADLS Gen2 (adlspocstore001)      ← permanent storage
  └── raw-data/
      ├── sample.txt              ← read source
      └── output.txt              ← write destination

App Service (webapp-adls-poc)    ← stateless compute
  └── no files — ever
```

---

## 🔗 Full Transfer Chain

```
GET /read?file=sample.txt
    │
    ▼
AdlsController.readFile("sample.txt")
    │  @GetMapping — GET only, no other methods
    │  @RequestParam — 400 if missing
    ▼
AdlsService.readFile("sample.txt")
    │  LogUtils.sanitize(fileName) → safe log output (CWE-117)
    │  validateFileName() → regex allowlist
    │  ManagedIdentityCredential → Storage Blob Data Contributor
    │  fileClient.getProperties() → verify exists + size
    │  Guard: size > 10MB → reject
    │  fileClient.read() → stream to ByteArrayOutputStream
    │  sanitizeError() → strip tokens from errors
    ▼
TransferResult.success(fileName, bytes, preview)
    │  Java 21 record — immutable, thread-safe
    ▼
ResponseEntity 200 — text/plain

─────────────────────────────────────────────

POST /write?file=output.txt  (no body — ASP is the content source)
    │
    ▼
AdlsController.writeFile("output.txt")
    │  @PostMapping — POST only, no body accepted
    │  @RequestParam — 400 if missing
    ▼
AdlsService.writeGeneratedContent("output.txt")
    │  LogUtils.sanitize(fileName) → safe log output (CWE-117)
    │  validateFileName() → regex allowlist + null byte check
    │  resolveEnv(WEBSITE_HOSTNAME, WEBSITE_INSTANCE_ID, REGION_NAME)
    │  Generates structured content from App Service environment
    │  writeToAdls() → fileClient.create(overwrite=true)
    │               → fileClient.append(inputStream, offset=0, length)
    │               → fileClient.flush(length, overwrite=true) → commit
    ▼
TransferResult.written(fileName, bytes)
    │  Java 21 record — immutable
    ▼
ResponseEntity 201 — text/plain
```

---

## 🗂️ Project Structure

```
adls-asp-java-poc/
├── .github/
│   └── workflows/
│       └── deploy.yml                    ← OIDC build + deploy + OWASP scan pipeline
├── src/main/java/com/poc/adls/
│   ├── AdlsApplication.java             ← @SpringBootApplication entry point
│   ├── AdlsConfig.java                  ← @Configuration(proxyBeanMethods=false) — MSI + ADLS client @Bean
│   ├── AdlsController.java              ← @RestController — /read + /write endpoints
│   ├── AdlsService.java                 ← @Service — readFile() + writeGeneratedContent()
│   ├── GlobalExceptionHandler.java      ← @RestControllerAdvice — global error handler
│   ├── LogUtils.java                    ← Log injection prevention — sanitizes user input
│   └── TransferResult.java             ← Java 21 record — immutable result type
├── src/main/resources/
│   └── application.properties           ← Spring Boot config
├── pom.xml                              ← Spring Boot 3.3 + Maven + OWASP plugin
├── .gitignore
└── README.md
```

---

## ☁️ Azure Resource Inventory

| Resource | Name | Type | Location | Note |
|---|---|---|---|---|
| Resource Group | `rg-adls-asp-poc` | Container | eastus | Teardown = delete this |
| Storage Account | `adlspocstore001` | ADLS Gen2 | eastus | HNS + AAD + firewall |
| Container | `raw-data` | Filesystem | — | No public access |
| App Service Plan | `asp-adls-poc` | B1 Linux | centralus | eastus has capacity issues |
| Web App | `webapp-adls-poc` | Java 21 | centralus | Spring Boot + MSI |
| Managed Identity | System-Assigned | MSI | centralus | Tied to web app lifecycle |
| Service Principal | `sp-github-deploy-poc` | OIDC only | Azure AD | Survives teardown |
| App Insights | `adls-poc-insights` | Monitoring | centralus | Request + error tracking |

---

## 🔍 Final Security Audit

| Vector | Status |
|---|---|
| Secrets in source code | ❌ Zero |
| IDs hardcoded in README | ❌ Zero — all derived from login |
| Secrets in GitHub Actions | ❌ Zero — IDs only |
| Secrets in App Service settings | ❌ Zero — config values only |
| Credential rotation | ✅ Automatic — Azure managed |
| OIDC token lifetime | ✅ Ephemeral — per pipeline run |
| Storage transport | ✅ HTTPS + TLS 1.2 enforced |
| Storage auth | ✅ AAD only — shared keys disabled |
| Storage network | ✅ App Service IPs only — firewall enforced |
| Public blob access | ✅ Disabled |
| FTP access | ✅ Disabled |
| SCM basic auth | ✅ Disabled — Kudu via AAD/portal only |
| RBAC scope | ✅ Contributor only on single storage account |
| Log injection (CWE-117) | ✅ LogUtils.sanitize() on all user-controlled log values |
| Path traversal | ✅ Strict regex allowlist on all file names |
| Null byte injection | ✅ Explicit null byte check |
| Blob soft delete | ✅ 7-day recovery window |
| Blob versioning | ⚠️ Not supported on ADLS Gen2 HNS accounts |
| POST body size | ✅ 10MB cap via Spring properties |
| No external write input | ✅ ASP generates content — no body accepted |
| Error log sanitization | ✅ Bearer/SAS tokens stripped |
| Stack traces in responses | ✅ Never — @ExceptionHandler + @RestControllerAdvice |
| Spring error page details | ✅ Never — server.error.include-message=never |
| Actuator details | ✅ Never — UP/DOWN only |
| Actuator version disclosure | ✅ info endpoint removed — health only |
| Dependency CVEs | ✅ OWASP dependency-check in pipeline |
| Blast radius if repo public | ✅ Zero exploitable values |

---

## 📊 GitHub Secrets Reference

| Secret | Derived From | Value Type |
|---|---|---|
| `AZURE_CLIENT_ID` | `$APP_ID` | App Registration ID — must update after SP recreate |
| `AZURE_TENANT_ID` | `$TENANT_ID` | Azure AD Directory ID |
| `AZURE_SUBSCRIPTION_ID` | `$SUBSCRIPTION_ID` | Azure Subscription ID |
| `AZURE_STORAGE_ACCOUNT_NAME` | `$STORAGE_ACCOUNT` | Config — not a secret |
| `AZURE_STORAGE_CONTAINER_NAME` | `$CONTAINER_NAME` | Config — not a secret |

> `AZURE_STORAGE_FILE_NAME` removed in Phase 2 — filename now passed as query parameter.

---

## 🎉 Final Security State

```
✅ HTTPS + TLS 1.2
✅ MSI — zero secrets
✅ ADLS firewall — App Service IPs only
✅ SCM basic auth disabled
✅ FTP disabled
✅ Storage Blob Data Contributor — least privilege
✅ Log injection prevention (CWE-117)
✅ Path traversal prevention
✅ Blob soft delete — 7 days
✅ OWASP scan in pipeline
✅ Zero hardcoded IDs anywhere
```

---

## 🗑️ Teardown

One command — deletes all Azure resources, stops all billing:

```bash
az group delete \
  --name $RESOURCE_GROUP \
  --yes \
  --no-wait
```

Verify teardown is complete:

```bash
# Check resource group is gone (takes 2-3 min)
az group show --name $RESOURCE_GROUP 2>&1 | grep -i "not found\|error"

# Confirm subscription has no active resources
az resource list --resource-group $RESOURCE_GROUP 2>&1
```

Expected after teardown completes:
```
(ResourceGroupNotFound) Resource group 'rg-adls-asp-poc' could not be found.
```

> GitHub repo, Java code, workflow, and SP in Azure AD are unaffected.
> Run Phases 1–12 to rebuild in under 15 minutes.

---

## 💡 Extending This PoC

| Next Step | Change Required |
|---|---|
| Stream large files | Replace `ByteArrayOutputStream` with chunked streaming in AdlsService |
| List files in container | Add `listFiles()` to AdlsService + `GET /list` endpoint |
| Delete files | Add `deleteFile()` + `DELETE /delete?file=` endpoint |
| Production hardening | P1v3 plan + VNet integration + Key Vault references |
| Reactive streaming | Migrate controller to Spring WebFlux for large file concurrency |
| Authentication | Add Spring Security + Azure AD for endpoint protection |

---

*Built with Java 21 · Spring Boot 3.3 · Azure App Service · ADLS Gen2 · Managed Identity · OIDC*
