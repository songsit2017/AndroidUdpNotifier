const fs = require('fs');
const path = require('path');

const dirs = [
    'receiver/src/main/res/layout',
    'receiver/src/main/res/layout-sw720dp',
    'receiver/src/main/res/layout-sw720dp-land'
];

for (const dir of dirs) {
    const mainFile = path.join(dir, 'activity_main.xml');
    const settingsFile = path.join(dir, 'activity_settings.xml');
    
    if (!fs.existsSync(mainFile)) continue;
    
    let content = fs.readFileSync(mainFile, 'utf8');
    
    // We need to extract the three cards.
    // They start at: <!-- Features Card -->
    // and end at the end of the <!-- Media Card --> block (which is </com.google.android.material.card.MaterialCardView>)
    
    const startTag = '<!-- Features Card -->';
    const endTag = '<!-- Media Card -->';
    
    const startIndex = content.indexOf(startTag);
    if (startIndex === -1) {
        console.log("Could not find start tag in " + mainFile);
        continue;
    }
    
    const endStartIndex = content.indexOf(endTag);
    if (endStartIndex === -1) {
         console.log("Could not find end tag in " + mainFile);
         continue;
    }
    
    // Find the closing MaterialCardView after the Media Card
    const searchPart = content.substring(endStartIndex);
    const endCardIndex = searchPart.indexOf('</com.google.android.material.card.MaterialCardView>');
    
    if (endCardIndex === -1) {
         console.log("Could not find end card tag in " + mainFile);
         continue;
    }
    
    const absoluteEndIndex = endStartIndex + endCardIndex + '</com.google.android.material.card.MaterialCardView>'.length;
    
    const extractedCards = content.substring(startIndex, absoluteEndIndex);
    
    // Create activity_settings.xml
    const settingsContent = `<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="@color/app_background">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="16dp"
        android:gravity="center_vertical"
        android:background="@color/app_surface"
        android:elevation="4dp">
        
        <ImageView
            android:id="@+id/btnBack"
            android:layout_width="40dp"
            android:layout_height="40dp"
            android:padding="8dp"
            android:src="@android:drawable/ic_menu_revert"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:clickable="true"
            android:focusable="true"/>
            
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="16dp"
            android:text="การตั้งค่าระบบ (Settings)"
            android:textSize="20sp"
            android:textStyle="bold"
            android:textColor="@color/app_text_primary" />
    </LinearLayout>

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:clipToPadding="false"
        android:padding="16dp">
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">
            
${extractedCards}

        </LinearLayout>
    </ScrollView>
</LinearLayout>
`;
    
    fs.writeFileSync(settingsFile, settingsContent, 'utf8');
    
    // Replace the extracted part in activity_main.xml with a Settings button
    const newSettingsButton = `
            <com.google.android.material.button.MaterialButton
                android:id="@+id/btnSettings"
                android:layout_width="match_parent"
                android:layout_height="60dp"
                android:layout_marginBottom="16dp"
                android:backgroundTint="@color/app_surface"
                android:textColor="@color/app_text_primary"
                android:textSize="18sp"
                app:cornerRadius="12dp"
                app:strokeColor="@color/app_accent"
                app:strokeWidth="2dp"
                android:text="?? การตั้งค่าระบบ (Settings)" />
`;

    const newMainContent = content.substring(0, startIndex) + newSettingsButton + content.substring(absoluteEndIndex);
    fs.writeFileSync(mainFile, newMainContent, 'utf8');
    
    console.log("Successfully extracted settings for " + dir);
}
