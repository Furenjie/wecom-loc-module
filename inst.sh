SZ=$(stat -c %s /data/local/tmp/WeComLoc.apk)
S=$(pm install-create -r -i com.android.shell | sed 's/.*\[\([0-9]*\)\].*/\1/')
cat /data/local/tmp/WeComLoc.apk | pm install-write -S $SZ $S base.apk
pm install-commit $S