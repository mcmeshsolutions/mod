package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorldPathsTest {
    private final Path root = Path.of("/srv/world");

    @Test
    void dimensionsRoundTrip() throws IOException {
        for (String d : new String[] {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "twilightforest:twilight_forest", "a:b/c"}) {
            assertEquals(d, WorldPaths.dimOf(root, WorldPaths.dirOf(root, d)));
        }
        assertEquals(root.resolve("DIM-1"), WorldPaths.dirOf(root, "minecraft:the_nether"));
        assertEquals(null, WorldPaths.dimOf(root, root.resolve("serverconfig")));
    }

    @Test
    void hostileInputsAreRejected() {
        assertThrows(IOException.class, () -> WorldPaths.dirOf(root, "x:../../etc"));
        assertThrows(IOException.class, () -> WorldPaths.dirOf(root, "NOCOLON"));
        assertThrows(IOException.class, () -> WorldPaths.auxPath(root, "../evil"));
        assertThrows(IOException.class, () -> WorldPaths.auxPath(root, "/etc/passwd"));
        assertThrows(IOException.class, () -> WorldPaths.auxPath(root, "a/../../b"));
        assertEquals(root.resolve("data/raids.dat"), assertDoesNotThrow(() -> WorldPaths.auxPath(root, "data/raids.dat")));
    }

    private static <T> T assertDoesNotThrow(org.junit.jupiter.api.function.ThrowingSupplier<T> s) {
        return org.junit.jupiter.api.Assertions.assertDoesNotThrow(s);
    }
}
