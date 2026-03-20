"""
CVP Vision Gateway — FastAPI
Receives JPEG frames from the Android app, runs Azure Vision OCR,
returns a structured VisionSignal. Raw media is never stored.
"""

import io
import logging
import os
import time
from typing import Optional

from azure.ai.vision.imageanalysis import ImageAnalysisClient
from azure.ai.vision.imageanalysis.models import VisualFeatures
from azure.core.credentials import AzureKeyCredential
from fastapi import FastAPI, HTTPException, Request, Header
from fastapi.responses import JSONResponse
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("cvp-gateway")

app = FastAPI(title="CVP Vision Gateway", version="1.0.0")

# ── Azure Vision client ───────────────────────────────────────────────────────

VISION_ENDPOINT = os.environ["AZURE_VISION_ENDPOINT"]   # https://CVP-demo.cognitiveservices.azure.com/
VISION_KEY      = os.environ["AZURE_VISION_KEY"]

_vision_client: Optional[ImageAnalysisClient] = None

def get_vision_client() -> ImageAnalysisClient:
    global _vision_client
    if _vision_client is None:
        _vision_client = ImageAnalysisClient(
            endpoint=VISION_ENDPOINT,
            credential=AzureKeyCredential(VISION_KEY),
        )
    return _vision_client


# ── Models ───────────────────────────────────────────────────────────────────

class VisionSignal(BaseModel):
    ocrText: Optional[str] = None
    objects: list[str] = []
    uiHint: Optional[str] = None
    rawMediaTransmitted: bool = False   # always False — we discard the frame after analysis


# ── UI hint inference from OCR text ──────────────────────────────────────────

_UI_HINTS = {
    "Outlook": ["outlook", "inbox", "from:", "subject:", "reply", "forward"],
    "Teams": ["teams", "channel", "chat", "meeting", "@ mention"],
    "Terminal": ["$", "git ", "npm ", "python ", "kubectl", "bash", "zsh", ">>"],
    "Sentinel": ["kql", "securityevent", "signinslog", "incident", "alert", "soc"],
    "Browser": ["http://", "https://", "www.", ".com", ".io", ".gov"],
    "Planner": ["task", "due date", "assigned to", "bucket", "sprint"],
}

def infer_ui_hint(text: str) -> Optional[str]:
    lower = text.lower()
    for hint, keywords in _UI_HINTS.items():
        if any(kw in lower for kw in keywords):
            return hint
    return None


# ── Routes ───────────────────────────────────────────────────────────────────

@app.get("/health")
async def health():
    return {"status": "ok", "service": "cvp-vision-gateway"}


@app.post("/v1/vision/analyze", response_model=VisionSignal)
async def analyze(
    request: Request,
    x_cvp_mode: str = Header(default="PRIVATE"),
):
    """
    Accept a raw JPEG body, run Azure Vision OCR + object tags,
    return structured VisionSignal. Frame is discarded immediately after analysis.

    Header x-cvp-mode: PRIVATE | EXEC | BUILDER  (CUI should never reach here)
    """
    if x_cvp_mode.upper() == "CUI":
        raise HTTPException(
            status_code=403,
            detail="CUI mode: raw media must not leave device. Use on-device OCR."
        )

    body = await request.body()
    if not body:
        raise HTTPException(status_code=400, detail="Empty body — expected JPEG bytes")
    if len(body) > 2 * 1024 * 1024:   # 2 MB guard
        raise HTTPException(status_code=413, detail="Frame too large (max 2 MB)")

    t0 = time.monotonic()
    try:
        client = get_vision_client()
        result = client.analyze(
            image_data=body,
            visual_features=[VisualFeatures.READ, VisualFeatures.TAGS],
        )
    except Exception as exc:
        logger.error("Azure Vision call failed: %s", exc)
        raise HTTPException(status_code=502, detail="Azure Vision error") from exc
    finally:
        # Explicit del — do not retain frame bytes in any variable after this
        del body

    elapsed_ms = int((time.monotonic() - t0) * 1000)

    # Extract OCR text
    ocr_lines: list[str] = []
    if result.read and result.read.blocks:
        for block in result.read.blocks:
            for line in block.lines:
                ocr_lines.append(line.text)
    ocr_text = "\n".join(ocr_lines) if ocr_lines else None

    # Extract object/scene tags (confidence > 0.6)
    objects: list[str] = []
    if result.tags and result.tags.values:
        objects = [t.name for t in result.tags.values if t.confidence > 0.6]

    ui_hint = infer_ui_hint(ocr_text) if ocr_text else None

    logger.info(
        "analyze: mode=%s ocr_chars=%d tags=%d ui_hint=%s elapsed_ms=%d",
        x_cvp_mode, len(ocr_text or ""), len(objects), ui_hint, elapsed_ms,
    )

    return VisionSignal(
        ocrText=ocr_text,
        objects=objects,
        uiHint=ui_hint,
        rawMediaTransmitted=False,
    )


@app.post("/v1/vision/document", response_model=VisionSignal)
async def analyze_document(request: Request):
    """
    Document Intelligence layout extraction for whiteboard photos / PDFs.
    Returns structured text (headings, tables as markdown).
    Requires CVPOCR Document Intelligence resource.
    """
    from azure.ai.documentintelligence import DocumentIntelligenceClient
    from azure.ai.documentintelligence.models import AnalyzeDocumentRequest

    DI_ENDPOINT = os.environ.get("AZURE_DI_ENDPOINT")
    DI_KEY       = os.environ.get("AZURE_DI_KEY")
    if not DI_ENDPOINT or not DI_KEY:
        raise HTTPException(status_code=501, detail="Document Intelligence not configured")

    body = await request.body()
    if not body:
        raise HTTPException(status_code=400, detail="Empty body")

    try:
        di_client = DocumentIntelligenceClient(
            endpoint=DI_ENDPOINT,
            credential=AzureKeyCredential(DI_KEY),
        )
        poller = di_client.begin_analyze_document(
            model_id="prebuilt-layout",
            body=AnalyzeDocumentRequest(bytes_source=body),
        )
        result = poller.result()
    except Exception as exc:
        logger.error("Document Intelligence call failed: %s", exc)
        raise HTTPException(status_code=502, detail="Document Intelligence error") from exc
    finally:
        del body

    paragraphs = [p.content for p in (result.paragraphs or [])]
    ocr_text = "\n".join(paragraphs) if paragraphs else None
    ui_hint = infer_ui_hint(ocr_text) if ocr_text else None

    return VisionSignal(
        ocrText=ocr_text,
        objects=[],
        uiHint=ui_hint,
        rawMediaTransmitted=False,
    )
