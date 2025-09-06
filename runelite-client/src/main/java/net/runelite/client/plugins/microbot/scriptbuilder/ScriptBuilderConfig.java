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

    @ConfigItem(
            keyName = "loop",
            name = "Loop",
            description = "Enable looping from top to bottom",
            position = 2
    )
    default boolean loop() {
        return false;
    }

    @ConfigItem(
            keyName = "loopDelay",
            name = "Loop Delay (ms)",
            description = "Delay before restarting the next loop",
            position = 3
    )
    default int loopDelay() {
        return 0;
    }
}
