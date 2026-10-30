"""按数据库隔离的跨进程删除/恢复互斥；崩溃时由OS释放锁。"""

from contextlib import contextmanager
import os
from pathlib import Path


@contextmanager
def exclusive_deletion(session):
    database = session.get_bind().url.database
    if not database or database == ":memory:":
        raise RuntimeError("删除执行需要文件数据库")
    lock_path = Path(database).with_suffix(".delete.lock")
    with lock_path.open("a+b") as handle:
        # Windows byte-range locks may extend beyond EOF; no content write needed.
        handle.seek(0)
        acquired = False
        try:
            if os.name == "nt":
                import msvcrt

                try:
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                    acquired = True
                except OSError as error:
                    if error.errno not in (13, 11, 36):
                        raise
            else:
                import fcntl

                try:
                    fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                    acquired = True
                except BlockingIOError:
                    pass
            yield acquired
        finally:
            if acquired:
                handle.seek(0)
                if os.name == "nt":
                    msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
