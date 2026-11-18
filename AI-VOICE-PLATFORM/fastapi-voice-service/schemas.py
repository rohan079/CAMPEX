from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

CallEventType = Literal["ringing", "answered", "failed", "closed", "not_interested"]
CampaignPlatform = Literal["facebook", "instagram", "whatsapp_business", "google_ads", "whatsapp_ads"]


class StartCallRequest(BaseModel):
    """What the lead service asks for. It has already checked consent and the follow-up schedule."""

    call_id: str = Field(min_length=1, max_length=64)
    lead_id: str | None = Field(default=None, max_length=64)
    destination: str = Field(pattern=r"^\+[1-9]\d{7,14}$")
    language: Literal["en", "te"] = "en"
    first_name: str | None = Field(default=None, max_length=120)
    business_name: str = Field(default="our business", min_length=1, max_length=120)
    business_description: str | None = Field(default=None, max_length=2000)
    ad_headline: str | None = Field(default=None, max_length=300)
    ad_body: str | None = Field(default=None, max_length=2000)
    follow_up: bool = False
    knowledge_base: str = Field(default="", max_length=12000)


class StartCallResponse(BaseModel):
    session_id: str
    status: Literal["initiated"] = "initiated"


class CallEvent(BaseModel):
    """What this service reports back to the lead service."""

    event_id: str
    call_id: str
    session_id: str | None = None
    type: CallEventType
    detail: str | None = None


class CallSummary(BaseModel):
    """A concise, locally generated summary sent to Spring's internal call history."""

    call_id: str = Field(min_length=1, max_length=64)
    summary: str = Field(min_length=1, max_length=10000)


class CampaignAssistRequest(BaseModel):
    """Copy inputs sent from Spring's dashboard-authenticated AI assist endpoint."""

    campaign_name: str = Field(min_length=1, max_length=160)
    objective: Literal["leads", "traffic", "sales", "awareness"]
    platforms: list[CampaignPlatform] = Field(min_length=1, max_length=5)
    language: Literal["en", "te"] = "en"
    prompt: str = Field(min_length=1, max_length=2000)
    content: str = Field(min_length=1, max_length=5000)
    ad_account_id: str | None = Field(default=None, max_length=64)


class CampaignAssistResponse(BaseModel):
    reply: str
    provider: str
    model: str
    suggested_copy: str | None = None
    tools_used: list[str] = Field(default_factory=list)


class MetaCampaignPublishRequest(BaseModel):
    campaign_name: str = Field(min_length=1, max_length=160)
    objective: Literal["leads", "traffic", "sales", "awareness"]
    platforms: list[Literal["facebook", "instagram", "whatsapp_ads"]] = Field(min_length=1, max_length=3)
    location: str = Field(min_length=1, max_length=160)
    age_min: int = Field(ge=18, le=65)
    age_max: int = Field(ge=18, le=65)
    headline: str = Field(min_length=1, max_length=80)
    body: str = Field(min_length=1, max_length=1500)
    cta: str = Field(min_length=1, max_length=40)
    ad_account_id: str = Field(min_length=1, max_length=64)
    page_id: str | None = Field(default=None, max_length=64)
    daily_budget_minor: int | None = Field(default=None, ge=100)
    confirmed: bool


class ProviderEvent(BaseModel):
    """A status callback from a SIP gateway or provider, in the same vocabulary as OpenAI's sideband events."""

    model_config = ConfigDict(extra="allow")

    session_id: str = Field(min_length=1, max_length=128)
    type: str = Field(min_length=1, max_length=80)
