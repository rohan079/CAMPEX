"""Performance intelligence endpoints, called only by the lead service (X-Service-Key)."""

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from dependencies import require_service_key
from services.llm_providers import ProviderUnavailable, adapter_for
from services.performance_intelligence import PerformanceIntelligenceService

router = APIRouter(prefix="/api/performance", tags=["Performance AI"], dependencies=[Depends(require_service_key)])


class AskBody(BaseModel):
    question: str = Field(min_length=1, max_length=1000)
    context: dict[str, Any]


class ExplainBody(BaseModel):
    task: str = Field(pattern="^(recommendations|anomaly)$")
    context: dict[str, Any]
    items: list[dict[str, Any]] = Field(max_length=50)


class CreativeBody(BaseModel):
    observation: str = Field(max_length=4000)
    creative: dict[str, Any]
    metrics: dict[str, Any]
    audience_notes: str | None = Field(default=None, max_length=500)


def _service(request: Request) -> PerformanceIntelligenceService:
    try:
        adapter = adapter_for(request.app.state.settings)
    except ProviderUnavailable as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc)) from exc
    return PerformanceIntelligenceService(adapter)


@router.post("/ask")
async def ask(body: AskBody, request: Request) -> dict[str, Any]:
    try:
        return await _service(request).ask(body.question, body.context)
    except ProviderUnavailable as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc)) from exc


@router.post("/explain")
async def explain(body: ExplainBody, request: Request) -> dict[str, Any]:
    try:
        return await _service(request).explain(body.task, body.context, body.items)
    except ProviderUnavailable as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc)) from exc


@router.post("/creative-suggestions")
async def creative_suggestions(body: CreativeBody, request: Request) -> dict[str, Any]:
    try:
        return await _service(request).creative_suggestions(body.model_dump())
    except ProviderUnavailable as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc)) from exc
