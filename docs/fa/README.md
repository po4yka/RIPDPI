<div dir="rtl">

<p align="center">
  <img src="../../app/src/main/ic_launcher-playstore.png" width="120" alt="نشان RIPDPI"/>
</p>

<h1 align="center">RIPDPI</h1>
<p align="center"><b>Routing & Internet Performance Diagnostics Platform Interface</b></p>

<p align="center">
  <a href="https://github.com/po4yka/RIPDPI/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/po4yka/RIPDPI/ci.yml?style=flat-square&label=CI" alt="CI"/></a>
  <a href="https://github.com/po4yka/RIPDPI/releases/latest"><img src="https://img.shields.io/github/v/release/po4yka/RIPDPI?style=flat-square" alt="نسخه"/></a>
  <a href="../../LICENSE"><img src="https://img.shields.io/github/license/po4yka?style=flat-square" alt="پروانه"/></a>
  &nbsp;
  <img src="https://img.shields.io/badge/Android-8.1%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="اندروید ۸٫۱+"/>
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="کاتلین"/>
  <img src="https://img.shields.io/badge/Rust-000000?style=flat-square&logo=rust&logoColor=white" alt="راست"/>
</p>

<p align="center"><a href="../../README.md">English</a> | <a href="../../README-ru.md">Русский</a> | <a href="../../README-es.md">Español</a> | <a href="../../README-de.md">Deutsch</a> | <a href="../../README-fr.md">Français</a> | <b>فارسی</b> | <a href="../../README-zh-CN.md">简体中文</a></p>

RIPDPI یک جعبه‌ابزار اندرویدی برای تشخیص و بهینه‌سازی مسیر شبکه است. راهبردهای بسته‌ای قابل پیکربندی را روی دستگاه اعمال می‌کند، می‌تواند به سرورهای رلهٔ تحت کنترل شما متصل شود و تشخیص هر اتصال را اجرا می‌کند تا مشخص شود چرا هر مقصدی شکست می‌خورد یا کیفیتش افت می‌کند. سه قابلیت به‌صورت مستقل یا ترکیبی کار می‌کنند.

## سه ستون

### راهبردهای بستهٔ روی دستگاه

تبدیلات قابل پیکربندی در سطح بسته را روی دستگاه اعمال می‌کند بدون اینکه ترافیک را به یک سرور رله هدایت کند. مسیر اصلی به دسترسی روت نیاز ندارد.

تکنیک‌های پشتیبانی‌شده: قطعه‌بندی و نامرتب‌سازی بخش‌های TCP، تزریق بسته‌های ساختگی، OOB (اشاره‌گر فوری)، قطعه‌بندی رکورد TLS، اولین پرواز TLS ساختگی، تغییر دست‌دهی QUIC، عادی‌سازی اثر انگشت DTLS، تغییر فیلد طول UDP، درج هدر افزونهٔ IPv6، ارسال بسته‌های خام تعریف‌شده با Lua، و نشانگرهای معنایی تطبیقی که موقعیت خود را در برابر `TCP_INFO` زنده حل می‌کنند. زنجیره‌های راهبرد از کرت‌های Rust درون این مخزن ساخته می‌شوند و به هیچ اجرایی خارجی وابسته نیستند.

وقتی رله‌ای پیکربندی نشده باشد، ترافیک مستقیماً از دستگاه خارج می‌شود — تغییرات روی دستگاه تنها چیزی هستند که در مسیر اعمال می‌گردد.

### رلهٔ VPN

ترافیک پراکسی یا VPN محلی را از طریق پروتکل‌های رمزنگاری‌شده به سروری که شما پیکربندی می‌کنید زنجیر می‌کند:

- **VLESS Reality و xHTTP** — پیاده‌سازی بومی Rust، بدون نیاز به محیط اجرای Go
- **WARP، Cloudflare Tunnel، MASQUE**
- **Hysteria2، TUIC v5، ShadowTLS v3، NaiveProxy**
- **AmneziaWG** — WireGuard با مبهم‌سازی دست‌دهی برای شبکه‌های با سانسور بالا
- **WebTunnel، obfs4، Snowflake، مسیر Google Apps Script**

هم حالت پراکسی محلی و هم حالت تغییر مسیر VPN اندرویدی با یا بدون رلهٔ پیکربندی‌شده کار می‌کنند.

### تشخیص

هر مقصد اتصال را به‌صورت جداگانه پویش می‌کند و یک نتیجهٔ نوع‌دار تولید می‌کند:

- `TRANSPARENT_WORKS` — مسیر خام کار می‌کند، نیازی به مداخله نیست
- `OWNED_STACK_ONLY` — فقط از طریق پشتهٔ TLS متعلق به برنامه کار می‌کند
- `NO_DIRECT_SOLUTION` — تغییرات روی دستگاه نمی‌توانند این مقصد را احیا کنند؛ رله لازم است
- `IP_BLOCK_SUSPECT` — مسدودسازی در سطح آدرس IP شناسایی شد

نتایج به ازای اثر انگشت هر شبکه ذخیره می‌شوند و وقتی همان شبکه دوباره دیده شود، به‌صورت خودکار بازپخش می‌گردند. صفحهٔ تشخیص شامل کاوش راهبرد TCP و QUIC در میان ۲۴ نامزد TCP و ۶ نامزد QUIC، شناسایی دستکاری DNS، توصیه‌های تحلیل‌گر DoH/DoT/DNSCrypt/DoQ، و بایگانی‌های تشخیص قابل صادرات است.

## چرا RIPDPI

شبکه‌های مدرن اندرویدی معمولاً اثر انگشت‌گیری L7 (TLS JA3/JA4، QUIC)، QoS تهاجمی روی شبکه‌های همراه و وای‌فای عمومی، ناسازگاری MTU و ECN، و قطع دست‌دهی TLS توسط جعبهٔ میانی را اعمال می‌کنند — این مداخلات باعث می‌شوند برخی اهداف شکست بخورند در حالی که اهداف دیگر روی همان شبکه به‌خوبی کار می‌کنند. یک تنظیم سراسری واحد نمی‌تواند به همهٔ موارد پاسخ دهد.

اصل طراحی RIPDPI: هر مقصد و هر شبکه را جداگانه طبقه‌بندی کن، سبک‌ترین راه‌حلی را که جواب می‌دهد اعمال کن، و آن را به یاد بسپار.

۱. **پاسخ به ازای هر مقصد و هر شبکه** — نه یک سیاست سراسری. تشخیص هر مرجع را دسته‌بندی می‌کند و نتیجه را با کلید هش اثر انگشت شبکه ذخیره می‌کند.
۲. **وقتی شبکه مشکل دارد، مسیر محلی را تغییر بده.** نشانگرهای معنایی، قرارگیری تقسیم تطبیقی، زنجیره‌های بار ساختگی، OOB/disorder، رکوردهای TLS تصادفی‌شده، تنوع اثر انگشت QUIC و DTLS — همگی از کرت‌های Rust درون مخزن مونتاژ می‌شوند.
۳. **اگر مسیر مستقیم تنزل یافت، به رلهٔ تونل‌شده برگرد.** VLESS Reality/xHTTP بومی Rust، به‌علاوهٔ WARP، MASQUE، Hysteria2، TUIC v5، ShadowTLS v3، NaiveProxy، AmneziaWG و Cloudflare Tunnel، اهدافی را که روی دستگاه قابل احیا نیستند مدیریت می‌کنند.
۴. **گزارش‌دهی صادقانه.** نتایج نوع‌دار و قابل‌نمایش‌اند؛ نتایج طبقه‌بند شکست سرکوب نمی‌شوند، بلکه به‌وضوح نشان داده می‌شوند؛ بسته‌های صادرات تشخیصی اطلاعات حساس را ویرایش می‌کنند.

## تصاویر صفحه

<p align="center">
  <img src="../../docs/screenshots/01-hero.png" width="200" alt="صفحهٔ خانهٔ RIPDPI"/>
  &nbsp;
  <img src="../../docs/screenshots/02-no-root.png" width="200" alt="RIPDPI بدون روت"/>
  &nbsp;
  <img src="../../docs/screenshots/03-privacy.png" width="200" alt="صفحهٔ حریم خصوصی RIPDPI"/>
  &nbsp;
  <img src="../../docs/screenshots/04-controls.png" width="200" alt="کنترل‌های RIPDPI"/>
</p>
<p align="center">
  <img src="../../docs/screenshots/05-diagnostics.png" width="200" alt="تشخیص RIPDPI"/>
  &nbsp;
  <img src="../../docs/screenshots/06-more-features.png" width="200" alt="نمای کلی ویژگی‌های RIPDPI"/>
</p>

## ویژگی‌ها

- **حالت پراکسی**: پراکسی SOCKS5 محلی روی پورت localhost پیکربندی‌شده.
- **حالت VPN**: ترافیک دستگاه اندرویدی را از طریق یک پل TUN-به-SOCKS محلی با استفاده از `VpnService` مسیریابی می‌کند.
- **وارد کردن پروفایل**: اسکن و تولید QR، به‌علاوهٔ وارد کردن URI‌های پراکسی از کلیپ‌بورد و اشتراک‌گذاری (`vless://`، `hysteria2://`، `ss://`، `amneziawg://` و بیشتر).
- **اشتراک‌ها**: فرمت‌های اشتراک base64، Clash / Clash.Meta YAML، sing-box JSON و WireGuard-INI با به‌روزرسانی خودکار پس‌زمینه، شناسایی پروفایل‌های تکراری، گروه‌های selector/urltest و تحویل چند‌آینه‌ای.
- **DNS رمزنگاری‌شده**: پشتیبانی از تحلیل‌گرهای DoH، DoT، DNSCrypt و DoQ در مسیرهای مرتبط با VPN.
- **کنترل‌های راهبرد**: خانوادهٔ split/disorder/fake برای TCP، قطعه‌بندی رکورد TLS و پروفایل‌های ساختگی، تنوع دست‌دهی QUIC و DTLS، تنوع فیلد طول UDP، هدرهای افزونهٔ IPv6، `rawsend` در Lua، فیلترهای فعال‌سازی به ازای هر مرحله، کنترل شناسهٔ IPv4 و تزریق OOB.
- **حافظهٔ سیاست به ازای هر شبکه**: نتایج اعتبارسنجی‌شده به ازای هر مرجع که با کلید اثر انگشت شبکه ذخیره می‌شوند؛ هنگام اتصال مجدد به‌صورت خودکار بازپخش می‌گردند.
- **کاوش تطبیقی**: کاوش خودکار راهبرد برای شبکه‌هایی که اولین بار دیده می‌شوند؛ بررسی مجدد `quick_v1` پس‌زمینه هنگام تحویل شبکه.
- **راه‌اندازی مجدد آگاه به تحویل**: ارزیابی مجدد سیاست زنده در گذار میان وای‌فای، همراه و رومینگ.
- **مرورگر RIPDPI**: مرورگر متعلق به برنامه برای اهداف HTTPS که به پشتهٔ TLS متعلق نیاز دارند؛ مسیر مشترک `SecureHttpClient` برای درخواست‌های ایجاد‌شده توسط برنامه.
- **سنجش و گزارش‌های زمان اجرا**: چرخهٔ حیات پراکسی، تصمیمات مسیر، رویدادهای بازگشت DNS، پیشرفت تشخیص و رویدادهای زمان اجرای بومی — به‌صورت تاریخچهٔ درون‌برنامه و صادرات پشتیبانی در دسترس‌اند.
- **کمک‌رسان روت اختیاری**: روی دستگاه‌های روت‌شده، با یک فرایند کمکی ممتاز، عملیات سوکت خام را باز می‌کند (FakeRst، MultiDisorder، قطعه‌بندی IP، SeqOverlap کامل، انتشار بستهٔ خام IPv4/IPv6).

## حالت‌های زمان اجرا

### پراکسی

پراکسی SOCKS5 روی پورت localhost پیکربندی‌شده. برای برنامه‌هایی که از پیکربندی پراکسی پشتیبانی می‌کنند. تغییرات راهبرد و زنجیر شدن از طریق رله روی همهٔ ترافیکی که از طریق پراکسی وارد می‌شود اعمال می‌گردد.

### VPN

از `VpnService` اندروید برای تغییر مسیر ترافیک دستگاه از طریق موتور محلی RIPDPI استفاده می‌کند. وقتی هیچ رله‌ای پیکربندی نشده باشد، حالت VPN تغییرات روی دستگاه را بدون تغییر IP خروجی اعمال می‌کند. وقتی رله پیکربندی شده باشد، ترافیک به‌صورت رمزنگاری‌شده به نقطهٔ پایانی پیکربندی‌شده ارسال می‌شود.

## حریم خصوصی

RIPDPI فراداده‌های عملیاتی را برای تشخیص و عیب‌یابی ذخیره می‌کند: تصاویر لحظه‌ای شبکه، وضعیت تحلیل‌گر، تصمیمات مسیر، نتایج پویش، وضعیت سرویس و رویدادهای زمان اجرای بومی.

RIPDPI این موارد را ذخیره **نمی‌کند**:
- ضبط کامل بسته‌ها
- محتوای ترافیک
- اسرار TLS

حریم خصوصی ترافیک رله به نقطهٔ پایانی رله و پروفایلی که شما پیکربندی می‌کنید بستگی دارد.

## ساخت

پیش‌نیازها: JDK 17، Android SDK، Android NDK `29.0.14206865`، زنجیرهٔ ابزار Rust `1.94.0`، و اهداف Rust اندروید برای ABIهای مورد نیاز.

```bash
git clone https://github.com/po4yka/RIPDPI.git
cd RIPDPI
./gradlew assembleDebug
```

ساخت‌های محلی به‌صورت پیش‌فرض از `arm64-v8a` استفاده می‌کنند (`ripdpi.localNativeAbisDefault`). برای شبیه‌ساز: `./gradlew assembleDebug -Pripdpi.localNativeAbis=x86_64`.

خروجی APK: `app/build/outputs/apk/debug/` و `app/build/outputs/apk/release/`.

## آزمایش

```bash
./gradlew testDebugUnitTest
bash scripts/ci/run-rust-native-checks.sh
bash scripts/ci/run-rust-network-e2e.sh
python3 -m unittest scripts.tests.test_offline_analytics_pipeline
```

جزئیات: [docs/testing.md](../../docs/testing.md)

## مستندات

- [یکپارچه‌سازی بومی و ماژول‌ها](../../docs/native/README.md)
- [زمان اجرای راهبرد بسته](../../docs/packet-strategy-runtime.md)
- [موتور پراکسی و سطح راهبرد](../../docs/native/proxy-engine.md)
- [پل TUN-به-SOCKS](../../docs/native/tunnel.md)
- [عملیات بستهٔ راهبرد و فهرست TLS](../../docs/strategy-pack-operations.md)
- [نمونه‌های پروفایل رله](../../docs/relay-profile-examples.md)
- [یادداشت‌های معماری](../../docs/architecture/README.md)
- [نقشهٔ راه](../../ROADMAP.md)

</div>
