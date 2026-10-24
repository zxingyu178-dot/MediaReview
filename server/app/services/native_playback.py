"""已验证本地句柄的有界 HTTP 单 Range 读取；不代理 Jellyfin。"""
import re
import threading
from datetime import UTC
from email.utils import formatdate, parsedate_to_datetime

from fastapi import Response
from fastapi.responses import StreamingResponse
from starlette.background import BackgroundTask

from app.core.errors import AppError
from app.services.native_sources import open_media

_SLOTS = threading.BoundedSemaphore(4)
MIME = {
    "mp4": "video/mp4", "mov": "video/quicktime", "mkv": "video/x-matroska",
    "webm": "video/webm", "m4v": "video/mp4", "ts": "video/mp2t", "m2ts": "video/mp2t",
    "avi": "video/x-msvideo", "wmv": "video/x-ms-wmv", "flv": "video/x-flv",
    "mpg": "video/mpeg", "mpeg": "video/mpeg",
}


class StreamBusy(AppError):
    status_code = 503
    code = "LOCAL_STREAM_BUSY"
    default_message = "本地播放连接已达上限，请关闭其他播放后重试"


def _date(value):
    try:
        parsed = parsedate_to_datetime(value)
        return int(parsed.replace(tzinfo=parsed.tzinfo or UTC).timestamp())
    except (TypeError, ValueError, OverflowError):
        return None


def _matches(value, etag):
    return any(part.strip().removeprefix("W/") in ("*", etag) for part in value.split(","))


def file_response(request, session, row):
    file, info = open_media(session, row)
    etag = '"' + row.fingerprint + '"'
    modified = int(info.st_mtime)
    size = info.st_size
    headers = {
        "Accept-Ranges": "bytes", "ETag": etag,
        "Last-Modified": formatdate(modified, usegmt=True),
        "Cache-Control": "private, max-age=0, must-revalidate",
        "X-Content-Type-Options": "nosniff",
    }
    mime = MIME.get((row.container or "").lower(), "application/octet-stream")
    incoming = request.headers
    # Precondition在Range之前处理。
    if incoming.get("if-match") and not (
        incoming["if-match"] == "*"
        or etag in [s.strip() for s in incoming["if-match"].split(",")]
    ):
        file.close()
        return Response(status_code=412, headers=headers)
    since = _date(incoming.get("if-unmodified-since"))
    if not incoming.get("if-match") and since is not None and modified > since:
        file.close()
        return Response(status_code=412, headers=headers)
    modified_since = _date(incoming.get("if-modified-since"))
    none_match = incoming.get("if-none-match")
    if (none_match and _matches(none_match, etag)) or (
        not none_match and modified_since is not None and modified <= modified_since
    ):
        file.close()
        return Response(status_code=304, headers=headers)
    start, end, status_code = 0, size - 1, 200
    range_header = incoming.get("range") if request.method == "GET" else None
    if_range = incoming.get("if-range")
    if range_header and if_range:
        date = _date(if_range)
        if if_range != etag and (date is None or date < modified):
            range_header = None
    if range_header and range_header.startswith("bytes="):
        match = re.fullmatch(r"bytes=(\d*)-(\d*)", range_header)
        valid = bool(match and (match[1] or match[2]) and size)
        if valid:
            try:
                if match[1]:
                    start = int(match[1])
                    end = min(size - 1, int(match[2])) if match[2] else size - 1
                else:
                    suffix = int(match[2])
                    start = max(0, size - suffix)
                    valid = suffix > 0
                valid = valid and 0 <= start <= end < size
            except ValueError:
                valid = False
        if not valid:
            file.close()
            headers["Content-Range"] = f"bytes */{size}"
            return Response(status_code=416, headers=headers)
        status_code = 206
        headers["Content-Range"] = f"bytes {start}-{end}/{size}"
    headers["Content-Length"] = str(max(0, end - start + 1))
    if request.method == "HEAD":
        file.close()
        return Response(status_code=200, media_type=mime, headers=headers)
    if not _SLOTS.acquire(blocking=False):
        file.close()
        raise StreamBusy()
    closed = False
    def close():
        nonlocal closed
        if not closed:
            closed = True
            file.close()
            _SLOTS.release()
    def chunks():
        try:
            file.seek(start)
            remaining = max(0, end - start + 1)
            while remaining:
                chunk = file.read(min(64 * 1024, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
                yield chunk
        finally:
            close()
    return StreamingResponse(chunks(), status_code=status_code, media_type=mime,
                             headers=headers, background=BackgroundTask(close))
