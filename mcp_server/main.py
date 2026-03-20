"""
CVP MCP Server — Copilot Vision Platform tool execution layer.

Exposes 4 tools to Foundry agents via MCP Streamable HTTP transport:
  create_note    → OneNote (or SQLite fallback)
  create_task    → Microsoft To-Do (or SQLite fallback)
  draft_message  → Teams (or SQLite fallback)
  get_status     → health / capability probe

Auth: Bearer token validated against MCP_BEARER_TOKEN env var.
      Set MCP_BEARER_TOKEN=<secret> in Container App env vars.
      Upgrade path: swap middleware for Entra managed identity.

Storage:
  If a graph_token is provided in the tool call → Microsoft Graph API (live M365).
  Otherwise → local SQLite (cvp_demo.db) for offline/demo use.

Run locally:
  MCP_BEARER_TOKEN=test uvicorn main:app --port 8001 --reload

Test:
  curl -X POST http://localhost:8001/mcp \
    -H "Authorization: Bearer test" \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
"""

import os
import uuid
import json
import logging
from contextlib import asynccontextmanager
from typing import Optional

import aiohttp
import aiosqlite
from fastapi import FastAPI, Request, Response, HTTPException
from fastapi.responses import JSONResponse
from mcp.server.fastmcp import FastMCP

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("cvp-mcp")

# ── Config ───────────────────────────────────────────────────────────────────

MCP_BEARER_TOKEN = os.environ.get("MCP_BEARER_TOKEN", "")
DB_PATH = os.environ.get("DB_PATH", "/tmp/cvp_demo.db")
GRAPH_BASE = "https://graph.microsoft.com/v1.0"

# Remote MCP servers to proxy at startup.
# JSON array: [{"label":"github","url":"https://...","headers":{"Authorization":"Bearer token"}}]
REMOTE_MCP_SERVERS: list[dict] = json.loads(os.environ.get("REMOTE_MCP_SERVERS", "[]"))

# ── SQLite init ───────────────────────────────────────────────────────────────

async def init_db():
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute("""
            CREATE TABLE IF NOT EXISTS notes (
                id TEXT PRIMARY KEY,
                title TEXT,
                body TEXT,
                created_at TEXT DEFAULT (datetime('now'))
            )""")
        await db.execute("""
            CREATE TABLE IF NOT EXISTS tasks (
                id TEXT PRIMARY KEY,
                title TEXT,
                due_date TEXT,
                notes TEXT,
                created_at TEXT DEFAULT (datetime('now'))
            )""")
        await db.execute("""
            CREATE TABLE IF NOT EXISTS drafts (
                id TEXT PRIMARY KEY,
                channel TEXT,
                body TEXT,
                created_at TEXT DEFAULT (datetime('now'))
            )""")
        await db.execute("""
            CREATE TABLE IF NOT EXISTS skills (
                name TEXT PRIMARY KEY,
                description TEXT,
                instructions TEXT NOT NULL,
                trigger_phrases TEXT,
                created_at TEXT DEFAULT (datetime('now'))
            )""")
        await db.commit()

# ── Graph API helpers ─────────────────────────────────────────────────────────

async def graph_post(token: str, path: str, payload: dict) -> dict:
    """POST to Microsoft Graph. Mirrors GraphApiClient.kt logic."""
    async with aiohttp.ClientSession() as session:
        async with session.post(
            f"{GRAPH_BASE}{path}",
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
            },
            json=payload,
        ) as resp:
            body = await resp.json()
            if resp.status not in range(200, 300):
                raise RuntimeError(f"Graph HTTP {resp.status}: {body}")
            return body


async def graph_get(token: str, path: str) -> dict:
    async with aiohttp.ClientSession() as session:
        async with session.get(
            f"{GRAPH_BASE}{path}",
            headers={"Authorization": f"Bearer {token}"},
        ) as resp:
            body = await resp.json()
            if resp.status not in range(200, 300):
                raise RuntimeError(f"Graph HTTP {resp.status}: {body}")
            return body


async def get_todo_list_id(token: str) -> Optional[str]:
    """Find the default 'Tasks' list ID in Microsoft To-Do."""
    try:
        data = await graph_get(token, "/me/todo/lists")
        lists = data.get("value", [])
        for lst in lists:
            if lst.get("isOwner") and lst.get("displayName") == "Tasks":
                return lst["id"]
        return lists[0]["id"] if lists else None
    except Exception as e:
        log.warning(f"get_todo_list_id failed: {e}")
        return None

# ── MCP server ────────────────────────────────────────────────────────────────

mcp = FastMCP("cvp-tools", version="1.0.0")


@mcp.tool()
async def create_note(
    title: str,
    body: str,
    graph_token: Optional[str] = None,
) -> dict:
    """
    Create a note. Saves to Microsoft OneNote if graph_token is provided,
    otherwise saves to local SQLite (demo mode).

    Args:
        title: Note title (max 60 chars)
        body: Note content (plain text or markdown)
        graph_token: Delegated Microsoft Graph access token (optional)

    Returns:
        {note_id, url?, storage}
    """
    if graph_token:
        try:
            html = (
                f"<!DOCTYPE html><html><head><title>{title}</title></head>"
                f"<body><h1>{title}</h1><p>{body.replace(chr(10), '<br/>')}</p>"
                f"<p><em>Created by CVP — Copilot Vision Platform</em></p></body></html>"
            )
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"{GRAPH_BASE}/me/onenote/pages",
                    headers={
                        "Authorization": f"Bearer {graph_token}",
                        "Content-Type": "text/html",
                    },
                    data=html.encode(),
                ) as resp:
                    data = await resp.json()
                    if resp.status in range(200, 300):
                        log.info(f"OneNote page created: {title}")
                        return {
                            "note_id": data.get("id", str(uuid.uuid4())),
                            "url": data.get("links", {}).get("oneNoteWebUrl", {}).get("href"),
                            "storage": "onenote",
                        }
                    else:
                        log.warning(f"OneNote failed {resp.status}, falling back to SQLite")
        except Exception as e:
            log.warning(f"OneNote error: {e}, falling back to SQLite")

    # SQLite fallback
    note_id = str(uuid.uuid4())
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(
            "INSERT INTO notes (id, title, body) VALUES (?, ?, ?)",
            (note_id, title, body),
        )
        await db.commit()
    log.info(f"Note saved to SQLite: {note_id}")
    return {"note_id": note_id, "url": None, "storage": "sqlite"}


@mcp.tool()
async def create_task(
    title: str,
    notes: str,
    due_date: Optional[str] = None,
    graph_token: Optional[str] = None,
) -> dict:
    """
    Create a task. Saves to Microsoft To-Do if graph_token is provided,
    otherwise saves to local SQLite (demo mode).

    Args:
        title: Task title
        notes: Task description or body
        due_date: Optional due date in ISO format (YYYY-MM-DD)
        graph_token: Delegated Microsoft Graph access token (optional)

    Returns:
        {task_id, storage}
    """
    if graph_token:
        try:
            list_id = await get_todo_list_id(graph_token)
            if list_id:
                payload: dict = {
                    "title": title,
                    "importance": "high",
                    "body": {"contentType": "text", "content": notes},
                }
                if due_date:
                    payload["dueDateTime"] = {
                        "dateTime": f"{due_date}T00:00:00",
                        "timeZone": "UTC",
                    }
                data = await graph_post(graph_token, f"/me/todo/lists/{list_id}/tasks", payload)
                log.info(f"To-Do task created: {title}")
                return {"task_id": data.get("id", str(uuid.uuid4())), "storage": "todo"}
            else:
                log.warning("No To-Do list found, falling back to SQLite")
        except Exception as e:
            log.warning(f"To-Do error: {e}, falling back to SQLite")

    # SQLite fallback
    task_id = str(uuid.uuid4())
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(
            "INSERT INTO tasks (id, title, due_date, notes) VALUES (?, ?, ?, ?)",
            (task_id, title, due_date, notes),
        )
        await db.commit()
    log.info(f"Task saved to SQLite: {task_id}")
    return {"task_id": task_id, "storage": "sqlite"}


@mcp.tool()
async def draft_message(
    channel: str,
    body: str,
    graph_token: Optional[str] = None,
    teams_chat_id: Optional[str] = None,
) -> dict:
    """
    Send or draft a Teams message. Sends via Microsoft Teams if graph_token
    and teams_chat_id are provided, otherwise saves to local SQLite.

    Args:
        channel: Logical channel name (e.g. 'team-updates', 'general')
        body: Message content
        graph_token: Delegated Microsoft Graph access token (optional)
        teams_chat_id: Teams chat ID (19:xxx@thread.v2) (optional)

    Returns:
        {draft_id, storage}
    """
    if graph_token and teams_chat_id:
        try:
            payload = {"body": {"content": body}}
            data = await graph_post(
                graph_token,
                f"/chats/{teams_chat_id}/messages",
                payload,
            )
            log.info(f"Teams message sent to {teams_chat_id}")
            return {"draft_id": data.get("id", str(uuid.uuid4())), "storage": "teams"}
        except Exception as e:
            log.warning(f"Teams error: {e}, falling back to SQLite")

    # SQLite fallback
    draft_id = str(uuid.uuid4())
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(
            "INSERT INTO drafts (id, channel, body) VALUES (?, ?, ?)",
            (draft_id, channel, body),
        )
        await db.commit()
    log.info(f"Draft saved to SQLite: {draft_id}")
    return {"draft_id": draft_id, "storage": "sqlite"}


@mcp.tool()
async def get_status() -> dict:
    """
    Return MCP server health and capability info.
    Useful for Foundry agent probing / governance validation.
    """
    return {
        "version": "1.1.0",
        "server": "cvp-mcp-server",
        "tools": ["create_note", "create_task", "draft_message", "get_status",
                  "save_skill", "get_skills", "delete_skill"],
        "storage_mode": "m365+sqlite_fallback",
        "transport": "streamable-http",
        "privacy": "no_raw_media_stored",
    }


# ── Voice-taught skills ───────────────────────────────────────────────────────

@mcp.tool()
async def save_skill(
    name: str,
    instructions: str,
    description: Optional[str] = None,
    trigger_phrases: Optional[str] = None,
) -> dict:
    """
    Save a learned behavior/skill for future sessions. Call when the user asks
    you to 'remember', 'learn', or 'always do' something. Skills are injected
    into every future session so you apply them automatically.

    Args:
        name: Short unique identifier (snake_case, e.g. 'expense_tagging')
        instructions: What to do — be specific about triggers and actions
        description: Human-readable one-line summary (optional)
        trigger_phrases: Comma-separated phrases that activate this skill (optional)
    """
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(
            """INSERT INTO skills (name, description, instructions, trigger_phrases)
               VALUES (?, ?, ?, ?)
               ON CONFLICT(name) DO UPDATE SET
                 instructions=excluded.instructions,
                 description=excluded.description,
                 trigger_phrases=excluded.trigger_phrases""",
            (name, description, instructions, trigger_phrases),
        )
        await db.commit()
    log.info(f"Skill saved: {name}")
    return {"skill_name": name, "status": "saved"}


@mcp.tool()
async def get_skills() -> dict:
    """Return all saved learned skills."""
    async with aiosqlite.connect(DB_PATH) as db:
        async with db.execute(
            "SELECT name, description, instructions, trigger_phrases FROM skills ORDER BY name"
        ) as cur:
            rows = await cur.fetchall()
    skills = [
        {"name": r[0], "description": r[1], "instructions": r[2], "trigger_phrases": r[3]}
        for r in rows
    ]
    return {"skills": skills, "count": len(skills)}


@mcp.tool()
async def delete_skill(name: str) -> dict:
    """Delete a saved skill by name."""
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute("DELETE FROM skills WHERE name = ?", (name,))
        await db.commit()
    log.info(f"Skill deleted: {name}")
    return {"skill_name": name, "status": "deleted"}


# ── Remote MCP proxy ──────────────────────────────────────────────────────────

def _register_proxy_tool(mcp_instance: FastMCP, label: str, url: str,
                          headers: dict, tool_name: str, tool_schema: dict):
    """Register a single proxied remote MCP tool on the local FastMCP server."""
    description = tool_schema.get("description", f"Proxied from {label}")
    original_name = tool_schema["name"]

    async def proxy_fn(**kwargs):
        payload = {
            "jsonrpc": "2.0", "id": 1,
            "method": "tools/call",
            "params": {"name": original_name, "arguments": kwargs},
        }
        async with aiohttp.ClientSession() as session:
            async with session.post(url, json=payload, headers=headers) as resp:
                data = await resp.json()
        return data.get("result") or data

    proxy_fn.__name__ = tool_name
    proxy_fn.__doc__ = description
    mcp_instance.tool(name=tool_name)(proxy_fn)


async def register_remote_mcp(label: str, url: str, headers: dict):
    """Discover tools from a Remote MCP server and register them as local proxies."""
    payload = {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}
    try:
        async with aiohttp.ClientSession() as session:
            async with session.post(url, json=payload, headers=headers,
                                    timeout=aiohttp.ClientTimeout(total=10)) as resp:
                data = await resp.json()
        tools = data.get("result", {}).get("tools", [])
        for tool in tools:
            tool_name = f"{label}__{tool['name']}"
            _register_proxy_tool(mcp, label, url, headers, tool_name, tool)
        log.info(f"Remote MCP '{label}': registered {len(tools)} tools from {url}")
    except Exception as e:
        log.warning(f"Remote MCP '{label}' discovery failed: {e}")


# ── FastAPI app ───────────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    for server_cfg in REMOTE_MCP_SERVERS:
        label = server_cfg.get("label", "remote")
        url = server_cfg.get("url", "")
        headers = server_cfg.get("headers", {})
        if url:
            await register_remote_mcp(label, url, headers)
    log.info(f"CVP MCP Server ready. DB: {DB_PATH}")
    yield


app = FastAPI(title="CVP MCP Server", lifespan=lifespan)


# ── Bearer token middleware ───────────────────────────────────────────────────

@app.middleware("http")
async def auth_middleware(request: Request, call_next):
    # Allow health checks unauthenticated
    if request.url.path == "/health":
        return await call_next(request)

    if MCP_BEARER_TOKEN:
        auth = request.headers.get("Authorization", "")
        if not auth.startswith("Bearer ") or auth[7:] != MCP_BEARER_TOKEN:
            return JSONResponse(
                status_code=401,
                content={"error": "Invalid or missing Bearer token"},
            )
    return await call_next(request)


@app.get("/health")
async def health():
    return {"status": "ok", "service": "cvp-mcp-server"}


# Mount the MCP Streamable HTTP transport at /mcp
mcp_asgi = mcp.streamable_http_app()
app.mount("/mcp", mcp_asgi)
