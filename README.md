/ CVP



A real-time AI assistant for Meta Ray-Ban smart glasses. See what you see, hear what you say, take actions in Microsoft 365 — all through voice.

![Cover](assets/cover.png)

**Android (CVP):** Built on [Meta Wearables DAT SDK](https://github.com/facebook/meta-wearables-dat-android) + Azure OpenAI Realtime + Azure AI Foundry Agents + MCP tool server. All tool execution routes through Microsoft-governed MCP infrastructure — no OpenClaw, no local gateway.



**Supported platforms:** Android (Pixel, Samsung, etc.) and iOS (iPhone)

---

## What It Does (Android / CVP)

Put on your glasses, tap the eye button, and talk:

- **"What am I looking at?"** — Azure Vision OCR captures your scene and the AI describes it through your glasses speaker
- **"Create a note about this meeting"** — Foundry agent calls the MCP server → OneNote page created in your Microsoft 365
- **"Add a task to follow up with the team"** — Foundry agent → MCP tool → Microsoft To-Do task created
- **"Send a Teams message with this summary"** — Foundry agent → MCP tool → Teams message sent to your configured chat
- **"Learn that when I say expense this, tag the note [EXPENSE]"** — voice-teaches a persistent skill; applied automatically in every future session
- **Natural conversation with barge-in** — interrupt the assistant mid-sentence; it stops immediately and processes what you said
- **ADHD focus coaching** — proactive check-ins: if you drift from your desk, it nudges you back

---

## How It Works (Android / CVP)

```
Meta Ray-Ban Glasses (DAT SDK)
  │  I420 YUV frames @ 24fps + PCM audio 16kHz
  ▼
VisionClaw Android App
  │  Burst capture: 1 JPEG per look (Work Mode tap)
  │  Continuous PCM audio streaming
  ▼
CVP Vision Gateway  (Azure Container App: cvpac)
  │  Azure Vision OCR → structured text only
  │  Raw JPEG discarded immediately (never stored)
  ▼
Azure OpenAI Realtime API  (gpt-4o-realtime-preview)
  │  WebSocket — voice conversation + tool routing
  │  Built-in VAD + echo cancellation
  │  VisionSignal injected as conversation context
  │  Barge-in: response.cancel sent on speech_started
  │  Fires execute(task=...) → ToolCallRouter
  ▼
Azure AI Foundry Agent  (CVP Orchestrator)
  │  Persistent agent — reasons over task + context
  │  Receives [MCP_TOOLS] + [LEARNED_SKILLS] per thread
  │  Calls call_mcp_tool(name, arguments) for any action
  │  requires_action → Android dispatches to MCP server
  ▼
CVP MCP Server  (Azure Container App)
  │  MCP Streamable HTTP transport
  │  Native tools: create_note / create_task / draft_message / get_status
  │  Skill tools: save_skill / get_skills / delete_skill  (SQLite-backed)
  │  Remote proxy: Foundry catalog tools via REMOTE_MCP_SERVERS env
  │  Microsoft Graph API → OneNote, To-Do, Teams
  │  SQLite fallback for offline / demo
  ▼
Microsoft 365 (Copilot for Work data plane)
```

---

## Voice-Taught Skills

The agent learns new behaviors at runtime — no redeploy needed.

**Teaching a skill:**
> "Learn that when I say expense this, create a note tagged [EXPENSE]"

The agent calls `save_skill` on the MCP server. The skill is stored in SQLite and injected as `[LEARNED_SKILLS]` context into every future Foundry thread — automatically applied whenever the trigger phrase is heard.

**Managing skills:**
> "What skills do you know?" → agent calls `get_skills`
> "Forget the expense skill" → agent calls `delete_skill`

**Adding Foundry catalog tools (admin):** The MCP server can proxy any Remote MCP server from the Azure AI Foundry catalog (GitHub, Tavily web search, Azure DevOps, Work IQ Calendar/Teams/SharePoint, Stripe, etc.) — see [Adding a Foundry catalog tool](#adding-a-foundry-catalog-tool) below.

---

## Repository Structure

```
VisionClaw/
├── samples/
│   ├── CameraAccessAndroid/   Android app (CVP — this is the active branch)
│   └── CameraAccess/          iOS app (Gemini + OpenClaw)
├── gateway/                   Python FastAPI — vision analysis (Azure Vision OCR)
├── mcp_server/                Python FastAPI — MCP tool server (M365 actions + skills + remote proxy)
├── agent_service/             Python — Foundry agent setup script (run once)
├── schemas/                   JSON contracts (VisionIntentEvent, StructuredContext, CopilotResponse)
├── infra/                     Bicep templates + AI Gateway governance guide
├── docs/                      Architecture, RAI/Privacy posture, Demo script
├── deploy.sh                  One-command Azure deployment
└── .env.deploy.example        Required secrets template
```

---

## Quick Start — Android (CVP)

### 1. Clone and open

```bash
git clone <your-fork-url>
```

Open `samples/CameraAccessAndroid/` in Android Studio.

### 2. Configure GitHub Packages (DAT SDK)

The Meta DAT Android SDK is distributed via GitHub Packages. You need a GitHub Personal Access Token with `read:packages` scope.

1. Create a **classic** token at [GitHub > Settings > Developer Settings > Personal Access Tokens](https://github.com/settings/tokens) with `read:packages` scope
2. Add to `samples/CameraAccessAndroid/local.properties`:

```properties
github_token=YOUR_GITHUB_TOKEN
```

> If you have the `gh` CLI: `gh auth token` — make sure it has `read:packages` scope.

### 3. Deploy the Azure backend (one-time)

The Android app requires two Azure backend services. Deploy them to the existing Azure environment:

```bash
# Fill in your Azure Vision key and an MCP bearer token (any secret string)
cp .env.deploy.example .env.deploy
nano .env.deploy

# Build images in Azure (no local Docker needed) + deploy Container Apps
source .env.deploy
bash deploy.sh all

# Create the Foundry persistent agent — prints an Agent ID to paste into Settings
bash deploy.sh agent
```

> Requires: `az login` + `az account set --subscription YOUR_SUBSCRIPTION_ID`

**What gets deployed:**

| Service | Container App | Purpose |
|---------|--------------|---------|
| Vision Gateway | `cvpac` | JPEG → Azure Vision OCR → VisionSignal JSON |
| MCP Server | `cvp-mcp-server` | MCP tools: notes, tasks, messages, skills, remote proxy |

### 4. Configure Android Settings

Build and run the app on your Android phone, then open **Settings** (gear icon) and fill in:

| Section | Field | Where to get it |
|---------|-------|----------------|
| Azure Speech | Region + Key | Your Azure Speech resource → Keys and Endpoint |
| Azure OpenAI | Endpoint + Key + Deployment | Your Azure OpenAI resource → Keys and Endpoint |
| Azure AI Foundry | Project Endpoint + Agent ID | Output of `bash deploy.sh agent` |
| Azure AI Foundry | MCP Server URL | `https://cvp-mcp-server.YOUR_ENV.YOUR_REGION.azurecontainerapps.io/mcp` |
| Azure AI Foundry | MCP Bearer Token | Your `MCP_BEARER_TOKEN` from `.env.deploy` |
| Azure Computer Vision | Endpoint + Key | Your Azure Computer Vision resource → Keys and Endpoint |
| Microsoft 365 | Graph Access Token | [graph.microsoft.com/graph-explorer](https://developer.microsoft.com/graph/graph-explorer) → Access token (top right). Scopes: `Notes.ReadWrite Tasks.ReadWrite Chat.ReadWrite` |
| Microsoft 365 | Teams Chat ID | Teams → chat → copy link → extract ID (`19:xxx@thread.v2`) |
| Vision (Work Mode) | Gateway URL + Token | `https://cvpac.YOUR_ENV.YOUR_REGION.azurecontainerapps.io` + your `MCP_BEARER_TOKEN` |

> The Graph Access Token expires in ~1 hour. Re-paste from Graph Explorer when it expires. For production, replace with an MSAL delegated flow.

### 5. Enable Developer Mode on glasses

1. Open the **Meta AI** app → Settings → App Info
2. Tap the **App version** number **5 times** to unlock Developer Mode
3. Toggle **Developer Mode** on

### 6. Try it out

**Without glasses (Phone mode):**
1. Tap **"Start on Phone"** — uses your phone's back camera
2. Tap the **AI button** (sparkle icon) to connect
3. Talk — it can see through your phone camera

**With Meta Ray-Ban glasses:**
1. Tap **"Start Streaming"**
2. Tap the **AI button** for real-time voice + vision

**Work Mode (vision capture):**
1. Enable **Work Mode** in Settings
2. Tap the **eye button** during a session to capture your current scene
3. Ask "what am I looking at?" or "create a note about this"

---

## Quick Start — iOS (original Gemini build)

> **Note for team:** The iOS app currently runs on Gemini Live + OpenClaw. It needs to be ported to the CVP Azure stack for parity with Android. See the **[iOS CVP Port — Team Action Required](#ios-cvp-port--team-action-required)** section below for the full porting guide. The original Gemini build remains functional in the meantime.

### 1. Clone and open

```bash
cd VisionClaw/samples/CameraAccess
open CameraAccess.xcodeproj
```

### 2. Add secrets

```bash
cp CameraAccess/Secrets.swift.example CameraAccess/Secrets.swift
```

Edit `Secrets.swift` with your [Gemini API key](https://aistudio.google.com/apikey) and optional OpenClaw/WebRTC config.

### 3. Build and run

Select your iPhone as the target and hit Run (Cmd+R).

### 4. Try it out

**Without glasses:** Tap **"Start on iPhone"** → tap the AI button → talk.

**With glasses:** Enable Developer Mode (same steps as Android), tap **"Start Streaming"** → AI button.

### OpenClaw (iOS optional — legacy)

Follow the [OpenClaw setup guide](https://github.com/nichochar/openclaw) and configure `Secrets.swift`:

```swift
static let openClawHost = "http://Your-Mac.local"
static let openClawPort = 18789
static let openClawGatewayToken = "your-gateway-token-here"
```

---

## iOS CVP Port — Team Action Required

> **Assigned to:** iOS team member with Xcode + iPhone
> **Reference implementation:** Android CVP (`samples/CameraAccessAndroid/`) — port Kotlin → Swift
> **Shared backend:** Same Azure Container Apps — no backend changes needed

The Android CVP build is the source of truth. The iOS port is a direct Kotlin → Swift translation: the architecture, class names, data flow, and Azure API calls are identical. The Python backend (Vision Gateway, MCP Server, Foundry Agent) is already deployed and shared.

### What needs to change

The iOS app lives in `samples/CameraAccess/CameraAccess/`. These are the files to create or replace:

#### 1. Replace `GeminiLiveService.swift` → `AzureRealtimeService.swift`

**Port from:** `azure/AzureRealtimeService.kt`

The existing `GeminiLiveService` uses `URLSessionWebSocketTask`. Replace with an equivalent using the same WebSocket API pointing at Azure OpenAI Realtime.

Key differences vs Gemini:
- Endpoint: `wss://{endpoint}/openai/realtime?api-version=2024-10-01-preview&deployment={deployment}`
- Auth header: `api-key: {key}` (not a query param)
- Session setup: send `session.update` JSON event on connect (same structure as Android)
- Events to handle: `session.created`, `session.updated`, `input_audio_buffer.speech_stopped`, `response.audio.delta`, `response.audio.done`, `response.done`, `response.cancelled`, `error`
- Tool calls arrive in `response.done` → `response.output[]` where `type == "function_call"`
- Tool responses sent as `conversation.item.create` (type `function_call_output`) + `response.create`
- `responsePending` flag: critical — set on `speech_stopped`, clear on `response.done`/`response.cancelled`. Prevents "conversation already has an active response" errors.
- **Barge-in**: send `response.cancel` on `input_audio_buffer.speech_started` when `_isModelSpeaking == true`

Public callbacks to preserve (same names as `GeminiLiveService` for drop-in ViewModel compatibility):
```swift
var onAudioReceived: ((Data) -> Void)?
var onTurnComplete: (() -> Void)?
var onInterrupted: (() -> Void)?
var onDisconnected: ((String?) -> Void)?
var onInputTranscription: ((String) -> Void)?
var onOutputTranscription: ((String) -> Void)?
var onSpeechStarted: (() -> Void)?
var onToolCall: ((GeminiToolCall) -> Void)?
```

#### 2. Replace `OpenClawBridge.swift` → `FoundryAgentBridge.swift`

**Port from:** `azure/FoundryAgentBridge.kt`

Implements `TaskBridge` protocol (same pattern as Android). Two paths:

**Primary path** (when `cvpFoundryProjectEndpoint` + `cvpFoundryAgentId` are set):
```
POST {foundryEndpoint}/openai/threads                          → threadId
POST {foundryEndpoint}/openai/threads/{id}/messages            → inject task + [MCP_TOOLS] + [LEARNED_SKILLS] + [GRAPH_TOKEN]
POST {foundryEndpoint}/openai/threads/{id}/runs                → runId
GET  {foundryEndpoint}/openai/threads/{id}/runs/{runId}        → poll (1s interval, 30s timeout)
  └─ requires_action → POST submit_tool_outputs → MCP server call_mcp_tool
GET  {foundryEndpoint}/openai/threads/{id}/messages            → last assistant message
DELETE {foundryEndpoint}/openai/threads/{id}                   → cleanup
```
Auth: `api-key: {azureOpenAIKey}` header on all requests. API version: `2024-05-01-preview`.

**Fallback path** (when agent not configured): Azure OpenAI Chat Completions → parse JSON action → call `GraphApiClient` directly. Same as the existing Android fallback.

Thread message injection (prepend to each message):
```swift
// [MCP_TOOLS] — fetched once per session via tools/list JSON-RPC
// [LEARNED_SKILLS] — fetched once per session via get_skills tool call
// [GRAPH_TOKEN: xxx] — ephemeral delegated token
// [TEAMS_CHAT_ID: xxx] — Teams chat for draft_message
```

#### 3. Create CVP Vision Pipeline

**Port from:** `cvp/VisionFramePipeline.kt`, `cvp/PrivateModePipeline.kt`, `cvp/BurstCaptureController.kt`

**`VisionFramePipeline.swift`** — protocol + `VisionSignal` struct:
```swift
struct VisionSignal {
    let ocrText: String?
    let objects: [String]
    let uiHint: String?
    let rawMediaTransmitted: Bool
    let error: String?
}

protocol VisionFramePipeline {
    func processFrame(_ jpegData: Data) async -> VisionSignal
}
```

**`PrivateModePipeline.swift`** — sends JPEG to the deployed gateway:
```swift
// POST https://cvpac.YOUR_ENV.YOUR_REGION.azurecontainerapps.io/v1/vision/analyze
// Headers: x-cvp-mode: PRIVATE, Authorization: Bearer {gatewayToken}
// Body: raw JPEG bytes
// Response: { ocrText, objects, uiHint, rawMediaTransmitted }
```

**`BurstCaptureController.swift`** — gates capture to one frame per user-initiated look. Uses an `AtomicBool`-equivalent (`OSAtomicCompareAndSwap32` or an actor) to ensure only the first frame per burst is processed.

#### 4. Create `GraphApiClient.swift`

**Port from:** `graph/GraphApiClient.kt`

Three async functions using `URLSession`:
```swift
func createTodoTask(token: String, title: String, body: String) async -> Result<String, Error>
func sendTeamsMessage(token: String, chatId: String, message: String) async -> Result<String, Error>
func createOneNotePage(token: String, title: String, content: String) async -> Result<String, Error>
```

Endpoints are identical to Android (`/v1.0/me/todo/lists/{id}/tasks`, etc.).

#### 5. Update `SettingsManager.swift`

Add these new keys (mirror Android `SettingsManager.kt`):

```swift
// Azure OpenAI
var azureOpenAIEndpoint: String      // e.g. https://YOUR_OPENAI_RESOURCE.openai.azure.com/
var azureOpenAIKey: String
var azureOpenAIDeployment: String    // default: "gpt-4o"

// Azure AI Foundry (MCP routing)
var cvpFoundryProjectEndpoint: String  // e.g. https://YOUR_FOUNDRY_ENDPOINT.inference.ml.azure.com
var cvpFoundryAgentId: String          // from deploy.sh agent output
var cvpMcpServerUrl: String            // https://cvp-mcp-server.YOUR_ENV.YOUR_REGION.azurecontainerapps.io/mcp
var cvpMcpBearerToken: String          // must match MCP_BEARER_TOKEN on server

// Azure Computer Vision
var azureVisionEndpoint: String
var azureVisionKey: String

// CVP Vision Gateway
var cvpGatewayUrl: String           // https://cvpac.YOUR_ENV.YOUR_REGION.azurecontainerapps.io
var cvpGatewayToken: String

// Microsoft 365
var microsoftGraphToken: String     // delegated token from Graph Explorer
var teamsDefaultChatId: String      // 19:xxx@thread.v2

// Modes
var workModeEnabled: Bool           // default false
var videoStreamingEnabled: Bool     // default true
var proactiveNotificationsEnabled: Bool
var focusCoachingEnabled: Bool
var focusCoachingIntervalSeconds: Int  // default 60
```

Remove (no longer needed): `openClawHost`, `openClawPort`, `openClawHookToken`, `openClawGatewayToken`

#### 6. Update `SettingsView.swift`

Replace OpenClaw section with Azure/Foundry sections. Reference `ui/SettingsScreen.kt` for exact field labels and groupings:

- **Microsoft 365** — Graph Access Token, Teams Chat ID
- **Azure Speech** — Region, Key
- **Azure OpenAI (Foundry Agent)** — Endpoint, API Key, Deployment
- **Azure AI Foundry (MCP Tool Routing)** — Project Endpoint, Agent ID, MCP Server URL, MCP Bearer Token
- **Azure Computer Vision** — Endpoint, API Key
- **Video** — Video Streaming toggle
- **Notifications** — Proactive Notifications toggle
- **Focus Coaching** — toggle + interval field
- **Vision (Work Mode)** — toggle + capture mode selector

#### 7. Update `GeminiSessionViewModel.swift` → `SessionViewModel.swift`

**Port from:** `gemini/GeminiSessionViewModel.kt`

Main wiring changes:
- Replace `GeminiLiveService` dependency with `AzureRealtimeService`
- Replace `OpenClawBridge` dependency with `FoundryAgentBridge`
- Add `visionPipeline: VisionFramePipeline?` and `burstController: BurstCaptureController`
- Add `injectVisionSignal(_ signal: VisionSignal)` — updates `lastVisionDescription` in UI state, calls `azureService.sendVisionSignal()`
- Add `startCoachingLoop()` — background `Task` that fires proactive focus checks on interval
- `onProactiveCapture` callback — wired to `triggerLook()` on the stream ViewModel

#### 8. Update `GeminiOverlayView.swift`

**Port from:** `ui/GeminiOverlayView.kt`

Status pills to show:
- **Foundry** (green/orange/red) — `FoundryAgentBridge` connection state
- **Vision** (blue, only when burst active + pipeline ready) — replaces previous Gemini indicator
- **"Vision: not configured"** (red) — Work Mode on but gateway not set up
- **"Seen: [description]"** — last Azure Vision result

### What does NOT change

- `AudioManager.swift` — PCM 16kHz in / 24kHz out is identical to the Azure Realtime API requirements. Reuse as-is.
- `ToolCallRouter.swift` — backend-agnostic, works with any `TaskBridge`. Reuse as-is.
- `ToolCallModels.swift` — data classes are shared. Reuse as-is.
- `WebRTC/` — unrelated to CVP, no changes.
- `IPhoneCameraManager.swift` — add a hook to call `triggerLook()` for phone-camera burst capture, otherwise unchanged.
- Python backend — already deployed, shared with Android. Zero changes.

### Secrets.swift.example — update

Replace OpenClaw fields with Azure fields:

```swift
// Before (remove these):
static let openClawHost = "http://YOUR_MAC_HOSTNAME.local"
static let openClawPort = 18789
static let openClawGatewayToken = "YOUR_OPENCLAW_GATEWAY_TOKEN"

// After (add these):
static let azureOpenAIEndpoint = "https://YOUR_OPENAI_RESOURCE.openai.azure.com/"
static let azureOpenAIKey = ""
static let azureOpenAIDeployment = "gpt-4o"
static let cvpGatewayUrl = "https://cvpac.YOUR_ENV.YOUR_REGION.azurecontainerapps.io"
static let cvpGatewayToken = ""  // same as MCP_BEARER_TOKEN from .env.deploy
static let cvpFoundryProjectEndpoint = ""  // from: bash deploy.sh agent
static let cvpFoundryAgentId = ""          // from: bash deploy.sh agent
static let cvpMcpServerUrl = "https://cvp-mcp-server.YOUR_ENV.YOUR_REGION.azurecontainerapps.io/mcp"
static let cvpMcpBearerToken = ""          // same as MCP_BEARER_TOKEN
static let azureVisionEndpoint = "https://YOUR_VISION_RESOURCE.cognitiveservices.azure.com/"
static let azureVisionKey = ""
```

### Testing checklist

Before marking the iOS port complete:

- [ ] App connects to Azure OpenAI Realtime — voice conversation works
- [ ] `responsePending` flag prevents "active response" disconnects
- [ ] Barge-in: speaking while model talks sends `response.cancel` and model stops immediately
- [ ] Work Mode tap fires burst capture → JPEG sent to gateway → VisionSignal returned → injected into session
- [ ] Vision status pill shows blue during burst, disappears when done
- [ ] "Seen: [description]" updates in overlay after each capture
- [ ] Speaking "create a note" → Foundry agent creates OneNote page (verify in M365)
- [ ] Speaking "add a task" → Foundry agent creates To-Do task
- [ ] Speaking "learn that..." → `save_skill` called → injected in next session
- [ ] Focus Coaching: with toggle on, periodic check-in fires; "SKIP" response is silent
- [ ] Graph token expired → graceful fallback message, not a crash
- [ ] Foundry Agent ID blank → falls back to Chat Completions path silently

---

## Azure Resources (CVP)

Deploy into your own Azure subscription and resource group. The table below shows the recommended resource types — name them however you like and update `.env.deploy` accordingly:

| Resource | Type | Role |
|----------|------|------|
| `YOUR_VISION_RESOURCE` | Azure Computer Vision | OCR / Image Analysis |
| `YOUR_SPEECH_RESOURCE` | Azure Speech Service | (Future: Voice Live API) |
| `YOUR_OPENAI_RESOURCE` | Azure OpenAI | gpt-4o-realtime — voice conversation |
| Your AI Foundry project | AI Foundry | Foundry persistent agent |
| `cvpac` | Container App | CVP Vision Gateway |
| `cvp-mcp-server` | Container App | CVP MCP Tool Server |
| `YOUR_REGISTRY` | Container Registry | Docker images for both services |
| `YOUR_CONTAINER_APPS_ENV` | Container Apps Env | Hosting environment |
| Your Log Analytics workspace | Log Analytics | Telemetry + audit logs |

**After deploying**, your live endpoints will look like:
- Vision Gateway: `https://cvpac.YOUR_ENV.YOUR_REGION.azurecontainerapps.io`
- MCP Server: `https://cvp-mcp-server.YOUR_ENV.YOUR_REGION.azurecontainerapps.io`
- Foundry Agent: `YOUR_FOUNDRY_AGENT_ID` (printed by `bash deploy.sh agent`)

---

## Architecture (Android / CVP)

### Key Files — Android

All source in `samples/CameraAccessAndroid/app/src/main/java/.../cameraaccess/`:

| File | Purpose |
|------|---------|
| `azure/AzureRealtimeService.kt` | Azure OpenAI Realtime WebSocket client (voice + tool calls + barge-in) |
| `azure/FoundryAgentBridge.kt` | Threads/runs REST API; injects [MCP_TOOLS] + [LEARNED_SKILLS]; handles requires_action |
| `cvp/VisionFramePipeline.kt` | Interface + `VisionSignal` data model |
| `cvp/AzureComputerVisionPipeline.kt` | Direct Azure Vision calls (development/fallback) |
| `cvp/PrivateModePipeline.kt` | Sends JPEG to CVP Vision Gateway |
| `cvp/BurstCaptureController.kt` | Gates frame capture to one JPEG per look |
| `cvp/CuiStreamOnlyPipeline.kt` | On-device ML Kit OCR (no frames leave device) |
| `graph/GraphApiClient.kt` | Microsoft Graph API: OneNote, To-Do, Teams |
| `openclaw/ToolCallRouter.kt` | Routes model tool calls to `FoundryAgentBridge` |
| `openclaw/ToolCallModels.kt` | Tool call data classes |
| `openclaw/TaskBridge.kt` | Interface for pluggable task execution backends |
| `gemini/GeminiSessionViewModel.kt` | Session lifecycle, ADHD coaching loop, UI state |
| `settings/SettingsManager.kt` | SharedPreferences — all configuration |
| `ui/SettingsScreen.kt` | In-app Settings screen |
| `stream/StreamViewModel.kt` | Glasses camera stream + burst capture wiring |

### Key Files — Python Backend

| File | Purpose |
|------|---------|
| `gateway/main.py` | FastAPI vision gateway — JPEG → Azure Vision OCR → VisionSignal |
| `mcp_server/main.py` | FastAPI MCP server — 7+ tools, voice-taught skills (SQLite), remote MCP proxy |
| `agent_service/agent.py` | One-shot: creates/updates Foundry agent with `call_mcp_tool` dispatcher |
| `infra/main.bicep` | Bicep: deploys both Container Apps |
| `infra/ai_gateway.md` | APIM governance setup guide (auth, rate limits, audit logging) |
| `docs/Architecture.md` | Full data flow diagram |
| `docs/RAI_Privacy.md` | Privacy posture + RAI policy |
| `docs/DemoScript.md` | 3-minute demo script |

### Tool Execution Flow (CVP)

The Foundry agent uses a **universal dispatcher** — a single `call_mcp_tool(name, arguments)` function. Android injects the current `tools/list` from the MCP server as `[MCP_TOOLS]` context into every thread, so the agent always knows what tools are available without requiring an agent redeploy when new tools are added.

1. User says *"Create a note about this whiteboard"*
2. Azure Realtime model fires `execute(task="create a note...")` tool call
3. `ToolCallRouter` → `FoundryAgentBridge.delegateTask()`
4. `FoundryAgentBridge` creates Foundry thread, posts task with:
   - `[MCP_TOOLS]` — current tool catalog from MCP server
   - `[LEARNED_SKILLS]` — voice-taught behaviors from previous sessions
   - `[GRAPH_TOKEN: ...]` — ephemeral delegated M365 token
5. Foundry Orchestrator Agent calls `call_mcp_tool("create_note", {title, body, graph_token})`
6. Run enters `requires_action` → Android extracts tool call → dispatches to MCP server
7. MCP server posts to `POST /v1.0/me/onenote/pages` via Microsoft Graph
8. Android submits tool output → run completes → spoken through glasses speaker

If the Foundry agent is not configured (Agent ID blank), falls back to Azure OpenAI Chat Completions + direct Graph API calls (zero regression).

### Voice-Taught Skill Flow

1. User says *"Learn that when I say expense this, create a note tagged [EXPENSE]"*
2. Foundry agent calls `call_mcp_tool("save_skill", {name, instructions, trigger_phrases})`
3. Skill stored in MCP server SQLite (`skills` table)
4. Next session: `FoundryAgentBridge` calls `get_skills` → injects as `[LEARNED_SKILLS]`
5. User says *"expense this"* → agent creates tagged note automatically

### Remote MCP Proxy (Foundry Catalog Tools)

The MCP server can proxy any Remote MCP server from the Azure AI Foundry catalog:

```bash
az containerapp update --name cvp-mcp-server \
  --resource-group YOUR_RESOURCE_GROUP \
  --set-env-vars 'REMOTE_MCP_SERVERS=[{"label":"tavily","url":"https://mcp.tavily.com/mcp/","headers":{"Authorization":"Bearer {key}"}}]'
```

At startup, the MCP server calls `tools/list` on each configured remote server and registers its tools with a `{label}__` prefix (e.g., `tavily__web_search`). These tools appear in `tools/list` and are automatically available to the Foundry agent via the `[MCP_TOOLS]` injection — no agent redeploy needed.

### ADHD Focus Coaching

When **Focus Coaching** is enabled in Settings, a background loop fires every N seconds (default 60s):
- Triggers a silent burst capture
- Sends `[FOCUS CHECK]` + scene description to the Realtime model
- Model responds `SKIP` (you look focused) or speaks a short nudge
- Designed to be non-intrusive: no nudge if you just acknowledged one

### Privacy Posture

- JPEG frames are analyzed and **immediately discarded** at the gateway — never stored
- PCM audio flows through Azure Realtime WebSocket only — never written to disk
- Microsoft Graph token is **ephemeral** — lives only in Foundry thread context, not persisted anywhere
- `rawMediaTransmitted: false` is asserted in all VisionSignal responses
- CUI mode: no media transmitted, no MCP writes — text-only path

See [docs/RAI_Privacy.md](docs/RAI_Privacy.md) for full posture.

---

## Deployment

### Full deploy (first time)

```bash
cp .env.deploy.example .env.deploy   # fill in AZURE_VISION_KEY + MCP_BEARER_TOKEN
source .env.deploy
bash deploy.sh all      # builds + pushes images to ACR + deploys Container Apps
bash deploy.sh agent    # creates Foundry agent, prints Agent ID
```

### Update a single service

```bash
bash deploy.sh gateway   # redeploy vision gateway only
bash deploy.sh mcp       # redeploy MCP server only
bash deploy.sh agent --update  # update existing agent system prompt / tool config
```

### Verify services

```bash
curl https://cvpac.YOUR_ENV.YOUR_REGION.azurecontainerapps.io/health
curl https://cvp-mcp-server.YOUR_ENV.YOUR_REGION.azurecontainerapps.io/health
```

### Test MCP tools locally

```bash
cd mcp_server
MCP_BEARER_TOKEN=test uvicorn main:app --port 8001
curl -X POST http://localhost:8001/mcp \
  -H "Authorization: Bearer test" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

### Adding a Foundry catalog tool

To connect any Remote MCP server from the [Azure AI Foundry catalog](https://ai.azure.com) (GitHub, Tavily, Azure DevOps, Work IQ, Stripe, etc.):

```bash
# Example: add Tavily web search
az containerapp update --name cvp-mcp-server \
  --resource-group YOUR_RESOURCE_GROUP \
  --set-env-vars 'REMOTE_MCP_SERVERS=[{"label":"tavily","url":"https://mcp.tavily.com/mcp/","headers":{"Authorization":"Bearer YOUR_TAVILY_KEY"}}]'
```

Multiple servers:
```json
[
  {"label": "github",   "url": "https://api.githubcopilot.com/mcp/",  "headers": {"Authorization": "Bearer ghp_xxx"}},
  {"label": "tavily",   "url": "https://mcp.tavily.com/mcp/",         "headers": {"Authorization": "Bearer tvly_xxx"}}
]
```

After updating, the new tools appear in `tools/list` as `{label}__{tool_name}` (e.g., `github__create_issue`, `tavily__web_search`). The Foundry agent picks them up automatically on the next session — no agent redeploy needed.

---

## Requirements

### Android (CVP)
- Android 14+ (API 34+)
- Android Studio Ladybug or newer
- GitHub account with `read:packages` token (for DAT SDK)
- Azure subscription with resources provisioned (see table above)
- Meta Ray-Ban glasses (optional — Phone mode works without them)

### iOS (original)
- iOS 17.0+
- Xcode 15.0+
- Gemini API key ([get one free](https://aistudio.google.com/apikey))
- Meta Ray-Ban glasses (optional)
- OpenClaw on your Mac (optional)

### Python backend
- Python 3.12+
- Azure CLI (`az login` configured)
- Access to your Azure subscription

---

## Troubleshooting

### Android / CVP

**"Azure OpenAI endpoint/key not configured"** — Open Settings → Azure OpenAI and paste your Azure OpenAI endpoint and key.

**Vision not working / "not configured" pill showing red** — Enable Work Mode in Settings and ensure CVP Vision Gateway URL is set. Verify `bash deploy.sh gateway` completed successfully.

**"conversation already has an active response" disconnects** — Fixed in current build via `responsePending` flag in `AzureRealtimeService.kt`. If it recurs, check that only one `response.create` is sent per user turn.

**Assistant keeps talking after I start speaking** — Barge-in is implemented in `AzureRealtimeService.kt`: `response.cancel` is sent when `speech_started` fires while `_isModelSpeaking == true`. If it recurs, verify the `silence_duration_ms: 300` VAD setting is applied in `session.update`.

**Foundry agent not executing tools** — Check Agent ID and Project Endpoint in Settings. Run `bash deploy.sh agent --test` to smoke-test the agent. If blank, the bridge falls back to Chat Completions (which won't call MCP tools).

**MCP tools not available / agent says "no tools listed"** — Check MCP Server URL and MCP Bearer Token in Settings → Azure AI Foundry. Verify the MCP server is healthy: `curl https://cvp-mcp-server.YOUR_ENV.YOUR_REGION.azurecontainerapps.io/health`

**Voice-taught skills not applying** — Check that the MCP server URL is configured in Settings. Skills are fetched fresh each session via `get_skills`; if the MCP server is unreachable, the `[LEARNED_SKILLS]` block is omitted silently.

**"Learn that..." not being saved** — The agent must call `call_mcp_tool("save_skill", ...)` in response. Verify the agent was updated with `bash deploy.sh agent --update` after the system prompt change. Check Logcat for `requires_action` events.

**Remote MCP proxy tool not found** — After updating `REMOTE_MCP_SERVERS`, the Container App must restart to re-discover tools. Check logs for `Remote MCP '{label}': registered N tools`. If `N == 0`, the remote server may require different auth headers or a different `tools/list` endpoint.

**Graph token expired** — The delegated token from Graph Explorer lasts ~1 hour. Re-paste a fresh token from [graph.microsoft.com/graph-explorer](https://developer.microsoft.com/graph/graph-explorer).

**MCP tools using SQLite instead of M365** — The Graph token was either blank or expired when the tool was called. Check Settings → Microsoft 365 → Graph Access Token.

**Container App deployment fails** — Ensure `az login` and `az account set --subscription YOUR_SUBSCRIPTION_ID` before running `deploy.sh`. Check that your account has Contributor access on your resource group.

**Gradle sync fails with 401** — Your GitHub token is missing or lacks `read:packages` scope. Check `local.properties`. Generate a new token at [github.com/settings/tokens](https://github.com/settings/tokens).

**Audio not working** — Ensure `RECORD_AUDIO` permission is granted. On Android 13+, grant manually in Settings > Apps if needed.

### iOS (original)

**OpenClaw connection timeout** — Make sure your phone and Mac are on the same Wi-Fi, the gateway is running (`openclaw gateway restart`), and the hostname matches your Mac's Bonjour name.

**"Gemini API key not configured"** — Add your key in `Secrets.swift` or in-app Settings.

For DAT SDK issues, see the [developer documentation](https://wearables.developer.meta.com/docs/develop/).

---

## License

This source code is licensed under the license found in the [LICENSE](LICENSE) file in the root directory of this source tree.
