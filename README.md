# ADLS Gen2 → Azure App Service File Transfer PoC
### Java 21 · Managed Identity · OIDC · Zero Secrets · Zero Hardcoded IDs

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
| **Build** | GitHub Actions builds + deploys — no Java or Maven needed locally |
| **Rebuild Time** | < 15 minutes from scratch (repo + Java code retained) |
| **Cost** | ~$0.018/hr on B1 — teardown stops all billing instantly |

> **Proven:** Full ADLS Gen2 → App Service connectivity, MSI auth,
> parameterized file transfer, production-grade CI/CD pipeline.
> Safe to make repo public — zero IDs, zero secrets hardcoded anywhere.

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

Prove end-to-end secure file transfer between **Azure Data Lake Storage Gen2**
and **Azure App Service** using:

- **Managed Identity (MSI)** — eliminates all credential management
- **Java 21** — modern language features, production-grade patterns
- **GitHub Actions + OIDC** — zero-secret CI/CD pipeline
- **Principle of Least Privilege** — Storage Blob Data Reader only
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
ADLS Gen2 (Storage Blob Data Reader)
```

### Security Decisions

| Control | Implementation | Why |
|---|---|---|
| Zero secrets in code | All config via `requireEnv()` | Prevents accidental exposure |
| Zero secrets in GitHub | OIDC — IDs only, no passwords | No blast radius if repo leaked |
| Zero secrets in App Settings | MSI handles auth entirely | No rotation risk |
| Zero hardcoded IDs in README | All derived from `az login` | Safe to make repo public |
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
`az ad sp create-for-rbac` will fail on rebuild. **Delete it first:**

```bash
# Set variables first (see Phase 1)
# Then check and delete if SP exists
SP_EXISTS=$(az ad sp list \
  --display-name sp-github-deploy-poc \
  --query "[0].appId" -o tsv)

if [ -n "$SP_EXISTS" ]; then
  echo "SP exists — deleting before rebuild..."
  az ad app delete --id $SP_EXISTS
  echo "SP deleted — safe to proceed"
else
  echo "No existing SP found — safe to proceed"
fi
```

### Resource Providers Persist
`Microsoft.Storage`, `Microsoft.Web`, `Microsoft.ManagedIdentity` stay registered
after teardown. Phase 1 includes a safety check regardless.

### B1 Linux Quota Persists
Quota approved at subscription level — survives teardown.
`centralus` remains available.

### GitHub Secrets Persist
All 6 secrets remain in the repo after teardown.
Only `AZURE_CLIENT_ID` needs updating if SP was deleted and recreated.

---

## ⏱️ 15-Minute Rebuild Guide

> **Assumption:** GitHub repo + Java code + workflow retained.
> Only Azure infrastructure needs to be recreated.

---

### Phase 1 · Azure Login + Set Variables (1 min)

```bash
# Login — browser handles identity
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

# Verify login + print derived values
az account show --query "{Name:name, State:state}" -o table
echo "---"
echo "TENANT_ID       : $TENANT_ID"
echo "SUBSCRIPTION_ID : $SUBSCRIPTION_ID"
echo "ACCOUNT_EMAIL   : $ACCOUNT_EMAIL"

# Provider safety check
az provider register --namespace Microsoft.Storage
az provider register --namespace Microsoft.Web
az provider register --namespace Microsoft.ManagedIdentity

# Confirm all registered
az provider show --namespace Microsoft.Storage \
  --query "registrationState" -o tsv
az provider show --namespace Microsoft.Web \
  --query "registrationState" -o tsv
az provider show --namespace Microsoft.ManagedIdentity \
  --query "registrationState" -o tsv
```

Expected:
```
Name                  State
--------------------  -------
Azure subscription 1  Enabled
---
TENANT_ID       : <derived from login>
SUBSCRIPTION_ID : <derived from login>
ACCOUNT_EMAIL   : <your email>
Registered
Registered
Registered
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
# Storage account — ADLS Gen2 with maximum security
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

# Container — auth-mode login required (shared key disabled)
az storage fs create \
  --name $CONTAINER_NAME \
  --account-name $STORAGE_ACCOUNT \
  --auth-mode login

# Upload test file
echo "ADLS to ASP Transfer PoC — $(date)" > sample.txt

az storage fs file upload \
  --source sample.txt \
  --path sample.txt \
  --file-system $CONTAINER_NAME \
  --account-name $STORAGE_ACCOUNT \
  --auth-mode login

# Verify
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
# App Service Plan — Linux B1
# centralus used — eastus has Linux capacity issues on new subscriptions
az appservice plan create \
  --name $ASP_NAME \
  --resource-group $RESOURCE_GROUP \
  --location centralus \
  --sku B1 \
  --is-linux

# Web App — Java 21
az webapp create \
  --name $WEBAPP_NAME \
  --resource-group $RESOURCE_GROUP \
  --plan $ASP_NAME \
  --runtime "JAVA:21-java21"

# Security hardening
az webapp update \
  --name $WEBAPP_NAME \
  --resource-group $RESOURCE_GROUP \
  --https-only true

# ⚠️ startup-file must be app.jar
# Azure App Service renames ALL deployed JARs to app.jar automatically
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
    AZURE_STORAGE_FILE_NAME="sample.txt"
```

---

### Phase 6 · Managed Identity + RBAC (1 min)

```bash
# Enable System-Assigned MSI
az webapp identity assign \
  --name $WEBAPP_NAME \
  --resource-group $RESOURCE_GROUP

# Capture Principal ID
export PRINCIPAL_ID=$(az webapp identity show \
  --name $WEBAPP_NAME \
  --resource-group $RESOURCE_GROUP \
  --query principalId -o tsv)

echo "Principal ID: $PRINCIPAL_ID"

# Assign Storage Blob Data Reader — least privilege
az role assignment create \
  --assignee $PRINCIPAL_ID \
  --role "Storage Blob Data Reader" \
  --scope /subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$STORAGE_ACCOUNT

# Verify
az role assignment list \
  --assignee $PRINCIPAL_ID \
  --query "[].{Role:roleDefinitionName, Scope:scope}" \
  -o table
```

---

### Phase 7 · Service Principal + OIDC Federated Credentials (2 min)

```bash
# Check and delete existing SP if rebuilding after teardown
SP_EXISTS=$(az ad sp list \
  --display-name $SP_NAME \
  --query "[0].appId" -o tsv)

if [ -n "$SP_EXISTS" ]; then
  echo "Existing SP found — deleting before recreate..."
  az ad app delete --id $SP_EXISTS
  echo "Deleted — proceeding"
fi

# Create SP for GitHub Actions deployment
az ad sp create-for-rbac \
  --name $SP_NAME \
  --role contributor \
  --scopes /subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP

# Capture App ID
export APP_ID=$(az ad sp list \
  --display-name $SP_NAME \
  --query "[0].appId" -o tsv)

echo "App ID: $APP_ID"

# Federated credential — push to main branch
az ad app federated-credential create \
  --id $APP_ID \
  --parameters "{
    \"name\": \"github-actions-main\",
    \"issuer\": \"https://token.actions.githubusercontent.com\",
    \"subject\": \"repo:$GITHUB_REPO:ref:refs/heads/main\",
    \"audiences\": [\"api://AzureADTokenExchange\"],
    \"description\": \"GitHub Actions OIDC for main branch\"
  }"

# Federated credential — manual workflow dispatch
az ad app federated-credential create \
  --id $APP_ID \
  --parameters "{
    \"name\": \"github-actions-dispatch\",
    \"issuer\": \"https://token.actions.githubusercontent.com\",
    \"subject\": \"repo:$GITHUB_REPO:workflow_dispatch\",
    \"audiences\": [\"api://AzureADTokenExchange\"],
    \"description\": \"GitHub Actions OIDC for manual dispatch\"
  }"
```

---

### Phase 8 · GitHub Secrets (1 min)

```bash
# Authenticate GitHub CLI
gh auth login

# Azure auth — IDs only, zero actual secret values
gh secret set AZURE_CLIENT_ID \
  --body "$APP_ID" \
  --repo $GITHUB_REPO

gh secret set AZURE_TENANT_ID \
  --body "$TENANT_ID" \
  --repo $GITHUB_REPO

gh secret set AZURE_SUBSCRIPTION_ID \
  --body "$SUBSCRIPTION_ID" \
  --repo $GITHUB_REPO

# Config values
gh secret set AZURE_STORAGE_ACCOUNT_NAME \
  --body "$STORAGE_ACCOUNT" \
  --repo $GITHUB_REPO

gh secret set AZURE_STORAGE_CONTAINER_NAME \
  --body "$CONTAINER_NAME" \
  --repo $GITHUB_REPO

gh secret set AZURE_STORAGE_FILE_NAME \
  --body "sample.txt" \
  --repo $GITHUB_REPO

# Verify — 6 secrets, zero actual secret values
gh secret list --repo $GITHUB_REPO
```

Expected:
```
NAME                          UPDATED
----------------------------  ------------------
AZURE_CLIENT_ID               just now
AZURE_TENANT_ID               just now
AZURE_SUBSCRIPTION_ID         just now
AZURE_STORAGE_ACCOUNT_NAME    just now
AZURE_STORAGE_CONTAINER_NAME  just now
AZURE_STORAGE_FILE_NAME       just now
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
gh run watch --repo $GITHUB_REPO
```

Pipeline stages:
```
✅ Build JAR       (~20s) — Java 21 compile + fat JAR (21.7MB)
✅ Azure Login     (~5s)  — OIDC token exchange, zero secrets
✅ Set Startup     (~5s)  — java -jar /home/site/wwwroot/app.jar
✅ Deploy JAR      (~30s) — deployed as app.jar to App Service
```

---

### Phase 10 · Verify (30 sec)

```bash
# Health probe — confirm server is running
curl https://$WEBAPP_NAME.azurewebsites.net/health

# Wait 30 seconds then trigger file transfer
curl https://$WEBAPP_NAME.azurewebsites.net/
```

**Expected:**
```
OK — ADLS PoC Running

╔══════════════════════════════════════╗
║      TRANSFER SUCCESSFUL  ✅          ║
╚══════════════════════════════════════╝
File      : sample.txt
Bytes     : 79
Timestamp : <timestamp>
Preview   : ADLS to ASP Transfer PoC — <date>
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
    ▼  (on first GET / — lazy init)
AdlsClientFactory(accountName)
    │
    ├── ManagedIdentityCredential.build()
    │       ↑ Azure IMDS provides token automatically
    │       ↑ Token scoped to Storage Blob Data Reader only
    │
    └── DataLakeServiceClientBuilder
            .endpoint("https://<account>.dfs.core.windows.net")
            .credential(managedIdentityCredential)
            .buildClient()
    │
    ▼
AdlsFileService.readFile("sample.txt")
    │
    ├── fileClient.getProperties()     → verify exists + get size
    ├── Guard: fileSize > 10MB         → reject oversized files
    ├── fileClient.read(outputStream)  → stream bytes into memory
    └── sanitizeError()                → strip tokens from errors
    │
    ▼
TransferResult.success(fileName, bytes, preview)
    │   Java 21 record — immutable, thread-safe
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
| Storage Account | `adlspocstore001` | ADLS Gen2 | eastus | HNS + AAD auth only |
| Container | `raw-data` | Filesystem | — | No public access |
| Test File | `sample.txt` | Blob | — | ~79 bytes |
| App Service Plan | `asp-adls-poc` | B1 Linux | centralus | eastus has capacity issues |
| Web App | `webapp-adls-poc` | Java 21 | centralus | Startup: app.jar |
| Managed Identity | System-Assigned | MSI | centralus | Tied to web app lifecycle |
| Service Principal | `sp-github-deploy-poc` | OIDC only | Azure AD | Survives teardown |

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
| Public blob access | ✅ Disabled |
| FTP access | ✅ Disabled |
| RBAC scope | ✅ Reader only — single storage account |
| Error log sanitization | ✅ Bearer/SAS tokens stripped |
| Memory safety | ✅ 10MB file size cap |
| Blast radius if repo public | ✅ Zero exploitable values |

---

## 📊 GitHub Secrets Reference

| Secret | Derived From | Value Type |
|---|---|---|
| `AZURE_CLIENT_ID` | `$APP_ID` | App Registration ID |
| `AZURE_TENANT_ID` | `$TENANT_ID` | Azure AD Directory ID |
| `AZURE_SUBSCRIPTION_ID` | `$SUBSCRIPTION_ID` | Azure Subscription ID |
| `AZURE_STORAGE_ACCOUNT_NAME` | `$STORAGE_ACCOUNT` | Config — not a secret |
| `AZURE_STORAGE_CONTAINER_NAME` | `$CONTAINER_NAME` | Config — not a secret |
| `AZURE_STORAGE_FILE_NAME` | Hardcoded `sample.txt` | Config — not a secret |

> All values set via CLI variables derived from `az login` — never typed manually.

---

## 🗑️ Teardown

One command — deletes all Azure resources, stops all billing:

```bash
az group delete \
  --name $RESOURCE_GROUP \
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

*Built with Java 21 · Azure App Service · ADLS Gen2 · Managed Identity · OIDC*
