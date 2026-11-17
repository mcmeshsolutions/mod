package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JvmMemoryTest {
    @TempDir Path game;

    @Test
    void applyToInstanceConfigSetsMemoryKeysAndKeepsUnknownOnes() throws Exception {
        Path cfg = game.resolve("instance.cfg");
        Files.writeString(cfg, "name=My Pack\nJavaVersion=21\nOverrideMemory=false\nMinMemAlloc=512\nMaxMemAlloc=1024\n");
        JvmMemory.applyToInstanceConfig(cfg, new JvmMemory.Plan(4096, 1024, false, false));
        List<String> out = Files.readAllLines(cfg, StandardCharsets.UTF_8);
        assertTrue(out.contains("name=My Pack"));
        assertTrue(out.contains("JavaVersion=21"));
        assertTrue(out.contains("OverrideMemory=true"));
        assertTrue(out.contains("MaxMemAlloc=4096"));
        assertTrue(out.contains("MinMemAlloc=1024"));
        assertTrue(out.contains("OverrideJavaArgs=true"));
        assertTrue(out.stream().anyMatch(l -> l.startsWith("JvmArgs=-XX:+UseG1GC")));
    }

    @Test
    void applyToInstanceConfigLeavesUserCustomJvmArgsAlone() throws Exception {
        Path cfg = game.resolve("instance.cfg");
        Files.writeString(cfg, "OverrideJavaArgs=true\nJvmArgs=-Dmy.custom=1\n");
        JvmMemory.applyToInstanceConfig(cfg, new JvmMemory.Plan(4096, 1024, false, false));
        List<String> out = Files.readAllLines(cfg, StandardCharsets.UTF_8);
        assertTrue(out.contains("JvmArgs=-Dmy.custom=1"));
        assertTrue(out.contains("MaxMemAlloc=4096"));
    }

    @Test
    void applyToInstanceConfigReEnablesOverrideJavaArgsWhenItWasExplicitlyFalse() throws Exception {
        Path cfg = game.resolve("instance.cfg");
        Files.writeString(cfg, "OverrideJavaArgs=false\nJvmArgs=-Dold.custom=1\n");
        JvmMemory.applyToInstanceConfig(cfg, new JvmMemory.Plan(4096, 1024, false, false));
        List<String> out = Files.readAllLines(cfg, StandardCharsets.UTF_8);
        assertTrue(out.contains("OverrideJavaArgs=true"), "the override flag must flip to true or the rewritten JvmArgs is ignored");
        assertTrue(out.contains("JvmArgs=" + JvmMemory.RECOMMENDED_GC_FLAGS));
    }

    @Test
    void applyToInstanceConfigInsertsMissingKeysInsideGeneralNotPastALaterSection() throws Exception {
        Path cfg = game.resolve("instance.cfg");
        Files.writeString(cfg, "[General]\nname=My Pack\n\n[UI]\nmods_Page\\Columns=\"abc\"\n");
        JvmMemory.applyToInstanceConfig(cfg, new JvmMemory.Plan(4096, 1024, false, false));
        List<String> out = Files.readAllLines(cfg, StandardCharsets.UTF_8);
        int general = out.indexOf("[General]");
        int ui = out.indexOf("[UI]");
        assertTrue(general >= 0 && ui > general);
        for (String key : new String[] {"OverrideMemory=true", "MaxMemAlloc=4096", "MinMemAlloc=1024", "OverrideJavaArgs=true"}) {
            int idx = out.indexOf(key);
            assertTrue(idx > general && idx < ui, key + " should land inside [General], not past [UI]: " + out);
        }
        assertTrue(out.indexOf("mods_Page\\Columns=\"abc\"") > ui, "the [UI] section itself must survive untouched");
    }

    @Test
    void applyToInstanceConfigIsANoOpWhenTheFileIsMissing() {
        JvmMemory.applyToInstanceConfig(game.resolve("instance.cfg"), new JvmMemory.Plan(4096, 1024, false, false));
        assertFalse(Files.exists(game.resolve("instance.cfg")));
    }

    @Test
    void oomInLogsSinceIgnoresReportsOlderThanTheCutoff() throws Exception {
        Path reports = game.resolve("crash-reports");
        Files.createDirectories(reports);
        Path old = reports.resolve("old.txt");
        Files.writeString(old, "java.lang.OutOfMemoryError: Java heap space");
        old.toFile().setLastModified(1000);
        assertFalse(JvmMemory.oomInLogsSince(game, 500_000));
    }

    @Test
    void oomInLogsSinceIgnoresUnrelatedCrashes() throws Exception {
        Path reports = game.resolve("crash-reports");
        Files.createDirectories(reports);
        Path fresh = reports.resolve("fresh.txt");
        Files.writeString(fresh, "some other exception");
        fresh.toFile().setLastModified(System.currentTimeMillis());
        assertFalse(JvmMemory.oomInLogsSince(game, 0));
    }

    @Test
    void oomInLogsSinceFindsAFreshOomReport() throws Exception {
        Path reports = game.resolve("crash-reports");
        Files.createDirectories(reports);
        Path oom = reports.resolve("oom.txt");
        Files.writeString(oom, "Description: The game crashed!\njava.lang.OutOfMemoryError: Java heap space");
        oom.toFile().setLastModified(System.currentTimeMillis());
        assertTrue(JvmMemory.oomInLogsSince(game, 0));
    }

    @Test
    void consumeSafetyValveFlagReadsOnceThenClears() throws Exception {
        Files.createDirectories(game);
        assertFalse(JvmMemory.consumeSafetyValveFlag(game));
        Files.writeString(game.resolve(JvmMemory.SAFETY_VALVE_FLAG), "trip");
        assertTrue(JvmMemory.consumeSafetyValveFlag(game));
        assertFalse(JvmMemory.consumeSafetyValveFlag(game));
    }

    @Test
    void planStepsHeapDownAfterTroubleAndPersistsState() {
        long total = JvmMemory.totalPhysicalMb();
        org.junit.jupiter.api.Assumptions.assumeTrue(total > 0);
        Path upd = game.resolve("mcmesh-update");
        JvmMemory.Plan first = JvmMemory.plan(game, upd, 1_000_000L);
        assertTrue(first.xmxMb() >= 2048);
        try {
            Files.createDirectories(upd);
            Files.writeString(upd.resolve(JvmMemory.SAFETY_VALVE_FLAG), "trip");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        JvmMemory.Plan second = JvmMemory.plan(game, upd, 2_000_000L);
        assertTrue(second.steppedDown());
        assertTrue(second.xmxMb() < first.xmxMb() || first.xmxMb() == 2048);
    }

    @Test
    void planOnlyGrowsAfterSeveralTroubleFreeLaunchesInARow() {
        long total = JvmMemory.totalPhysicalMb();
        org.junit.jupiter.api.Assumptions.assumeTrue(total > 8192, "needs headroom above the baseline cap to observe growth");
        Path upd = game.resolve("mcmesh-update");
        JvmMemory.Plan p = JvmMemory.plan(game, upd, 1_000_000L);
        long baseline = p.xmxMb();
        assertFalse(p.grew());

        for (int i = 0; i < 2; i++) {
            p = JvmMemory.plan(game, upd, 2_000_000L + i);
            assertFalse(p.grew(), "growth should wait for the full stable streak");
            assertEquals(baseline, p.xmxMb());
        }
        p = JvmMemory.plan(game, upd, 3_000_000L);
        assertTrue(p.grew());
        assertEquals(baseline + 512, p.xmxMb());
    }
}
