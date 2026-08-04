const fs = require('fs');
const files = [
    'receiver/src/main/res/layout/activity_main.xml',
    'receiver/src/main/res/layout-sw720dp/activity_main.xml',
    'receiver/src/main/res/layout-sw720dp-land/activity_main.xml'
];
for (const file of files) {
    if (fs.existsSync(file)) {
        let content = fs.readFileSync(file, 'utf8');
        content = content.replace(/android:text="[^"]*\(Settings\)"/g, 'android:text="⚙️ การตั้งค่าระบบ (Settings)"');
        fs.writeFileSync(file, content, 'utf8');
        console.log('Fixed ' + file);
    }
}
