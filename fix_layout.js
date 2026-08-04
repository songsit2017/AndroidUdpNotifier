const fs = require('fs');

const files = [
    'receiver/src/main/res/layout/activity_main.xml',
    'receiver/src/main/res/layout-sw720dp/activity_main.xml',
    'receiver/src/main/res/layout-sw720dp-land/activity_main.xml'
];

for (const file of files) {
    try {
        if (!fs.existsSync(file)) {
            console.log("Not found: " + file);
            continue;
        }
        let content = fs.readFileSync(file, 'utf8');
        
        // Match SwitchMaterial and TextView below it.
        const regex = /<com\.google\.android\.material\.switchmaterial\.SwitchMaterial\s+android:id="([^"]+)"\s+android:layout_width="match_parent"\s+android:layout_height="wrap_content"\s+android:text="([^"]+)"\s+android:checked="([^"]+)"\s*\/>\s*<TextView[^>]*android:text="([^"]+)"[^>]*\/>/g;
        
        let count = 0;
        content = content.replace(regex, (match, id, title, checked, subtitle) => {
            count++;
            const isTablet = file.includes('sw720dp');
            const titleSize = isTablet ? '18sp' : '16sp';
            const subSize = isTablet ? '14sp' : '12sp';
            
            // Remove any trailing spaces or newlines in the strings
            title = title.replace(/\n/g, ' ').replace(/\s+/g, ' ').trim();
            subtitle = subtitle.replace(/\n/g, ' ').replace(/\s+/g, ' ').trim();
            
            return `<LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal" android:gravity="center_vertical" android:layout_marginBottom="12dp">
    <LinearLayout android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content" android:orientation="vertical" android:paddingEnd="16dp">
        <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="${title}" android:textSize="${titleSize}" android:textColor="@color/app_text_primary" />
        <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="${subtitle}" android:textSize="${subSize}" android:textColor="@color/app_text_secondary" />
    </LinearLayout>
    <com.google.android.material.switchmaterial.SwitchMaterial android:id="${id}" android:layout_width="wrap_content" android:layout_height="wrap_content" android:checked="${checked}" />
</LinearLayout>`;
        });
        
        if (count > 0) {
            fs.writeFileSync(file, content, 'utf8');
            console.log("Replaced " + count + " occurrences in " + file);
        } else {
            console.log("No matches in " + file);
        }
    } catch (e) {
        console.error("Error processing " + file + ": " + e);
    }
}
