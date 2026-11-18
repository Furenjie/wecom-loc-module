import os
import zipfile

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
base = os.path.join(root, "build", "base.apk")
out = os.path.join(root, "build", "unaligned.apk")

with zipfile.ZipFile(base, "r") as zin, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    zout.write(os.path.join(root, "build", "dex", "classes.dex"), "classes.dex")
    zout.write(os.path.join(root, "assets", "xposed_init"), "assets/xposed_init")

print("wrote", out, os.path.getsize(out))
