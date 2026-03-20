# CVP Demo Script — 3 Minutes

## Setup (before demo)

**One-time deploy** (run once per environment):
```bash
cp .env.deploy.example .env.deploy   # fill in AZURE_VISION_KEY + MCP_BEARER_TOKEN
source .env.deploy
bash deploy.sh all                   # builds images → pushes to YOUR_REGISTRY → deploys Container Apps
bash deploy.sh agent                 # creates Foundry agent, prints Agent ID
```

**Android Settings** (paste from deploy output):
- Azure OpenAI Endpoint + Key (your Azure OpenAI resource)
- Vision Gateway URL: `https://cvpac.YOUR_ENV.YOUR_REGION.azurecontainerapps.io`
- Azure AI Foundry Project Endpoint + Agent ID (from `deploy.sh agent` output)
- Graph token from [graph.microsoft.com/graph-explorer](https://developer.microsoft.com/graph/graph-explorer) (1hr, scopes: Notes.ReadWrite, Tasks.ReadWrite, Chat.ReadWrite)

**Verify services are up:**
```bash
curl https://cvpac.YOUR_ENV.YOUR_REGION.azurecontainerapps.io/health
curl https://cvp-mcp-server.YOUR_ENV.YOUR_REGION.azurecontainerapps.io/health
```

---

## Minute 1 — What It Is (30s talk + 30s demo)

**Say**: "CVP turns your smart glasses into a Copilot for Work input device. You're wearing the camera — it sees what you see. You speak, it acts."

**Show**: App connected, glasses paired, audio streaming indicator active.

"The entire stack is Microsoft-native. Azure OpenAI Realtime for voice, Foundry agents for reasoning, MCP tools for action. No custom runtimes, no workarounds."

---

## Minute 2 — Vision + Note Capture (core demo)

**Action**: Point glasses at a whiteboard or screen with text. Say:
> "Create a note about what's on my screen."

**What happens**:
1. Glasses camera fires burst capture (1 JPEG)
2. JPEG → CVP Vision Gateway → Azure Vision OCR → `ocrText` returned
3. `ocrText` injected into Azure Realtime session as `[CVP Visual Context - CURRENT]`
4. Model says: *"Creating a note with that content now..."*
5. `execute(task=...)` tool fires → `FoundryAgentBridge` creates Foundry thread
6. Foundry Orchestrator Agent calls `create_note` on MCP server
7. MCP server → Microsoft Graph → OneNote page created
8. Model speaks confirmation through glasses speaker

**Show**: OneNote open on laptop → refresh → new page appears.

**Or show**: `sqlite3 cvp_demo.db "SELECT title, body FROM notes ORDER BY created_at DESC LIMIT 1;"`

---

## Minute 3 — Task + Governance Story (30s demo + 30s talk)

**Action**: Say:
> "Remind me to follow up with the team about this tomorrow."

**What happens**: Foundry agent calls `create_task` → Microsoft To-Do task created with due date.

**Show**: Microsoft To-Do open → task appears.

**Governance story** (point to Azure portal or slide):

"Every MCP tool call routes through our AI Gateway — authentication, rate limits, IP restrictions, and audit logs. All in one governed entry point. No agent code changes needed."

"And if we want human approval before any action executes..." *(change `require_approval` to `"always"`)* "...the agent pauses and shows you the plan before doing anything."

---

## Key Talking Points

| Point | One-liner |
|-------|-----------|
| No OpenClaw | "We replaced the legacy tool runtime with proper MCP — it's the Microsoft-native standard" |
| No raw media | "The JPEG never leaves Azure. Only structured text reaches the agent." |
| MCP governance | "One AI Gateway policy applies to all tool calls — auth, rate limits, audit logs" |
| ADHD focus | "It coaches you proactively — if you drift, it nudges you back to work" |
| M365 native | "Outputs land in OneNote, To-Do, Teams — where your work already lives" |

---

## Fallback (if M365 / network unavailable)

All tool calls fall back to SQLite automatically. Demo with:
```bash
sqlite3 cvp_demo.db "SELECT * FROM notes; SELECT * FROM tasks; SELECT * FROM drafts;"
```
Story: "In offline mode, everything queues locally and syncs when connectivity returns."
