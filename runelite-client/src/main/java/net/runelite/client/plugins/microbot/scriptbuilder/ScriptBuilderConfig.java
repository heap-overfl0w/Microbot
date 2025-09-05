package net.runelite.client.plugins.microbot.scriptbuilder;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("scriptbuilder")
public interface ScriptBuilderConfig extends Config {

    @ConfigItem(
            keyName = "enabled",
            name = "Enable",
            description = "Open the Script Builder panel",
            position = 1
    )
    default boolean enabled() {
        return false;
    }
}

