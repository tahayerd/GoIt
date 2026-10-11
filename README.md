# GoIt Transit - Bursa (FOSS Moovit Alternative)

Reklamlardan, telemetriden ve izleyicilerden tamamen arındırılmış, hafif ve kişisel kullanıma özel Bursa toplu taşıma uygulaması.

## Özellikler
- **Sıfır İzleyici / Sıfır Reklam:** Hiçbir analitik, telemetri veya reklam SDK'sı barındırmaz.
- **Doğrudan Veri Kaynağı:** Bursa Ulaşım (Burulaş / Bursakart) doğrudan REST uç noktaları üzerinden çalışır.
- **Canlı Otobüs Takibi:** Seçilen hattaki tüm araçların anlık GPS koordinatları, plakaları, seyir hızları ve yön bilgileri.
- **Hat ve Durak Arama:** Anlık arama (örn. `B16A`, `Heykel`, `D8A`).
- **Açık Kaynak Harita:** MapLibre Native Android + Carto Positron / OpenStreetMap katmanları (Google Play Services bağımlılığı yoktur).

## Mimari
- **Platform:** Android Native (Kotlin + Jetpack Compose)
- **Ağ:** OkHttp + Kotlinx Serialization
- **Asenkron Yapı:** Coroutines & StateFlow (MVVM)

## Derleme (Build)
Projeyi derlemek için:
```bash
./gradlew assembleDebug
```
veya doğrudan Android Studio ile açıp cihazınıza yükleyebilirsiniz.
