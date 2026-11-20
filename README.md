<div align="center">

# mcmesh-mod

**Minecraft client and host mod for zero-port-forwarding, serverless shared worlds with automatic host failover.**

[![CI](https://github.com/mcmeshsolutions/mod/actions/workflows/ci.yml/badge.svg)](https://github.com/mcmeshsolutions/mod/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62B47A.svg)](https://www.minecraft.net)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1-orange.svg)](https://neoforged.net)
[![Java](https://img.shields.io/badge/Java-21-blue.svg?logo=openjdk)](https://adoptium.net)

[Features](#features) | [Project Structure](#project-structure) | [Building & Testing](#building-and-testing) | [Mod API](#public-mod-api-devmcmeshapi) | [In-Game Tools](#in-game-commands--tools)

</div>

---

## Overview

`mcmesh-mod` turns any player's Minecraft client into an elastic, failover-ready world host backed by [gatewayd](https://github.com/mcmeshsolutions/gatewayd).

When a world starts, the mod syncs only changed chunks and auxiliary mod data from the gateway's content-addressed store, streams delta commits while hosting, and hands off authority to the next online peer when the host leaves, without kicking connected guests or requiring router port forwarding.

## Features

- **Automatic Host Election & Failover:** Eligible peers automatically take over hosting when the current host disconnects or clicks **`[Hand Over & Leave]`** in the pause menu. Guests reconnect seamlessly to the same gateway address.
- **Incremental CAS Chunk & Mod Data Sync:** Downloads only missing SHA-256 chunk blobs and mod state (FTB Quests, JourneyMap/Xaero waypoints, Create contraptions, Cobblemon party data) before hosting, then streams delta WAL frames during gameplay.
- **Host Resource Defense & Peace-of-Mind HUD:**
  - Corner HUD showing live TPS, guest count, and standby readiness.
  - Configurable guest view-distance caps, FPS floor protection, and automated battery-drain handover (`HandoverAdvisor`).
- **Frictionless Onboarding:**
  - **First-Launch Wizard (`/meshwizard`):** Guided gateway pairing and invite code redemption.
  - **Clipboard Invite Auto-Detection:** Copying a `https://<gateway>/join/...` link surfaces a 1-click connect banner on the title screen.
  - **Singleplayer Converter:** Upload and convert any existing singleplayer world into a shared mesh world from the world selection screen.
- **In-Game Spatial & Replay Tools:**
  - **`/meshsince` ("What Changed" 3D Lens):** Renders 3D bounding-box overlays around structures built or mined while you were offline, with builder attribution tooltips.
  - **`/meshdigest`:** Summarizes regional build activity and completed FTB Quests since your last session.
  - **`/meshtimelapse`:** Exports `.mcpr` ReplayMod archives from historical gateway revisions.

## Project Structure

| Module | Package | Description |
|---|---|---|
| `api/` | `dev.mcmesh.api` | Public API for third-party mods and KubeJS (`MeshApi`, `MeshWorld`, `MeshEvent`, `MeshState`, `MeshRole`) |
| `core/` | `dev.mcmesh.core` | Loader-independent core: gateway HTTP/SSE/WS client, Ed25519 commit signing, CAS diffing, `LoadShed`, `HandoverAdvisor`, `SinceLens`, `TimelapseExporter` |
| `neoforge/` | `dev.mcmesh.neoforge` | NeoForge 1.21.1 entrypoints, Mixins, HUD overlays, title-screen UI, ModGuard, and slash commands |

## Building and Testing

Requires **Java 21** (Eclipse Temurin JDK 21 recommended) and Gradle.

```bash
# Run loader-independent core and API unit tests
gradle :core:test :api:test

# Build the NeoForge mod jar, API jar, and run all checks
gradle :neoforge:build :core:test :api:test
```

Build outputs:
- `neoforge/build/libs/mcmesh-0.3.0.jar`
- `api/build/libs/mcmesh-api-0.3.0.jar`

## Public Mod API (`dev.mcmesh.api`)

Third-party mods can query mesh state or subscribe to lifecycle events with zero hard runtime dependency when `mcmesh` is absent. See [`mod-api.md`](https://github.com/mcmeshsolutions/docs/blob/main/mod-api.md) and [`mcmesh_compat.schema.json`](https://github.com/mcmeshsolutions/docs/blob/main/mcmesh_compat.schema.json) for full documentation.

```java
import dev.mcmesh.api.MeshApi;
import dev.mcmesh.api.MeshEvent;

MeshApi.get().ifPresent(mesh -> {
    mesh.currentWorld().ifPresent(world -> {
        System.out.println("World: " + world.name() + " rev=" + world.revisionId());
    });

    mesh.subscribe(MeshEvent.RevisionCommitted.class, ev -> {
        System.out.println("Committed revision: " + ev.revisionId());
    });
});
```

## In-Game Commands & Tools

| Command / Keybind | Purpose |
|---|---|
| `/meshwizard` | Launch the guided gateway pairing and setup wizard |
| `/meshsince` | Toggle 3D bounding-box diff lens for offline world changes |
| `/meshdigest` | View regional build and FTB Quests summary since last login |
| `/meshtimelapse` | Export `.mcpr` ReplayMod archive from revision history |
| `F9` / `F10` | Operator spatial overlay and full-screen island/halo map |

## Ecosystem

| Repository | Description |
|---|---|
| [mcmeshsolutions/gatewayd](https://github.com/mcmeshsolutions/gatewayd) | Coordination gateway daemon, CAS store, and relay |
| [mcmeshsolutions/mod](https://github.com/mcmeshsolutions/mod) | Minecraft NeoForge 1.21.1 client and host mod (this repo) |
| [mcmeshsolutions/web](https://github.com/mcmeshsolutions/web) | WebUI panel, 3D voxel viewer, and spatial inspectors |
| [mcmeshsolutions/deploy](https://github.com/mcmeshsolutions/deploy) | Docker Compose, systemd, Caddy TLS, and homelab templates |
| [mcmeshsolutions/docs](https://github.com/mcmeshsolutions/docs) | OpenAPI 3.1.0 spec, JSON schemas, and mod API docs |

---

<div align="center">
<sub>Licensed under the <a href="https://opensource.org/licenses/MIT">MIT License</a>. Not affiliated with Mojang or Microsoft.</sub>
</div>
