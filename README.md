# GoIt Transit — Bursa (Modern FOSS Transit App)

A lightweight, personal transit companion for Bursa. No ads, no telemetry, no trackers.

## Features

### Yol Tarifi (Trip Planner)
- Search places, POIs, or stations; GoIt resolves them using the baked-in station database (13,922 records) and falls back to Nominatim (OSM).
- **Google Maps Integration:** Share or paste Google Maps links (short links, embed URLs, CID hex coordinates) directly into GoIt to set trip endpoints.
- **Alternative Routes & Sorting:** Sort route alternatives by **Önerilen** (Recommended), **En Hızlı** (Fastest), **Az Yürüme** (Least Walking), or **Az Aktarma** (Least Transfers).
- **Realistic Durations & Live ETAs:** Distance-based bus run times (~22 km/h + dwell times), live board ETAs (`~X dk sonra`), and calculated arrival times.
- **Kayıtlı Yerler & Geçmiş:** One-tap Home and Work shortcuts, custom bookmarked places, and recent journey history.

### Canlı Yolculuk Modu (Live Directions)
- Tap **"Yolculuğu Başlat"** on any selected plan to launch full turn-by-turn transit guidance.
- **In-App HUD Overlay & Foreground Service (fully automatic transit companion):**
  - **Yürüyüş (Walk):** Live walking distance and time countdown to board stop.
  - **Bekleme (Wait):** Live bus arrival ETA polling; boarding is **auto-detected** from GPS speed and distance from stop (no manual confirmation).
  - **Araçta (Ride):** One-way stop countdown ("N durak kaldı"), expandable stop list (passed, current, upcoming) with remaining duration estimation.
  - **İniş Uyarısı:** "Get ready" high-priority vibration alarm when approaching the alight stop (<= 250 m or <= 1 stop remaining), with red imminent-alight banner.
  - **Varış (Arrival):** Celebration alert upon reaching the final destination.

### Duraklar (Stops)
- Nearby stops sorted by distance with live approaching-bus ETAs, fares, and vehicle amenity indicators (AC, wheelchair access).
- Stop detail: upcoming arrivals (10 s auto-refresh), full day schedule, and served lines.
- Guaranteed single nearest-stop **"Sen burdasın"** badge.

### Hatlar (Routes)
- Full city route catalog (409 lines: bus, metro, tram) with search by code.
- Recently used routes pinned to the top (max 15, stored locally).
- Route detail: live vehicle positions on the map with animated bus markers, speed, plate numbers, and direction filters.

### Ayarlar & Özelleştirme (Settings)
- **Tema Seçimi:** System / Light / Dark / Pure AMOLED (`#000000`).
- Configurable maximum walking distance limits:
  - **Erişim Yürüyüşü:** 400 m, 600 m, 800 m, 1200 m
  - **Aktarma Yürüyüşü:** 200 m, 300 m, 400 m, 600 m
- Saved places manager and onboarding tour reset.

### İlk Açılış Rehberi (Onboarding)
- 5-step interactive tour explaining tabs, features, and managing location & notification permissions (clean professional UI).

### 1.0 Release Highlights
- **Fluid Gestures & Responsive Panels:** True smooth height animation via `animateContentSize` on cards, unified vertical drag-and-tap gestures with tap/drag isolation to prevent flickering, and draggable panel areas excluding scrollable bus and stop lists.
- **Zero-Lag Map Touch Interaction:** Instantaneous 0 ms detection of finger down/up on map canvas (`FrameLayout` touch interception), gracefully minimizing overlay cards (`scale: 0.88`, `alpha: 0.35`) and gently shrinking HUD (`scale: 0.95`, `alpha: 0.88`), with fluid spring restoration on release.
- **Snappy Tab Switching:** Fast and responsive spring tab transitions (`stiffness: 1400`) between Yol Tarifi, Duraklar, and Hatlar.
- **Offline Schedule Pipeline & Cache:** Daily automated timetable crawl combined with on-device chunked offline caching (128 buckets, 24h background sync on Wi-Fi).
- **Road-Snapped Routes & OSRM Foot Routing:** Real street-following foot legs via FOSSGIS foot routing, clipped bus polyline shapes, and directional snap-to-polyline vehicle rendering.
- **Expandable Bus Detail Panel:** Interactive two-column view featuring a vertical stop timeline with live bus positioning, passenger fare calculation (`TAM`, `İNDİRİMLİ`, `ÖĞRENCİ`), upcoming departures, and estimated arrival time.
- **Continuous Refined Location:** Low-overhead continuous GPS location tracking with a redesigned multi-ring location marker.
- **Dual-Tone Route Visualization:** Dual-tone route styling (pastel baseline, vibrant passed path, muted remaining segment) with subtle stop indicators.
- **Compact Origin/Destination Picker:** Single-line search bar with quick current location shortcut and most-visited destination chips.
- **Stable Keystore Signing:** Reproducible fixed debug keystore in CI matching the local development key (`SHA-1: DE:D8:68:61:54:FC:2C:C0:C1:70:B8:7B:B5:F3:D6:A0:C7:5A:B2:07`), enabling seamless direct updates without uninstalling.

## Privacy
- Zero analytics, ads, or tracker SDKs.
- Transit data comes directly from Burulaş/Bursakart public REST endpoints.
- The only optional external lookups are Nominatim (OSM) for venues missing from the local database and FOSSGIS for foot routing.

## Architecture
- **Platform:** Android Native (Kotlin + Jetpack Compose)
- **Maps:** MapLibre Native Android 11.13.5 + Carto Positron / Dark Matter (no Google Play Services)
- **Networking:** OkHttp + kotlinx.serialization
- **Async:** Coroutines & StateFlow (MVVM)
- **Theme:** Material 3 with Light, Dark, and Pure AMOLED options
- **16 KB Page-Size:** Fully verified native ELF alignment (AGP 8.5.2, build-tools 35.0.0, zipalign -P 16)

## Building
APKs are built in CI only. Push to `master` (or run the workflow manually) and GitHub Actions produces `app-debug.apk`, uploaded both as a workflow artifact and as the rolling `latest` release.

## Roadmap
- [ ] Timetable-based departure planning ("Şu saatte var / kalk").
- [ ] Favorite stop approach notifications (`ApproachWatchService`).
- [ ] Home screen widget for quick stop arrivals (`GoItWidgetProvider`).
