#!/system/bin/sh
set -eu

prefs=/data/data/com.dudu.autoui/shared_prefs/settings.xml
saved=/data/local/tmp/dudu-topbar-original-date
next="$prefs.dudu-next"
launcher=com.dudu.autoui/.ui.activity.launcher.LauncherActivity
[ -f "$prefs" ] && [ ! -e "$prefs.bak" ]

if [ ! -f "$saved" ]; then
    sed -n '/<string name="SDATA_DATE_FROMAT">/p' "$prefs" > "$saved"
fi

am force-stop com.dudu.autoui
trap 'rm -f "$next"; am start -n "$launcher" >/dev/null' EXIT
awk -v label="$1" -v saved="$saved" '
    BEGIN { getline original < saved; close(saved) }
    /<string name="SDATA_DATE_FROMAT">/ { next }
    /<string name="SDATA_CENTER_DATE_FROMAT">\047ตำบล/ {
        print "    <string name=\"SDATA_CENTER_DATE_FROMAT\"></string>"
        next
    }
    /<\/map>/ {
        if (length(label)) {
            print "    <string name=\"SDATA_DATE_FROMAT\">\047" label "\047</string>"
        } else if (length(original)) {
            print original
        }
        closed++
    }
    { print }
    END { if (closed != 1) exit 1 }
' "$prefs" > "$next"
chown system:system "$next"
chmod 660 "$next"
chcon u:object_r:system_app_data_file:s0 "$next"
mv -f "$next" "$prefs"
if [ -z "$1" ]; then rm -f "$saved"; fi
echo UPDATED_AND_RELOADED
