import re

files = [
    'receiver/src/main/res/layout/activity_main.xml',
    'receiver/src/main/res/layout-sw720dp/activity_main.xml',
    'receiver/src/main/res/layout-sw720dp-land/activity_main.xml'
]

for f in files:
    try:
        with open(f, 'r', encoding='utf-8') as file:
            content = file.read()
        
        # Regex to find SwitchMaterial followed by TextView
        pattern = r'<com\.google\.android\.material\.switchmaterial\.SwitchMaterial\s+android:id="([^"]+)"\s+android:layout_width="match_parent"\s+android:layout_height="wrap_content"\s+android:text="([^"]+)"\s+android:checked="([^"]+)"\s*/>\s*<TextView[^>]*android:text="([^"]+)"[^>]*/>'
        
        def replacer(match):
            id_val = match.group(1)
            title = match.group(2)
            checked = match.group(3)
            subtitle = match.group(4)
            
            # Determine textSize based on file
            title_size = "18sp" if "sw720dp" in f else "16sp"
            sub_size = "14sp" if "sw720dp" in f else "12sp"
            
            return f'''<LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal" android:gravity="center_vertical" android:layout_marginBottom="12dp">
                        <LinearLayout android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content" android:orientation="vertical" android:paddingEnd="16dp">
                            <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="{title}" android:textSize="{title_size}" android:textColor="@color/app_text_primary" />
                            <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="{subtitle}" android:textSize="{sub_size}" android:textColor="@color/app_text_secondary" />
                        </LinearLayout>
                        <com.google.android.material.switchmaterial.SwitchMaterial android:id="{id_val}" android:layout_width="wrap_content" android:layout_height="wrap_content" android:checked="{checked}" />
                    </LinearLayout>'''
        
        new_content, count = re.subn(pattern, replacer, content)
        
        if count > 0:
            with open(f, 'w', encoding='utf-8') as file:
                file.write(new_content)
            print(f"Replaced {count} occurrences in {f}")
        else:
            print(f"No matches in {f}")
            
    except Exception as e:
        print(f"Error processing {f}: {e}")
