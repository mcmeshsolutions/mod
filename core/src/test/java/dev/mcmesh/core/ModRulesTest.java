package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModRulesTest {
    private static final Set<String> BUILTIN = Set.of("minecraft", "mcmesh");
    private static final Map<String, String> HAVE = Map.of("minecraft", "m", "mcmesh", "x", "create", "aa", "jei", "bb");

    private static JsonObject doc(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void noRulesMeansUnrestricted() {
        assertNull(ModRules.effective(null, null));
        assertNull(ModRules.effective(doc("{}"), doc("{\"allow\":[]}")));
        assertNull(ModRules.check(null, HAVE, BUILTIN).problem());
    }

    @Test
    void emptyAllowArrayIsNoRestrictionEvenWithExtraMods() {
        JsonObject empty = doc("{\"allow\":[],\"ignore\":[]}");
        assertNull(ModRules.effective(empty, empty));
        assertNull(ModRules.check(empty, HAVE, BUILTIN).problem());
    }

    @Test
    void packRulesApplyWhenTheWorldHasNone() {
        JsonObject pack = doc("{\"allow\":[{\"id\":\"create\",\"hash\":\"AA\"}],\"ignore\":[\"jei\"]}");
        assertSame(pack, ModRules.effective(doc("{}"), pack));
        assertSame(pack, ModRules.effective(null, pack));
        assertNull(ModRules.check(pack, HAVE, BUILTIN).problem());
    }

    @Test
    void worldRulesWinAndAreNotLoosenedByThePack() {
        JsonObject world = doc("{\"allow\":[{\"id\":\"create\",\"hash\":\"aa\"}]}");
        JsonObject pack = doc("{\"allow\":[{\"id\":\"create\",\"hash\":\"aa\"}],\"ignore\":[\"jei\"]}");
        JsonObject eff = ModRules.effective(world, pack);
        assertSame(world, eff);
        ModRules.Result r = ModRules.check(eff, HAVE, BUILTIN);
        assertEquals("Extra mod not allowed: jei", r.problem());
        assertEquals(1, r.offending().size());
        assertArrayEquals(new String[] {"jei", "bb"}, r.offending().get(0));
    }

    @Test
    void missingAndDifferentMods() {
        JsonObject rules = doc("{\"allow\":[{\"id\":\"create\",\"hash\":\"zz\"},{\"id\":\"jei\",\"hash\":\"\"},{\"id\":\"farmers\",\"hash\":\"cc\"}]}");
        ModRules.Result r = ModRules.check(rules, HAVE, BUILTIN);
        assertEquals("Different version of mod: create", r.problem());
        assertArrayEquals(new String[] {"create", "aa"}, r.offending().get(0));
        assertEquals("Missing mod: farmers", ModRules.check(doc("{\"allow\":[{\"id\":\"farmers\",\"hash\":\"cc\"}],\"ignore\":[\"create\",\"jei\"]}"), HAVE, BUILTIN).problem());
    }
}
