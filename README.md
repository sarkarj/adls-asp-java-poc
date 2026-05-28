# ADLS Gen2 → Azure App Service File Transfer PoC
### Java 21 · Managed Identity · OIDC · Zero Secrets

![Build](https://img.shields.io/badge/build-passing-brightgreen)
![Java](https://img.shields.io/badge/Java-21-orange)
![Azure](https://img.shields.io/badge/Azure-App%20Service%20B1%20Linux-blue)
![Auth](https://img.shields.io/badge/Auth-Managed%20Identity-green)
![Secrets](https://img.shields.io/badge/Secrets%20in%20Code-Zero-brightgreen)

---

## ⚡ 30-Second Read

| What | Detail |
|---|---|
| **Goal** | Read a text file from Azure Data Lake Storage Gen2 and transfer it to Azure App Service |
| **Auth** | System-Assigned Managed Identity — zero credentials, zero secrets anywhere |
| **Deploy** | GitHub Actions with OIDC federated credentials — no service principal secrets |
| **Language** | Java 21 — virtual threads, records, fail-fast validation |
| **Rebuild Time** | < 15 minutes from scratch (repo + Java code retained) |
| **Cost** | ~$0.018/hr on B1 — teardown stops all billing instantly |

> **Proven:** Full ADLS Gen2 → App Service connectivity, MSI auth, parameterized file transfer, production-grade CI/CD pipeline.

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        GitHub Actions                           │
│                                                                 │
│  on: push to main                                               │
│       │                                                         │
│       ├── Build JAR (Java 21 / Maven)                          │
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
│   GET /health  ──► "OK — ADLS PoC Running"                      │
│   GET /        ──► triggers file transfer                        │
│                                                                 │
│   App Settings (non-sensitive config only):                     │
│     AZURE_STORAGE_ACCOUNT_NAME   = adlspocstore001              │
│     AZURE_STORAGE_CONTAINER_NAME = raw-data                     │
│     AZURE_STORAGE_FILE_NAME      = sample.txt                   │
│                                                                 │
│   System-Assigned Managed Identity ──────────────────────────┐  │
│   (Azure manages token, rotates automatically)               │  │
└──────────────────────────────────────────────────────────────┼──┘
                                                               │
                    RBAC: Storage Blob Data Reader             │
                    Scope: adlspocstore001 only                │
                                                               ▼
┌─────────────────────────────────────────────────────────────────┐
│              ADLS Gen2 (Standard LRS, eastus)                   │
│                                                                 │
│   adlspocstore001                                               │
│   └── raw-data  (container)                                     │
│       └── sample.txt  ◄── file read + returned to caller        │
│                                                                 │
│   Security:                                                     │
│     ✅ HTTPS only · TLS 1.2 minimum                              │
│     ✅ No public blob access                                      │
│     ✅ No shared key access — AAD only                           │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🎯 Objective

Prove end-to-end secure file transfer between **Azure Data Lake Storage Gen2** and **Azure App Service** using:

- **Managed Identity (MSI)** — eliminates all credential management
- **Java 21** — modern language features, production-grade patterns
- **GitHub Actions + OIDC** — zero-secret CI/CD pipeline
- **Principle of Least Privilege** — Storage Blob Data Reader only

This PoC validates the connectivity and security pattern before building full file transfer implementations.

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
    │  subject = repo:sarkarj/adls-asp-java-poc:ref:refs/heads/main
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
ADLS Gen2 (Storage Blob Data Reader)
```

### Security Decisions

| Control | Implementation | Why |
|---|---|---|
| Zero secrets in code | All config via `requireEnv()` | Prevents accidental exposure |
| Zero secrets in GitHub | OIDC — IDs only, no passwords | No blast radius if repo leaked |
| Zero secrets in App Settings | MSI handles auth entirely | No rotation risk |
| ManagedIdentityCredential direct | Skips 6-provider chain | Faster cold start on App Service |
| Lazy ADLS client init | Built on first HTTP request | Server starts before any Azure calls |
| TLS 1.2 minimum | Enforced at storage account level | Blocks obsolete protocols |
| No shared key access | `--allow-shared-key-access false` | Forces AAD auth only |
| No public blob access | `--allow-blob-public-access false` | Zero anonymous access |
| FTP disabled | `--ftps-state Disabled` | Reduces attack surface |
| RBAC tightly scoped | Reader only, single storage account | Least privilege |
| Error sanitization | Bearer/SAS tokens stripped from logs | Prevents token leakage |
| Memory cap | 10MB file size limit | Guards against OOM |
| Immutable result | Java 21 record | Thread-safe by design |
| `System.exit(1)` on failure | Non-zero exit code | CI/CD detects failed runs |

---

## 📋 Prerequisites

Before the 15-minute rebuild, ensure you have:

```
✅ Azure CLI installed      → brew install azure-cli
✅ GitHub CLI installed     → brew install gh
✅ Java 21 installed        → brew install openjdk@21
✅ Maven installed          → brew install maven
✅ Azure Pay-As-You-Go      → portal.azure.com
✅ GitHub repo cloned       → github.com/sarkarj/adls-asp-java-poc
✅ gh authenticated         → gh auth login
✅ az authenticated         → az login
```

---

## ⚠️ Rebuild Considerations

> Read this before starting if rebuilding after a teardown.

### Service Principal Already Exists
After `az group delete`, the SP `sp-github-deploy-poc` remains in Azure AD.
`az ad sp create-for-rbac` will fail on rebuild. **Delete it first:**

```bash
# Check if SP exists
az ad sp list --display-name sp-github-deploy-poc --query "[0].appId" -o tsv

# If it returns an App ID — delete it before Phase 7
az ad app delete \
  --id $(az ad sp list --display-name sp-github-deploy-poc --query "[0].appId" -o tsv)
```

### Resource Providers Persist
`Microsoft.Storage`, `Microsoft.Web`, `Microsoft.ManagedIdentity` remain registered
after teardown — no action needed. Phase 1 includes a safety check anyway.

### B1 Linux Quota Persists
Quota approved at subscription level — survives teardown. `centralus` remains available.

### GitHub Secrets Persist
All 6 secrets remain in the repo after teardown — no action needed unless
Subscription ID or Tenant ID changes.

---

## ⏱️ 15-Minute Rebuild Guide

> **Assumption:** GitHub repo + Java code + workflow retained.
> Only Azure infrastructure needs to be recreated.

---

### Phase 1 · Azure Login + Provider Safety Check (1 min)

```bash
az login --tenant "9ffea5dd-3a0c-4a40-b755-398c3d380b50"
az account set --subscription "b4000e1c-6776-4c86-be94-e40611ec1852"
az account show --query "{Name:name, State:state}" -o table

# Register providers — safe to run even if already registered
az provider register --namespace Microsoft.Storage
az provider register --namespace Microsoft.Web
az provider register --namespace Microsoft.ManagedIdentity

# Verify all registered
az provider show --namespace Microsoft.Storage --query "registrationState" -o tsv
az provider show --namespace Microsoft.Web --query "registrationState" -o tsv
az provider show --namespace Microsoft.ManagedIdentity --query "registrationState" -o tsv
```

Expected:
```
Registered
Registered
Registered
```

---

### Phase 2 · Resource Group (30 sec)

```bash
az group create \
  --name rg-adls-asp-poc \
  --location eastus
```

---

### Phase 3 · ADLS Gen2 + Container + File (2 min)

```bash
# Storage account — ADLS Gen2 with maximum security
az storage account create \
  --name adlspocstore001 \
  --resource-group rg-adls-asp-poc \
  --location eastus \
  --sku Standard_LRS \
  --kind StorageV2 \
  --hns true \
  --https-only true \
  --min-tls-version TLS1_2 \
  --allow-blob-public-access false \
  --allow-shared-key-access false

# Container — auth-mode login required (shared key disabled)
az storage fs create \
  --name raw-data \
  --account-name adlspocstore001 \
  --auth-mode login

# Upload test file
echo "ADLS to ASP Transfer PoC — Jagannath Sarkar — $(date)" > sample.txt

az storage fs file upload \
  --source sample.txt \
  --path sample.txt \
  --file-system raw-data \
  --account-name adlspocstore001 \
  --auth-mode login

# Verify
az storage fs file list \
  --file-system raw-data \
  --account-name adlspocstore001 \
  --auth-mode login \
  --query "[].{Name:name, Size:contentLength}" \
  -o table
```

---

### Phase 4 · App Service Plan + Web App (2 min)

```bash
# App Service Plan — Linux B1 (centralus — eastus has capacity issues)
az appservice plan create \
  --name asp-adls-poc \
  --resource-group rg-adls-asp-poc \
  --location centralus \
  --sku B1 \
  --is-linux

# Web App — Java 21
az webapp create \
  --name webapp-adls-poc \
  --resource-group rg-adls-asp-poc \
  --plan asp-adls-poc \
  --runtime "JAVA:21-java21"

# Security hardening + startup command in one block
az webapp update \
  --name webapp-adls-poc \
  --resource-group rg-adls-asp-poc \
  --https-only true

az webapp config set \
  --name webapp-adls-poc \
  --resource-group rg-adls-asp-poc \
  --ftps-state Disabled \
  --min-tls-version 1.2 \
  --startup-file "java -jar /home/site/wwwroot/app.jar"
```

> ⚠️ Startup command is `app.jar` — App Service renames all deployed JARs to `app.jar` automatically.

---

### Phase 5 · App Settings (30 sec)

```bash
az webapp config appsettings set \
  --name webapp-adls-poc \
  --resource-group rg-adls-asp-poc \
  --settings \
    AZURE_STORAGE_ACCOUNT_NAME="adlspocstore001" \
    AZURE_STORAGE_CONTAINER_NAME="raw-data" \
    AZURE_STORAGE_FILE_NAME="sample.txt"
```

---

### Phase 6 · Managed Identity + RBAC (1 min)

```bash
# Enable System-Assigned MSI
az webapp identity assign \
  --name webapp-adls-poc \
  --resource-group rg-adls-asp-poc

# Capture Principal ID
export PRINCIPAL_ID=$(az webapp identity show \
  --name webapp-adls-poc \
  --resource-group rg-adls-asp-poc \
  --query principalId -o tsv)

echo "Principal ID: $PRINCIPAL_ID"

# Assign Storage Blob Data Reader — least privilege
az role assignment create \
  --assignee $PRINCIPAL_ID \
  --role "Storage Blob Data Reader" \
  --scope /subscriptions/b4000e1c-6776-4c86-be94-e40611ec1852/resourceGroups/rg-adls-asp-poc/providers/Microsoft.Storage/storageAccounts/adlspocstore001

# Verify
az role assignment list \
  --assignee $PRINCIPAL_ID \
  --query "[].{Role:roleDefinitionName, Scope:scope}" \
  -o table
```

---

### Phase 7 · Service Principal + OIDC Federated Credentials (2 min)

> ⚠️ If rebuilding after teardown — delete old SP first (see Rebuild Considerations above).

```bash
# Create SP for GitHub Actions deployment
az ad sp create-for-rbac \
  --name sp-github-deploy-poc \
  --role contributor \
  --scopes /subscriptions/b4000e1c-6776-4c86-be94-e40611ec1852/resourceGroups/rg-adls-asp-poc

# Save App ID
export APP_ID=$(az ad sp list \
  --display-name sp-github-deploy-poc \
  --query "[0].appId" -o tsv)

echo "App ID: $APP_ID"

# Federated credential — push to main
az ad app federated-credential create \
  --id $APP_ID \
  --parameters '{
    "name": "github-actions-main",
    "issuer": "https://token.actions.githubusercontent.com",
    "subject": "repo:sarkarj/adls-asp-java-poc:ref:refs/heads/main",
    "audiences": ["api://AzureADTokenExchange"],
    "description": "GitHub Actions OIDC for main branch"
  }'

# Federated credential — manual workflow dispatch
az ad app federated-credential create \
  --id $APP_ID \
  --parameters '{
    "name": "github-actions-dispatch",
    "issuer": "https://token.actions.githubusercontent.com",
    "subject": "repo:sarkarj/adls-asp-java-poc:workflow_dispatch",
    "audiences": ["api://AzureADTokenExchange"],
    "description": "GitHub Actions OIDC for manual dispatch"
  }'
```

---

### Phase 8 · GitHub Secrets (1 min)

```bash
# Azure auth — IDs only, zero actual secret values
gh secret set AZURE_CLIENT_ID \
  --body "$APP_ID" \
  --repo sarkarj/adls-asp-java-poc

gh secret set AZURE_TENANT_ID \
  --body "9ffea5dd-3a0c-4a40-b755-398c3d380b50" \
  --repo sarkarj/adls-asp-java-poc

gh secret set AZURE_SUBSCRIPTION_ID \
  --body "b4000e1c-6776-4c86-be94-e40611ec1852" \
  --repo sarkarj/adls-asp-java-poc

# Config values
gh secret set AZURE_STORAGE_ACCOUNT_NAME \
  --body "adlspocstore001" \
  --repo sarkarj/adls-asp-java-poc

gh secret set AZURE_STORAGE_CONTAINER_NAME \
  --body "raw-data" \
  --repo sarkarj/adls-asp-java-poc

gh secret set AZURE_STORAGE_FILE_NAME \
  --body "sample.txt" \
  --repo sarkarj/adls-asp-java-poc

# Verify — 6 secrets, zero actual secret values
gh secret list --repo sarkarj/adls-asp-java-poc
```

---

### Phase 9 · Deploy (2 min)

```bash
# Navigate to repo
cd /path/to/adls-asp-java-poc

# Trigger pipeline — empty commit if no code changes
git commit --allow-empty -m "chore: trigger rebuild deployment"
git push origin main

# Watch pipeline live
gh run watch --repo sarkarj/adls-asp-java-poc
```

Pipeline stages:
```
✅ Build JAR       (~20s) — Java 21 compile + fat JAR
✅ Azure Login     (~5s)  — OIDC token exchange
✅ Set Startup     (~5s)  — java -jar /home/site/wwwroot/app.jar
✅ Deploy JAR      (~30s) — 21.7MB to App Service
```

---

### Phase 10 · Verify (30 sec)

```bash
# Health probe — must return 200 before transfer test
curl https://webapp-adls-poc.azurewebsites.net/health

# File transfer — wait 30s after health is green
curl https://webapp-adls-poc.azurewebsites.net/
```

**Expected:**
```
OK — ADLS PoC Running

╔══════════════════════════════════════╗
║      TRANSFER SUCCESSFUL  ✅          ║
╚══════════════════════════════════════╝
File      : sample.txt
Bytes     : 79
Timestamp : 2026-XX-XXT...Z
Preview   : ADLS to ASP Transfer PoC — Jagannath Sarkar — ...
```

---

## 🔗 Full Transfer Chain

```
curl GET /
    │
    ▼
AdlsFileTransfer.main()
    │
    ├── requireEnv("AZURE_STORAGE_ACCOUNT_NAME")    → adlspocstore001
    ├── requireEnv("AZURE_STORAGE_CONTAINER_NAME")  → raw-data
    └── requireEnv("AZURE_STORAGE_FILE_NAME")       → sample.txt
    │
    ├── HTTP server starts FIRST on port $PORT
    │   └── /health returns 200 immediately (no Azure calls)
    │
    ▼  (on first GET / request — lazy init)
AdlsClientFactory(accountName)
    │
    ├── ManagedIdentityCredential.build()
    │       ↑ Azure IMDS endpoint provides token automatically
    │       ↑ Token scoped to Storage Blob Data Reader only
    │
    └── DataLakeServiceClientBuilder
            .endpoint("https://adlspocstore001.dfs.core.windows.net")
            .credential(managedIdentityCredential)
            .buildClient()
    │
    ▼
AdlsFileService.readFile("sample.txt")
    │
    ├── fileClient.getProperties()     → verify file exists + get size
    ├── Guard: fileSize > 10MB         → reject — returns failure result
    ├── fileClient.read(outputStream)  → stream bytes into memory
    └── sanitizeError()                → strip tokens from any error message
    │
    ▼
TransferResult.success(fileName, bytes, preview)
    │   Java 21 record — immutable, thread-safe, validated in constructor
    │
    ▼
HTTP 200 — plain text response
```

---

## 🗂️ Project Structure

```
adls-asp-java-poc/
├── .github/
│   └── workflows/
│       └── deploy.yml                    ← OIDC build + deploy pipeline
├── src/main/java/com/poc/adls/
│   ├── AdlsFileTransfer.java             ← Entry point + HTTP server (lazy init)
│   ├── AdlsClientFactory.java            ← MSI credential + ADLS client builder
│   ├── AdlsFileService.java              ← Reusable parameterized file operations
│   └── TransferResult.java              ← Immutable Java 21 result record
├── pom.xml                              ← Maven build + fat JAR + shade config
├── .gitignore
└── README.md
```

---

## ☁️ Azure Resource Inventory

| Resource | Name | Type | Location | Note |
|---|---|---|---|---|
| Resource Group | `rg-adls-asp-poc` | Container | eastus | Teardown = delete this |
| Storage Account | `adlspocstore001` | ADLS Gen2 | eastus | HNS enabled |
| Container | `raw-data` | Filesystem | — | AAD auth only |
| Test File | `sample.txt` | Blob | — | 79 bytes |
| App Service Plan | `asp-adls-poc` | B1 Linux | centralus | eastus has capacity issues |
| Web App | `webapp-adls-poc` | Java 21 | centralus | app.jar startup |
| Managed Identity | System-Assigned | MSI | centralus | Tied to web app |
| Service Principal | `sp-github-deploy-poc` | OIDC only | Azure AD | Survives teardown |

---

## 🔍 Final Security Audit

| Vector | Status |
|---|---|
| Secrets in source code | ❌ Zero |
| Secrets in GitHub Actions | ❌ Zero — IDs only |
| Secrets in App Service settings | ❌ Zero — config values only |
| Credential rotation | ✅ Automatic — Azure managed |
| OIDC token lifetime | ✅ Ephemeral — per pipeline run only |
| Storage transport | ✅ HTTPS + TLS 1.2 enforced |
| Storage auth | ✅ AAD only — shared keys disabled |
| Public blob access | ✅ Disabled |
| FTP access | ✅ Disabled |
| RBAC scope | ✅ Reader only — single storage account |
| Error log sanitization | ✅ Bearer/SAS tokens stripped |
| Memory safety | ✅ 10MB file size cap |
| Blast radius if repo leaked | ✅ Zero exploitable values |

---

## 📊 GitHub Secrets Reference

| Secret | Type | Value |
|---|---|---|
| `AZURE_CLIENT_ID` | App Registration ID | SP App ID (not a secret) |
| `AZURE_TENANT_ID` | Directory ID | Azure AD Tenant (not a secret) |
| `AZURE_SUBSCRIPTION_ID` | Subscription ID | Azure Subscription (not a secret) |
| `AZURE_STORAGE_ACCOUNT_NAME` | Config | `adlspocstore001` |
| `AZURE_STORAGE_CONTAINER_NAME` | Config | `raw-data` |
| `AZURE_STORAGE_FILE_NAME` | Config | `sample.txt` |

> None of the above are actual secrets — all are non-sensitive identifiers or config values.

---

## 🗑️ Teardown

One command — deletes all Azure resources, stops all billing:

```bash
az group delete \
  --name rg-adls-asp-poc \
  --yes \
  --no-wait
```

> GitHub repo, Java code, workflow, and SP in Azure AD are unaffected.
> Run Phases 1–10 to rebuild in under 15 minutes.

---

## 💡 Extending This PoC

| Next Step | Change Required |
|---|---|
| Transfer different file | Update `AZURE_STORAGE_FILE_NAME` secret |
| Different container | Update `AZURE_STORAGE_CONTAINER_NAME` secret |
| Write back to ADLS | Upgrade RBAC → `Storage Blob Data Contributor` |
| Stream large files | Replace `ByteArrayOutputStream` with chunked streaming |
| List + transfer multiple files | Add `listFiles()` to `AdlsFileService` |
| Production hardening | P1v3 plan + VNet integration + Key Vault references |

---

*Built by Jagannath Sarkar · Java 21 · Azure App Service · ADLS Gen2 · Managed Identity · OIDC*
