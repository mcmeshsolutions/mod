package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerCommandTest {
    private static final List<String> CLIENT = List.of("-Xms512m", "-Xmx6G", "-XstartOnFirstThread", "-Djava.library.path=/natives", "-p", "/libs/a.jar:/libs/b.jar",
            "--add-modules", "ALL-MODULE-PATH", "cpw.mods.bootstraplauncher.BootstrapLauncher", "--username", "alice", "--version", "neoforge-21.1.266", "--gameDir",
            "/home/a/.minecraft", "--assetsDir", "/assets", "--assetIndex", "17", "--uuid", "u", "--accessToken", "secret", "--userType", "msa", "--width", "854",
            "--height", "480", "--demo", "--launchTarget", "neoforgeclient", "--fml.neoForgeVersion", "21.1.266", "--fml.mcVersion", "1.21.1");

    @Test
    void aClientCommandLineBecomesAServerOne() {
        List<String> out = WorkerCommand.rewrite(CLIENT, "/home/a/worker", "3G", List.of("-Dmcmesh.worker=true"));
        assertEquals("-Xmx3G", out.get(0));
        assertTrue(out.contains("-Dmcmesh.worker=true"));
        assertTrue(out.contains("-Djava.library.path=/natives"));
        assertTrue(out.contains("cpw.mods.bootstraplauncher.BootstrapLauncher"));
        assertEquals("/home/a/worker", out.get(out.indexOf("--gameDir") + 1));
        assertEquals("neoforgeserver", out.get(out.indexOf("--launchTarget") + 1));
        assertEquals("21.1.266", out.get(out.indexOf("--fml.neoForgeVersion") + 1));
        assertEquals("nogui", out.get(out.size() - 1));
    }

    @Test
    void nothingThatIsAboutTheAccountOrTheWindowSurvives() {
        List<String> out = WorkerCommand.rewrite(CLIENT, "/w", "3G", List.of());
        for (String gone : List.of("--username", "alice", "--accessToken", "secret", "--uuid", "--assetsDir", "--width", "--height", "--demo", "-Xms512m", "-Xmx6G", "-XstartOnFirstThread", "--version")) {
            assertFalse(out.contains(gone), gone);
        }
    }

    @Test
    void aCommandLineWithoutALaunchTargetIsNotOneWeCanUse() {
        assertNull(WorkerCommand.rewrite(List.of("-jar", "x.jar"), "/w", "3G", List.of()));
    }

    @Test
    void aServerOrDevTargetKeepsItsName() {
        List<String> out = WorkerCommand.rewrite(List.of("Main", "--launchTarget", "forgeserverdev", "--gameDir", "."), "/w", "2G", List.of());
        assertEquals("forgeserverdev", out.get(out.indexOf("--launchTarget") + 1));
    }
}
