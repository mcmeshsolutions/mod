package dev.mcmesh.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Small, declarative file fix-ups a signed manifest can carry for breaking changes (a renamed config key, a
 * file that moved) without shipping arbitrary code: delete, move/rename, or a regex replace, each restricted
 * to mods/ and config/ by the same path checks PackSwap already applies to put/rm. No scripting engine, no
 * reflection, no process spawning - the signing key can only ever do what these three ops literally say.
 *
 * JDK-only on purpose (no Gson): PackSwap - and therefore this class - is extracted and run as a bare class
 * file with no classpath when it swaps a staged update in after the game exits, so the manifest's JsonObject
 * op gets translated into an {@link Op} by the (Gson-capable) updater before it ever reaches this class.
 */
final class PackPatch {
    private PackPatch() { }

    /** kind is "delete", "move" or "regex"; unused fields for a given kind are null. */
    record Op(String kind, String path, String from, String to, String pattern, String replace) { }

    /** Checked at manifest-verify time, same spirit as PackSwap.badPath: null means the op is safe to queue. */
    static String bad(Path gameDir, Op op) {
        switch (op.kind()) {
            case "delete" -> {
                return PackSwap.badPath(gameDir, op.path());
            }
            case "move" -> {
                String a = PackSwap.badPath(gameDir, op.from());
                String b = PackSwap.badPath(gameDir, op.to());
                return a != null ? a : b;
            }
            case "regex" -> {
                String p = PackSwap.badPath(gameDir, op.path());
                if (p != null) {
                    return p;
                }
                if (op.pattern() == null || op.replace() == null) {
                    return "regex op needs pattern and replace";
                }
                try {
                    Pattern.compile(op.pattern());
                } catch (PatternSyntaxException e) {
                    return "bad regex: " + e.getMessage();
                }
                return null;
            }
            default -> {
                return "unknown op: " + op.kind();
            }
        }
    }

    /** Applies one op. */
    static void apply(Path gameDir, Op op) throws IOException {
        Path game = gameDir.toAbsolutePath().normalize();
        switch (op.kind()) {
            case "delete" -> Files.deleteIfExists(game.resolve(op.path()));
            case "move" -> {
                Path src = game.resolve(op.from());
                if (Files.isRegularFile(src)) {
                    Files.createDirectories(game.resolve(op.to()).getParent());
                    Files.move(src, game.resolve(op.to()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            case "regex" -> {
                Path target = game.resolve(op.path());
                if (!Files.isRegularFile(target)) {
                    return;
                }
                String text = Files.readString(target, StandardCharsets.UTF_8);
                String patched = Pattern.compile(op.pattern(), Pattern.MULTILINE).matcher(text).replaceAll(op.replace());
                if (!patched.equals(text)) {
                    Files.writeString(target, patched, StandardCharsets.UTF_8);
                }
            }
            default -> throw new IOException("unknown op: " + op.kind());
        }
    }

    /** One ready-file line (no "patch\t" prefix): kind then each field base64'd, tab-joined, empty for unused fields. */
    static String encode(Op op) {
        return String.join("\t", op.kind(), b64(op.path()), b64(op.from()), b64(op.to()), b64(op.pattern()), b64(op.replace()));
    }

    static Op decode(String line) throws IOException {
        String[] f = line.split("\t", -1);
        if (f.length != 6) {
            throw new IOException("bad patch line");
        }
        return new Op(f[0], unb64(f[1]), unb64(f[2]), unb64(f[3]), unb64(f[4]), unb64(f[5]));
    }

    private static String b64(String s) {
        return s == null ? "" : Base64.getEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        return s.isEmpty() ? null : new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }
}
