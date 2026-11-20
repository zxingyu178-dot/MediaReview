"""SQLite 目录表达式。查询必须与目录索引的 SQL 一致。"""

from sqlalchemy import func, literal_column


def sqlite_folder_dirname(path):
    slash = literal_column("'/'")
    unified = func.replace(path, literal_column("char(92)"), slash)
    flattened = func.replace(unified, literal_column("'//'"), slash)
    return func.rtrim(flattened, func.replace(flattened, slash, literal_column("''")))
