# K9 Control (Android)

K9 köpek kamerası için uzaktan kontrol birimi: 5"/7" Android telefon, root
gerekmez. `k9-project` deposundaki `claude/no-stab` dalının karşılığıdır.

| Yön | İçerik | Port | Uygulama tarafı |
|---|---|---|---|
| K9 → app | Video H.265 RTP | 5001 | `udpsrc ! rtpjitterbuffer ! rtph265depay ! h265parse ! decodebin3 ! glimagesink` (donanım decoder) |
| K9 → app | Ses Opus RTP | 14551 | `udpsrc ! rtpjitterbuffer ! opusdec ! openslessink` |
| app → K9 | Ses Opus RTP (bas-konuş) | 14552 | `openslessrc ! opusenc ! rtpopuspay ! valve ! udpsink` |
| app → K9 | Kontrol, 8 bayt, 4 Hz | 5005 | `net/ControlSender` (heartbeat; K9 4 s paket almazsa video keser) |
| app → K9 | Ayar JSON, seq+ACK | 5006 | `net/ConfigClient` (Ayarlar → K9 ayarları) |

## Yapı

Eski uygulamayla aynı tasarım dili: 64dp sol nav sütunu (sayfa seçimi),
80dp sağ fonksiyon çubuğu (yanında açılır paneller), üst şerit (chip'ler),
her sayfa ve panel ayrı layout dosyası.

```
res/layout/
  main.xml            video + top_bar + left_bar + right_bar + page_* + dialog_* (include)
  top_bar.xml         link chip (durum, fps), tarih/saat chip (açılır GPS paneli), pil chip (UKB, K9)
  left_bar.xml        VİDEO / KAYIT / DURUM / AYARLAR nav hücreleri, çıkış
  right_bar.xml       bitrate, LED, blind, harita, kayıt, hoparlör, bas-konuş
  dialog_bitrate.xml  dialog_led_blind.xml  dialog_speaker.xml  dialog_record.xml  dialog_map.xml
  page_record.xml     disk kartı + tür filtresi/arama + kart listesi + ExoPlayer (media3) oynatıcı
  page_status.xml     üç kart (BAĞLANTI / K9 AYARLARI / KONTROL BİRİMİ): üstte yuvarlak ikon, hap satırlar, renkli değerler
  page_status.xml     link, video, K9 ayarları, telefon pil/GPS/kiosk
  page_settings.xml   yönetici şifresi kapısı; bağlantı ayarları, K9 uzak ayarları, şifre değiştirme, kiosk
app/src/main/jni/video-native.c      GStreamer: video + ses rx + PTT tx, tek context thread, hata -> backoff ile yeniden kurma
app/src/main/java/com/esetron/k9/
  MainActivity.java                  orkestrasyon: native, ağ, yaşam döngüsü
  ui/LeftBar.java  ui/RightBar.java  ui/TopBar.java      çubuklar
  ui/RecordPage.java  ui/StatusPage.java  ui/SettingsPage.java   sayfalar
  Settings.java                      SharedPreferences (IP, portlar, jitter, kiosk PIN)
  video/NativeBridge.java            JNI
  net/ControlSender.java             8 bayt kontrol paketi, sabit 4 Hz
  net/ConfigClient.java              K9 ayar kanalı (get/set/reset)
  kiosk/                             device owner + LockTask, açılışta başlatma
  capture/CaptureService.java        ekran kaydı (MediaProjection, ön plan servisi) -> Movies/K9
  capture/Screenshot.java            PixelCopy(yalnız video yüzeyi, arayüz yok) -> Pictures/K9
  ui/FullScreenSweetAlertDialog.java SweetAlert (onay/hata/başarı) - sistem çubukları gizli (kiosk)
  map/MapController.java             osmdroid çevrimdışı harita, telefon GPS'i, MGRS
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

Ayarlar sayfası yönetici şifresi ister (varsayılan `palem`, sayfa içinden
değiştirilir; `Settings.java`). Kiosk çıkış PIN'i ayrı (varsayılan `1453`).

Sonrasında uygulama HOME olur, LockTask'ta kilitlenir (Geri/Son uygulamalar
kapalı), açılışta kendisi başlar. Cihaz sahibi değilse ekran sabitleme
(ilk açılışta onay; Geri+Son uygulamalar ile çıkış). Ayarlar → Kiosk'tan
çık (PIN, varsayılan `1453`, `Settings.java`).

Cihaz sahipliğini kaldırmak: Ayarlar → Kiosk'tan çık, sonra
`adb shell dpm remove-active-admin com.esetron.k9/.kiosk.KioskAdminReceiver`
(uygulama `clearDeviceOwnerApp` çağırmadıysa fabrika ayarı gerekir).

## Harita

Eski projedeki egolabs "offline map" ile aynı mekanizma (osmdroid
`OfflineTileProvider`). Tile arşivi (`map.sqlite`, MOBAC "osmdroid SQLite"
çıktısı; `.zip/.mbtiles/.gemf` de olur) şu sırayla aranır:

1. `Android/data/com.esetron.k9/files/osmdroid/` (root ve izin gerekmez; USB ile kopyalayın)
2. `/sdcard/osmdroid/` (eski projenin yeri; Android 11+ scoped storage okumaya izin vermeyebilir)
3. APK içinde `assets/map.sqlite` varsa ilk açılışta 1'e kopyalanır

Arşiv yoksa çevrimiçi OSM kullanılır ve harita açılınca ekranda yol gösterilir;
DURUM sayfası "Harita kaynağı" satırında hangisinin aktif olduğu yazar.
Operatör konumu telefon GPS'inden; K9 konumu telemetri eklenince
`MapController.setK9Position`.

## Kayıtlar

Ekran kaydı `Movies/K9/K9_*.mp4`, ekran görüntüsü `Pictures/K9/K9_*.png`
(MediaStore; USB MTP ile görünür, izin istemez). Ekran kaydı Android'in
"ekranı yakala" onayını ister (sistem gereği, her başlatmada).
