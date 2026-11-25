#!/system/bin/sh
MODDIR=${0%/*}
/system/bin/am start --user 0 -n io.github.isxlan0.miappxspace/.MainActivity || "$MODDIR/bin/miapp" status
