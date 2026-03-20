# CVP Responsible AI & Privacy Posture

## No Raw Media Retention

CVP is designed with a privacy-first, media-ephemeral architecture:

| Data Type | Handling |
|-----------|---------|
| JPEG frames | Analyzed by Azure Vision API, then **immediately discarded** at gateway. Never stored, logged, or forwarded. |
| PCM audio | Streamed through Azure OpenAI Realtime WebSocket. Never written to disk or retained. |
| Voice transcripts | Ephemeral — live only in Realtime session context. |
| OCR text / scene tags | Structured output only. Injected as conversation context, not persisted. |
| Graph tokens | Passed through Foundry thread context (ephemeral). Never stored on device or server. |

`rawMediaTransmitted: false` is asserted in all `VisionSignal` responses from the gateway.

## CUI Stream-Only Mode

When `mode == CUI_STREAM_ONLY`:
- The Vision Gateway **rejects any request containing `frame_b64`** (HTTP 400)
- No MCP tools that write to external systems are callable
- Only text transcripts are processed
- Suitable for environments where camera capture must be prevented

## Tool Approval Workflow

The MCP server supports an optional **human-in-the-loop approval step** before any
write tool executes. Configure in `agent_service/agent.py`:

```python
"require_approval": "always"   # agent pauses, user sees proposed action
"require_approval": "never"    # auto-execute (demo mode)
```

This maps to the Foundry Agent tool approval mechanism and satisfies
*"agent plans actions, but execution requires explicit approval"* governance narrative.

## What CVP Does NOT Do

- Does **not** store images, video, or audio on any server
- Does **not** retain conversation history beyond the active session
- Does **not** use OpenClaw or any unapproved tool runtime
- Does **not** transmit raw media to any service other than Azure Vision (PRIVATE mode only)
- Does **not** write to Microsoft 365 without an explicit user action (voice command)

## Governance

MCP tool traffic is routable through **Azure AI Gateway (APIM)** for:
- Authentication enforcement (Entra / Bearer token)
- Rate limiting (prevent runaway tool calls)
- IP restrictions (egress from Azure only)
- Audit logging (all tool calls logged to Log Analytics workspace)

See [infra/ai_gateway.md](../infra/ai_gateway.md) for setup instructions.

## Known Limitations (Demo Build)

- Graph token is a short-lived delegated token (1hr) from Graph Explorer — not a production auth flow
- SQLite fallback stores data locally with no encryption (demo only)
- CUI mode policy is enforced at the gateway but not cryptographically enforced on-device
- Audio VAD (Voice Activity Detection) uses server-side VAD from Azure OpenAI — not local

## Data Classification

This prototype processes data at **PRIVATE** classification level only.
It is **not certified for CUI, PHI, PII, or any regulated data** in its current form.
