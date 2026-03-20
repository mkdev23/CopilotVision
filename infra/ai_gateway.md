# CVP AI Gateway — MCP Governance Setup

Route MCP tool traffic through Azure API Management (APIM) to enforce authentication,
rate limits, IP restrictions, and audit logging — without changing any agent code.

## Prerequisites

| Requirement | Details |
|-------------|---------|
| Foundry resource access | Azure AI Account Owner / Azure AI Owner on your Foundry resource |
| APIM access | API Management Service Contributor or Owner on the APIM instance |
| APIM SKU | v2 tier (Basic v2 for dev/test); same Entra tenant + subscription as Foundry resource |
| APIM uniqueness | Must not already be associated with another AI Gateway |

---

## Step 1 — Enable AI Gateway in Foundry Portal

1. Sign in to [Microsoft Foundry portal](https://ai.azure.com) and confirm "New Foundry" toggle is **ON**.
2. Navigate: **Operate → Admin console → AI Gateway** tab.
3. Select **"Add AI Gateway"**.
4. Select your Foundry resource.
5. Choose:
   - **Create new APIM** → creates a Basic v2 SKU APIM instance (suitable for demo), OR
   - **Use existing APIM** → must meet requirements above.
6. Complete the wizard. AI Gateway is now connected to your Foundry resource.

**Outcome**: A governed entry point for all MCP traffic. Policies apply at the Foundry resource level.

---

## Step 2 — Configure Governance Policies

In the APIM instance connected to your AI Gateway, add the following policies to the MCP API:

### Authentication Enforcement
```xml
<!-- Validate Bearer token from Foundry agent requests -->
<validate-jwt header-name="Authorization" failed-validation-httpcode="401">
    <issuer-signing-keys>
        <key>{{mcp-bearer-token}}</key>
    </issuer-signing-keys>
</validate-jwt>
```

For production: use Entra managed identity validation instead of static key.

### Rate Limiting
```xml
<!-- 100 calls per minute per agent -->
<rate-limit-by-key calls="100" renewal-period="60"
    counter-key="@(context.Request.Headers.GetValueOrDefault("Authorization",""))" />
```

### IP Restrictions
```xml
<!-- Allow only Azure Container Apps egress IPs -->
<ip-filter action="allow">
    <address-range from="20.0.0.0" to="20.255.255.255" />
    <!-- Add your Container Apps outbound IPs from Azure portal -->
</ip-filter>
```

### Audit Logging
```xml
<!-- Log all MCP tool calls to Log Analytics (YOUR_LOG_ANALYTICS_WORKSPACE) -->
<log-to-eventhub logger-id="cvp-audit-logger">
    @{
        return new JObject(
            new JProperty("session_id", context.Request.Headers.GetValueOrDefault("x-session-id", "unknown")),
            new JProperty("tool_call", context.Request.Body.As<string>()),
            new JProperty("timestamp", DateTime.UtcNow.ToString("o")),
            new JProperty("source_ip", context.Request.IpAddress)
        ).ToString();
    }
</log-to-eventhub>
```

---

## Step 3 — Connect MCP Server to Foundry Agent via Gateway

When running `agent_service/agent.py`, set `MCP_SERVER_URL` to the **APIM gateway URL**
instead of the Container App URL directly:

```bash
# Direct (no governance):
MCP_SERVER_URL=https://cvp-mcp-server.YOUR_ENV.YOUR_REGION.azurecontainerapps.io/mcp

# Via AI Gateway (governed):
MCP_SERVER_URL=https://cvp-apim.azure-api.net/mcp
```

No changes to `agent.py` — governance is transparent to the agent.

---

## Step 4 — Tool Approval Workflow (Human-in-the-Loop)

To require explicit approval before the agent executes write tools:

In `agent_service/agent.py`, change:
```python
"require_approval": "never"   # auto-execute all tools
```
to:
```python
"require_approval": "always"  # pause, show plan, require human confirm
```

Demo story: *"Agent plans actions, but execution requires explicit approval"* —
ideal for enterprise Responsible AI narrative.

---

## Supported Auth Methods (MCP Governance)

| Method | Use Case | Setup |
|--------|----------|-------|
| Managed Identity (Entra) | Production | Assign RBAC to MCP server's managed identity |
| Bearer token (static) | Demo / dev | Set `MCP_BEARER_TOKEN` in Container App secrets |
| OAuth passthrough | Enterprise SSO | Configure APIM OAuth 2.0 policy |
| Unauthenticated | Local dev only | Leave `MCP_BEARER_TOKEN` empty |

---

## Copilot Studio Integration (Future)

If wrapping as a Copilot Studio agent:
- Copilot Studio supports MCP tools/resources with **Streamable HTTP transport**
- SSE transport is deprecated and **not supported after August 2025** — our MCP server uses Streamable HTTP ✅
- Requires Generative Orchestration enabled on the Copilot Studio environment
- Tools/resources are dynamically reflected from the MCP server (no redeploy needed)
