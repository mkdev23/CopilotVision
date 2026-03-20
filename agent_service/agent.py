"""
CVP Agent Service — creates and configures the Foundry persistent agent.

Uses the Azure AI Foundry Agents REST API directly (no azure-ai-projects SDK dependency).

Run ONCE to create the agent, then copy the printed agent ID into Android
Settings → Azure AI Foundry Agent ID.

Usage:
    pip install -r requirements.txt
    cp .env.example .env          # fill in values
    python agent.py               # creates agent, prints Agent ID
    python agent.py --delete      # deletes existing agent by ID
    python agent.py --test        # creates agent + runs smoke test

Environment variables (set in .env or shell):
    FOUNDRY_PROJECT_ENDPOINT   e.g. https://cvp.eastus2.inference.ml.azure.com
    FOUNDRY_API_KEY             Azure OpenAI / Foundry API key
    MCP_SERVER_URL              e.g. https://cvp-mcp-server.orangeflower-68897f93.eastus2.azurecontainerapps.io/mcp
    MCP_BEARER_TOKEN            Bearer token for the MCP server
    AGENT_ID                    (optional) existing agent ID to update instead of create
"""

import argparse
import os
import json
import time
from pathlib import Path

import requests
from dotenv import load_dotenv

load_dotenv()

FOUNDRY_PROJECT_ENDPOINT = os.environ["FOUNDRY_PROJECT_ENDPOINT"].rstrip("/")
FOUNDRY_API_KEY           = os.environ["FOUNDRY_API_KEY"]
MCP_SERVER_URL            = os.environ["MCP_SERVER_URL"]
MCP_BEARER_TOKEN          = os.environ.get("MCP_BEARER_TOKEN", "")
AGENT_ID                  = os.environ.get("AGENT_ID", "")

API_VERSION = "2024-05-01-preview"
AGENTS_BASE = f"{FOUNDRY_PROJECT_ENDPOINT}/openai"

HEADERS = {
    "api-key": FOUNDRY_API_KEY,
    "Content-Type": "application/json",
}

# ── System prompt ─────────────────────────────────────────────────────────────

CVP_SYSTEM_PROMPT = """You are CVP — the Copilot Vision Platform Orchestrator Agent for Microsoft 365.

You help users at work by acting on what their Meta Ray-Ban smart glasses see and hear.
You receive a StructuredContext with OCR text, scene description, and a voice transcript.

CRITICAL RULES:
- You NEVER store raw images, audio, or video. Only structured text is processed.
- You ALWAYS use MCP tools for any action (notes, tasks, messages). Never simulate or pretend.
- Before calling a tool, announce briefly what you will do: "Creating a note now..."
- After tool success, give a short spoken confirmation (max 15 words, natural).

GRAPH TOKEN HANDLING:
If the message contains [GRAPH_TOKEN: <token>], extract the token and pass it as the
graph_token argument to whichever MCP tool you call. The token is ephemeral — never
repeat or store it. If no token is provided, call the tool without graph_token (SQLite
fallback will be used).

TOOL ROUTING:
Use call_mcp_tool to execute any action. Available tools are listed in [MCP_TOOLS]
at the start of each conversation thread. Match the user's intent to the right tool
name and pass correct arguments per the schema. For M365-authenticated actions,
include the graph_token from [GRAPH_TOKEN] in the arguments if the tool schema
lists it as a parameter.

LEARNED SKILLS:
[LEARNED_SKILLS] lists behaviors you have been taught in previous sessions.
Apply them automatically when relevant. Do not mention them unless asked.
Example: if a skill says "tag expense notes with [EXPENSE]", do so silently.

TEACHING NEW SKILLS:
When the user says "learn that...", "remember to...", "always do...", or similar,
call call_mcp_tool("save_skill", {"name": "<snake_case_id>",
"instructions": "<precise description of what to do>",
"trigger_phrases": "<comma-separated trigger phrases>"}).
Confirm with at most: "Got it, I'll remember that."

PRIVACY:
- CUI mode: only text input, no MCP tools that write externally
- PRIVATE mode: full tool access
- Always confirm before writing to M365 systems
"""

# ── Tool definitions (universal MCP dispatcher — skills discovered at runtime) ──
#
# A single call_mcp_tool function replaces the previous hardcoded list.
# Android injects [MCP_TOOLS] into each thread so the agent knows what tools
# are available without requiring an agent update when new tools are deployed.

TOOLS = [
    {"type": "function", "function": {
        "name": "call_mcp_tool",
        "description": (
            "Execute any available CVP tool. "
            "Available tools and their schemas are listed in each thread as [MCP_TOOLS]. "
            "Choose the correct tool name and pass arguments that match its schema. "
            "Never guess a tool name — only use names that appear in [MCP_TOOLS]."
        ),
        "parameters": {
            "type": "object",
            "required": ["name", "arguments"],
            "properties": {
                "name": {
                    "type": "string",
                    "description": "The tool name exactly as listed in [MCP_TOOLS]",
                },
                "arguments": {
                    "type": "object",
                    "description": "Arguments object matching the tool's inputSchema",
                },
            },
        },
    }},
]

# ── REST helpers ──────────────────────────────────────────────────────────────

def _post(path: str, body: dict) -> dict:
    url = f"{AGENTS_BASE}{path}?api-version={API_VERSION}"
    r = requests.post(url, headers=HEADERS, json=body)
    if not r.ok:
        raise RuntimeError(f"POST {path} → {r.status_code}: {r.text}")
    return r.json()

def _get(path: str) -> dict:
    url = f"{AGENTS_BASE}{path}?api-version={API_VERSION}"
    r = requests.get(url, headers=HEADERS)
    if not r.ok:
        raise RuntimeError(f"GET {path} → {r.status_code}: {r.text}")
    return r.json()

def _delete(path: str):
    url = f"{AGENTS_BASE}{path}?api-version={API_VERSION}"
    r = requests.delete(url, headers=HEADERS)
    if not r.ok:
        raise RuntimeError(f"DELETE {path} → {r.status_code}: {r.text}")

# ── Agent operations ──────────────────────────────────────────────────────────

def create_agent() -> str:
    """Create a new persistent CVP Orchestrator agent. Returns agent ID."""
    body = {
        "model": "gpt-4o",
        "name": "CVP Orchestrator",
        "instructions": CVP_SYSTEM_PROMPT,
        "tools": TOOLS,
        "metadata": {
            "service": "cvp",
            "version": "1.0.0",
            "privacy": "no_raw_media",
        },
    }
    agent = _post("/assistants", body)
    agent_id = agent["id"]

    print(f"\n✅ Agent created successfully!")
    print(f"   Agent ID : {agent_id}")
    print(f"   Name     : {agent.get('name')}")
    print(f"   Model    : {agent.get('model')}")
    print(f"\n📋 Copy this Agent ID into Android Settings → Azure AI Foundry Agent ID:\n")
    print(f"   {agent_id}\n")

    Path(".agent_id").write_text(agent_id)
    print(f"   (Also saved to .agent_id)\n")
    return agent_id


def update_agent(agent_id: str) -> str:
    """Update an existing agent's config (system prompt, tools)."""
    url = f"{AGENTS_BASE}/assistants/{agent_id}?api-version={API_VERSION}"
    body = {
        "instructions": CVP_SYSTEM_PROMPT,
        "tools": TOOLS,
    }
    r = requests.post(url, headers=HEADERS, json=body)
    if not r.ok:
        raise RuntimeError(f"Update agent → {r.status_code}: {r.text}")
    print(f"✅ Agent {agent_id} updated.")
    return agent_id


def delete_agent(agent_id: str):
    """Delete an agent by ID."""
    _delete(f"/assistants/{agent_id}")
    print(f"🗑️  Agent {agent_id} deleted.")


def test_agent(agent_id: str):
    """Quick smoke test: create thread, send message, run, print result."""
    print(f"\n🧪 Testing agent {agent_id}...")

    thread = _post("/threads", {})
    thread_id = thread["id"]

    _post(f"/threads/{thread_id}/messages", {
        "role": "user",
        "content": "Call get_status and tell me what tools are available.",
    })

    run = _post(f"/threads/{thread_id}/runs", {"assistant_id": agent_id})
    run_id = run["id"]

    # Poll until done
    for _ in range(30):
        time.sleep(2)
        run = _get(f"/threads/{thread_id}/runs/{run_id}")
        status = run["status"]
        if status in ("completed", "failed", "cancelled", "expired"):
            break
        print(f"   ... {status}")

    print(f"   Run status: {status}")

    if status == "completed":
        messages = _get(f"/threads/{thread_id}/messages")
        for msg in reversed(messages.get("data", [])):
            if msg["role"] == "assistant":
                content = msg["content"][0]["text"]["value"] if msg["content"] else "(empty)"
                print(f"   Agent response: {content[:300]}")
                break

    _delete(f"/threads/{thread_id}")
    print("   Thread deleted. Test complete.\n")


# ── Main ──────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="CVP Agent Service")
    parser.add_argument("--delete", action="store_true", help="Delete existing agent")
    parser.add_argument("--update", action="store_true", help="Update existing agent")
    parser.add_argument("--test",   action="store_true", help="Run smoke test after create/update")
    parser.add_argument("--agent-id", default=AGENT_ID, help="Agent ID (override env)")
    args = parser.parse_args()

    agent_id = args.agent_id or (Path(".agent_id").read_text().strip() if Path(".agent_id").exists() else "")

    if args.delete:
        if not agent_id:
            print("❌ No agent ID provided (set AGENT_ID env or use --agent-id)")
            exit(1)
        delete_agent(agent_id)

    elif args.update and agent_id:
        agent_id = update_agent(agent_id)
        if args.test:
            test_agent(agent_id)

    else:
        agent_id = create_agent()
        if args.test:
            test_agent(agent_id)
