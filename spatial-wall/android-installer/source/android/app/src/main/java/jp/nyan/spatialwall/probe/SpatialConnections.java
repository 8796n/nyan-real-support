// SPDX-License-Identifier: MIT
package jp.nyan.spatialwall.probe;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Shared full-app/installer metadata only. Secrets live in SpatialCredentials.
final class SpatialConnections {
    static final String PREFS = "spatial_connections_v2";
    private static final String IDS = "spatial_connections";
    private static final String SELECTED = "spatial_selected";
    private final SharedPreferences prefs;

    static final class Connection {
        final String id, name, host;
        final int port;

        Connection(String id, String name, String host, int port) {
            if (id == null || !id.matches("[a-zA-Z0-9-]{1,64}") || name == null
                    || name.trim().isEmpty() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl)
                    || !WsClient.validHost(host) || port < 1 || port > 65535)
                throw new IllegalArgumentException("Invalid Spatial Wall connection");
            this.id = id; this.name = name; this.host = host; this.port = port;
        }

        String address() { return host + ":" + port; }
        boolean sameEndpoint(Connection other) {
            return other != null && id.equals(other.id) && host.equalsIgnoreCase(other.host)
                    && port == other.port;
        }
    }

    SpatialConnections(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    List<Connection> list() {
        Set<String> ids = prefs.getStringSet(IDS, Set.of());
        if (ids.size() > 64) throw new IllegalStateException("Too many Spatial Wall connections");
        List<Connection> result = new ArrayList<>();
        for (String id : ids) result.add(read(id));
        result.sort(Comparator.comparing(c -> c.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    Connection selected() {
        String id = prefs.getString(SELECTED, "");
        return prefs.getStringSet(IDS, Set.of()).contains(id) ? read(id) : null;
    }

    void save(Connection connection) {
        Set<String> ids = new HashSet<>(prefs.getStringSet(IDS, Set.of()));
        ids.add(connection.id);
        if (ids.size() > 64) throw new IllegalArgumentException("Too many Spatial Wall connections");
        commit(write(prefs.edit(), connection).putStringSet(IDS, ids));
    }

    // Credential persistence must succeed before calling this metadata transaction.
    void saveAndSelect(Connection connection) {
        Set<String> ids = new HashSet<>(prefs.getStringSet(IDS, Set.of()));
        ids.add(connection.id);
        if (ids.size() > 64) throw new IllegalArgumentException("Too many Spatial Wall connections");
        commit(write(prefs.edit(), connection).putStringSet(IDS, ids).putString(SELECTED, connection.id));
    }

    void clear() { commit(prefs.edit().clear()); }

    void select(Connection connection) {
        if (!prefs.getStringSet(IDS, Set.of()).contains(connection.id))
            throw new IllegalArgumentException("Unknown Spatial Wall connection");
        commit(prefs.edit().putString(SELECTED, connection.id));
    }

    void delete(Connection connection) {
        Set<String> ids = new HashSet<>(prefs.getStringSet(IDS, Set.of()));
        ids.remove(connection.id);
        String prefix = prefix(connection.id);
        SharedPreferences.Editor edit = prefs.edit().putStringSet(IDS, ids)
                .remove(prefix + "name").remove(prefix + "host").remove(prefix + "port");
        if (connection.id.equals(prefs.getString(SELECTED, ""))) edit.remove(SELECTED);
        commit(edit);
    }

    private Connection read(String id) {
        String prefix = prefix(id);
        try {
            return new Connection(id, prefs.getString(prefix + "name", ""), prefs.getString(prefix + "host", ""),
                    prefs.getInt(prefix + "port", 0));
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("Invalid saved Spatial Wall connection", failure);
        }
    }

    private static String prefix(String id) { return "spatial." + id + "."; }

    private static SharedPreferences.Editor write(SharedPreferences.Editor edit, Connection connection) {
        String prefix = prefix(connection.id);
        return edit.putString(prefix + "name", connection.name).putString(prefix + "host", connection.host)
                .putInt(prefix + "port", connection.port);
    }

    private static void commit(SharedPreferences.Editor edit) {
        if (!edit.commit()) throw new IllegalStateException("Could not save Spatial Wall connections");
    }
}
