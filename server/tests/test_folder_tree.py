"""Stage8H: isolated SQLite and a router-only app, never production lifespan."""

import json
from datetime import datetime
from types import SimpleNamespace

import pytest
import sqlalchemy as sa
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.v1.media import router
from app.core.errors import AppError
from app.core.responses import error_response
from app.db.models import Favorite, LibrarySelection, MediaCacheIndex
from app.db.session import Database


@pytest.fixture
def tree_api(tmp_path):
    database = Database(tmp_path / "tree.sqlite")
    database.create_all()
    app = FastAPI()

    @app.exception_handler(AppError)
    async def app_error_handler(request, exc):
        return error_response(exc)

    app.state.database = database
    app.state.settings = SimpleNamespace(
        jellyfin=SimpleNamespace(user_id="fixture-user"),
        security=SimpleNamespace(pairing_required=False),
    )
    app.include_router(router, prefix="/api/v1")
    with database.session() as db:
        db.add(LibrarySelection(jellyfin_id="lib", name="媒体", selected=True))
        db.commit()
    with TestClient(app) as client:
        yield client, database
    database.dispose()


def seed(database, paths, library="lib", kind="image"):
    with database.session() as db:
        for i, path in enumerate(paths):
            key = f"{library}-{i}"
            db.add(
                MediaCacheIndex(
                    media_id=key,
                    jellyfin_id=key,
                    library_id=library,
                    name=key,
                    media_type=kind,
                    media_path=path,
                    fingerprint=f"fp-{i}",
                )
            )
        db.commit()


def nodes(client, **params):
    response = client.get("/api/v1/media/folder-tree", params=params)
    assert response.status_code == 200, response.text
    return response.json()["data"]


@pytest.mark.parametrize("prefix", ["D:/Media/", "C:\\Media\\", "/mnt/media/", "\\\\host\\share\\"])
def test_hierarchy_privacy_and_ancestor_statistics(tree_api, prefix):
    client, database = tree_api
    seed(
        database,
        [
            prefix + "Movies/Action/a.jpg",
            prefix + "Movies/SciFi/b.jpg",
            prefix + "Movies/direct.jpg",
            prefix + "Photos/2025/a.jpg",
            prefix + "Photos/2026/b.jpg",
        ],
    )
    result = nodes(client)
    by_name = {n["name"]: n for n in result}
    root = next(n for n in result if n["parent_id"] is None)
    assert root["recursive_media_count"] == 5
    assert by_name["Action"]["parent_id"] == by_name["Movies"]["folder_id"]
    assert by_name["Movies"]["child_count"] == 2
    assert by_name["Movies"]["direct_media_count"] == 1
    assert by_name["Movies"]["recursive_media_count"] == 3
    assert by_name["Movies"]["direct_image_count"] == 1
    assert by_name["Movies"]["direct_cover_url"].startswith("/api/v1/media/")
    assert by_name["2025"]["parent_id"] == by_name["Photos"]["folder_id"]
    encoded = json.dumps(result)
    for secret in ("D:", "C:", "/mnt/", "host", "share", "media_path"):
        assert secret not in encoded


def test_common_prefix_retains_visible_branch_and_recursive_paging(tree_api):
    client, database = tree_api
    seed(database, ["D:/Media/Movies/欧美/动作/a.jpg", "D:/Media/Movies/欧美/科幻/b.jpg"])
    result = nodes(client)
    by_name = {n["name"]: n for n in result}
    assert set(by_name) == {"媒体", "欧美", "动作", "科幻"}
    assert by_name["欧美"]["direct_media_count"] == 0
    assert by_name["欧美"]["cover_url"]
    assert by_name["欧美"]["direct_cover_url"] is None
    for name, count in (("欧美", 2), ("动作", 1), ("媒体", 2)):
        response = client.get(
            "/api/v1/media", params={"folder_id": by_name[name]["folder_id"], "page_size": 1}
        )
        assert response.status_code == 200, response.text
        assert response.json()["data"]["total"] == count
        assert len(response.json()["data"]["items"]) == 1
    old = client.get("/api/v1/media/folders").json()["data"]
    assert len(old) == 2
    response = client.get("/api/v1/media", params={"folder_id": old[0]["folder_id"]})
    assert response.json()["data"]["total"] == 1


def test_multiple_libraries_no_images_and_pathless(tree_api):
    client, database = tree_api
    with database.session() as db:
        db.add(LibrarySelection(jellyfin_id="other", name="视频", selected=True))
        db.commit()
    seed(database, ["/data/Photos/a.jpg"])
    seed(database, ["/data/Photos/a.mp4", None], library="other", kind="video")
    result = nodes(client)
    roots = [n for n in result if n["parent_id"] is None]
    assert len(roots) == 2
    video = next(n for n in roots if n["library_id"] == "other")
    assert video["recursive_media_count"] == 2
    assert video["direct_media_count"] == 1
    assert video["cover_url"] is None
    assert len({n["folder_id"] for n in result}) == len(result)


def test_cache_hit_mutation_rollback_selection_and_database_isolation(tree_api, monkeypatch):
    from app.services import folder_tree

    client, database = tree_api
    seed(database, ["/data/A/a.jpg"])
    first = nodes(client)
    original = folder_tree._build_tree
    calls = []

    def counted(*args, **kwargs):
        calls.append(1)
        return original(*args, **kwargs)

    monkeypatch.setattr(folder_tree, "_build_tree", counted)
    assert nodes(client) == first
    assert not calls
    with database.session() as db:
        db.execute(sa.update(MediaCacheIndex).values(is_available=False))
        db.commit()
    assert nodes(client)[0]["recursive_media_count"] == 0
    assert len(calls) == 1
    with database.session() as db:
        db.execute(sa.update(MediaCacheIndex).values(is_available=True))
        db.rollback()
    assert nodes(client)[0]["recursive_media_count"] == 0
    with database.session() as db:
        db.execute(sa.update(LibrarySelection).values(name="重命名"))
        db.commit()
    assert nodes(client)[0]["name"] == "重命名"


def test_large_metadata_tree_and_bounded_media_page(tree_api):
    client, database = tree_api
    seed(
        database,
        [
            f"/data/Media/group{i // 10}/level2/level3/folder{i}/image{j}.jpg"
            for i in range(100)
            for j in range(10)
        ],
    )
    result = nodes(client)
    root = next(n for n in result if n["parent_id"] is None)
    assert root["recursive_media_count"] == 1000
    assert all("items" not in n and "media_path" not in n for n in result)
    # Android albums()由直接含图片的旧folders元数据映射，相同fixture须提供100本相册。
    folders = client.get("/api/v1/media/folders")
    assert folders.status_code == 200
    albums = [folder for folder in folders.json()["data"] if folder["image_count"] > 0]
    assert len(albums) == 100
    assert all(album["image_count"] == 10 for album in albums)
    response = client.get(
        "/api/v1/media", params={"folder_id": root["folder_id"], "page_size": 13, "page": 2}
    )
    assert response.json()["data"]["total"] == 1000
    assert len(response.json()["data"]["items"]) == 13


def test_direct_scope_latest_cover_and_source_change(tree_api):
    client, database = tree_api
    seed(database, ["D:/Media/Movies/direct.jpg", "D:/Media/Movies/Child/new.jpg"])
    with database.session() as db:
        db.get(MediaCacheIndex, "lib-0").created_at = datetime(2025, 1, 1)
        db.get(MediaCacheIndex, "lib-1").created_at = datetime(2026, 1, 1)
        db.commit()
    movie = next(n for n in nodes(client) if n["name"] == "Movies")
    assert "/lib-1/" in movie["cover_url"]
    assert "/lib-0/" in movie["direct_cover_url"]
    response = client.get(
        "/api/v1/media", params={"folder_id": movie["folder_id"], "include_descendants": "false"}
    )
    assert response.json()["data"]["total"] == 1
    assert response.json()["data"]["items"][0]["media_id"] == "lib-0"
    with database.session() as db:
        db.get(MediaCacheIndex, "lib-1").size_bytes = 12345
        db.commit()
    changed = next(n for n in nodes(client) if n["name"] == "Movies")
    assert changed["folder_id"] == movie["folder_id"]
    assert changed["cover_url"] != movie["cover_url"]
    with database.session() as db:
        db.delete(db.get(MediaCacheIndex, "lib-1"))
        db.commit()
    assert next(n for n in nodes(client) if n["name"] == "Movies")["recursive_media_count"] == 1


def test_disjoint_physical_roots_do_not_merge_same_named_folders(tree_api):
    client, database = tree_api
    seed(database, ["D:/Photos/a.jpg", "E:/Photos/b.jpg"])
    leaves = [n for n in nodes(client) if n["name"] == "Photos"]
    assert len(leaves) == 2
    assert leaves[0]["folder_id"] != leaves[1]["folder_id"]
    for leaf in leaves:
        assert leaf["recursive_media_count"] == 1
        response = client.get("/api/v1/media", params={"folder_id": leaf["folder_id"]})
        assert response.json()["data"]["total"] == 1


def test_selection_upsert_and_database_cache_isolation(tree_api, tmp_path):
    from app.adapters.jellyfin.models import MediaItem
    from app.services import folder_tree, media_index

    client, database = tree_api
    seed(database, ["/data/Photos/a.jpg"])
    before = nodes(client)
    isolated = Database(tmp_path / "second.sqlite")
    isolated.create_all()
    try:
        with isolated.session() as db:
            db.add(LibrarySelection(jellyfin_id="lib", name="另一个数据库", selected=True))
            db.commit()
            tree = folder_tree.get_tree(db, ["lib"])
            assert tree.nodes[0].name == "另一个数据库"
            assert tree.nodes[0].recursive_media_count == 0
        with database.session() as db:
            media_index.upsert_media_items(
                db,
                [
                    MediaItem(
                        media_id="new",
                        jellyfin_id="new",
                        library_id="lib",
                        name="new",
                        media_type="video",
                        media_path="/data/Videos/new.mp4",
                        fingerprint="new",
                    )
                ],
                generation="next",
            )
            db.commit()
        assert nodes(client)[0]["recursive_media_count"] == 2
        with database.session() as db:
            db.add(LibrarySelection(jellyfin_id="second", name="第二库", selected=True))
            db.commit()
            media_index.apply_selection(db, ["second"], known_ids={"lib", "second"})
            db.commit()
        assert [n["library_id"] for n in nodes(client)] == ["second"]
        assert before[0]["recursive_media_count"] == 1
    finally:
        isolated.dispose()


def test_writer_uncommitted_tree_never_leaks_to_reader(tree_api):
    from app.services import folder_tree

    _, database = tree_api
    seed(database, ["/data/A/a.jpg"])
    with database.session() as reader, database.session() as writer:
        assert folder_tree.get_tree(reader, ["lib"]).nodes[0].recursive_media_count == 1
        writer.execute(sa.update(MediaCacheIndex).values(is_available=False))
        assert folder_tree.get_tree(writer, ["lib"]).nodes[0].recursive_media_count == 0
        assert folder_tree.get_tree(reader, ["lib"]).nodes[0].recursive_media_count == 1
        writer.commit()
        assert folder_tree.get_tree(reader, ["lib"]).nodes[0].recursive_media_count == 0


@pytest.mark.parametrize("core", [False, True])
def test_tree_rebuilt_before_dbapi_commit_does_not_survive_commit(tree_api, core):
    from app.services import folder_tree

    _, database = tree_api
    seed(database, ["/data/A/a.jpg"])
    with database.session() as reader:
        assert folder_tree.get_tree(reader, ["lib"]).nodes[0].recursive_media_count == 1
        seen = []

        def before_actual_commit(connection):
            # Installed after service Engine listener: invalidate already occurred,
            # but DBAPI has not committed. Rebuild the old committed tree here.
            seen.append(folder_tree.get_tree(reader, ["lib"]).nodes[0].recursive_media_count)

        sa.event.listen(database.engine, "commit", before_actual_commit)
        try:
            if core:
                with database.engine.begin() as connection:
                    connection.execute(sa.update(MediaCacheIndex).values(is_available=False))
            else:
                with database.session() as writer:
                    writer.execute(sa.update(MediaCacheIndex).values(is_available=False))
                    writer.commit()
            assert seen == [1]
            assert folder_tree.get_tree(reader, ["lib"]).nodes[0].recursive_media_count == 0
        finally:
            sa.event.remove(database.engine, "commit", before_actual_commit)


def test_external_engine_commit_and_new_pool_connection_are_probed(tree_api, monkeypatch):
    from app.services import folder_tree

    _, database = tree_api
    seed(database, ["/data/A/a.jpg"])
    calls = []
    original = folder_tree._build_tree

    def counted(*args, **kwargs):
        calls.append(1)
        return original(*args, **kwargs)

    monkeypatch.setattr(folder_tree, "_build_tree", counted)
    with database.session() as reader:
        assert folder_tree.get_tree(reader, ["lib"]).nodes[0].recursive_media_count == 1
        assert len(calls) == 1
        # Holding this connection forces the second session to obtain a fresh one.
        with database.session() as fresh:
            assert folder_tree.get_tree(fresh, ["lib"]).nodes[0].recursive_media_count == 1
            assert len(calls) == 2
            folder_tree.get_tree(fresh, ["lib"])
            assert len(calls) == 2
        external = sa.create_engine(database.engine.url)
        try:
            with external.begin() as connection:
                connection.execute(sa.update(MediaCacheIndex).values(is_available=False))
            assert folder_tree.get_tree(reader, ["lib"]).nodes[0].recursive_media_count == 0
        finally:
            external.dispose()


def test_cross_library_tree_rejected_and_combined_filters_keep_page2(tree_api):
    client, database = tree_api
    seed(database, [f"/data/Media/Folder/Child/image{i}.jpg" for i in range(8)])
    with database.session() as db:
        db.add(LibrarySelection(jellyfin_id="other", name="库B", selected=True))
        for i in range(8):
            row = db.get(MediaCacheIndex, f"lib-{i}")
            row.name = f"匹配-{i}" if i < 7 else "不符合搜索"
            if i == 6:
                row.media_type = "video"
        db.add(Favorite(media_id="lib-1"))
        db.commit()
    root = next(n for n in nodes(client) if n["library_id"] == "lib" and n["parent_id"] is None)
    filters = {
        "folder_id": root["folder_id"],
        "media_type": "image",
        "search": "匹配",
        "exclude_favorites": "true",
        "page": 2,
        "page_size": 2,
        "sort_by": "name",
        "sort_order": "asc",
    }
    response = client.get("/api/v1/media", params=filters)
    assert response.status_code == 200
    page = response.json()["data"]
    assert page["total"] == 5
    assert page["page"] == 2
    assert [item["media_id"] for item in page["items"]] == ["lib-3", "lib-4"]
    response = client.get("/api/v1/media", params={**filters, "library_id": "other"})
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "MEDIA_NOT_FOUND"
    with database.session() as db:
        db.get(LibrarySelection, "lib").selected = False
        db.commit()
    response = client.get("/api/v1/media", params=filters)
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "MEDIA_NOT_FOUND"
