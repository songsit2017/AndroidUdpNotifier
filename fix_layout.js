const fs = require('fs');

const original = fs.readFileSync('receiver/src/main/res/layout/activity_main.xml', 'utf8');

const headerEnd = original.indexOf('<!-- Permissions Card -->');
const header = original.substring(original.indexOf('<!-- Header -->'), headerEnd);

const permissionsEnd = original.indexOf('<!-- Features Card -->');
const permissions = original.substring(headerEnd, permissionsEnd);

const featuresEnd = original.indexOf('<!-- Safety Card -->');
const features = original.substring(permissionsEnd, featuresEnd);

const safetyEnd = original.indexOf('<!-- Media Card -->');
const safety = original.substring(featuresEnd, safetyEnd);

const mediaEnd = original.indexOf('</com.google.android.material.card.MaterialCardView>', safetyEnd) + '</com.google.android.material.card.MaterialCardView>'.length;
const media = original.substring(safetyEnd, mediaEnd);

const buttonsStart = mediaEnd;
const buttonsEnd = original.indexOf('<com.google.android.material.card.MaterialCardView', buttonsStart);
const buttons = original.substring(buttonsStart, buttonsEnd);

const connHealthStart = buttonsEnd;
const connHealthEnd = original.indexOf('</com.google.android.material.card.MaterialCardView>', connHealthStart) + '</com.google.android.material.card.MaterialCardView>'.length;
const connHealth = original.substring(connHealthStart, connHealthEnd);

const logsStart = original.indexOf('<com.google.android.material.card.MaterialCardView', connHealthEnd);
const logsEnd = original.indexOf('</com.google.android.material.card.MaterialCardView>', logsStart) + '</com.google.android.material.card.MaterialCardView>'.length;
const logs = original.substring(logsStart, logsEnd);

// FIX: Find the exact end of tvVersionInfo
const tvVersionStart = original.indexOf('<TextView\n        android:id="@+id/tvVersionInfo"');
const tvVersionEnd = original.indexOf('/>', tvVersionStart) + '/>'.length;
const footer = original.substring(logsEnd, tvVersionEnd);

const templateLand = `<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="horizontal"
    android:baselineAligned="false"
    android:background="@color/app_background">

    <!-- Left Column -->
    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="match_parent"
        android:layout_weight="1"
        android:orientation="vertical"
        android:padding="16dp">

${header}
        <Space android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" />
${connHealth}

    </LinearLayout>

    <!-- Right Column -->
    <ScrollView
        android:layout_width="0dp"
        android:layout_height="match_parent"
        android:layout_weight="1"
        android:clipToPadding="false"
        android:padding="16dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

${permissions}
${features}
${safety}
${media}
${buttons}

            <Space android:layout_width="match_parent" android:layout_height="16dp" />
${logs}
${footer}
        </LinearLayout>
    </ScrollView>
</LinearLayout>
`;

fs.writeFileSync('receiver/src/main/res/layout-land/activity_main.xml', templateLand);

function makeSw720dp(xmlString) {
    let s = xmlString;
    s = s.replace(/textSize="28sp"/g, 'textSize="36sp"');
    s = s.replace(/textSize="14sp"/g, 'textSize="18sp"');
    s = s.replace(/textSize="12sp"/g, 'textSize="16sp"');
    s = s.replace(/textSize="16sp"/g, 'textSize="20sp"');
    s = s.replace(/textSize="18sp"/g, 'textSize="24sp"');

    // Wrap switches
    s = s.replace(/(<com\.google\.android\.material\.switchmaterial\.SwitchMaterial[\s\S]*?\/>\s*<TextView[\s\S]*?\/>)/g, 
        '<LinearLayout android:layout_width="0dp" android:layout_height="wrap_content" android:layout_columnWeight="1" android:orientation="vertical" android:padding="8dp">\n$1\n</LinearLayout>');

    function wrapCard(sectionName) {
        let regex = new RegExp(`(<TextView[^>]*text="${sectionName}"[\\s\\S]*?\\/>\\s*)(<LinearLayout[\\s\\S]*?)(?=<\\/LinearLayout>\\s*<\\/com\\.google\\.android\\.material\\.card\\.MaterialCardView>)`, 'g');
        s = s.replace(regex, '$1<GridLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:columnCount="2">$2</GridLayout>');
    }
    
    wrapCard('Features');
    wrapCard('Safety');
    wrapCard('Media');
    
    return s;
}

fs.writeFileSync('receiver/src/main/res/layout-sw720dp/activity_main.xml', makeSw720dp(original));
fs.writeFileSync('receiver/src/main/res/layout-sw720dp-land/activity_main.xml', makeSw720dp(templateLand));

console.log("Fixed all layouts without trailing tags!");
