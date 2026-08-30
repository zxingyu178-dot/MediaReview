"""TaskManager 工作循环健壮性测试。

独立审查 I-1 发现的既有生产缺陷:一次瞬时 SQLite 锁冲突抛出的异常会
从 _loop 逃逸并永久杀死后台任务引擎(媒体刷新/雪碧图全部停摆)。
回归合同:_tick 的瞬时异常必须被捕获并继续下一轮,只有取消才能停止循环。
"""

from __future__ import annotations

import asyncio

from app.db.session import Database
from app.services.tasks import TaskManager


def test_task_loop_survives_transient_tick_errors(tmp_path) -> None:
    async def scenario() -> tuple[int, int]:
        db = Database(tmp_path / "tasks-loop.db")
        db.create_all()
        manager = TaskManager(db, poll_interval=0.02)
        failures = {"n": 0}
        successes = {"n": 0}

        async def flaky_tick() -> None:
            if failures["n"] < 3:
                failures["n"] += 1
                raise RuntimeError("transient database is locked")
            successes["n"] += 1

        manager._tick = flaky_tick  # type: ignore[method-assign]
        try:
            await manager.start()
            await asyncio.sleep(0.4)
        finally:
            await manager.stop()
            db.dispose()
        return failures["n"], successes["n"]

    failures, successes = asyncio.run(scenario())
    assert failures == 3, "前置:异常确实发生了 3 次"
    assert successes >= 1, "瞬时异常不得杀死后台任务工作循环"
