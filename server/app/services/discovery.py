"""局域网自动发现 UDP 应答端。

负载协议(与 Android `ServerDiscovery` 对齐):
- 手机加入/向组播组 239.255.42.99:35001 发送探测包,内容与回复首字节为字面量 PROBE 头。
- 本服务器收到 PROBE 头后,向来源地址/端口回一个小包:
  `MEDIAREVIEW  <server_name>\n<port>`,手机解析出 host(来源 IP)+ port。

实现为独立线程 UDP socket,不阻塞 FastAPI 请求线程;由应用 lifespan 启动/停止。
若组播不可用(网络限制),手机端可退化到手动 IP,本应答端不影响主流程。
"""

from __future__ import annotations

import socket
import threading

from app.core.logging import get_logger

logger = get_logger("discovery")

# 与 Android ServerDiscovery 一致
MULTICAST_GROUP = "239.255.42.99"
MULTICAST_PORT = 35001
PROBE_PREFIX = b"MEDIAREVIEW_DISCOVER"


class DiscoveryResponder:
    """后台 UDP 应答器:收到探测即回包。可 start/stop。"""

    def __init__(
        self,
        server_name: str = "MediaReview",
        port: int = 8766,
        group: str = MULTICAST_GROUP,
        mcast_port: int = MULTICAST_PORT,
    ) -> None:
        self.server_name = server_name
        self.port = port
        self.group = group
        self.mcast_port = mcast_port
        self._sock: socket.socket | None = None
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()

    def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="discovery-responder", daemon=True)
        self._thread.start()
        logger.info("自动发现应答端已启动: %s:%s", self.group, self.mcast_port)

    def _run(self) -> None:
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                sock.bind(("", self.mcast_port))
            except OSError:
                sock.bind(("0.0.0.0", self.mcast_port))  # noqa: S104 组播需绑定全接口
            # 加入组播组(允许绑定任意本机地址)
            sock.setsockopt(
                socket.IPPROTO_IP,
                socket.IP_ADD_MEMBERSHIP,
                socket.inet_aton(self.group) + socket.inet_aton("0.0.0.0"),  # noqa: S104
            )
            sock.settimeout(0.5)
            self._sock = sock
        except OSError as exc:
            logger.warning("自动发现应答端启动失败(不影响主服务): %s", exc)
            return

        payload = f"MEDIAREVIEW {self.server_name}\n{self.port}".encode()
        while not self._stop.is_set():
            try:
                data, addr = sock.recvfrom(1024)
            except TimeoutError:
                continue
            except OSError:
                break
            if data.startswith(PROBE_PREFIX):
                try:
                    sock.sendto(payload, addr)
                    logger.debug("响应发现探测: %s", addr)
                except OSError as exc:
                    logger.debug("发现应答失败 %s: %s", addr, exc)

    def stop(self) -> None:
        self._stop.set()
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
        if self._thread is not None:
            self._thread.join(timeout=1.0)
        logger.info("自动发现应答端已停止")


__all__ = ["DiscoveryResponder"]
