package com.mod.windlaunch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class WindLaunchConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    boolean switchToMaceEnabled = true;
    boolean autoMoveEnabled = true;

    static WindLaunchConfig load(Path path) {
        try {
            if (!Files.exists(path)) {
                WindLaunchConfig config = new WindLaunchConfig();
                config.save(path);
                return config;
            }

            String json = Files.readString(path);
            WindLaunchConfig config = GSON.fromJson(json, WindLaunchConfig.class);
            return config != null ? config : new WindLaunchConfig();
        } catch (IOException | JsonSyntaxException ignored) {
            return new WindLaunchConfig();
        }
    }

    void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            String json = GSON.toJson(this);

            Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }
}

