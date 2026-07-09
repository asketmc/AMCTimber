package com.asketmc.timber;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("XpBridge - disabled mode is a hard no-op")
class XpBridgeTest {

    @Test
    @Tag("P0")
    void grantDoesNotTouchPlayerOrSchedulerWhenDisabled() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("xp.enabled", false);
        yml.set("xp.mode", "none");
        yml.set("xp.command", "skillsadmin xp %player% %skill% %amount%");

        XpBridge bridge = new XpBridge(Logger.getAnonymousLogger(), null, new TimberConfig(yml));
        Player player = throwingPlayerProxy();

        assertFalse(bridge.present());
        assertDoesNotThrow(() -> bridge.grant(player, 50));
    }

    @Test
    void presentRequiresEnabledCommandModeAndNonBlankCommand() {
        assertFalse(new XpBridge(Logger.getAnonymousLogger(), null, new TimberConfig(new YamlConfiguration())).present());

        YamlConfiguration enabled = new YamlConfiguration();
        enabled.set("xp.enabled", true);
        assertTrue(new XpBridge(Logger.getAnonymousLogger(), null, new TimberConfig(enabled)).present());

        YamlConfiguration noCommand = new YamlConfiguration();
        noCommand.set("xp.command", " ");
        assertFalse(new XpBridge(Logger.getAnonymousLogger(), null, new TimberConfig(noCommand)).present());

        YamlConfiguration noneMode = new YamlConfiguration();
        noneMode.set("xp.mode", "none");
        assertFalse(new XpBridge(Logger.getAnonymousLogger(), null, new TimberConfig(noneMode)).present());
    }

    private static Player throwingPlayerProxy() {
        return (Player) Proxy.newProxyInstance(
                XpBridgeTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    throw new AssertionError("disabled XP bridge must not touch Player." + method.getName());
                });
    }
}

