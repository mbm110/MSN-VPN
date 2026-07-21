# 🛡️ MSN-VPN

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/msnvpn_launcher.png" width="120" alt="MSN-VPN icon">
</p>

<p align="center">
  <b>کلاینت نیتیو اندروید برای اتصال آزاد و امن</b>
</p>

<p align="center">
  <a href="https://github.com/mbm110/MSN-VPN/releases"><img src="https://img.shields.io/github/v/release/mbm110/MSN-VPN?display_name=tag&style=for-the-badge&color=74c69d" alt="Release"></a>
  <a href="https://github.com/mbm110/MSN-VPN/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/mbm110/MSN-VPN/build.yml?branch=master&style=for-the-badge&label=Android%20build" alt="Android build"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-6c5ce7?style=for-the-badge" alt="AGPL-3.0"></a>
  <a href="https://github.com/CluvexStudio/Aether"><img src="https://img.shields.io/badge/core-Aether-101411?style=for-the-badge" alt="Aether core"></a>
</p>

---

<div dir="rtl" align="right">

یک اپلیکیشن VPN اندرویدی سبک، قدرتمند و متن‌باز که برای عبور از فیلترینگ و برقراری اتصال خصوصی و امن طراحی شده. کاملاً نیتیو (بدون واسطه‌های سنگین) و با هسته‌ی پرسرعت نوشته‌شده با زبان **Rust** و **Kotlin**.

<br>

## ⚡️ قابلیت‌های اصلی

| ⚡ | توضیح |
|---|---|
| **🔘 اتصال یک‌لمسی (One-tap Connect)** | با یک ضربه وصل شو، با یک ضربه قطع کن |
| **🌐 حالت VPN** | کل ترافیک دستگاه از تونل عبور می‌کنه |
| **🧩 حالت Proxy** | پروکسی SOCKS5 محلی روی 127.0.0.1:1819 |
| **🔀 Split Tunneling** | انتخاب اپ‌هایی که از VPN عبور کنن |
| **🛡️ Kill Switch** | اگر تونل قطع شه، ترافیک درز نمی‌کنه |
| **🌍 نمایش IP + پرچم** | نمایش IP عمومی (IPv4) و پرچم کشور |
| **📶 نمایش پینگ** | تأخیر اتصال به‌صورت زنده |
| **🔔 نوتیفیکیشن زنده** | سرعت لحظه‌ای آپلود/دانلود + دکمه قطع |
| **📜 لاگ زنده** | جزئیات کامل اسکن و تونل |
| **⬆️ بروزرسانی درون‌برنامه‌ای** | بدون نیاز به گیت‌هاب |

<br>

### جزئیات بیشتر

• **اتصال با یک ضربه (One-tap Connect)** و رابط کاربری روان و مدرن

• **دو حالت اتصال:**
  • 🌐 **حالت VPN** — کل ترافیک دستگاه از تونل رد می‌شود
  • 🧩 **حالت Proxy** — پروکسی محلی SOCKS5 روی 127.0.0.1:1819 برای اپ‌های دلخواه

• **پشتیبانی از چند پروتکل قدرتمند:**
  • **MASQUE** روی HTTP/3 (با fallback خودکار به HTTP/2 وقتی UDP/QUIC بسته باشد)
  • **WireGuard** برای شبکه‌هایی که در دسترس باشد
  • **WARP-on-WARP** (gool) از طریق هسته

• 🔍 **اسکن خودکار Endpoint** با تشخیص در سطح IP

• 🔄 **اتصال مجدد سریع** با gateway کش‌شده (Cached-gateway reconnect)

• 🛡️ **تأیید Ironclad** برای امنیت بیشتر در اسکن MASQUE

• 📱 **Split Tunneling** نیتیو با انتخاب‌گر اپ (همراه نام و آیکون برنامه‌ها)

• 🎯 **سیاست‌های کشف gateway:** حالت «Cache & refresh» یا «Fresh scan»

• ⚙️ **کنترل سرعت اسکن** و انتخاب بین IPv4 / IPv6

• 📊 **نمایش فازهای اتصال** به‌صورت واضح (شروع، اسکن، اتصال، متصل)

• 🔔 **نوتیفیکیشن زنده** با نمایش لحظه‌ای سرعت آپلود/دانلود + دکمه قطع

• 📜 **لاگ‌های زنده و تمام‌صفحه** برای مشاهده جزئیات اتصال

• 🎨 **طراحی Material 3** با رنگ‌های داینامیک هماهنگ با تم گوشی

• ⬆️ **به‌روزرسانی مستقیم درون برنامه** (Check for updates)

<br>

## ✨ قابلیت‌های ویژه‌ی MSN-VPN (که در نسخه اصلی نبود و ما اضافه کردیم)

| 🔒 | توضیح |
|---|---|
| **Kill Switch واقعی** | چک‌باکس در تنظیمات — اگر اتصال VPN قطع شود، ترافیک درز نمی‌کنه |
| **نمایش IP عمومی (نسخه ۴)** | بعد اتصال، IP واقعی خروجی رو می‌بینید |
| **نمایش پرچم کشور** | پرچم کشور سرور خروجی |
| **نمایش پینگ** | تأخیر اتصال به‌صورت زنده |

• 🔒 **Kill Switch واقعی** — با یک چک‌باکس در تنظیمات؛ اگر اتصال VPN به هر دلیلی قطع شود، جلوی نشت ترافیک و لو رفتن IP واقعی شما گرفته می‌شود

• 🌍 **نمایش IP عمومی (نسخه ۴)** پس از اتصال

• 🚩 **نمایش پرچم کشور** آی‌پی متصل‌شده

• 📶 **نمایش پینگ (Latency)** اتصال فعال به‌صورت زنده

<br>

## 📥 دانلود

نسخه‌ها به‌صورت رایگان و عمومی در گیت‌هاب منتشر شده:

🔗 https://github.com/mbm110/MSN-VPN/releases

| نوع دستگاه | فایل |
|---|---|
| گوشی‌های امروزی (۶۴ بیتی) | **arm64-v8a** |
| گوشی‌های قدیمی (۳۲ بیتی) | **armeabi-v7a** |

<br>

## 💡 پیشنهاد برای اولین اتصال

با پروتکل **MASQUE**، حالت **HTTP/3** و گزینه **Cache & refresh** شروع کنید. اگر شبکه‌تان UDP/QUIC را مسدود می‌کند، از تنظیمات اسکنر به **HTTP/2** سوئیچ کنید.

<br>

## 🔌 پروتکل‌های پشتیبانی‌شده

| پروتکل | کاربرد |
|---|---|
| **MASQUE** | پیش‌فرض توصیه‌شده — روی HTTP/3 با fallback خودکار به HTTP/2 |
| **WireGuard** | پروتکل سریع مستقیم در شبکه‌هایی که UDP در دسترسه |
| **WARP-on-WARP** | تونل تو در توی WireGuard از طریق هسته Aether |

<br>

## 🛠️ بیلد از سورس

### نیازمندی‌ها
- Android Studio + Android SDK 36
- Android NDK 26.3.11579264
- CMake 3.22.1
- JDK 17
- Rust stable + targets اندروید

### دستورات بیلد
```bash
# هر دو نسخه
./gradlew assembleDebug

# فقط یک نسخه
./gradlew assembleDebug -PtargetAbi=arm64-v8a
./gradlew assembleDebug -PtargetAbi=armeabi-v7a
```

<br>

## 📂 ساختار پروژه

```
app/                 لایه اندروید (Kotlin) و پل JNI
core/aether/         هسته شبکه Aether (Rust)
core/quiche/         کتابخانه QUIC/HTTP3
.github/             workflow بیلد خودکار
```

<br>

---

🔓 **متن‌باز (Open Source)** تحت لایسنس GNU AGPL-3.0

🦀 هسته‌ی پرسرعت نوشته‌شده با **Rust** | رابط کاربری با **Kotlin**

🚫 **بدون تبلیغات، بدون ثبت‌نام، بدون ردیابی**

<br>

## 🙏 تشکر از

- [Aether](https://github.com/CluvexStudio/Aether) — هسته شبکه قدرتمند
- [quiche](https://github.com/cloudflare/quiche) — کتابخانه QUIC و HTTP/3
- [ZethRise](https://github.com/ZethRise) — سازنده اصلی Aethery

<br>

<p align="center">
  <a href="https://github.com/mbm110/MSN-VPN">🔗 github.com/mbm110/MSN-VPN</a>
</p>

<p align="center">
  ساخته شده با ❤️ برای کاربران آزادی‌خواه اینترنت
</p>

</div>