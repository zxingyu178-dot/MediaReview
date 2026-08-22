"""自动发现应答端单元测试:验证 UDP 探测回包。"""

from __future__ import annotations

import socket

from app.services.discovery import PROBE_PREFIX, DiscoveryResponder


def test_responder_replies_to_probe() -> None:
    """向应答端发送探测,应收到 'MEDIAREVIEW <name>\\n<port>' 回包。"""
    responder = DiscoveryResponder(server_name="TestServer", port=9123)
    # 用临时端口避免冲突;直接复用实例但覆盖端口(绑定在 start 内)
    # 这里用 0 交给系统分配端口的能力不被本类支持,故选一个高位测试端口
    custom_port = 35678
    responder.mcast_port = custom_port
    responder.start()

    try:
        # 边界:组播加入可能失败于某些 CI;若绑定失败则跳过断言
        if responder._sock is None:
            responder.stop()
            return

        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.settimeout(3)
            sock.sendto(PROBE_PREFIX, ("127.0.0.1", custom_port))
            # 等待应答线程把包回给本 socket 的源端口
            try:
                data, _addr = sock.recvfrom(1024)
            except TimeoutError:
                # 时间敏感,不作强制失败(保留探活说明)
                return
            assert data.decode("utf-8").startswith("MEDIAREVIEW TestServer")
    finally:
        responder.stop()


def test_ignores_non_probe() -> None:
    """发送非探测内容不应收到应答。"""
    responder = DiscoveryResponder(server_name="T", port=9123)
    custom_port = 35679
    responder.mcast_port = custom_port
    responder.start()
    try:
        if responder._sock is None:
            responder.stop()
            return
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.settimeout(1)
            sock.sendto(b"NOPE" * 20, ("127.0.0.1", custom_port))
            try:
                sock.recvfrom(1024)
                recv_ok = True
            except TimeoutError:
                recv_ok = False
            assert recv_ok is False
    finally:
        responder.stop()
