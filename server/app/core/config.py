"""配置系统。

加载优先级(从低到高):
1. 内置默认值
2. 数据根目录下 config/config.json
3. 环境变量 MEDIAREVIEW__<SECTION>__<KEY>

data_root 默认 %ProgramData%\\MediaReview,可用环境变量
MEDIAREVIEW_DATA_ROOT 覆盖(开发与测试必须使用该变量,禁止写死路径)。
"""

from __future__ import annotations

import json
import os
import re
from ipaddress import IPv4Address, IPv6Address, ip_address
from pathlib import Path
from typing import Any
from urllib.parse import quote, unquote, urlsplit

from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator, model_validator

DEFAULT_DATA_ROOT = r"%ProgramData%\MediaReview"

# 环境变量前缀,例如 MEDIAREVIEW__SERVER__PORT=9000
_ENV_PREFIX = "MEDIAREVIEW__"
_HOST_LABEL = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
_INVALID_PERCENT_ESCAPE = re.compile(r"%(?![0-9A-Fa-f]{2})")
_PATH_SEGMENT_SAFE = "-._~!$&'()*+,;=:@"
_DEFAULT_JELLYFIN_URL = "http://127.0.0.1:8096"


def _normalize_hostname(hostname: str) -> str:
    try:
        address = ip_address(hostname)
    except ValueError:
        address = None
    if address is not None:
        if isinstance(address, IPv6Address):
            return f"[{address.compressed}]"
        assert isinstance(address, IPv4Address)
        return address.compressed
    try:
        ascii_hostname = hostname.encode("idna").decode("ascii")
    except UnicodeError as exc:
        raise ValueError("Jellyfin URL host 非法") from exc
    trailing_dot = ascii_hostname.endswith(".")
    body = ascii_hostname[:-1] if trailing_dot else ascii_hostname
    if not body or len(ascii_hostname) > 253:
        raise ValueError("Jellyfin URL host 非法")
    if any(_HOST_LABEL.fullmatch(label) is None for label in body.split(".")):
        raise ValueError("Jellyfin URL host 非法")
    return body.lower() + ("." if trailing_dot else "")


def _normalize_base_path(path: str) -> str:
    if path in ("", "/"):
        return ""
    if not path.startswith("/") or _INVALID_PERCENT_ESCAPE.search(path):
        raise ValueError("Jellyfin URL base path 非法")
    raw_segments = path[1:].split("/")
    if raw_segments[-1] == "":
        raw_segments.pop()
    if not raw_segments or any(not segment for segment in raw_segments):
        raise ValueError("Jellyfin URL base path 非法")
    normalized: list[str] = []
    for raw_segment in raw_segments:
        decoded = unquote(raw_segment, errors="strict")
        if (
            decoded in (".", "..")
            or "/" in decoded
            or "\\" in decoded
            or any(ord(char) < 32 or ord(char) == 127 for char in decoded)
        ):
            raise ValueError("Jellyfin URL base path 非法")
        normalized.append(quote(decoded, safe=_PATH_SEGMENT_SAFE))
    return "/" + "/".join(normalized)


_SERVER_ONLY_SUFFIXES = (".internal", ".local", ".lan")


def _client_reachable_hostname(
    hostname: str, allowlist: set[str] | frozenset[str] = frozenset()
) -> str:
    normalized = _normalize_hostname(hostname.strip("[]"))
    plain = normalized.strip("[]").rstrip(".").casefold()
    if plain == "localhost":
        raise ValueError("客户端 URL host 不得为回环地址")
    try:
        address = ip_address(plain)
    except ValueError:
        if plain not in allowlist and ("." not in plain or plain.endswith(_SERVER_ONLY_SUFFIXES)):
            raise ValueError("客户端 URL host 需要显式 allowlist") from None
        return normalized
    if address.is_loopback or address.is_unspecified or address.is_multicast:
        raise ValueError("客户端 URL host 不可由局域网客户端访问")
    return normalized


def normalize_jellyfin_base_url(value: str) -> str:
    """校验并规范化 Jellyfin HTTP(S) origin 与可选 base path。"""
    if not isinstance(value, str):
        raise ValueError("Jellyfin URL 必须是字符串")
    raw = value.strip()
    if not raw or "?" in raw or "#" in raw or "\\" in raw:
        raise ValueError("Jellyfin URL 不允许 query、fragment 或反斜杠")
    if any(ord(char) < 32 or ord(char) == 127 for char in raw):
        raise ValueError("Jellyfin URL 包含非法控制字符")
    try:
        parsed = urlsplit(raw)
    except ValueError as exc:
        raise ValueError("Jellyfin URL 无法解析") from exc
    scheme = parsed.scheme.lower()
    if scheme not in {"http", "https"}:
        raise ValueError("Jellyfin URL 只允许 http 或 https")
    if "@" in parsed.netloc or parsed.username is not None or parsed.password is not None:
        raise ValueError("Jellyfin URL 不允许 userinfo")
    if not parsed.hostname:
        raise ValueError("Jellyfin URL 必须包含 host")
    try:
        port = parsed.port
    except ValueError as exc:
        raise ValueError("Jellyfin URL port 非法") from exc
    if port == 0:
        raise ValueError("Jellyfin URL port 非法")
    hostname = _normalize_hostname(parsed.hostname)
    authority = hostname if port is None else f"{hostname}:{port}"
    return f"{scheme}://{authority}{_normalize_base_path(parsed.path)}"


def _secret_text(value: object) -> str:
    if isinstance(value, SecretStr):
        return value.get_secret_value()
    return value if isinstance(value, str) else ""


def jellyfin_url_contains_api_key(url: str, api_key: str) -> bool:
    """按大小写无关、percent 解码语义判断 URL 是否携带非空 server key。"""
    if not api_key:
        return False
    needles = {api_key.casefold(), quote(api_key, safe="").casefold()}
    try:
        needles.add(api_key.encode("idna").decode("ascii").casefold())
    except UnicodeError:
        pass
    representations = {url.casefold()}
    decoded = url
    for _ in range(3):
        next_decoded = unquote(decoded, errors="strict")
        representations.add(next_decoded.casefold())
        if next_decoded == decoded:
            break
        decoded = next_decoded
    return any(needle in candidate for needle in needles for candidate in representations)


def ensure_jellyfin_url_excludes_api_key(url: str, api_key: str) -> None:
    """拒绝任何组件携带 server key 的 Jellyfin URL；错误不得包含输入或 key。"""
    if jellyfin_url_contains_api_key(url, api_key):
        raise ValueError("Jellyfin URL 不得包含服务器 API Key")


class ServerConfig(BaseModel):
    # 局域网服务必须监听全部网卡,供手机直连(见 docs/DEPLOYMENT.md)
    host: str = "0.0.0.0"  # noqa: S104
    port: int = Field(default=8766, ge=1, le=65535)


class JellyfinConfig(BaseModel):
    model_config = ConfigDict(
        hide_input_in_errors=True,
        validate_assignment=True,
        validate_default=True,
    )

    url: str = _DEFAULT_JELLYFIN_URL
    # 可选的手机可达地址；留空时按请求 MediaReview 所用 host 派生。
    client_url: str = ""
    # 明确允许客户端 DNS 可解析的本地域名；默认不猜测单标签或私有后缀。
    client_host_allowlist: list[str] = []
    api_key: SecretStr = SecretStr("")
    # Jellyfin 用户 ID,留空时由阶段 2 自动发现
    user_id: str = ""

    @model_validator(mode="before")
    @classmethod
    def _validate_url_api_key_pair(cls, data: object) -> object:
        if not isinstance(data, dict):
            return data
        raw_key = data.get("api_key", "")
        for field, default in (("url", _DEFAULT_JELLYFIN_URL), ("client_url", "")):
            raw_url = data.get(field, default)
            if isinstance(raw_url, str) and raw_url.strip():
                normalized_url = normalize_jellyfin_base_url(raw_url)
                ensure_jellyfin_url_excludes_api_key(normalized_url, _secret_text(raw_key))
        return data

    @field_validator("url")
    @classmethod
    def _validate_url(cls, value: str) -> str:
        return normalize_jellyfin_base_url(value)

    @field_validator("client_host_allowlist")
    @classmethod
    def _validate_client_host_allowlist(cls, value: list[str]) -> list[str]:
        normalized = []
        for host in value:
            item = _normalize_hostname(host.strip("[]")).rstrip(".").casefold()
            if not item or item == "localhost":
                raise ValueError("客户端 host allowlist 非法")
            normalized.append(item)
        return sorted(set(normalized))

    @field_validator("client_url")
    @classmethod
    def _normalize_client_url(cls, value: str) -> str:
        return normalize_jellyfin_base_url(value) if value.strip() else ""

    @model_validator(mode="after")
    def _validate_client_url(self) -> JellyfinConfig:
        value = self.client_url
        if not value.strip():
            return self
        normalized = normalize_jellyfin_base_url(value)
        parsed = urlsplit(normalized)
        _client_reachable_hostname(parsed.hostname or "", frozenset(self.client_host_allowlist))
        return self

    @property
    def host(self) -> str:
        return self.url

    def is_configured(self) -> bool:
        return bool(self.api_key.get_secret_value())

    def client_base_url(self, request_host: str) -> str:
        """返回手机可达 Jellyfin base URL，不复制 server key。"""
        if self.client_url:
            result = self.client_url
        else:
            parsed = urlsplit(self.url)
            normalized_host = _client_reachable_hostname(
                request_host, frozenset(self.client_host_allowlist)
            )
            authority = (
                normalized_host if parsed.port is None else f"{normalized_host}:{parsed.port}"
            )
            result = f"{parsed.scheme}://{authority}{parsed.path}"
        ensure_jellyfin_url_excludes_api_key(result, self.api_key.get_secret_value())
        return result


class StorageConfig(BaseModel):
    data_root: str = DEFAULT_DATA_ROOT
    cache_max_gb: float = Field(default=20.0, gt=0)
    # ffmpeg/ffprobe 所在目录;留空时使用系统 PATH
    ffmpeg_dir: str = ""


class SecurityConfig(BaseModel):
    pairing_required: bool = True
    # 开发环境专用开关: 允许远程客户端生成配对码。
    # 生产默认 False -> 仅本机(127.0.0.1/::1)或管理后台可调 /pairing/code,
    # 手机只能调 /pairing/verify,配对码由本机/管理后台展示给用户。
    pairing_code_remote_allowed: bool = False


class FeaturesConfig(BaseModel):
    sprites: bool = True
    duplicate_scan: bool = True


class AppConfig(BaseModel):
    model_config = ConfigDict(hide_input_in_errors=True)

    server: ServerConfig = ServerConfig()
    jellyfin: JellyfinConfig = JellyfinConfig()
    storage: StorageConfig = StorageConfig()
    security: SecurityConfig = SecurityConfig()
    features: FeaturesConfig = FeaturesConfig()

    def masked_dict(self) -> dict[str, Any]:
        """返回脱敏后的配置(用于诊断/管理接口),不暴露 API Key。"""
        data = self.model_dump(mode="json")
        jellyfin = data["jellyfin"]
        # 诊断只表达是否配置，不回传 server-only/loopback 主机。
        jellyfin["url"] = "configured" if self.jellyfin.url else ""
        jellyfin["client_url"] = "configured" if self.jellyfin.client_url else ""
        jellyfin["api_key"] = "********" if self.jellyfin.is_configured() else ""
        return data


def resolve_data_root(data_root: str | None = None) -> Path:
    """解析数据根目录:展开环境变量引用与用户目录。"""
    raw = data_root or os.environ.get("MEDIAREVIEW_DATA_ROOT") or DEFAULT_DATA_ROOT
    expanded = os.path.expandvars(raw)
    expanded = os.path.expanduser(expanded)
    return Path(expanded).absolute()


def _apply_env_overrides(config: AppConfig) -> AppConfig:
    # python 模式保留 SecretStr 实例,避免掩码字符串回灌破坏密钥
    data: dict[str, Any] = config.model_dump()
    for section_name in ("server", "jellyfin", "storage", "security", "features"):
        for key in data[section_name]:
            env_name = f"{_ENV_PREFIX}{section_name.upper()}__{key.upper()}"
            env_value = os.environ.get(env_name)
            if env_value is not None:
                data[section_name][key] = env_value
    return AppConfig.model_validate(data)


class ConfigLoadError(Exception):
    """配置文件损坏或格式非法。"""


def persist_jellyfin_user_id(config_file: Path, user_id: str) -> None:
    """把 Jellyfin user_id 原子写入 config.json(保留其他配置)。

    仅在运行时第一次确定 user_id 时触发一次;写失败不阻断业务(下次启动重新发现)。
    """
    payload: dict[str, Any] = {}
    if config_file.exists():
        try:
            # PowerShell 5.1 Set-Content -Encoding UTF8 会写 BOM,必须容忍。
            raw = json.loads(config_file.read_text(encoding="utf-8-sig"))
            if isinstance(raw, dict):
                payload = raw
        except (OSError, json.JSONDecodeError):
            payload = {}
    jellyfin = payload.get("jellyfin")
    if not isinstance(jellyfin, dict):
        jellyfin = payload["jellyfin"] = {}
    if jellyfin.get("user_id") == user_id:
        return
    jellyfin["user_id"] = user_id
    config_file.parent.mkdir(parents=True, exist_ok=True)
    tmp = config_file.with_name(f"{config_file.name}.tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(config_file)


def load_config(config_file: Path | None = None) -> AppConfig:
    """加载配置。config_file 显式指定时优先使用,否则读取数据根目录下默认位置。"""
    from app.core.paths import PathManager

    if config_file is None:
        config_file = PathManager(resolve_data_root()).config_file

    merged: dict[str, Any] = {}
    if config_file.exists():
        try:
            # PowerShell 5.1 Set-Content -Encoding UTF8 会写 BOM,必须容忍。
            raw = json.loads(config_file.read_text(encoding="utf-8-sig"))
        except (OSError, json.JSONDecodeError) as exc:
            raise ConfigLoadError(f"配置文件无法解析: {config_file}") from exc
        if not isinstance(raw, dict):
            raise ConfigLoadError(f"配置文件必须是 JSON 对象: {config_file}")
        merged = raw

    config = AppConfig.model_validate(merged)
    return _apply_env_overrides(config)


__all__ = [
    "AppConfig",
    "ConfigLoadError",
    "FeaturesConfig",
    "JellyfinConfig",
    "SecurityConfig",
    "ServerConfig",
    "StorageConfig",
    "load_config",
    "ensure_jellyfin_url_excludes_api_key",
    "jellyfin_url_contains_api_key",
    "normalize_jellyfin_base_url",
    "persist_jellyfin_user_id",
    "resolve_data_root",
]
