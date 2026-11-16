# تشخیص اجسام با دوربین (Object Detection App)

اپلیکیشن اندروید نوشته‌شده با **Kotlin** که با استفاده از دوربین گوشی و مدل هوش مصنوعی `gpt-6-luna` از طریق API، اجسام موجود در تصویر را تشخیص می‌دهد.

## ویژگی‌ها
- پیش‌نمایش زنده دوربین به صورت **دایره‌ای** (مطابق وایرفریم)
- گرفتن عکس و ارسال به صورت **Base64** به API
- نمایش نتیجه به فارسی (مثال: «یک انسان هست»)
- دکمه **تست مجدد**

## پیش‌نیازها
- Android Studio Hedgehog یا جدیدتر
- JDK 17
- حداقل SDK 24

## نحوه ساخت و اجرا

1. پروژه را در Android Studio باز کنید.
2. Gradle Sync را انجام دهید.
3. یک دستگاه واقعی یا امولاتور با دوربین انتخاب کنید.
4. Run کنید.

## ساخت APK

### روش ۱: از Android Studio
- Build > Build Bundle(s) / APK(s) > Build APK(s)

### روش ۲: با خط فرمان
```bash
./gradlew assembleDebug
```
فایل APK در مسیر زیر ساخته می‌شود:
`app/build/outputs/apk/debug/app-debug.apk`

### روش ۳: با GitHub Actions (پیشنهادی برای خروجی خودکار)

۱. یک ریپازیتوری جدید در GitHub بسازید.
۲. محتویات این پروژه را پوش کنید.
۳. فایل workflow زیر را اضافه کنید:

`.github/workflows/build-apk.yml`

```yaml
name: Build APK

on:
  push:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Setup Android SDK
        uses: android-actions/setup-android@v3
      - name: Grant execute permission for gradlew
        run: chmod +x gradlew
      - name: Build Debug APK
        run: ./gradlew assembleDebug
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/app-debug.apk
```

بعد از push، از تب Actions می‌توانید APK را دانلود کنید.

## نکات مهم
- کلید API به صورت hardcode داخل کد قرار دارد (برای تست). در نسخه واقعی از BuildConfig یا local.properties استفاده کنید.
- مدل ممکن است از فرمت vision استاندارد OpenAI پشتیبانی کند. اگر پاسخ خطا داد، فرمت `content` را بررسی کنید.
- برای کاهش حجم درخواست، تصویر به حداکثر ۸۰۰ پیکسل اسکیل و با کیفیت ۷۵٪ فشرده می‌شود.

## ساختار پروژه
```
app/
  src/main/
    java/.../MainActivity.kt   ← منطق اصلی دوربین + API
    res/layout/activity_main.xml
    AndroidManifest.xml
```

ساخته شده برای درخواست کاربر.
