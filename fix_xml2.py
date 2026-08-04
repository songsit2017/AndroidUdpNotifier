import re
import os

files = [
    'receiver/src/main/res/layout-sw720dp-land/activity_main.xml',
    'receiver/src/main/res/layout-sw720dp/activity_main.xml'
]

for f in files:
    with open(f, 'r', encoding='utf-8') as file:
        content = file.read()
    
    # We replaced 3 occurrences of <LinearLayout -> <GridLayout in each file
    # We need to replace the corresponding </LinearLayout> -> </GridLayout>
    
    # The pattern is:
    # </LinearLayout>
    #             </com.google.android.material.card.MaterialCardView>
    
    # For Features, Safety, Media cards.
    # Wait, the Permissions card also has </LinearLayout> \n </com.google... 
    # But it STILL uses <LinearLayout> as opening tag!
    # Let's count occurrences.
    
    # Let's just do a regex that replaces </LinearLayout> with </GridLayout> IF the parent card is Features, Safety, or Media.
    
    # A safer way: replace </LinearLayout>\s*</com.google.android.material.card.MaterialCardView>
    # with </GridLayout>\n            </com.google.android.material.card.MaterialCardView>
    # But ONLY for the 2nd, 3rd, and 4th matches! (Since 1st is Permissions, 5th is Log card which also has a different structure)
    
    parts = re.split(r'(</LinearLayout>\s*</com.google.android.material.card.MaterialCardView>)', content)
    # parts[1] is Permissions card closing
    # parts[3] is Features card closing
    # parts[5] is Safety card closing
    # parts[7] is Media card closing
    
    if len(parts) >= 9:
        parts[3] = parts[3].replace('</LinearLayout>', '</GridLayout>')
        parts[5] = parts[5].replace('</LinearLayout>', '</GridLayout>')
        parts[7] = parts[7].replace('</LinearLayout>', '</GridLayout>')
        
        new_content = ''.join(parts)
        with open(f, 'w', encoding='utf-8') as file:
            file.write(new_content)
        print(f"Fixed {f}")
    else:
        print(f"Failed to find enough parts in {f}")

