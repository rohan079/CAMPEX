import io
import json

import httpx
import pytest

from config import Settings
from services.meta_ad_builder import MetaAdBuilder
from services.meta_ads_mcp import MetaAdsToolError


def builder(handler):
    return MetaAdBuilder(Settings(meta_graph_api_version="v27.0"), httpx.MockTransport(handler))


@pytest.mark.asyncio
async def test_video_ad_is_built_from_uploaded_video_thumbnail_and_created_paused():
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        path = request.url.path
        if path.endswith("/advideos"):
            return httpx.Response(200, json={"id": "777"})
        if path.endswith("/adimages"):
            return httpx.Response(200, json={"images": {"thumb": {"hash": "h123"}}})
        if path.endswith("/adcreatives"):
            return httpx.Response(200, json={"id": "888"})
        if path.endswith("/ads"):
            return httpx.Response(200, json={"id": "999"})
        if path.endswith("/777"):
            return httpx.Response(200, json={"status": {"video_status": "ready"}})
        return httpx.Response(404, json={"error": {"code": 100}})

    b = builder(handler)
    video_id = await b.upload_video("tok", "act_5", "Villa tour", io.BytesIO(b"\x00\x00\x00\x18ftypmp42"), 12, "video/mp4")
    assert video_id == "777"
    assert calls[0].url.host == "graph-video.facebook.com"
    assert calls[0].headers["authorization"] == "Bearer tok" and "tok" not in str(calls[0].url)
    assert (await b.video_status("tok", "777"))["video_status"] == "ready"
    image_hash = await b.upload_image("tok", "act_5", "thumb", b"\x89PNG....")
    creative = await b.create_creative("tok", ad_account_id="act_5", page_id="42", kind="video", name="Villa [cx-1]",
                                       link_url="https://greenfields.example/villas", message="Visit this weekend.",
                                       headline="Villas near Guntur", description=None, cta="LEARN_MORE",
                                       image_hash=image_hash, video_id=video_id)
    assert creative == "888"
    spec = json.loads(dict(httpx.QueryParams(calls[3].content.decode()))["object_story_spec"])
    assert spec == {"page_id": "42", "video_data": {"video_id": "777", "image_hash": "h123", "message": "Visit this weekend.",
                    "title": "Villas near Guntur", "call_to_action": {"type": "LEARN_MORE", "value": {"link": "https://greenfields.example/villas"}}}}
    assert await b.create_ad("tok", "act_5", "55", "888", "Villa [cx-1]") == "999"
    assert dict(httpx.QueryParams(calls[4].content.decode()))["status"] == "PAUSED"


@pytest.mark.asyncio
async def test_creative_rules_are_enforced_before_calling_meta():
    b = builder(lambda r: httpx.Response(500))
    with pytest.raises(MetaAdsToolError, match="https"):
        await b.create_creative("t", ad_account_id="act_5", page_id="1", kind="image", name="n", link_url="http://x.com",
                                message="m", headline="h", description=None, cta="LEARN_MORE", image_hash="h")
    with pytest.raises(MetaAdsToolError, match="call to action"):
        await b.create_creative("t", ad_account_id="act_5", page_id="1", kind="image", name="n", link_url="https://x.com",
                                message="m", headline="h", description=None, cta="BUY_NOW", image_hash="h")
    with pytest.raises(MetaAdsToolError, match="video ID"):
        await b.create_creative("t", ad_account_id="act_5", page_id="1", kind="video", name="n", link_url="https://x.com",
                                message="m", headline="h", description=None, cta="LEARN_MORE", image_hash="h")


@pytest.mark.asyncio
async def test_meta_errors_are_reported_without_tokens():
    b = builder(lambda r: httpx.Response(400, json={"error": {"code": 352, "message": "Video format not supported, token=abc"}}))
    with pytest.raises(MetaAdsToolError) as err:
        await b.upload_video("secret", "act_5", "v", io.BytesIO(b"x"), 1, "video/mp4")
    assert "secret" not in str(err.value) and "token=abc" not in str(err.value)
