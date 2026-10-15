# K9 Control (Android)

K9 köpek kamerası için uzaktan kontrol birimi: 5"/7" Android telefon, root
gerekmez. `k9-project` deposundaki `claude/no-stab` dalının karşılığıdır.

| Yön | İçerik | Port | Uygulama tarafı |
|---|---|---|---|
| K9 → app | Video H.265 RTP | 5001 | `udpsrc ! rtpjitterbuffer ! rtph265depay ! h265parse ! amcviddec-* ! glimagesink` |
| K9 → app | Ses Opus RTP | 14551 | `udpsrc ! rtpjitterbuffer ! opusdec ! openslessink` |
| app → K9 | Ses Opus RTP (bas-konuş) | 14552 | `openslessrc ! opusenc ! rtpopuspay ! valve ! udpsink` |
| app → K9 | Kontrol, 8 bayt, 4 Hz | 5005 | `net/ControlSender` (heartbeat; K9 4 s paket almazsa video keser) |
| app → K9 | Ayar JSON, seq+ACK | 5006 | `net/ConfigClient` (Ayarlar → K9 ayarları) |

## Yapı

```
app/src/main/jni/video-native.c      GStreamer: video + ses rx + PTT tx, tek context thread, hata -> backoff ile yeniden kurma
app/src/main/java/com/esetron/k9/
  MainActivity.java                  tek ekran: video, üst bilgi, sol kontrol, sağ işlem
  Settings.java                      SharedPreferences (IP, portlar, jitter, kiosk PIN)
  video/NativeBridge.java            JNI
  net/ControlSender.java             8 bayt kontrol paketi, sabit 4 Hz
  net/ConfigClient.java              K9 ayar kanalı (get/set/reset)
  kiosk/                             device owner + LockTask, açılışta başlatma
  capture/CaptureService.java        ekran kaydı (MediaProjection, ön plan servisi) -> Movies/K9
  capture/Screenshot.java            PixelCopy(video) + UI -> Pictures/K9
  map/MapController.java             osmdroid çevrimdışı harita, telefon GPS'i, MGRS
  ui/SettingsDialog.java             ayarlar
```

## Derleme

Gereksinimler: Android Studio (AGP 7.4.2, Gradle 7.5), NDK 25.0.8775105,
GStreamer Android universal 1.26.x.

```bash
# GStreamer Android binaries
# https://gstreamer.freedesktop.org/data/pkg/android/  -> gstreamer-1.0-android-universal-1.26.11.tar.xz
mkdir -p /opt/gstreamer-android && tar -xf gstreamer-1.0-android-universal-1.26.11.tar.xz -C /opt/gstreamer-android
# gradle.properties: gstAndroidRoot=/opt/gstreamer-android   (veya GSTREAMER_ROOT_ANDROID ortam değişkeni)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`org/freedesktop/gstreamer/GStreamer.java` ndk-build tarafından üretilir
(`.gitignore`'da). İlk derlemede `gradle wrapper` jar'ı yoksa
`gradle wrapper --gradle-version 7.5` ile üretin.

## Kiosk (root'suz)

Tam kiosk için telefon fabrika ayarlarında, hesap eklenmeden:

```bash
adb install -r app-debug.apk
adb shell dpm set-device-owner com.esetron.k9/.kiosk.KioskAdminReceiver
```

Sonrasında uygulama HOME olur, LockTask'ta kilitlenir (Geri/Son uygulamalar
kapalı), açılışta kendisi başlar. Cihaz sahibi değilse ekran sabitleme
(ilk açılışta onay; Geri+Son uygulamalar ile çıkış). Ayarlar → Kiosk'tan
çık (PIN, varsayılan `1453`, `Settings.java`).

Cihaz sahipliğini kaldırmak: Ayarlar → Kiosk'tan çık, sonra
`adb shell dpm remove-active-admin com.esetron.k9/.kiosk.KioskAdminReceiver`
(uygulama `clearDeviceOwnerApp` çağırmadıysa fabrika ayarı gerekir).

## Harita

Çevrimdışı tile arşivlerini (`.sqlite`/`.zip`/`.mbtiles`, osmdroid formatı,
ör. MOBAC "osmdroid SQLite" çıktısı) USB ile şuraya kopyalayın:

```
Android/data/com.esetron.k9/files/osmdroid/
```

Arşiv yoksa ağ varsa çevrimiçi OSM kullanılır. Operatör konumu telefon
GPS'inden; K9 konumu telemetri eklenince `MapController.setK9Position`.

## Kayıtlar

Ekran kaydı `Movies/K9/K9_*.mp4`, ekran görüntüsü `Pictures/K9/K9_*.png`
(MediaStore; USB MTP ile görünür, izin istemez). Ekran kaydı Android'in
"ekranı yakala" onayını ister (sistem gereği, her başlatmada).
