// SPDX-License-Identifier: MIT
package jp.nyan.spatialwall.probe;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SpatialConnectionsTest {
    private static final class Preferences {
        final Map<String, Object> values = new HashMap<>();
        boolean failWrites;
        final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("edit")) return editor();
                    if (method.getName().equals("contains")) return values.containsKey(args[0]);
                    if (method.getName().startsWith("get")) return values.getOrDefault(args[0], args[1]);
                    throw new AssertionError(method.getName());
                });

        private SharedPreferences.Editor editor() {
            Map<String, Object> changes = new HashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        if (method.getName().startsWith("put")) changes.put((String) args[0], args[1]);
                        else if (method.getName().equals("remove")) changes.put((String) args[0], null);
                        else if (method.getName().equals("commit")) {
                            if (failWrites) return false;
                            changes.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); });
                            return true;
                        } else throw new AssertionError(method.getName());
                        return proxy;
                    });
        }
    }

    private static SpatialConnections.Connection pc(String id, String host) {
        return new SpatialConnections.Connection(id, id, host, 8797);
    }

    @Test public void metadataAndSelectionCommitTogetherWithoutSecrets() {
        Preferences preferences = new Preferences();
        SpatialConnections connections = new SpatialConnections(preferences.prefs);
        connections.saveAndSelect(pc("home", "home.local"));
        connections.save(pc("work", "192.168.1.10"));
        assertEquals("home", connections.selected().id);
        assertEquals(2, connections.list().size());
        assertTrue(preferences.values.keySet().stream().noneMatch(key -> key.contains("token") || key.contains("secret")));
        connections.select(pc("work", "192.168.1.10"));
        assertEquals("work", new SpatialConnections(preferences.prefs).selected().id);
        assertEquals("spatial_connections_v2", SpatialConnections.PREFS);
    }

    @Test public void failedSaveDoesNotChangeSelectionOrPublishNewProfile() {
        Preferences preferences = new Preferences();
        SpatialConnections connections = new SpatialConnections(preferences.prefs);
        connections.saveAndSelect(pc("home", "home.local"));
        preferences.failWrites = true;
        assertThrows(IllegalStateException.class, () -> connections.saveAndSelect(pc("work", "work.local")));
        assertEquals("home", connections.selected().id);
        assertEquals(1, connections.list().size());
    }

    @Test public void corruptStoredMetadataIsAStorageFailureWithoutAutomaticRepair() {
        Preferences preferences = new Preferences();
        SpatialConnections connections = new SpatialConnections(preferences.prefs);
        connections.saveAndSelect(pc("home", "home.local"));
        preferences.values.put("spatial.home.host", "bad/host");
        assertTrue(assertThrows(IllegalStateException.class, connections::selected).getCause()
                instanceof IllegalArgumentException);
        assertThrows(IllegalStateException.class, connections::list);
        assertEquals("bad/host", preferences.values.get("spatial.home.host"));
        assertEquals("home", preferences.values.get("spatial_selected"));
        assertThrows(IllegalArgumentException.class, () -> pc("input", "bad/host"));
    }

    @Test public void sameAddressDoesNotMergeDifferentPcIdentities() {
        SpatialConnections connections = new SpatialConnections(new Preferences().prefs);
        var home = pc("home", "192.168.1.10");
        var work = pc("work", "192.168.1.10");
        connections.saveAndSelect(home); connections.save(work);
        assertFalse(home.sameEndpoint(work));
        assertTrue(home.sameEndpoint(new SpatialConnections.Connection("home", "Renamed", home.host, home.port)));
        assertFalse(home.sameEndpoint(pc("home", "new.local")));
        connections.delete(work); assertEquals("home", connections.selected().id);
        connections.delete(home); assertNull(connections.selected()); assertTrue(connections.list().isEmpty());
    }

    @Test public void invalidOrExcessMetadataCannotReplaceSelection() {
        SpatialConnections connections = new SpatialConnections(new Preferences().prefs);
        var home = pc("home", "home.local"); connections.saveAndSelect(home);
        for (String host : new String[]{"evil/path", "bad\r\nhost", ""})
            assertThrows(IllegalArgumentException.class, () -> pc("bad", host));
        for (int port : new int[]{0, 65536})
            assertThrows(IllegalArgumentException.class, () -> new SpatialConnections.Connection("bad", "Bad", home.host, port));
        for (int i = 0; i < 63; ++i) connections.save(pc("pc-" + i, "pc.local"));
        assertThrows(IllegalArgumentException.class, () -> connections.saveAndSelect(pc("extra", "pc.local")));
        assertEquals("home", connections.selected().id);
    }
}
