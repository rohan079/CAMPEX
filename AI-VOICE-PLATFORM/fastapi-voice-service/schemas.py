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


class AssistImage(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    mime_type: Literal["image/jpeg", "image/png", "image/webp"]
    data_base64: str = Field(min_length=32, max_length=3_500_000)


class CampaignAssistRequest(BaseModel):
    """Copy / design inputs sent from Spring's dashboard-authenticated AI assist endpoint."""

    campaign_name: str = Field(min_length=1, max_length=160)
    objective: Literal["leads", "traffic", "sales", "awareness"]
    platforms: list[CampaignPlatform] = Field(min_length=1, max_length=5)
    language: Literal["en", "te"] = "en"
    prompt: str = Field(min_length=1, max_length=2000)
    content: str = Field(default="", max_length=5000)  # empty allowed for image-led design prompts
    ad_account_id: str | None = Field(default=None, max_length=64)
    client_id: str | None = Field(default=None, max_length=64)
    client_name: str | None = Field(default=None, max_length=160)
    skill_id: str | None = Field(default=None, max_length=120)
    images: list[AssistImage] = Field(default_factory=list, max_length=3)
    mode: Literal["assist", "design"] = "assist"
    generate_image: bool = False


class GeneratedAssistImage(BaseModel):
    mime_type: Literal["image/png", "image/jpeg", "image/webp"] = "image/png"
    data_base64: str
    prompt: str | None = None


class CampaignAssistResponse(BaseModel):
    reply: str
    provider: str
    model: str
    suggested_copy: str | None = None
    suggested_headline: str | None = None
    suggested_cta: str | None = None
    design_notes: str | None = None
    skill_id: str | None = None
    tools_used: list[str] = Field(default_factory=list)
    generated_images: list[GeneratedAssistImage] = Field(default_factory=list)
    image_error: str | None = None


class SkillSummary(BaseModel):
    id: str
    name: str
    description: str
    source: Literal["bundled", "uploaded"]
    featured: bool = False


class SkillUploadRequest(BaseModel):
    markdown: str = Field(min_length=1, max_length=80_000)
    name: str | None = Field(default=None, max_length=120)
    description: str | None = Field(default=None, max_length=400)


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


class MetaCampaignLookupRequest(BaseModel):
    """Find campaigns Campex created, by the publish tag embedded in the campaign name."""

    ad_account_id: str = Field(min_length=1, max_length=64)
    name_contains: str = Field(min_length=4, max_length=100)


class MetaCampaignStatusRequest(BaseModel):
    ad_account_id: str = Field(min_length=1, max_length=64)
    campaign_id: str = Field(min_length=1, max_length=64, pattern=r"^[0-9]+$")


class ProviderEvent(BaseModel):
    """A status callback from a SIP gateway or provider, in the same vocabulary as OpenAI's sideband events."""

    model_config = ConfigDict(extra="allow")

    session_id: str = Field(min_length=1, max_length=128)
    type: str = Field(min_length=1, max_length=80)
