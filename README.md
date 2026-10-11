# GoIt Transit — Bursa (FOSS Moovit Alternative)

A lightweight, personal transit companion for Bursa. No ads, no telemetry, no trackers.

## Features

### Yol Tarifi (Trip Planner)
- Search a place or venue by name; GoIt resolves it to the nearest stop.
- Plans the trip from your nearest stop to the destination (same-route first, one-transfer fallback).
- Uses a baked-in local station/POI database (13,922 records); falls back to Nominatim (OSM) for places not found locally.
- Map shows the route line, A/B endpoints and a stop timeline with a direction toggle.

### Duraklar (Stops)
- Nearby stops sorted by distance with live approaching-bus ETAs and fare prices.
- Stop detail: upcoming arrivals (10 s auto-refresh), full day schedule, and served lines.
- Favorite stops and quick "start journey" from any stop.

### Hatlar (Routes)
- Full city route catalog (409 lines: bus, metro, tram) with search by code.
- Recently used routes pinned to the top (max 15, stored locally).
- Route detail: all stops, live vehicle positions on the map, direction chips, and fare info.
- "Sen burdasın" badge under a stop when you are within ~150 m of it.

### Live Tracking
- Animated bus markers with bearing/rotation, plate labels and speed.
- Journey notifications: a foreground service alerts you with vibration when you get within ~250 m of your destination.

## Privacy
- Zero analytics, ads, or tracker SDKs.
- Data comes directly from Burulaş/Bursakart public REST endpoints.
- The only third-party network call is Nominatim (OSM) for geocoding places missing from the local database.

## Architecture
- **Platform:** Android Native (Kotlin + Jetpack Compose)
- **Maps:** MapLibre Native Android 11.13.5 + Carto Positron / Dark Matter (no Google Play Services)
- **Networking:** OkHttp + kotlinx.serialization
- **Async:** Coroutines & StateFlow (MVVM)
- **Theme:** Material 3 with automatic dark mode

## Building
APKs are built in CI only. Push to `master` (or run the workflow manually) and GitHub Actions produces `app-debug.apk`, uploaded both as a workflow artifact and as the rolling `latest` release.

## Roadmap
- [ ] User-adjustable walking distance limits in Settings (currently Moovit defaults: access <= 800 m, transfer <= 400 m, progressive widening).
- [ ] Offline schedule lookup without network ping.
