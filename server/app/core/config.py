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
from pathlib import Path
from typing import Any

from pydantic import BaseModel, Field, SecretStr

DEFAULT_DATA_ROOT = r"%ProgramData%\MediaReview"

# 环境变量前缀,例如 MEDIAREVIEW__SERVER__PORT=9000
_ENV_PREFIX = "MEDIAREVIEW__"


class ServerConfig(BaseModel):
    # 局域网服务必须监听全部网卡,供手机直连(见 docs/DEPLOYMENT.md)
    host: str = "0.0.0.0"  # noqa: S104
    port: int = Field(default=8765, ge=1, le=65535)


class JellyfinConfig(BaseModel):
    url: str = "http://127.0.0.1:8096"
    api_key: SecretStr = SecretStr("")
    # Jellyfin 用户 ID,留空时由阶段 2 自动发现
    user_id: str = ""

    @property
    def host(self) -> str:
        return self.url.rstrip("/")

    def is_configured(self) -> bool:
        return bool(self.api_key.get_secret_value())


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
    server: ServerConfig = ServerConfig()
    jellyfin: JellyfinConfig = JellyfinConfig()
    storage: StorageConfig = StorageConfig()
    security: SecurityConfig = SecurityConfig()
    features: FeaturesConfig = FeaturesConfig()

    def masked_dict(self) -> dict[str, Any]:
        """返回脱敏后的配置(用于诊断/管理接口),不暴露 API Key。"""
        data = self.model_dump(mode="json")
        jellyfin = data["jellyfin"]
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
            raw = json.loads(config_file.read_text(encoding="utf-8"))
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
            raw = json.loads(config_file.read_text(encoding="utf-8"))
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
    "persist_jellyfin_user_id",
    "resolve_data_root",
]
