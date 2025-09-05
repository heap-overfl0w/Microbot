package net.runelite.client.plugins.microbot.scriptbuilder.condition;

public interface Condition {
    boolean isSatisfied();
    String getDescription();
    String getDetailedDescription();
    ConditionType getType();
    void reset(boolean randomize);
    void pause();
    void resume();
}

