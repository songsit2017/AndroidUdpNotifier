const fs = require('fs');
const original = fs.readFileSync('receiver/src/main/res/layout/activity_main.xml', 'utf8');

const matches = [...original.matchAll(/btnRequestOverlay/g)];
console.log('Original has btnRequestOverlay matches:', matches.length);

const s = original.replace(/textSize="28sp"/g, 'textSize="36sp"');
console.log('s has btnRequestOverlay matches:', [...s.matchAll(/btnRequestOverlay/g)].length);

function wrapCard(sectionName, xmlString) {
    let s = xmlString;
    let regex = new RegExp(`(<TextView[^>]*text="${sectionName}"[\\s\\S]*?\\/>\\s*)(<LinearLayout[\\s\\S]*?)(?=<\\/LinearLayout>\\s*<\\/com\\.google\\.android\\.material\\.card\\.MaterialCardView>)`, 'g');
    return s.replace(regex, '$1<GridLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:columnCount="2">$2</GridLayout>');
}

let s1 = wrapCard('Features', original);
console.log('Features wrapCard matches:', [...s1.matchAll(/btnRequestOverlay/g)].length);
