package net.runelite.client.plugins.microbot.scriptbuilder.model;

import lombok.Builder;
import lombok.Data;

import java.lang.reflect.Method;
import java.util.List;

@Data
@Builder
public class BlockDefinition {
    private String id;
    private String displayName;
    private String group;
    private Class<?> declaringClass;
    private Method method;
    private List<BlockParam> params;
}
