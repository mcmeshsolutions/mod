# mcmesh-mod

**`mcmesh-mod`** is the client and host Minecraft mod for [mcmesh](https://github.com/mcmeshsolutions), enabling serverless shared worlds with automatic host failover, content-addressed chunk streaming, and zero port forwarding on **NeoForge 1.21.1**.

---

## Features

- **Automatic Host Election & Seamless Handover:** Whoever is online with eligible hardware hosts the world directly from their client or standby instance. When the host leaves via the pause menu **`[Hand Over & Leave]`** button (or loses power), the next player takes over from the latest revision without disconnecting guests.
- **Incremental Chunk & Auxiliary File Sync:** Downloads only changed chunks and mod data (FTB Quests, JourneyMap/Xaero waypoints, Create contraptions, Cobblemon party data) from the gateway CAS store before hosting, and streams delta WAL frames while playing.
- **Host Peace-of-Mind HUD & Resource Defense:**
  - Corner HUD widget displaying live TPS, connected guest count, and failover standby readiness.
  - Configurable guest view-distance caps, FPS floor protection, and automated battery-drain handovers (`HandoverAdvisor`).
- **Out-of-the-Box Onboarding:**
  - **First-Launch Wizard (`/meshwizard`):** Guided setup for gateway pairing and invite codes.
  - **Clipboard Invite Auto-Detection:** Copies of `https://<gateway>/join/...` links automatically display a 1-click connect banner on the title screen.
  - **"Host to Mesh" World Converter:** Convert and upload any existing singleplayer world into a shared mesh world directly from the singleplayer selection screen.
- **In-Game Spatial Tools:**
  - **`/meshsince` ("What Changed" 3D Lens):** Renders 3D bounding-box highlights around structures built or mined while you were offline with builder attribution tooltips.
  - **`/meshdigest`:** Summarizes regional construction activity and completed FTB Quests since your last login.
  - **`/meshtimelapse`:** Exports `.mcpr` ReplayMod archives from historical gateway revisions and plays back smooth cinematic camera paths in-game.

---

## Project Structure

| Subproject | Package | Description |
|---|---|---|
| `api/` | `dev.mcmesh.api` | Public API for third-party mods and KubeJS scripts (`MeshApi`, `MeshWorld`, `MeshEvent`, `MeshState`, `MeshRole`) |
| `core/` | `dev.mcmesh.core` | Loader-independent core: HTTP/SSE/WebSocket gateway client, Ed25519 commit signing, CAS manifest diffing, `LoadShed`, `HandoverAdvisor`, `SinceLens`, `TimelapseExporter` |
| `neoforge/` | `dev.mcmesh.neoforge` | NeoForge 1.21.1 mod entrypoints, Mixins, HUD overlays, title-screen onboarding UI, ModGuard, and slash commands |

---

## Building and Testing

Requires **Java 21** (Temurin JDK 21 recommended).

```bash
# Run loader-independent core unit tests
gradle :core:test

# Compile and build the NeoForge mod jar and API jar
gradle :neoforge:build :api:build
```

Built artifacts are placed in:
- `neoforge/build/libs/mcmesh-<version>.jar`
- `api/build/libs/mcmesh-api-<version>.jar`

---

## Public Mod API (`dev.mcmesh.api`)

Other mods can query mesh world state or register lifecycle listeners without depending on internal implementation classes:

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
