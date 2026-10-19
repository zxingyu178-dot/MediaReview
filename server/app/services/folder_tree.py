"""Folder metadata tree. Paths and scopes remain server-side; cache stores no ORM rows."""

from __future__ import annotations

import hashlib
from collections import OrderedDict
from dataclasses import dataclass, field
from threading import RLock
from weakref import WeakKeyDictionary

import sqlalchemy as sa
from pydantic import BaseModel
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session

from app.db.models import LibrarySelection, MediaCacheIndex, MediaLibraryMembership
from app.services.media_index import folder_dirname_for_path, library_membership_condition
from app.services.thumbnail_cache import media_source_fingerprint, source_version


class FolderNode(BaseModel):
    folder_id: str
    parent_id: str | None
    library_id: str
    name: str
    child_count: int = 0
    direct_media_count: int = 0
    recursive_media_count: int = 0
    direct_image_count: int = 0
    cover_url: str | None = None
    direct_cover_url: str | None = None


@dataclass
class Tree:
    nodes: tuple[FolderNode, ...]
    # Node membership expressed by direct directories, never downloaded media IDs.
    scopes: dict[str, tuple[str, tuple[str, ...] | None]]
    direct_scopes: dict[str, tuple[str, tuple[str, ...], bool]]


@dataclass
class _State:
    generation: int = 0
    cache: OrderedDict = field(default_factory=OrderedDict)


_states: WeakKeyDictionary = WeakKeyDictionary()
_lock = RLock()


def _invalidate(engine):
    with _lock:
        state = _states.get(engine)
        if state is not None:
            state.generation += 1
            state.cache.clear()


@sa.event.listens_for(Engine, "after_cursor_execute")
def _written(connection, cursor, statement, parameters, context, executemany):
    """Catch ORM, Core bulk sync/delete and textual writes, including partial sync."""
    sql = statement.lstrip().lower()
    if sql.startswith(("insert", "update", "delete", "replace")) and any(
        table in sql for table in ("media_cache_index", "library_selection", "media_library_membership")
    ):
        connection.info["folder_tree_written"] = True
        _invalidate(connection.engine)


@sa.event.listens_for(Engine, "commit")
@sa.event.listens_for(Engine, "rollback")
def _transaction_ended(connection):
    # A tree built in the writer transaction must not survive commit or rollback.
    if connection.info.pop("folder_tree_written", False):
        connection.info["folder_tree_probe_after_write"] = True
        _invalidate(connection.engine)


@sa.event.listens_for(Session, "before_commit")
def _before_session_commit(session):
    # Engine commit notification precedes DBAPI commit. A second invalidation
    # after the real commit closes the reader/writer race in WAL mode.
    changed = any(
        isinstance(row, (MediaCacheIndex, LibrarySelection, MediaLibraryMembership))
        for row in (*session.new, *session.dirty, *session.deleted)
    )
    if changed or session.connection().info.get("folder_tree_written", False):
        session.info["folder_tree_commit"] = True


@sa.event.listens_for(Session, "after_commit")
def _after_session_commit(session):
    if session.info.pop("folder_tree_commit", False):
        _invalidate(session.get_bind())


def _id(library: str, directory: tuple[str, ...] | None) -> str:
    payload = repr((library, directory))
    return "ft_" + hashlib.sha256(payload.encode()).hexdigest()[:24]


def _cover(row) -> str:
    version = source_version(media_source_fingerprint(row))
    return f"/api/v1/media/{row.media_id}/thumbnail?v={version}"


def _build_tree(session: Session, targets: tuple[str, ...]) -> Tree:
    libraries = {
        r.jellyfin_id: r.name
        for r in session.scalars(
            sa.select(LibrarySelection).where(LibrarySelection.jellyfin_id.in_(targets))
        )
    }
    rows = session.execute(
        sa.select(
            MediaCacheIndex.media_id,
            sa.func.coalesce(MediaLibraryMembership.library_id, MediaCacheIndex.library_id).label("library_id"),
            MediaCacheIndex.media_path,
            MediaCacheIndex.media_type,
            MediaCacheIndex.created_at,
            MediaCacheIndex.modified_at,
            MediaCacheIndex.size_bytes,
            MediaCacheIndex.fingerprint,
            MediaCacheIndex.jellyfin_id,
        )
        .outerjoin(MediaLibraryMembership, MediaLibraryMembership.media_id == MediaCacheIndex.media_id)
        .where(
            MediaCacheIndex.is_available.is_(True),
            sa.func.coalesce(MediaLibraryMembership.library_id, MediaCacheIndex.library_id).in_(targets),
            sa.or_(MediaLibraryMembership.media_id.is_(None), MediaLibraryMembership.is_available.is_(True)),
        )
        .order_by(MediaCacheIndex.created_at.desc(), MediaCacheIndex.media_id.asc())
    ).all()
    nodes = []
    scopes = {}
    direct_scopes = {}
    for library in targets:
        members = [r for r in rows if r.library_id == library]
        directories = {}
        anchors = {}
        prepared = []
        for row in members:
            dirname = folder_dirname_for_path(row.media_path)
            # Preserve legacy SQL directory normalization for query membership.
            parts = tuple(p for p in (dirname or "").split("/") if p)
            prepared.append((row, dirname, parts))
            if parts:
                directories.setdefault(parts, set()).add(dirname)
                anchors[parts] = (
                    2
                    if (row.media_path or "").startswith(("\\\\", "//"))
                    else (1 if parts[0].endswith(":") else 0)
                )
        paths = sorted(directories)
        common = 0
        if paths:
            while common < min(map(len, paths)) and len({p[common] for p in paths}) == 1:
                common += 1
        # Retain the last shared display segment (e.g. 欧美), collapse private prefix.
        cut = 0 if library.startswith("local_") else max(0, common - 1)
        # Drive/UNC anchor names must never become visible for disjoint paths.
        root_id = _id(library, None)
        root = FolderNode(
            folder_id=root_id,
            parent_id=None,
            library_id=library,
            name=libraries.get(library, "媒体库"),
        )
        local = {(): root}
        visible_paths = {}
        for path in paths:
            # Never display storage anchors, even when the shared prefix ends at
            # a UNC share. Different physical roots may have the same display name.
            start = max(cut, anchors[path])
            visible = path[start:]
            if visible and visible[0].endswith(":"):
                visible = visible[1:]
            visible_paths[path] = tuple(path[: start + d] for d in range(1, len(visible) + 1))
            for depth in range(1, len(visible) + 1):
                key = path[: start + depth]
                if key not in local:
                    # Identity includes absolute internal ancestor, library scoped.
                    ancestor = path[: start + depth]
                    parent = root if depth == 1 else local[key[:-1]]
                    local[key] = FolderNode(
                        folder_id=_id(library, ancestor),
                        parent_id=parent.folder_id,
                        library_id=library,
                        name=key[-1],
                    )
                    parent.child_count += 1
        scope_dirs = {key: set() for key in local}
        direct_dirs = {key: set() for key in local}
        pathless = set()
        for row, dirname, parts in prepared:
            chain = visible_paths.get(parts, ())
            direct = local[chain[-1]] if chain else root
            direct_key = chain[-1] if chain else ()
            if dirname:
                direct_dirs[direct_key].add(dirname)
            else:
                pathless.add(direct_key)
            direct.direct_media_count += 1
            if row.media_type == "image":
                direct.direct_image_count += 1
                if direct.direct_cover_url is None:
                    direct.direct_cover_url = _cover(row)
            for key in ((), *chain):
                node = local[key]
                node.recursive_media_count += 1
                if dirname:
                    scope_dirs[key].add(dirname)
                if row.media_type == "image" and node.cover_url is None:
                    node.cover_url = _cover(row)
        for key, node in local.items():
            nodes.append(node)
            scopes[node.folder_id] = (library, None if not key else tuple(sorted(scope_dirs[key])))
            direct_scopes[node.folder_id] = (
                library,
                tuple(sorted(direct_dirs[key])),
                key in pathless,
            )
    return Tree(tuple(nodes), scopes, direct_scopes)


def get_tree(session: Session, library_ids) -> Tree:
    session.flush()
    targets = tuple(sorted(set(library_ids)))
    connection = session.connection()
    engine = connection.engine
    # Uncommitted metadata is visible only to its writer, never other requests.
    if connection.info.get("folder_tree_written", False):
        return _build_tree(session, targets)
    # SQLite tracks commits made by other connections. This constant-time probe
    # also covers Core connection commits and external engines, without scanning
    # paths. Values are connection-local, so compare only against that connection.
    version = connection.exec_driver_sql("PRAGMA data_version").scalar_one()
    previous = connection.info.get("folder_tree_data_version")
    connection.info["folder_tree_data_version"] = version
    after_write = connection.info.pop("folder_tree_probe_after_write", False)
    if previous != version or after_write:
        _invalidate(connection.engine)
    with _lock:
        state = _states.setdefault(engine, _State())
        key = (targets, state.generation)
        cached = state.cache.get(key)
        if cached is not None:
            state.cache.move_to_end(key)
            return cached
        tree = _build_tree(session, targets)
        state.cache[key] = tree
        while len(state.cache) > 8:
            state.cache.popitem(last=False)
        return tree


def scope_condition(session: Session, library_ids, folder_id: str, *, include_descendants=True):
    tree = get_tree(session, library_ids)
    scope = tree.scopes.get(folder_id)
    if scope is None:
        return None
    from app.services.media_index import _folder_dirname_expression

    library, directories = scope
    condition = library_membership_condition([library])
    if not include_descendants:
        library, direct_dirs, has_pathless = tree.direct_scopes[folder_id]
        dirname = _folder_dirname_expression()
        return sa.and_(
            condition,
            sa.or_(
                dirname.in_(direct_dirs),
                sa.or_(dirname.is_(None), dirname == "") if has_pathless else sa.false(),
            ),
        )
    if directories is not None:
        condition = sa.and_(condition, _folder_dirname_expression().in_(directories))
    return condition
