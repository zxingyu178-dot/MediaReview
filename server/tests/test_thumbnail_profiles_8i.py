"""Stage8I 固定缩略图档位的认证 HTTP 与真实上游参数合同。"""

import asyncio

import httpx
import pytest
from test_media_image_proxy_11 import secure_image_client  # noqa: F401

from app.adapters.jellyfin.client import JellyfinClient
from app.core.config import JellyfinConfig
from app.db import models


@pytest.mark.parametrize(
    "profile,expected",
    [
        ("GRID_SMALL", (256, 144, 60)),
        ("GRID_LARGE", (480, 270, 65)),
        ("ALBUM", (320, 427, 65)),
    ],
)
def test_profile_contract(secure_image_client, profile, expected):  # noqa: F811
    client, token, requests = secure_image_client
    auth = {"Authorization": f"Bearer {token}"}
    url = f"/api/v1/media/img-ok/thumbnail?profile={profile}"
    assert client.get(url).status_code == 401
    response = client.get(url, headers=auth)
    assert response.status_code == 200
    assert dict(requests[-1].url.params) == {
        "fillWidth": str(expected[0]),
        "fillHeight": str(expected[1]),
        "quality": str(expected[2]),
    }
    count = len(requests)
    assert client.get(url, headers=auth).headers["X-MediaReview-Cache"] == "HIT"
    assert (
        client.get(url, headers={**auth, "If-None-Match": response.headers["ETag"]}).status_code
        == 304
    )
    assert len(requests) == count


def test_default_bounded_variants_and_invalidation(secure_image_client):  # noqa: F811
    client, token, requests = secure_image_client
    auth = {"Authorization": f"Bearer {token}"}
    url = "/api/v1/media/img-ok/thumbnail"
    default = client.get(url, headers=auth)
    assert requests[-1].url.params["quality"] == "65"
    count = len(requests)
    large = client.get(url + "?profile=GRID_LARGE", headers=auth)
    assert large.headers["ETag"] == default.headers["ETag"]
    arbitrary = client.get(url + "?width=999&height=999&quality=1", headers=auth)
    assert arbitrary.headers["ETag"] == default.headers["ETag"]
    assert len(requests) == count
    assert client.get(url + "?profile=UNKNOWN", headers=auth).status_code == 422
    assert len(requests) == count
    small = client.get(
        url + "?profile=GRID_SMALL", headers={**auth, "If-None-Match": large.headers["ETag"]}
    )
    assert small.status_code == 200
    assert small.headers["ETag"] != large.headers["ETag"]
    assert client.get(url, headers=auth).headers["X-MediaReview-Cache"] == "HIT"
    with client.app.state.database.session() as session:
        row = session.get(models.MediaCacheIndex, "img-ok")
        row.size_bytes = 999
        session.commit()
    changed = client.get(url, headers={**auth, "If-None-Match": large.headers["ETag"]})
    assert changed.status_code == 200
    assert changed.headers["ETag"] != large.headers["ETag"]


def test_jellyfin_old_call_keeps_quality_80():
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(200, content=b"jpeg", headers={"Content-Type": "image/jpeg"})

    async def run():
        async with JellyfinClient(
            JellyfinConfig(), transport=httpx.MockTransport(handler)
        ) as client:
            await client.thumbnail_image("item")

    asyncio.run(run())
    assert dict(requests[0].url.params) == {"maxWidth": "480", "quality": "80"}
