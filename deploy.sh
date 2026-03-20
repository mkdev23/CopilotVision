#!/usr/bin/env bash
# CVP Azure Deployment Script
#
# Builds and deploys both backend services to Azure:
#   1. cvp-gateway     → existing cvpac Container App (vision analysis)
#   2. cvp-mcp-server  → new Container App (MCP tool execution)
#
# Prerequisites:
#   az login
#   az account set --subscription YOUR_SUBSCRIPTION_ID
#
# Usage:
#   bash deploy.sh              # deploy both services
#   bash deploy.sh gateway      # deploy gateway only
#   bash deploy.sh mcp          # deploy MCP server only
#   bash deploy.sh agent        # run agent_service/agent.py to create/update Foundry agent

set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────

SUBSCRIPTION="YOUR_SUBSCRIPTION_ID"
RESOURCE_GROUP="YOUR_RESOURCE_GROUP"
REGISTRY="YOUR_REGISTRY"
REGISTRY_SERVER="YOUR_REGISTRY.azurecr.io"
CONTAINER_ENV="YOUR_CONTAINER_APPS_ENV"
LOCATION="YOUR_REGION"

# Existing gateway Container App
GATEWAY_APP="cvpac"
GATEWAY_IMAGE="${REGISTRY_SERVER}/cvp-gateway"

# New MCP server Container App
MCP_APP="cvp-mcp-server"
MCP_IMAGE="${REGISTRY_SERVER}/cvp-mcp-server"

TAG="${IMAGE_TAG:-latest}"

# ── Helpers ───────────────────────────────────────────────────────────────────

log() { echo -e "\n\033[1;34m▶ $*\033[0m"; }
ok()  { echo -e "\033[1;32m✅ $*\033[0m"; }
err() { echo -e "\033[1;31m❌ $*\033[0m"; exit 1; }

require_env() {
    [[ -z "${!1:-}" ]] && err "Required env var $1 is not set. Export it before running deploy.sh"
}

# ── Login check ───────────────────────────────────────────────────────────────

check_login() {
    if ! az account show &>/dev/null; then
        err "Not logged in to Azure. Run: az login && az account set --subscription $SUBSCRIPTION"
    fi
    az account set --subscription "$SUBSCRIPTION"
    ok "Azure subscription set: $SUBSCRIPTION"
}

# ── Build & push via ACR Tasks (no local Docker needed) ──────────────────────

build_gateway() {
    log "Building gateway image → ${GATEWAY_IMAGE}:${TAG}"
    az acr build \
        --registry "$REGISTRY" \
        --image "cvp-gateway:${TAG}" \
        --file gateway/Dockerfile \
        gateway/
    ok "Gateway image built and pushed"
}

build_mcp() {
    log "Building MCP server image → ${MCP_IMAGE}:${TAG}"
    az acr build \
        --registry "$REGISTRY" \
        --image "cvp-mcp-server:${TAG}" \
        --file mcp_server/Dockerfile \
        mcp_server/
    ok "MCP server image built and pushed"
}

# ── Deploy Container Apps ─────────────────────────────────────────────────────

deploy_gateway() {
    log "Deploying gateway to Container App: ${GATEWAY_APP}"
    require_env AZURE_VISION_ENDPOINT
    require_env AZURE_VISION_KEY

    az deployment group create \
        --resource-group "$RESOURCE_GROUP" \
        --template-file infra/main.bicep \
        --parameters \
            location="$LOCATION" \
            containerRegistry="$REGISTRY_SERVER" \
            imageTag="$TAG" \
            azureVisionEndpoint="$AZURE_VISION_ENDPOINT" \
            azureVisionKey="$AZURE_VISION_KEY" \
            mcpBearerToken="${MCP_BEARER_TOKEN:-placeholder}"

    ok "Gateway deployed: https://$(az containerapp show \
        --name $GATEWAY_APP --resource-group $RESOURCE_GROUP \
        --query 'properties.configuration.ingress.fqdn' -o tsv)"
}

deploy_mcp() {
    log "Deploying MCP server Container App: ${MCP_APP}"
    require_env MCP_BEARER_TOKEN
    require_env AZURE_VISION_KEY

    az deployment group create \
        --resource-group "$RESOURCE_GROUP" \
        --template-file infra/main.bicep \
        --parameters \
            location="$LOCATION" \
            containerRegistry="$REGISTRY_SERVER" \
            imageTag="$TAG" \
            azureVisionKey="$AZURE_VISION_KEY" \
            mcpBearerToken="$MCP_BEARER_TOKEN"

    MCP_FQDN=$(az containerapp show \
        --name "$MCP_APP" \
        --resource-group "$RESOURCE_GROUP" \
        --query 'properties.configuration.ingress.fqdn' -o tsv)

    ok "MCP server deployed: https://${MCP_FQDN}/mcp"
    echo ""
    echo "  Set this in agent_service/.env:"
    echo "  MCP_SERVER_URL=https://${MCP_FQDN}/mcp"
    echo ""
}

# ── Grant ACR pull to Container Apps managed identity ─────────────────────────

grant_acr_pull() {
    log "Granting ACR pull permissions to Container App managed identities"

    ACR_ID=$(az acr show --name "$REGISTRY" --resource-group "$RESOURCE_GROUP" --query id -o tsv)

    for APP in "$GATEWAY_APP" "$MCP_APP"; do
        if az containerapp show --name "$APP" --resource-group "$RESOURCE_GROUP" &>/dev/null; then
            PRINCIPAL=$(az containerapp show \
                --name "$APP" \
                --resource-group "$RESOURCE_GROUP" \
                --query 'identity.principalId' -o tsv 2>/dev/null || echo "")

            if [[ -n "$PRINCIPAL" ]]; then
                az role assignment create \
                    --assignee "$PRINCIPAL" \
                    --role "AcrPull" \
                    --scope "$ACR_ID" \
                    --only-show-errors || true
                ok "AcrPull granted to $APP (principal: $PRINCIPAL)"
            fi
        fi
    done
}

# ── Run Foundry agent setup ───────────────────────────────────────────────────

deploy_agent() {
    log "Running Foundry agent setup (agent_service/agent.py)"
    cd agent_service
    pip install -q -r requirements.txt
    python agent.py "$@"
    cd ..
}

# ── Main ──────────────────────────────────────────────────────────────────────

TARGET="${1:-all}"

check_login

case "$TARGET" in
    gateway)
        build_gateway
        deploy_gateway
        ;;
    mcp)
        build_mcp
        deploy_mcp
        grant_acr_pull
        ;;
    agent)
        shift || true
        deploy_agent "$@"
        ;;
    all)
        build_gateway
        build_mcp
        deploy_gateway
        deploy_mcp
        grant_acr_pull
        echo ""
        log "All services deployed. Next: run 'bash deploy.sh agent' to configure Foundry agent."
        ;;
    *)
        echo "Usage: bash deploy.sh [all|gateway|mcp|agent] [--test]"
        exit 1
        ;;
esac
