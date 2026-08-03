import re

with open('sender/src/main/res/layout/activity_main.xml', 'r', encoding='latin-1', errors='replace') as f:
    text = f.read()

# Replace the specific lines directly
lines = text.split('\n')
for i in range(len(lines)):
    if 'android:text' in lines[i]:
        if 'UdpSender' in lines[i] or 'Required Permissions' in lines[i] or 'Waiting for notifications' in lines[i] or 'Check for Updates' in lines[i] or 'Version 1.0.0' in lines[i]:
            continue
        # It's one of the Thai texts
        if '1.' in lines[i]:
            lines[i] = '                android:text="1. อนุญาตอ่านการแจ้งเตือน"'
        elif '2.' in lines[i]:
            lines[i] = '                android:text="2. อนุญาตเข้าถึง GPS เบื้องหลัง"'
        elif '3.' in lines[i]:
            lines[i] = '                android:text="3. ปิดการจำกัดแบตเตอรี่"'
        elif '??' in lines[i] or 'btnViewLocation' in lines[i-7]: # Check context for the location button
            lines[i] = '        android:text="📍 ดูพิกัดที่จอดรถล่าสุด" />'
        elif i < 30: # It's the subtitle at line 24
            lines[i] = '        android:text="ส่งการแจ้งเตือนไปยังจอรถยนต์"'

with open('sender/src/main/res/layout/activity_main.xml', 'w', encoding='utf-8') as f:
    f.write('\n'.join(lines))
