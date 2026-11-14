Java.perform(function () {
    Java.scheduleOnMainThread(function () {
        const prefs = Java.use('com.dudu.autoui.common.n1.r0');
        const get = prefs.a.overload('java.lang.String', 'java.lang.String');
        const put = prefs.b.overload('java.lang.String', 'java.lang.String');
        const key = 'SDATA_DATE_FROMAT';
        const backup = 'UDP_NOTIFIER_ORIGINAL_DATE_FORMAT';
        const missing = 'UDP_NOTIFIER_NO_SAVED_FORMAT';
        const current = get.call(prefs, key, '');
        let original = get.call(prefs, backup, missing);
        const label = LOCATION_LABEL;
        if (label) {
            if (original === missing) {
                const parser = Java.use('android.util.Xml').newPullParser();
                parser.setInput(Java.use('java.io.StringReader').$new('<map>' + ORIGINAL_DATE_XML + '</map>'));
                original = current;
                for (let event = parser.next(); event !== 1; event = parser.next()) {
                    if (event === 2 && parser.getName() === 'string' &&
                        parser.getAttributeValue(null, 'name') === key) {
                        original = parser.nextText();
                        break;
                    }
                }
                put.call(prefs, backup, original);
            }
            put.call(prefs, key, "'" + label + "'");
        } else if (original !== missing) {
            put.call(prefs, key, original);
            put.call(prefs, backup, missing);
        }
        const bus = Java.use('org.greenrobot.eventbus.c').d();
        bus.b.overload('java.lang.Object').call(
            bus, Java.use('com.dudu.autoui.manage.h0.d.g').$new()
        );
        console.log('DUDU_TOPBAR_UPDATED');
    });
});
