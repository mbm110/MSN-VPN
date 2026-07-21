# 🛡️ MSN-VPN

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/msnvpn_launcher.png" width="120" alt="MSN-VPN icon">
</p>

<p align="center" dir="rtl">
  <b>کلاینت نیتیو اندروید برای اتصال امن، خصوصی و عبور از فیلترینگ</b>
</p>

<p align="center">
  <a href="https://github.com/mbm110/MSN-VPN/releases"><img src="https://img.shields.io/github/v/release/mbm110/MSN-VPN?display_name=tag&style=for-the-badge&color=74c69d" alt="Release"></a>
  <a href="https://github.com/mbm110/MSN-VPN/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/mbm110/MSN-VPN/build.yml?branch=master&style=for-the-badge&label=Android%20build" alt="Android build"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-6c5ce7?style=for-the-badge" alt="AGPL-3.0"></a>
  <a href="https://github.com/CluvexStudio/Aether"><img src="https://img.shields.io/badge/core-Aether-101411?style=for-the-badge" alt="Aether core"></a>
</p>

---

<br>

<div dir="rtl">

## 📋 معرفی
**MSN-VPN** یک اپلیکیشن VPN اندرویدی متن‌باز، سبک و قدرتمنده که با هسته‌ی **Aether** (نوشته‌شده با Rust) کار می‌کنه. این برنامه رابط کاربری مدرن اندروید، تونل VPN/TUN نیتیو، انتخاب پروتکل، نمایش لحظه‌ای وضعیت اتصال و انتشار نسخه‌ها رو در خودش داره.

<br>

## ✨ قابلیت‌های اصلی

| ⚡ | توضیح |
|---|---|
| **🔘 اتصال یک‌لمسی** | با یه ضربه وصل شو، با یه ضربه قطع کن — رابط کاربری روان و مدرن |
| **🌐 حالت VPN** | کل ترافیک دستگاه از تونل عبور می‌کنه |
| **🧩 حالت Proxy** | پروکسی SOCKS5 محلی روی `127.0.0.1:1819` برای استفاده اپ‌های دلخواه |
| **🔀 Split Tunneling** | انتخاب کنید کدوم اپ‌ها از VPN عبور کنن و کدوم‌ها مستقیم برن |
| **🛡️ Kill Switch** | **ویژه MSN-VPN** — اگه تونل قطع شه، ترافیک هیچ جا درز نمی‌کنه |
| **🌍 نمایش IP + پرچم** | **ویژه MSN-VPN** — بعد اتصال، IP عمومی (IPv4) و پرچم کشور رو می‌بینید |
| **📶 نمایش پینگ** | **ویژه MSN-VPN** — تأخیر اتصال رو زنده نشون میده |
| **🔔 نوتیفیکیشن زنده** | سرعت لحظه‌ای آپلود/دانلود + دکمه قطع مستقیم از نوتیفیکیشن |
| **📜 لاگ زنده** | جزئیات کامل اتصال، اسکن و تونل |
| **⬆️ بروزرسانی درون‌برنامه‌ای** | بدون نیاز به گیت‌هاب، مستقیم از تنظیمات آپدیت کنید |
| **🎨 Material 3** | طراحی مدرن با رنگ‌های داینامیک هماهنگ با تم گوشی |

<br>

## 🔒 Kill Switch (ویژه MSN-VPN)

> کیل سوییچ یک ویژگی **امنیتی حیاتی** است که بعد از اتصال از درز اطلاعات شمار جلوگیری می کند.

- ✅ توی **Settings** یک چک‌باکس واقعی داره — روش بزن تا فعال بشه
- ✅ اگه تونل VPN به هر دلیلی قطع بشه (به جز قطع دستی خودتون)، بلافاصله یه تونل مسدودکننده ساخته میشه که **هیچ بسته‌ای به اینترنت باز خارج نمیشه**
- ✅ هیچ ترافیکی نشت نمی‌کنه تا وقتی که اتصال دوباره برقرار بشه یا خودتون قطع کنید
- ✅ بدون Kill Switch اگه VPN قطع بشه، ترافیک به صورت عادی از شبکه اصلی عبور می‌کنه

<br>

## 🌍 مشاهده IP و پرچم کشور (ویژه MSN-VPN)

بعد از اتصال موفق، در پایین دکمه اتصال خواهید دید:
- **آی‌پی عمومی** (نسخه ۴)
- **پرچم کشور** سرور خروجی
- **پینگ** اتصال

این اطلاعات از طریق APIهای امن (HTTPS) دریافت میشه و کاملاً در لحظه به‌روز می‌شه.

<br>

## 🔌 پروتکل‌های پشتیبانی‌شده

| پروتکل | کاربرد |
|---|---|
| **MASQUE** | پیش‌فرض توصیه‌شده. روی HTTP/3 با fallback خودکار به HTTP/2 |
| **WireGuard** | پروتکل سریع مستقیم در شبکه‌هایی که UDP در دسترسه |
| **WARP-on-WARP** | تونل تو در توی WireGuard از طریق هسته Aether |

> 💡 **نکته:** فیلترینگ شبکه بسته به مکان و اپراتور فرق داره. اگه یک پروتکل وصل نمیشه، پروتکل دیگه رو امتحان کن.

<br>

## 📥 دانلود و نصب

<p align="center">
  <a href="https://github.com/mbm110/MSN-VPN/releases/latest"><code>⬇️ دریافت آخرین نسخه از گیت‌هاب</code></a>
</p>

| نوع دستگاه | فایل مورد نیاز |
|---|---|
| گوشی‌های امروزی (۶۴ بیتی) | `app-arm64-v8a-debug.apk` |
| گوشی‌های قدیمی (۳۲ بیتی) | `app-armeabi-v7a-debug.apk` |

📌 فایل APK رو از بخش Releases دانلود کنید و نصب کنید. موقع نصب اگه اندروید اجازه خواست، Allow رو بزنید.

<br>

## 🛠️ بیلد از سورس

### نیازمندی‌ها
- Android Studio + Android SDK 36
- Android NDK `26.3.11579264`
- CMake `3.22.1`
- JDK 17
- Rust stable + targets اندروید:
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi
  ```

### دستورات بیلد
```bash
# هر دو نسخه (ARM64 + ARMv7)
./gradlew assembleDebug

# فقط یک نسخه
./gradlew assembleDebug -PtargetAbi=arm64-v8a
./gradlew assembleDebug -PtargetAbi=armeabi-v7a

# خروجی:
# app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
# app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk
```

<br>

## 📂 ساختار پروژه

```
app/                 لایه اندروید (Kotlin) و پل JNI
core/aether/         هسته شبکه Aether (Rust)
core/quiche/         کتابخانه QUIC/HTTP3 (وابستگی Aether)
.github/             workflow بیلد خودکار
```

<br>

## 📜 مجوز

MSN-VPN تحت لایسنس **GNU AGPL-3.0** منتشر شده است. هسته Aether و وابستگی‌های دیگر لایسنس‌های خود را دارند.

<br>

## 🙏 تشکر از

- [Aether](https://github.com/CluvexStudio/Aether) — هسته شبکه قدرتمند
- [quiche](https://github.com/cloudflare/quiche) — کتابخانه QUIC و HTTP/3
- [ZethRise](https://github.com/ZethRise) — سازنده اصلی Aethery که این پروژه از روی آن ساخته شده

<br>

---

<p align="center">
  ساخته شده با ❤️ برای کاربران آزادی‌خواه اینترنت
</p>

</div>