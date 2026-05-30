# 🏍️ Velocitron RoadTracker & Telemetry Cockpit
> **The Ultimate Moto-Dynamic Analysis & GPS Route Vault for Enthusiast Riders**

Velocitron merges state-of-the-art **GPS path tracking/curating** with a high-octane **Physics Telemetry Cockpit**, letting motorcycle riders analyze lean angles, cornering gravitational forces, speed histograms, and vertical ascent profiles—all synced seamlessly to an offline-first encrypted Cloud replication vault.

---

## 🌟 Key Epic Features

### 1. 📍 Dual GPS Route Tracking & Manual Drafting
- **Live GPS Logger**: Tap "GO" to start a live sub-second GPS tracking loop with real-time speed, path rendering, and automated sensor logging.
- **Manual Path Builder**: Switch to Drawing Mode to sketch out future custom routes or target paths directly on the map surface, providing immediate distance and topological calculations.
- **Unified Import Hub**: Import third-party `.gpx` tracks from other rider ecosystems seamlessly.

### 2. ⚡ The Telemetry Dynamic Cockpit (`WOW` Effect)
- **Lean Angle Analyzer**: Uses the onboard accelerometer or an advanced wave-modeling simulation engine to analyze motorcycle lean (roll angle) dynamically. Supports maximum left/right lean angle limits and thresholds.
- **Gravitational G-Force Recorder**: Graphs peak cornering forces (lateral and linear G-forces) experienced during aggressive leans.
- **Speed & Lean Histogram**: A synchronized dual-axis time-series visualization illustrating exact throttle-to-bank-angle relationships over each second of the journey.
- **Vertical Ascent Tracker**: Registers altitude fluctuations and displays total vertical meters ascended.

### 3. 🌐 Seamless Hybrid Map & UI
- **Leaflet Interactive Canvas**: Styled custom dark & light map configurations tailored for day and night-time high-contrast navigation.
- **Space-Age Dashboard Aesthetics**: Styled in a premium cosmic slate visual language with screaming-neon accent colors (Electric Cyan, Cockpit Orange, Neon Pink, and Neon Green) to prevent screen fatigue on the open road.

### 4. ☁️ Encrypted Cloud Sync & Local Persistence
- **Room SQLite Engine**: Local, lightning-fast database storage ensuring everything is recorded offline, preventing open-road network dropouts from ruining your logs.
- **Cloud Replication Vault**: Unique, secure numeric Cloud IDs allow syncing full route logs, telemetry histories, and GPX structures with the remote hub.

---

## 🚀 How It Works (Under the Hood)
- **Jetpack Compose**: Multi-threaded, state-reactive, edge-to-edge rendering with custom canvases.
- **Core Sensors**: Subscribes to `Sensor.TYPE_ACCELEROMETER` to calculate Euler roll angles, translating $R = \text{atan2}(a_x, \sqrt{a_y^2 + a_z^2})$ to real degrees on the fly.
- **Leaflet.js + WebView JS Bridge**: Efficient dual-direction JavaScript interface pushing GPX coordinates and real-time location vectors.
- **Optimized Room Migrations**: Fallback-to-destructive migrations with specialized type-converters encoding Moshi timeline arrays to custom database columns.

---

## 🛠️ Build & Development Requirements
- **Android Studio Jellyfish+** or equivalent build tools
- **Kotlin 2.0+**
- **Gradle Kotlin DSL (.kts)**
- **Jetpack Compose / Material 3**

*Velocitron is built for riders who live for the curves. Lean in, open the throttle, and let the telemetry speak!*
