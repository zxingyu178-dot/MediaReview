"""Stage8I review scope: isolated index, frozen sessions, strict wire types."""
import json

import pytest
import sqlalchemy as sa

from app.api.v1.review import CreateSessionBody, SourceSnapshot, _validated_source
from app.core.errors import ValidationFailedError
from app.db.models import MediaCacheIndex, ReviewSessionItem
from app.db.session import Database
from app.services import folder_tree, review


@pytest.fixture
def scope_db(tmp_path):
    database = Database(tmp_path / "scope.sqlite")
    database.create_all()
    with database.session() as db:
        for key, lib, path in (
            ("direct", "lib", "/media/A/a.mp4"),
            ("child", "lib", "/media/A/Child/b.mp4"),
            ("other", "lib", "/media/B/c.mp4"),
            ("foreign", "foreign", "/media/A/d.mp4"),
        ):
            db.add(MediaCacheIndex(media_id=key, jellyfin_id=key, library_id=lib,
                name=key, media_type="video", media_path=path, fingerprint=key))
        db.commit()
        yield db
    database.dispose()


def queue(db, session):
    return set(db.scalars(sa.select(ReviewSessionItem.media_id).where(
        ReviewSessionItem.session_id == session.session_id)))


def folders(db, libraries=("lib",)):
    return {n.name: n.folder_id for n in folder_tree.get_tree(db, list(libraries)).nodes}


@pytest.mark.parametrize("descendants,expected", [(True, {"direct", "child"}), (False, {"direct"})])
def test_single_scope_and_descendants(scope_db, descendants, expected):
    ids = folders(scope_db)
    created = review.create_session_from_index(scope_db, library_ids=["lib"],
        filter_snapshot={"folder_ids": [ids["A"]], "include_descendants": descendants})
    assert queue(scope_db, created) == expected


def test_multiple_scope_or_and_frozen_old_session(scope_db):
    ids = folders(scope_db)
    snapshot = {"folder_ids": [ids["A"], ids["B"]]}
    created = review.create_session_from_index(scope_db, library_ids=["lib"], filter_snapshot=snapshot)
    assert queue(scope_db, created) == {"direct", "child", "other"}
    scope_db.add(MediaCacheIndex(media_id="new", jellyfin_id="new", library_id="lib",
        name="new", media_type="video", media_path="/media/A/new.mp4", fingerprint="new"))
    scope_db.flush()
    assert queue(scope_db, created) == {"direct", "child", "other"}
    assert json.loads(created.filter_snapshot)["folder_ids"] == snapshot["folder_ids"]
    assert json.loads(created.filter_snapshot)["include_descendants"] is True


def test_scope_filters_before_duplicate_rank_with_type_and_search(scope_db):
    # Same hash outside scope sorts first: it must not hide the inside representative.
    for key in ("direct", "other"):
        row = scope_db.get(MediaCacheIndex, key)
        row.sha256 = "a" * 64
        row.size_bytes = 100
        row.duration_ms = 1000
        row.name = "match-a" if key == "other" else "match-z"
    scope_db.add(MediaCacheIndex(media_id="image", jellyfin_id="image", library_id="lib",
        name="match-image", media_type="image", media_path="/media/A/i.jpg", fingerprint="image"))
    scope_db.flush()
    created = review.create_session_from_index(scope_db, library_ids=["lib"],
        filter_snapshot={"folder_ids": [folders(scope_db)["A"]], "media_type": "video", "search": "match"})
    assert queue(scope_db, created) == {"direct"}


@pytest.mark.parametrize("value", ["ft_bad", None, 1, {}, [1], [True], [""], ["x"] * 101])
def test_strict_folder_wire_types(value):
    with pytest.raises(ValidationFailedError):
        _validated_source(CreateSessionBody(source=SourceSnapshot(filter={"folder_ids": value})))


@pytest.mark.parametrize("value", ["true", 1, 0, None, []])
def test_strict_descendants_wire_type(value):
    with pytest.raises(ValidationFailedError):
        _validated_source(CreateSessionBody(source=SourceSnapshot(filter={"include_descendants": value})))


@pytest.mark.parametrize("foreign", [False, True])
def test_unknown_or_foreign_scope_fail_closed(scope_db, foreign):
    node = next(iter(folders(scope_db, ["foreign"]).values())) if foreign else "ft_unknown"
    with pytest.raises(ValueError):
        review.create_session_from_index(scope_db, library_ids=["lib"], filter_snapshot={"folder_ids": [node]})
