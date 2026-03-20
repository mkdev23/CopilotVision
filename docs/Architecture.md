# CVP Architecture — Copilot Vision Platform

## Overview

CVP is a Microsoft-native glasses input modality for Copilot for Work, designed for
ADHD-friendly workplace assistance. Meta Ray-Ban smart glasses provide ambient vision
and audio; the CVP stack processes this into structured context and routes actions
through Microsoft Foundry agents and MCP tools.

## Data Flow

```
Meta Ray-Ban Smart Glasses (DAT SDK)
  │  I420 YUV frames @ 24fps + PCM audio 16kHz
  ▼
VisionClaw Android App
  │  Burst capture (1 JPEG per look) — 85% quality
  │  Audio PCM streamed continuously
  │  ← Burst gate: only fires on explicit user gesture
  ▼
┌─────────────────────────────────────────────────┐
│           CVP Vision Gateway                    │
│  gateway/main.py  (Azure Container App: cvpac)  │
│                                                 │
│  POST /v1/vision/analyze                        │
│  ├── Rejects frame if mode == CUI_STREAM_ONLY   │
│  ├── Azure Vision OCR (Read API 2024-02-01)     │
│  ├── Azure Vision Image Analysis (tags)         │
│  └── Returns VisionSignal JSON                  │
│      rawMediaTransmitted: false (frame dropped) │
└─────────────────────────────────────────────────┘
  │  VisionSignal { ocrText, objects, uiHint }
  ▼
Azure OpenAI Realtime API (gpt-4o-realtime-preview)
  │  WebSocket (wss://)
  │  ← AzureRealtimeService.kt
  │  Audio ↔ streamed in real time
  │  VisionSignal injected as conversation context
  │  Tool: execute(task) → ToolCallRouter
  ▼
FoundryAgentBridge.kt
  │  Foundry Agents REST API (threads/runs)
  │  POST /agents/v1.0/threads/{id}/runs
  │  Polls until complete
  ▼
┌─────────────────────────────────────────────────┐
│         CVP Orchestrator (Foundry Agent)        │
│  Created by agent_service/agent.py              │
│  Model: gpt-4o                                  │
│  Tools: MCP server (cvp_tools)                  │
└─────────────────────────────────────────────────┘
  │  MCP tool calls (Streamable HTTP)
  ▼
┌─────────────────────────────────────────────────┐
│           CVP MCP Server                        │
│  mcp_server/main.py  (Azure Container App)      │
│                                                 │
│  Tools:                                         │
│  ├── create_note(title, body, graph_token?)     │
│  ├── create_task(title, due_date, notes, ...)   │
│  ├── draft_message(channel, body, ...)          │
│  └── get_status()                               │
│                                                 │
│  Storage:                                       │
│  ├── Microsoft Graph API (if graph_token set)   │
│  └── SQLite (cvp_demo.db) as fallback           │
└─────────────────────────────────────────────────┘
  │  Microsoft Graph API v1.0
  ▼
Microsoft 365 (Copilot for Work data plane)
  ├── OneNote (create_note)
  ├── Microsoft To-Do (create_task)
  └── Microsoft Teams (draft_message)
```

## Components

| Component | Tech | Location | Purpose |
|-----------|------|----------|---------|
| Android App | Kotlin, Jetpack Compose | VisionClaw repo | Glasses camera + audio + UI |
| Vision Gateway | Python FastAPI | Azure Container App `cvpac` | JPEG → structured text |
| Realtime Service | OkHttp WebSocket | `azure/AzureRealtimeService.kt` | Voice conversation |
| Foundry Bridge | Kotlin | `azure/FoundryAgentBridge.kt` | Task delegation to Foundry |
| Foundry Agent | Azure AI Foundry | `cvp-resource` project | Reasoning + tool routing |
| MCP Server | Python FastAPI + MCP SDK | Azure Container App | Tool execution layer |
| AI Gateway | Azure APIM | Connected to `cvp-resource` | Governance / audit |
| M365 | Microsoft Graph | Microsoft 365 tenant | Output destination |

## Privacy Architecture

- **No raw media stored at any layer**: JPEG is analyzed and dropped immediately at gateway
- **No audio persisted**: PCM audio flows through Realtime WebSocket, never written to disk
- **Ephemeral context**: Foundry threads are deleted after completion
- **Token handling**: Graph tokens live only in Foundry thread context (ephemeral)
- **CUI mode**: No media transmitted, no MCP tool writes, text-only path

## Auth Chain

```
Android App
  → AzureRealtimeService: api-key header (Azure OpenAI key)
  → PrivateModePipeline: Bearer token → Vision Gateway
  → FoundryAgentBridge: Bearer token (Azure OpenAI key) → Foundry Agents REST API
    → Foundry Agent → MCP_BEARER_TOKEN → CVP MCP Server
      → graph_token (delegated, from Graph Explorer) → Microsoft Graph
```
