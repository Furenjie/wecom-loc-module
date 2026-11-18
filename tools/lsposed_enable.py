import sqlite3
import sys

if len(sys.argv) != 3:
    print("usage: lsposed_enable.py <modules_config.db> <module-apk-path>")
    sys.exit(2)

db = sys.argv[1]
apk_path = sys.argv[2]

con = sqlite3.connect(db)
con.execute(
    "insert or replace into modules(module_pkg_name, apk_path) values(?, ?)",
    ("com.fj.wecomloc", apk_path),
)
for user_id in (0, 128):
    con.execute(
        "insert or replace into modules_state"
        "(module_pkg_name, user_id, enabled, scope_request_blocked) values(?, ?, ?, ?)",
        ("com.fj.wecomloc", user_id, 1, 0),
    )
    con.execute(
        "insert or ignore into scope(module_pkg_name, app_pkg_name, user_id) values(?, ?, ?)",
        ("com.fj.wecomloc", "com.tencent.wework", user_id),
    )
con.commit()
print("modules:", list(con.execute(
    "select module_pkg_name, apk_path from modules where module_pkg_name='com.fj.wecomloc'")))
print("states:", list(con.execute(
    "select * from modules_state where module_pkg_name='com.fj.wecomloc'")))
print("scope:", list(con.execute(
    "select * from scope where module_pkg_name='com.fj.wecomloc'")))
con.execute("pragma wal_checkpoint(truncate)")
con.commit()
con.execute("vacuum")
con.commit()
con.close()
