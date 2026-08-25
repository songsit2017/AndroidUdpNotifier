# DUDU Phone Notification Bridge

ส่ง notification, การตอบกลับ และสถานะจากโทรศัพท์ Android ไปยังจอรถผ่าน UDP ที่จับคู่และเข้ารหัส โดยยังคง launcher ของ DUDUOS เป็น launcher หลัก

## แอปในโปรเจกต์

- `sender` — **DUDU Phone Connector** ติดตั้งบนโทรศัพท์และอ่าน notification ที่ผู้ใช้อนุญาต
- `receiver` — **DUDU Notification Bridge** ติดตั้งบนจอรถ รับ UDP, แสดง notification, TTS และ popup สำรอง
- `dudu-compat` — **DUDU Notification Connector** ตัวเชื่อมขนาดเล็กสำหรับให้ `AppNoticeService` ของ DUDU วาดการ์ดแจ้งเตือนด้วย UI เดิมของ launcher

## ติดตั้งบน DUDUOS

1. ติดตั้ง `ADH-Notifier-Client.apk` บนจอรถและเปิดหนึ่งครั้ง
2. ติดตั้ง `DUDU-Notification-Compat.apk` แล้วเปิดหนึ่งครั้งเพื่ออนุญาต Notification
3. ปิด Battery optimization ของ Client และจับคู่กับแอปโทรศัพท์ด้วย QR
4. กด **ทดสอบ POPUP และเสียง** ใน Client ถ้าตัวเชื่อมพร้อม การ์ดทดสอบจะปรากฏด้วย UI ของ DUDU มุมขวาบน

ถ้า Connector ไม่ได้ติดตั้ง, ลายเซ็นไม่ตรงกัน หรือยังไม่ได้รับสิทธิ์ Notification ตัว Client จะใช้ Android notification พร้อม popup สำรองโดยอัตโนมัติ จึงไม่กลืนข้อความเงียบ ๆ

> [!WARNING]
> DUDUOS 3.7 ที่ทดสอบบน DUDUOS7 รับ local notification เข้า UI ของ launcher เฉพาะ package ของ WeChat/QQ แบบ hard-code ตัว Compatibility APK จึงแยกใช้ package `com.tencent.mm` และ **ติดตั้งร่วมกับ WeChat จริงบนจอไม่ได้** ตัว Connector ไม่มีสิทธิ์ Internet และรับคำสั่งจาก Client ที่เซ็นด้วยกุญแจเดียวกันผ่าน signature permission เท่านั้น

## ความเข้ากันได้ของ protocol

payload เดิมยังใช้ได้ ฟิลด์ใหม่เป็น optional:

- `appName` — ชื่อแอปต้นทาง
- `notificationKey` — คีย์สำหรับ update/remove notification เดิม
- `actions` / `replyActionId` — ปุ่ม action และ voice reply
- `type: "remove"` — ลบ notification ตาม `notificationKey` หรือ package

Client รุ่นใหม่ยังรับ payload รุ่นเก่าและ Sender รุ่นใหม่ยังใช้การจับคู่/การเข้ารหัสเดิม

## Build

ต้องใช้ JDK 17 และ Android SDK:

```powershell
.\gradlew.bat :sender:assembleDebug :receiver:assembleDebug :dudu-compat:assembleDebug
.\gradlew.bat :sender:lintDebug :receiver:lintDebug :dudu-compat:lintDebug
```

ไฟล์ debug:

- `sender/build/outputs/apk/debug/ADH-Notifier-Server.apk`
- `receiver/build/outputs/apk/debug/ADH-Notifier-Client.apk` (`com.example.receiverapp.preview`)
- `dudu-compat/build/outputs/apk/debug/DUDU-Notification-Compat.apk` (`com.tencent.mm`)

release ต้องเซ็น Client และ Connector ด้วย keystore เดียวกัน มิฉะนั้น signature permission จะปฏิเสธการเชื่อมต่อ
