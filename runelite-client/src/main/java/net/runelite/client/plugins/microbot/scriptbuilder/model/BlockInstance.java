package net.runelite.client.plugins.microbot.scriptbuilder.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlockInstance {
    public enum Kind { ACTION, IF, ELSE, ENDIF, COMMENT }

    private Kind kind = Kind.ACTION;
    private String definitionId;
    private List<String> args = new ArrayList<>();

    private String conditionDefinitionId;
    private List<String> conditionArgs = new ArrayList<>();
    private boolean negateCondition = false;

    private String comment;
}
