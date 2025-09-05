package net.runelite.client.plugins.microbot.scriptbuilder.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BlockParam {
    private String name;
    private Class<?> type;
}

