# DUDU AI Reply Proxy

Cloudflare Worker กลางที่ถือ Gemini/Anthropic API key ไว้แทนแอป `receiver` เพื่อไม่ให้ key จริงถูกฝังลง APK
แอปจะยิง request มาที่ Worker นี้แทนที่จะเรียก Gemini/Claude ตรงๆ — Worker จะลอง Gemini ก่อน ถ้าไม่ได้ผลค่อย fallback ไป Claude Haiku แล้วส่งข้อความตอบกลับให้แอป

## Deploy (รันเองครั้งเดียว)

ต้องมีบัญชี Cloudflare (ฟรี) และ Node.js

```powershell
npm install -g wrangler
wrangler login
```

สร้าง KV namespace สำหรับ rate limit:

```powershell
cd proxy
wrangler kv namespace create RATE_LIMIT_KV
```

คำสั่งด้านบนจะพิมพ์ `id` ออกมา เอาไปแทนที่ `REPLACE_WITH_KV_NAMESPACE_ID` ใน `wrangler.toml`

ตั้ง secrets (ใส่ค่าจริงตอน prompt ถาม ไม่ต้องพิมพ์ในคำสั่ง):

```powershell
wrangler secret put GEMINI_API_KEY
wrangler secret put ANTHROPIC_API_KEY
wrangler secret put PROXY_SHARED_SECRET
```

`PROXY_SHARED_SECRET` คือรหัสผ่านที่แอปใช้ยืนยันตัวกับ Worker — สุ่มเอาเอง เช่น:

```powershell
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"
```

Deploy:

```powershell
wrangler deploy
```

จะได้ URL แบบ `https://dudu-ai-reply-proxy.<your-subdomain>.workers.dev`

## ผูกกับแอป Android

ตั้งค่าตอน build แอป `receiver` ด้วย environment variable (หรือ `-P` gradle property) สองตัว:

- `AI_PROXY_URL` = URL ของ Worker ด้านบน
- `AI_PROXY_SECRET` = ค่าเดียวกับ `PROXY_SHARED_SECRET`

ถ้า build ผ่าน GitHub Actions ให้เพิ่มเป็น repo secrets:

```powershell
gh secret set AI_PROXY_URL --repo songsit2017/AndroidUdpNotifier
gh secret set AI_PROXY_SECRET --repo songsit2017/AndroidUdpNotifier
```

ถ้า `AI_PROXY_URL` ว่าง แอปจะข้ามการเรียก AI ไปใช้ข้อความตอบกลับสำเร็จรูปแทนโดยอัตโนมัติ

## Rate limit ในตัว

Worker จำกัดไว้ที่ 20 request/ชั่วโมงต่อ IP และ 300 request/วันรวมทั้งหมด (แก้ได้ที่ `RATE_LIMIT_PER_IP_PER_HOUR` / `RATE_LIMIT_TOTAL_PER_DAY` ใน `worker.js`) กันไม่ให้ค่าใช้จ่าย Gemini/Anthropic บานปลายแม้ `PROXY_SHARED_SECRET` จะรั่วออกไป — ต่างจาก API key ตรงๆ ตรงที่ปรับ/ปิดได้ทันทีโดยไม่ต้องออก APK ใหม่ (`wrangler deploy` ใหม่ หรือ revoke secret ผ่าน dashboard)
