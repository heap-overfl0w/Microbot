package net.runelite.client.plugins.microbot.scriptbuilder;

import net.runelite.client.plugins.microbot.scriptbuilder.model.BlockDefinition;
import net.runelite.client.plugins.microbot.scriptbuilder.model.BlockParam;
import net.runelite.client.plugins.microbot.scriptbuilder.model.BlockInstance;
import net.runelite.client.plugins.microbot.scriptbuilder.registry.BlockRegistry;
import net.runelite.client.plugins.microbot.scriptbuilder.runner.ScriptRunner;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVarDef;
import net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVarType;
import net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVars;
import net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVarRegistry;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ScriptBuilderPanel extends JPanel {

    private final BlockRegistry registry = new BlockRegistry();
    private final DefaultListModel<BlockInstance> scriptModel = new DefaultListModel<>();
    private final JList<BlockInstance> scriptList = new JList<>(scriptModel);
    private final List<JList<BlockDefinition>> availableLists = new ArrayList<>();
    private final JPanel availableContainer = new JPanel();
    private final JTextField searchField = new JTextField();
    private final List<Section> sections = new ArrayList<>();
    private final JTextArea logArea = new JTextArea();
    private final ScriptRunner runner = new ScriptRunner(registry);
    private static boolean DISPATCHER_REGISTERED = false;
    private static long QS_LAST_SHIFT_TIME = 0L;
    private static int QS_SHIFT_TAPS = 0;
    private static boolean QS_OPEN = false;
    private static boolean QS_PENDING = false;
    private static long QS_SUPPRESS_UNTIL = 0L;
    private JPopupMenu openPopup;
    private final String scriptVarKey = "scriptbuilder";
    private JPanel variablesPanel;
    private int pairHighlightIndex = -1;
    private JDialog execDialog;

    private final java.util.Deque<java.util.List<BlockInstance>> history = new java.util.ArrayDeque<>();
    private final java.util.Deque<java.util.List<BlockInstance>> redoStack = new java.util.ArrayDeque<>();
    private boolean applyingUndo = false;
    private boolean applyingRedo = false;

    public ScriptBuilderPanel(Object ignoredConfigManager) {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(8, 8, 8, 8));

        availableContainer.setLayout(new BoxLayout(availableContainer, BoxLayout.Y_AXIS));
        availableContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        registry.groups().forEach((group, defs) -> addSection(group, defs));
        addLogicSection();

        JMenuBar categoryBar = new JMenuBar();
        categoryBar.setBorderPainted(false);
        JButton categoriesBtn = new JButton("Categories");
        categoriesBtn.setFocusPainted(false);
        categoriesBtn.setBorder(BorderFactory.createEmptyBorder(4,8,4,8));
        categoriesBtn.setOpaque(true);
        final Color catBase = categoriesBtn.getBackground();
        final Color catHover = catBase.brighter();
        categoriesBtn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { categoriesBtn.setBackground(catHover); }
            @Override public void mouseExited(java.awt.event.MouseEvent e) { categoriesBtn.setBackground(catBase); }
        });
        JPopupMenu groupsPopup = buildGroupsPopup(categoriesBtn);
        categoriesBtn.addActionListener(e -> {
            if (openPopup != null && openPopup.isVisible()) openPopup.setVisible(false);
            openPopup = groupsPopup;
            groupsPopup.show(categoriesBtn, 0, categoriesBtn.getHeight());
        });
        categoryBar.add(categoriesBtn);
        // Variables button removed; variables are discoverable via the Variables panel
        categoryBar.add(Box.createHorizontalGlue());
        JButton startBtnTop = new JButton("Start");
        startBtnTop.setFocusPainted(false);
        startBtnTop.setBorder(BorderFactory.createEmptyBorder(4,8,4,8));
        startBtnTop.addActionListener(e -> runScript());
        JButton stopBtnTop = new JButton("Stop");
        stopBtnTop.setFocusPainted(false);
        stopBtnTop.setBorder(BorderFactory.createEmptyBorder(4,8,4,8));
        stopBtnTop.addActionListener(e -> runner.stop());

        Color baseGreen = ColorScheme.PROGRESS_COMPLETE_COLOR.darker();
        Color hoverGreen = ColorScheme.PROGRESS_COMPLETE_COLOR;
        Color baseRed = ColorScheme.PROGRESS_ERROR_COLOR.darker();
        Color hoverRed = ColorScheme.PROGRESS_ERROR_COLOR;
        styleActionButton(startBtnTop, baseGreen, hoverGreen);
        styleActionButton(stopBtnTop, baseRed, hoverRed);
        JButton saveBtnTop = new JButton("Save");
        saveBtnTop.setFocusPainted(false);
        saveBtnTop.setBorder(BorderFactory.createEmptyBorder(4,8,4,8));
        saveBtnTop.addActionListener(e -> saveScript());
        JButton loadBtnTop = new JButton("Load");
        loadBtnTop.setFocusPainted(false);
        loadBtnTop.setBorder(BorderFactory.createEmptyBorder(4,8,4,8));
        loadBtnTop.addActionListener(e -> loadScript());
        JButton consoleBtn = new JButton("Console");
        consoleBtn.setFocusPainted(false);
        consoleBtn.setBorder(BorderFactory.createEmptyBorder(4,8,4,8));
        consoleBtn.addActionListener(e -> openConsoleWindow());
        categoryBar.add(startBtnTop);
        categoryBar.add(stopBtnTop);
        categoryBar.add(saveBtnTop);
        categoryBar.add(loadBtnTop);
        categoryBar.add(consoleBtn);
        JScrollPane categoryScroll = new JScrollPane(categoryBar,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        categoryScroll.setBorder(BorderFactory.createEmptyBorder());
        categoryScroll.getHorizontalScrollBar().setUnitIncrement(32);
        add(categoryScroll, BorderLayout.NORTH);

        scriptList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                BlockInstance bi = (BlockInstance) value;
                int indent = computeIndent(index, bi);
                StringBuilder pad = new StringBuilder();
                for (int i = 0; i < indent; i++) pad.append("  ");
                if (bi.getKind() == null || bi.getKind() == BlockInstance.Kind.ACTION) {
                    BlockDefinition d = registry.get(bi.getDefinitionId());
                    if (d == null) {
                        c.setText(pad + String.valueOf(bi.getDefinitionId()));
                    } else if ("SLEEPS.SLEEPUNTIL".equals(d.getId())) {
                        BlockDefinition cd = registry.get(bi.getConditionDefinitionId());
                        String timeout = bi.getArgs().isEmpty() ? "" : bi.getArgs().get(0);
                        String condLabel = cd != null && cd.getMethod() != null
                                ? (cd.getGroup() + ": " + cd.getMethod().getName() + formatArgs(cd, bi.getConditionArgs()))
                                : "<unset condition>";
                        String label = "Sleeps: sleepUntil(" + (timeout == null || timeout.isEmpty() ? "?" : timeout) + ") " + condLabel;
                        c.setText(pad + label);
                    } else if (d.getMethod() == null) {
                        c.setText(pad + String.valueOf(bi.getDefinitionId()));
                    } else {
                        String label = d.getGroup() + ": " + d.getMethod().getName() + formatArgs(d, bi.getArgs());
                        c.setText(pad + label);
                    }
                } else if (bi.getKind() == BlockInstance.Kind.COMMENT) {
                    String text = bi.getComment() == null ? "" : bi.getComment();
                    c.setText(pad + "// " + text);
                    c.setForeground(new Color(150, 150, 150));
                } else if (bi.getKind() == BlockInstance.Kind.IF) {
                    BlockDefinition cd = registry.get(bi.getConditionDefinitionId());
                    if (cd != null && cd.getMethod() != null) {
                        String label = (bi.isNegateCondition() ? "! " : "") + cd.getGroup() + ": " + cd.getMethod().getName() + formatArgs(cd, bi.getConditionArgs());
                        c.setText(pad + "IF " + label);
                    } else {
                        c.setText(pad + "IF " + (bi.isNegateCondition() ? "! " : "") + "<unset condition>");
                    }
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else if (bi.getKind() == BlockInstance.Kind.ELSE) {
                    c.setText(pad + "ELSE");
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else if (bi.getKind() == BlockInstance.Kind.ENDIF) {
                    c.setText(pad + "END IF");
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                }
                if (!isSelected && index == pairHighlightIndex) {
                    c.setOpaque(true);
                    c.setBackground(new Color(65, 90, 130));
                }
                return c;
            }
        });

        scriptList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JButton removeBtn = new JButton("Remove");
        removeBtn.addActionListener(e -> {
            int idx = scriptList.getSelectedIndex();
            if (idx < 0) return;
            int res = JOptionPane.showConfirmDialog(this, "Remove selected line?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (res != JOptionPane.YES_OPTION) return;
            pushSnapshot();
            BlockInstance k = scriptModel.get(idx);
            if (k.getKind() == BlockInstance.Kind.IF) {
                int end = findMatchingEndIf(idx);
                if (end >= 0) {
                    scriptModel.remove(end);
                    scriptModel.remove(idx);
                    return;
                }
            } else if (k.getKind() == BlockInstance.Kind.ENDIF) {
                int ifIdx = findMatchingIf(idx);
                if (ifIdx >= 0) {
                    scriptModel.remove(idx);
                    scriptModel.remove(ifIdx);
                    return;
                }
            }
            scriptModel.remove(idx);
        });

        JButton upBtn = new JButton("Up");
        upBtn.addActionListener(e -> moveSelected(-1));
        JButton downBtn = new JButton("Down");
        downBtn.addActionListener(e -> moveSelected(1));

        JPanel center = new JPanel(new BorderLayout());
        JLabel scriptLbl = new JLabel("Script");
        scriptLbl.setHorizontalAlignment(SwingConstants.CENTER);
        scriptLbl.setFont(scriptLbl.getFont().deriveFont(Font.BOLD));
        center.add(scriptLbl, BorderLayout.NORTH);
        center.add(new JScrollPane(scriptList), BorderLayout.CENTER);
        JPanel centerBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        centerBtns.add(upBtn);
        centerBtns.add(downBtn);
        centerBtns.add(removeBtn);
        center.add(centerBtns, BorderLayout.SOUTH);

        variablesPanel = createVariablesPanel();
        JPanel centerWrap = wrap(center);
        JPanel variablesWrap = wrap(variablesPanel);
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, centerWrap, variablesWrap);
        mainSplit.setResizeWeight(0.5);
        add(mainSplit, BorderLayout.CENTER);

        // Moved initial load to after variablesPanel is created

        scriptList.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, java.awt.event.InputEvent.CTRL_DOWN_MASK),
                "undo");
        scriptList.getActionMap().put("undo", new UndoAction(this));

        scriptList.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Y, java.awt.event.InputEvent.CTRL_DOWN_MASK),
                "redo");
        scriptList.getActionMap().put("redo", new RedoAction(this));

        centerWrap.setPreferredSize(new Dimension(480, centerWrap.getPreferredSize().height));
        variablesWrap.setPreferredSize(new Dimension(480, variablesWrap.getPreferredSize().height));

        scriptList.addListSelectionListener(e -> updatePairHighlight());
        scriptList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    int idx = scriptList.locationToIndex(e.getPoint());
                    if (idx >= 0) openParamEditor(scriptModel.get(idx));
                }
            }
        });

        scriptList.addMouseListener(new java.awt.event.MouseAdapter() {
            private void maybeShowPopup(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int idx = scriptList.locationToIndex(e.getPoint());
                if (idx < 0) return;
                scriptList.setSelectedIndex(idx);
                JPopupMenu menu = new JPopupMenu();
                JMenuItem addComment = new JMenuItem("Add comment above");
                addComment.addActionListener(a -> {
                    int sel = scriptList.getSelectedIndex();
                    if (sel < 0) return;
                    String text = JOptionPane.showInputDialog(ScriptBuilderPanel.this, "Comment text");
                    if (text == null) return;
                    pushSnapshot();
                    BlockInstance comment = new BlockInstance();
                    comment.setKind(BlockInstance.Kind.COMMENT);
                    comment.setComment(text);
                    scriptModel.add(sel, comment);
                    scriptList.setSelectedIndex(sel);
                });
                JMenuItem duplicate = new JMenuItem("Duplicate");
                duplicate.addActionListener(a -> duplicateSelected());
                JMenuItem remove = new JMenuItem("Remove");
                remove.addActionListener(a -> {
                    int sel = scriptList.getSelectedIndex();
                    if (sel < 0) return;
                    int res = JOptionPane.showConfirmDialog(ScriptBuilderPanel.this, "Remove selected line?", "Confirm Remove", JOptionPane.YES_NO_OPTION);
                    if (res != JOptionPane.YES_OPTION) return;
                    pushSnapshot();
                    BlockInstance k = scriptModel.get(sel);
                    if (k.getKind() == BlockInstance.Kind.IF) {
                        int end = findMatchingEndIf(sel);
                        if (end >= 0) {
                            scriptModel.remove(end);
                            scriptModel.remove(sel);
                            return;
                        }
                    } else if (k.getKind() == BlockInstance.Kind.ENDIF) {
                        int ifIdx = findMatchingIf(sel);
                        if (ifIdx >= 0) {
                            scriptModel.remove(sel);
                            scriptModel.remove(ifIdx);
                            return;
                        }
                    }
                    scriptModel.remove(sel);
                });
                menu.add(addComment);
                menu.add(duplicate);
                menu.add(remove);
                menu.show(scriptList, e.getX(), e.getY());
            }
            @Override public void mousePressed(java.awt.event.MouseEvent e) { maybeShowPopup(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { maybeShowPopup(e); }
        });

        scriptList.setDragEnabled(true);
        scriptList.setDropMode(DropMode.INSERT);
        scriptList.setTransferHandler(new TransferHandler() {
            protected Transferable createTransferable(JComponent c) {
                return new DataHandlerWrapper(scriptList.getSelectedValue());
            }
            public int getSourceActions(JComponent c) { return MOVE; }
            public boolean canImport(TransferSupport s) { return s.isDataFlavorSupported(DataFlavor.stringFlavor); }
            public boolean importData(TransferSupport s) {
                try {
                    pushSnapshot();
                    int idx = scriptList.getDropLocation().getIndex();
                    int from = scriptList.getSelectedIndex();
                    if (from == -1) return false;
                    int start = from, end = from;
                    BlockInstance b = scriptModel.get(from);
                    if (b.getKind() == BlockInstance.Kind.IF) {
                        int e = findMatchingEndIf(from);
                        if (e > from) { start = from; end = e; }
                    } else if (b.getKind() == BlockInstance.Kind.ENDIF) {
                        int sIdx = findMatchingIf(from);
                        if (sIdx >= 0) { start = sIdx; end = from; }
                    }
                    int len = end - start + 1;
                    java.util.List<BlockInstance> segment = new java.util.ArrayList<>();
                    for (int i = start; i <= end; i++) segment.add(scriptModel.get(i));
                    for (int i = end; i >= start; i--) scriptModel.remove(i);
                    if (idx > end) idx -= len; else if (idx > start) idx = start;
                    for (int i = 0; i < segment.size(); i++) scriptModel.add(idx + i, segment.get(i));
                    scriptList.setSelectedIndex(idx);
                    return true;
                } catch (Exception ex) { return false; }
            }
        });

        if (!DISPATCHER_REGISTERED) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
                if (e.getID() != java.awt.event.KeyEvent.KEY_RELEASED) return false;
                if (e.getKeyCode() != java.awt.event.KeyEvent.VK_SHIFT) return false;
                long now0 = System.currentTimeMillis();
                if (QS_OPEN || QS_PENDING) return false;
                long now = System.currentTimeMillis();
                if (now < QS_SUPPRESS_UNTIL) return false;
                if (now - QS_LAST_SHIFT_TIME < 400) {
                    QS_SHIFT_TAPS++;
                } else {
                    QS_SHIFT_TAPS = 1;
                }
                QS_LAST_SHIFT_TIME = now;
                if (QS_SHIFT_TAPS >= 2) {
                    QS_SHIFT_TAPS = 0;
                    QS_PENDING = true;
                    Component fo = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                    ScriptBuilderPanel panel = (ScriptBuilderPanel) javax.swing.SwingUtilities.getAncestorOfClass(ScriptBuilderPanel.class, fo);
                    SwingUtilities.invokeLater(() -> {
                        if (panel != null) panel.openQuickSearchDialog();
                    });
                }
                return false;
            });
            DISPATCHER_REGISTERED = true;
        }

        if (!loadDefaultFromJson()) {
            loadDefaultScript();
        }
    }

    private JPanel wrap(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        p.setBorder(new EmptyBorder(4,4,4,4));
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private JPanel createVariablesPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        JLabel lbl = new JLabel("Variables");
        lbl.setHorizontalAlignment(SwingConstants.CENTER);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(ColorScheme.DARK_GRAY_COLOR);
        header.add(lbl, BorderLayout.CENTER);
        panel.add(header, BorderLayout.NORTH);

        JPanel listPanel = new JPanel();
        listPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));

        JScrollPane scroll = new JScrollPane(listPanel);
        panel.add(scroll, BorderLayout.CENTER);

        JButton addBtn = new JButton("Add Variable");
        addBtn.addActionListener(e -> openAddVariableDialog(null));
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        bottom.add(addBtn);
        panel.add(bottom, BorderLayout.SOUTH);

        Runnable rebuild = () -> {
            listPanel.removeAll();
            GridBagLayout gbl = new GridBagLayout();
            JPanel table = new JPanel(gbl);
            table.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(4,6,4,6);
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.gridy = 0;
            gbc.gridx = 0; gbc.weightx = 0.3; JLabel h0 = headerLabel("Label"); table.add(h0, gbc);
            gbc.gridx = 1; gbc.weightx = 0.3; JLabel h1 = headerLabel("Name"); table.add(h1, gbc);
            gbc.gridx = 2; gbc.weightx = 0.2; JLabel h2 = headerLabel("Type"); table.add(h2, gbc);
            gbc.gridx = 3; gbc.weightx = 0.2; JLabel h3 = headerLabel("Value(s)"); table.add(h3, gbc);

            ScriptVarRegistry reg = ScriptVarRegistry.forKey(scriptVarKey);
            java.util.List<ScriptVarDef> defs = reg.definitions();
            java.util.Map<Integer, JComponent> editors = new java.util.HashMap<>();
            table.putClientProperty("var_defs", defs);
            for (ScriptVarDef def : defs) {
                String fullKey = scriptVarKey + "." + def.getName();
                gbc.gridy++;
                JLabel c0 = cellLabel(def.getLabel() != null ? def.getLabel() : def.getName());
                c0.putClientProperty("row", gbc.gridy - 1);
                c0.putClientProperty("col", 0);
                gbc.gridx = 0; gbc.weightx = 0.3; table.add(c0, gbc);

                JLabel c1 = cellLabel(def.getName());
                c1.putClientProperty("row", gbc.gridy - 1);
                c1.putClientProperty("col", 1);
                gbc.gridx = 1; gbc.weightx = 0.3; table.add(c1, gbc);

                JLabel c2 = cellLabel(def.getType().name());
                c2.putClientProperty("row", gbc.gridy - 1);
                c2.putClientProperty("col", 2);
                gbc.gridx = 2; gbc.weightx = 0.2; table.add(c2, gbc);

                JComponent editor = editorFor(fullKey, def);
                int rowIdx = gbc.gridy - 1;
                editor.putClientProperty("row", rowIdx);
                editor.putClientProperty("col", 3);
                if (editor instanceof Container) {
                    for (Component ch : ((Container) editor).getComponents()) {
                        if (ch instanceof JComponent) {
                            ((JComponent) ch).putClientProperty("row", rowIdx);
                            ((JComponent) ch).putClientProperty("col", 3);
                            if (ch instanceof Container) {
                                for (Component ch2 : ((Container) ch).getComponents()) {
                                    if (ch2 instanceof JComponent) {
                                        ((JComponent) ch2).putClientProperty("row", rowIdx);
                                        ((JComponent) ch2).putClientProperty("col", 3);
                                    }
                                }
                            }
                        }
                    }
                }
                gbc.gridx = 3; gbc.weightx = 0.2; table.add(editor, gbc);
                editors.put(gbc.gridy - 1, editor);
            }
            table.putClientProperty("var_editors", editors);
            table.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() < 2) return;
                    Component deep = SwingUtilities.getDeepestComponentAt(table, e.getX(), e.getY());
                    if (deep == null) return;
                    Object rowObj = null; Object colObj = null;
                    if (deep instanceof JComponent) {
                        rowObj = ((JComponent) deep).getClientProperty("row");
                        colObj = ((JComponent) deep).getClientProperty("col");
                    }
                    if (rowObj == null) return;
                    int rowIdx;
                    try { rowIdx = Integer.parseInt(rowObj.toString()); } catch (Exception ex) { return; }
                    int colIdx = -1; try { if (colObj != null) colIdx = Integer.parseInt(colObj.toString()); } catch (Exception ex) { colIdx = -1; }
                    @SuppressWarnings("unchecked")
                    java.util.Map<Integer, JComponent> eds = (java.util.Map<Integer, JComponent>) table.getClientProperty("var_editors");
                    if (eds == null) return;
                    if (colIdx == 0) {
                    java.util.List<ScriptVarDef> ldefs = (java.util.List<ScriptVarDef>) table.getClientProperty("var_defs");
                        if (ldefs != null && rowIdx >= 0 && rowIdx < ldefs.size()) {
                            ScriptVarDef d = ldefs.get(rowIdx);
                            String cur = d.getLabel() != null ? d.getLabel() : d.getName();
                            String input = JOptionPane.showInputDialog(ScriptBuilderPanel.this, "Edit label", cur);
                            if (input != null) {
                                ScriptVarRegistry.forKey(scriptVarKey).updateLabel(d.getName(), input);
                                Runnable rb = (Runnable) variablesPanel.getClientProperty("vars_rebuild");
                                if (rb != null) rb.run();
                            }
                        }
                        return;
                    }
                    JComponent ed = eds.get(rowIdx);
                    if (ed == null) return;
                    ed.requestFocusInWindow();
                    if (ed instanceof JComboBox) {
                        @SuppressWarnings("rawtypes") JComboBox box = (JComboBox) ed;
                        box.showPopup();
                    } else if (ed instanceof JTextField) {
                        ((JTextField) ed).selectAll();
                    } else if (ed instanceof JSpinner) {
                        JComponent editorComp = ((JSpinner) ed).getEditor();
                        if (editorComp instanceof JSpinner.DefaultEditor) {
                            JFormattedTextField tf = ((JSpinner.DefaultEditor) editorComp).getTextField();
                            tf.requestFocusInWindow();
                            tf.selectAll();
                        } else {
                            ed.requestFocusInWindow();
                        }
                    }
                }
            });
            table.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                JComponent last;
                @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                    Component deep = SwingUtilities.getDeepestComponentAt(table, e.getX(), e.getY());
                    if (last != null) {
                        last.setBorder(null);
                        last.setOpaque(false);
                        last.repaint();
                        last = null;
                    }
                    if (deep instanceof JComponent) {
                        JComponent jc = (JComponent) deep;
                        Object row = jc.getClientProperty("row");
                        Object col = jc.getClientProperty("col");
                        if (row != null && col != null) {
                            int cidx;
                            try { cidx = Integer.parseInt(col.toString()); } catch (Exception ex) { cidx = -1; }
                            if (cidx != 0 && cidx != 3) {
                                return;
                            }
                            jc.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
                            jc.setOpaque(true);
                            jc.setBackground(ColorScheme.DARK_GRAY_COLOR);
                            jc.repaint();
                            last = jc;
                        }
                    }
                }
            });
            listPanel.setLayout(new BorderLayout());
            listPanel.add(table, BorderLayout.NORTH);
            listPanel.revalidate();
            listPanel.repaint();
        };

        panel.putClientProperty("vars_rebuild", rebuild);
        rebuild.run();
        return panel;
    }

    private void openAddVariableDialog(ScriptVarType preset) {
        JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), "Add Variable", Dialog.ModalityType.APPLICATION_MODAL);
        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(new EmptyBorder(8,8,8,8));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4,4,4,4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridy = 0;

        JTextField name = new JTextField();
        JComboBox<ScriptVarType> type = new JComboBox<>(ScriptVarType.values());
        if (preset != null) type.setSelectedItem(preset);
        JTextField defVal = new JTextField();
        JCheckBox defBool = new JCheckBox();
        JPanel defContainer = new JPanel(new CardLayout());
        defContainer.add(defVal, "TEXT");
        defContainer.add(defBool, "BOOL");
        JTextField label = new JTextField();

        content.add(new JLabel("Name (lowerCamelCase)"), gc); gc.gridy++; content.add(name, gc); gc.gridy++;
        content.add(new JLabel("Type"), gc); gc.gridy++; content.add(type, gc); gc.gridy++;
        JLabel defLbl = new JLabel("Default Value");
        content.add(defLbl, gc); gc.gridy++; content.add(defContainer, gc); gc.gridy++;
        content.add(new JLabel("Label"), gc); gc.gridy++; content.add(label, gc); gc.gridy++;

        // When ENUM is selected, use Default Value as a comma-separated list of options
        Runnable syncTypeUi = () -> {
            ScriptVarType t = (ScriptVarType) type.getSelectedItem();
            CardLayout cl = (CardLayout) defContainer.getLayout();
            if (t == ScriptVarType.ENUM) {
                defLbl.setText("Default Value (comma-separated i.e., value1, value2)");
                cl.show(defContainer, "TEXT");
            } else if (t == ScriptVarType.BOOLEAN) {
                defLbl.setText("Default Value");
                cl.show(defContainer, "BOOL");
            } else {
                defLbl.setText("Default Value");
                cl.show(defContainer, "TEXT");
            }
            SwingUtilities.invokeLater(() -> {
                dlg.pack();
                int desired = Math.max(defLbl.getPreferredSize().width + 150, 360);
                if (dlg.getWidth() != desired) {
                    dlg.setSize(new Dimension(desired, dlg.getHeight()));
                }
            });
        };
        type.addActionListener(e -> syncTypeUi.run());
        syncTypeUi.run();

        JButton add = new JButton("Add");
        add.addActionListener(e -> {
            String n = name.getText().trim();
            ScriptVarType t = (ScriptVarType) type.getSelectedItem();
            String dv = defVal.getText();
            String lab = label.getText().trim().isEmpty() ? n : label.getText().trim();
            if (n.isEmpty() || t == null) { dlg.dispose(); return; }
            switch (t) {
                case BOOLEAN:
                    boolean bv = defBool.isSelected();
                    ScriptVars.register(scriptVarKey, ScriptVarDef.bool(n, bv, lab));
                    ScriptVars.set(scriptVarKey + "." + n, bv);
                    break;
                case INTEGER:
                    int iv = 0; try { iv = Integer.parseInt(dv); } catch (Exception ignored) {}
                    ScriptVars.register(scriptVarKey, ScriptVarDef.integer(n, iv, lab));
                    ScriptVars.set(scriptVarKey + "." + n, iv);
                    break;
                case DOUBLE:
                    double dvv = 0d; try { dvv = Double.parseDouble(dv); } catch (Exception ignored) {}
                    ScriptVars.register(scriptVarKey, ScriptVarDef.dbl(n, dvv, lab));
                    ScriptVars.set(scriptVarKey + "." + n, dvv);
                    break;
                case STRING:
                    ScriptVars.register(scriptVarKey, ScriptVarDef.string(n, dv, lab));
                    ScriptVars.set(scriptVarKey + "." + n, dv);
                    break;
                case ENUM:
                    java.util.List<String> opts = new java.util.ArrayList<>();
                    if (dv != null && !dv.isEmpty()) {
                        for (String s : dv.split(",")) if (!s.trim().isEmpty()) opts.add(s.trim());
                    }
                    String def = opts.isEmpty() ? "" : opts.get(0);
                    ScriptVars.register(scriptVarKey, ScriptVarDef.enm(n, opts, def, lab));
                    ScriptVars.set(scriptVarKey + "." + n, def);
                    break;
                default:
                    break;
            }
            Runnable rebuild = (Runnable) variablesPanel.getClientProperty("vars_rebuild");
            if (rebuild != null) rebuild.run();
            dlg.dispose();
        });
        content.add(add, gc);
        dlg.setContentPane(content);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    private JComponent editorFor(String fullKey, ScriptVarDef def) {
        switch (def.getType()) {
            case BOOLEAN: {
                boolean cur = false; try { Object v = ScriptVars.get(fullKey); cur = v instanceof Boolean ? (Boolean) v : Boolean.parseBoolean(String.valueOf(v)); } catch (Exception ignored) {}
                JCheckBox cb = new JCheckBox();
                cb.setSelected(cur);
                cb.addActionListener(e -> ScriptVars.set(fullKey, cb.isSelected()));
                return cb;
            }
            case INTEGER: {
                int cur = 0; try { Object v = ScriptVars.get(fullKey); cur = v instanceof Number ? ((Number) v).intValue() : Integer.parseInt(String.valueOf(v)); } catch (Exception ignored) {}
                JSpinner sp = new JSpinner(new SpinnerNumberModel(cur, Integer.MIN_VALUE, Integer.MAX_VALUE, 1));
                sp.addChangeListener(e -> ScriptVars.set(fullKey, ((Number) sp.getValue()).intValue()));
                return sp;
            }
            case DOUBLE: {
                double cur = 0; try { Object v = ScriptVars.get(fullKey); cur = v instanceof Number ? ((Number) v).doubleValue() : Double.parseDouble(String.valueOf(v)); } catch (Exception ignored) {}
                JSpinner sp = new JSpinner(new SpinnerNumberModel(cur, -1e9, 1e9, 0.1));
                sp.addChangeListener(e -> ScriptVars.set(fullKey, ((Number) sp.getValue()).doubleValue()));
                return sp;
            }
            case ENUM: {
                JComboBox<String> combo = new JComboBox<>(def.getEnumOptions().toArray(new String[0]));
                combo.setSelectedItem(String.valueOf(ScriptVars.get(fullKey)));
                combo.addActionListener(e -> ScriptVars.set(fullKey, combo.getSelectedItem()));
                return combo;
            }
            case STRING:
            default: {
                JTextField tf = new JTextField(String.valueOf(ScriptVars.get(fullKey)));
                tf.addActionListener(e -> ScriptVars.set(fullKey, tf.getText()));
                tf.addFocusListener(new java.awt.event.FocusAdapter() { public void focusLost(java.awt.event.FocusEvent e) { ScriptVars.set(fullKey, tf.getText()); }});
                return tf;
            }
        }
    }

    private JPopupMenu buildVariablesPopup(JButton owner) {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem addAny = new JMenuItem("Add Variable...");
        addAny.addActionListener(e -> openAddVariableDialog(null));
        popup.add(addAny);
        popup.add(new JSeparator());

        java.util.List<ScriptVarDef> defs = ScriptVarRegistry.forKey(scriptVarKey).definitions();

        if (defs.isEmpty()) {
            JMenuItem none = new JMenuItem("No variables defined");
            none.setEnabled(false);
            popup.add(none);
        } else {
            DefaultListModel<ScriptVarDef> model = new DefaultListModel<>();
            defs.forEach(model::addElement);
            JList<ScriptVarDef> list = new JList<>(model);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setVisibleRowCount(-1);
            list.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                    ScriptVarDef d = (ScriptVarDef) value;
                    String label = d.getLabel() != null && !d.getLabel().isEmpty() ? d.getLabel() : d.getName();
                    c.setText(label + " (" + d.getType().name().toLowerCase() + ")");
                    return c;
                }
            });
            list.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() >= 1) {
                        ScriptVarDef d = list.getSelectedValue();
                        if (d != null) {
                            String token = "var:" + d.getName();
                            try {
                                Toolkit.getDefaultToolkit().getSystemClipboard()
                                        .setContents(new java.awt.datatransfer.StringSelection(token), null);
                            } catch (Exception ignored) {}
                            popup.setVisible(false);
                        }
                    }
                }
            });
            JScrollPane sp = new JScrollPane(list);
            int rowH = Math.max(list.getFixedCellHeight() != -1 ? list.getFixedCellHeight() : 22, 20);
            int maxH = Math.min(300, Math.max(140, defs.size() * rowH + 6));
            sp.setPreferredSize(new Dimension(240, maxH));
            popup.add(sp);
        }

        popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {}
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { if (openPopup == popup) openPopup = null; }
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { if (openPopup == popup) openPopup = null; }
        });
        return popup;
    }

    private JPopupMenu buildGroupsPopup(JButton owner) {
        JPopupMenu popup = new JPopupMenu();
        DefaultListModel<Section> model = new DefaultListModel<>();
        for (Section s : sections) model.addElement(s);
        JList<Section> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                Section s = (Section) value;
                c.setText(s.title);
                return c;
            }
        });
        list.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int idx = list.locationToIndex(e.getPoint());
                if (idx >= 0) list.setSelectedIndex(idx);
            }
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() >= 1) {
                    Section s = list.getSelectedValue();
                    if (s != null) {
                        popup.setVisible(false);
                        JPopupMenu cat = buildCategoryPopup(s);
                        if (openPopup != null && openPopup.isVisible()) openPopup.setVisible(false);
                        openPopup = cat;
                        cat.show(owner, 0, owner.getHeight());
                    }
                }
            }
        });
        JScrollPane sp = new JScrollPane(list);
        int rowH = Math.max(list.getFixedCellHeight() != -1 ? list.getFixedCellHeight() : 22, 20);
        int maxH = Math.min(320, Math.max(120, sections.size() * rowH + 4));
        sp.setPreferredSize(new Dimension(220, maxH));
        popup.add(sp);
        popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {}
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { if (openPopup == popup) openPopup = null; }
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { if (openPopup == popup) openPopup = null; }
        });
        return popup;
    }

    private JLabel headerLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }
    private JLabel cellLabel(String text) {
        return new JLabel(text);
    }

    private void loadDefaultScript() {
        try {
            scriptModel.clear();
            BlockInstance if1 = new BlockInstance();
            if1.setKind(BlockInstance.Kind.IF);
            if1.setConditionDefinitionId("Rs2Bank.isOpen()");
            if1.setNegateCondition(true);
            scriptModel.addElement(if1);

            BlockInstance act1 = new BlockInstance();
            act1.setKind(BlockInstance.Kind.ACTION);
            act1.setDefinitionId("Rs2Bank.walkToBankAndUseBank()");
            scriptModel.addElement(act1);

            BlockInstance sleep = new BlockInstance();
            sleep.setKind(BlockInstance.Kind.ACTION);
            sleep.setDefinitionId("SLEEPS.SLEEPUNTIL");
            sleep.getArgs().add("3000");
            sleep.setConditionDefinitionId("Rs2Bank.isOpen()");
            scriptModel.addElement(sleep);

            BlockInstance if2 = new BlockInstance();
            if2.setKind(BlockInstance.Kind.IF);
            if2.setConditionDefinitionId("Rs2Bank.isOpen()");
            scriptModel.addElement(if2);

            BlockInstance withdraw = new BlockInstance();
            withdraw.setKind(BlockInstance.Kind.ACTION);
            withdraw.setDefinitionId("Rs2Bank.withdrawX(String,int)");
            withdraw.getArgs().add("Diamond");
            withdraw.getArgs().add("2");
            scriptModel.addElement(withdraw);

            BlockInstance endif1 = new BlockInstance();
            endif1.setKind(BlockInstance.Kind.ENDIF);
            scriptModel.addElement(endif1);

            BlockInstance endif2 = new BlockInstance();
            endif2.setKind(BlockInstance.Kind.ENDIF);
            scriptModel.addElement(endif2);

            if (!scriptModel.isEmpty()) scriptList.setSelectedIndex(0);
        } catch (Exception ignored) {}
    }

    public List<BlockInstance> getCurrentScript() {
        return snapshotCurrent();
    }

    // backup functionality removed: rely on default.json at startup

    private boolean loadDefaultFromJson() {
        try {
            java.io.InputStream is = ScriptBuilderPanel.class.getResourceAsStream("/net/runelite/client/plugins/microbot/scriptbuilder/default.json");
            if (is == null) {
                File src = new File("runelite-client/src/main/java/net/runelite/client/plugins/microbot/scriptbuilder/default.json");
                if (src.exists()) {
                    is = new java.io.FileInputStream(src);
                }
            }
            if (is == null) return false;
            try (java.io.InputStreamReader reader = new java.io.InputStreamReader(is)) {
                com.google.gson.JsonObject root = new com.google.gson.JsonParser().parse(reader).getAsJsonObject();
                String key = scriptVarKey;
                if (root.has("scriptKey") && !root.get("scriptKey").isJsonNull()) {
                    key = root.get("scriptKey").getAsString();
                }

                if (root.has("variables") && root.get("variables").isJsonObject()) {
                    ScriptVars.clear(key);
                    com.google.gson.JsonObject vars = root.getAsJsonObject("variables");
                    if (vars.has("definitions") && vars.get("definitions").isJsonArray()) {
                        for (com.google.gson.JsonElement el : vars.getAsJsonArray("definitions")) {
                            if (!el.isJsonObject()) continue;
                            com.google.gson.JsonObject d = el.getAsJsonObject();
                            String name = d.has("name") ? d.get("name").getAsString() : null;
                            String type = d.has("type") ? d.get("type").getAsString() : null;
                            String label = d.has("label") && !d.get("label").isJsonNull() ? d.get("label").getAsString() : (name != null ? name : "");
                            if (name == null || type == null) continue;
                            ScriptVarType t;
                            try { t = ScriptVarType.valueOf(type); } catch (Exception ex) { continue; }
                            switch (t) {
                                case BOOLEAN: {
                                    boolean dv = d.has("default") && !d.get("default").isJsonNull() && d.get("default").getAsBoolean();
                                    ScriptVars.register(key, ScriptVarDef.bool(name, dv, label));
                                    break; }
                                case INTEGER: {
                                    int dv = 0; try { if (d.has("default") && !d.get("default").isJsonNull()) dv = d.get("default").getAsInt(); } catch (Exception ignored) {}
                                    ScriptVars.register(key, ScriptVarDef.integer(name, dv, label));
                                    break; }
                                case DOUBLE: {
                                    double dv = 0d; try { if (d.has("default") && !d.get("default").isJsonNull()) dv = d.get("default").getAsDouble(); } catch (Exception ignored) {}
                                    ScriptVars.register(key, ScriptVarDef.dbl(name, dv, label));
                                    break; }
                                case ENUM: {
                                    java.util.List<String> opts = new java.util.ArrayList<>();
                                    if (d.has("options") && d.get("options").isJsonArray()) {
                                        for (com.google.gson.JsonElement oe : d.getAsJsonArray("options")) opts.add(oe.getAsString());
                                    }
                                    String dv = opts.isEmpty() ? "" : opts.get(0);
                                    if (d.has("default") && !d.get("default").isJsonNull()) dv = d.get("default").getAsString();
                                    ScriptVars.register(key, ScriptVarDef.enm(name, opts, dv, label));
                                    break; }
                                case STRING:
                                default: {
                                    String dv = d.has("default") && !d.get("default").isJsonNull() ? d.get("default").getAsString() : "";
                                    ScriptVars.register(key, ScriptVarDef.string(name, dv, label));
                                    break; }
                            }
                        }
                    }
                    if (vars.has("values") && vars.get("values").isJsonObject()) {
                        com.google.gson.JsonObject vals = vars.getAsJsonObject("values");
                        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : vals.entrySet()) {
                            String fullKey = key + "." + e.getKey();
                            com.google.gson.JsonElement v = e.getValue();
                            if (v == null || v.isJsonNull()) continue;
                            if (v.isJsonPrimitive()) {
                                com.google.gson.JsonPrimitive p = v.getAsJsonPrimitive();
                                if (p.isBoolean()) ScriptVars.set(fullKey, p.getAsBoolean());
                                else if (p.isNumber()) ScriptVars.set(fullKey, p.getAsNumber());
                                else ScriptVars.set(fullKey, p.getAsString());
                            } else {
                                ScriptVars.set(fullKey, v.getAsString());
                            }
                        }
                    }
                }

                java.util.List<BlockInstance> loadedSteps = new java.util.ArrayList<>();
                if (root.has("script") && root.get("script").isJsonObject()) {
                    com.google.gson.JsonObject sc = root.getAsJsonObject("script");
                    if (sc.has("steps") && sc.get("steps").isJsonArray()) {
                        java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<java.util.List<BlockInstance>>(){}.getType();
                        loadedSteps = new com.google.gson.Gson().fromJson(sc.get("steps"), t);
                    }
                }

                if (!loadedSteps.isEmpty()) {
                    scriptModel.clear();
                    for (BlockInstance bi : loadedSteps) scriptModel.addElement(bi);
                    if (!scriptModel.isEmpty()) scriptList.setSelectedIndex(0);
                }

                if (variablesPanel != null) {
                    Runnable rebuild = (Runnable) variablesPanel.getClientProperty("vars_rebuild");
                    if (rebuild != null) rebuild.run();
                }
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean loadCombinedFromFile(File file) {
        try (java.io.FileReader fr = new java.io.FileReader(file)) {
            com.google.gson.JsonElement parsed = new com.google.gson.JsonParser().parse(fr);
            if (parsed.isJsonArray()) return false;
            com.google.gson.JsonObject root = parsed.getAsJsonObject();
            String key = scriptVarKey;
            if (root.has("scriptKey") && !root.get("scriptKey").isJsonNull()) key = root.get("scriptKey").getAsString();
            if (root.has("variables") && root.get("variables").isJsonObject()) {
                ScriptVars.clear(key);
                com.google.gson.JsonObject vars = root.getAsJsonObject("variables");
                if (vars.has("definitions") && vars.get("definitions").isJsonArray()) {
                    for (com.google.gson.JsonElement el : vars.getAsJsonArray("definitions")) {
                        if (!el.isJsonObject()) continue;
                        com.google.gson.JsonObject d = el.getAsJsonObject();
                        String name = d.has("name") ? d.get("name").getAsString() : null;
                        String type = d.has("type") ? d.get("type").getAsString() : null;
                        String label = d.has("label") && !d.get("label").isJsonNull() ? d.get("label").getAsString() : (name != null ? name : "");
                        if (name == null || type == null) continue;
                        ScriptVarType t;
                        try { t = ScriptVarType.valueOf(type); } catch (Exception ex) { continue; }
                        switch (t) {
                            case BOOLEAN: {
                                boolean dv = d.has("default") && !d.get("default").isJsonNull() && d.get("default").getAsBoolean();
                                ScriptVars.register(key, ScriptVarDef.bool(name, dv, label));
                                break; }
                            case INTEGER: {
                                int dv = 0; try { if (d.has("default") && !d.get("default").isJsonNull()) dv = d.get("default").getAsInt(); } catch (Exception ignored) {}
                                ScriptVars.register(key, ScriptVarDef.integer(name, dv, label));
                                break; }
                            case DOUBLE: {
                                double dv = 0d; try { if (d.has("default") && !d.get("default").isJsonNull()) dv = d.get("default").getAsDouble(); } catch (Exception ignored) {}
                                ScriptVars.register(key, ScriptVarDef.dbl(name, dv, label));
                                break; }
                            case ENUM: {
                                java.util.List<String> opts = new java.util.ArrayList<>();
                                if (d.has("options") && d.get("options").isJsonArray()) {
                                    for (com.google.gson.JsonElement oe : d.getAsJsonArray("options")) opts.add(oe.getAsString());
                                }
                                String dv = opts.isEmpty() ? "" : opts.get(0);
                                if (d.has("default") && !d.get("default").isJsonNull()) dv = d.get("default").getAsString();
                                ScriptVars.register(key, ScriptVarDef.enm(name, opts, dv, label));
                                break; }
                            case STRING:
                            default: {
                                String dv = d.has("default") && !d.get("default").isJsonNull() ? d.get("default").getAsString() : "";
                                ScriptVars.register(key, ScriptVarDef.string(name, dv, label));
                                break; }
                        }
                    }
                }
                if (vars.has("values") && vars.get("values").isJsonObject()) {
                    com.google.gson.JsonObject vals = vars.getAsJsonObject("values");
                    for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : vals.entrySet()) {
                        String fullKey = key + "." + e.getKey();
                        com.google.gson.JsonElement v = e.getValue();
                        if (v == null || v.isJsonNull()) continue;
                        if (v.isJsonPrimitive()) {
                            com.google.gson.JsonPrimitive p = v.getAsJsonPrimitive();
                            if (p.isBoolean()) ScriptVars.set(fullKey, p.getAsBoolean());
                            else if (p.isNumber()) ScriptVars.set(fullKey, p.getAsNumber());
                            else ScriptVars.set(fullKey, p.getAsString());
                        } else {
                            ScriptVars.set(fullKey, v.getAsString());
                        }
                    }
                }
            }
            // If variables ended up empty (older backups), seed from default.json variables
            ScriptVarRegistry reg = ScriptVarRegistry.forKey(scriptVarKey);
            if (reg.definitions().isEmpty()) {
                loadVariablesFromDefaultOnly();
            }
            java.util.List<BlockInstance> loadedSteps = new java.util.ArrayList<>();
            if (root.has("script") && root.get("script").isJsonObject()) {
                com.google.gson.JsonObject sc = root.getAsJsonObject("script");
                if (sc.has("steps") && sc.get("steps").isJsonArray()) {
                    java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<java.util.List<BlockInstance>>(){}.getType();
                    loadedSteps = new com.google.gson.Gson().fromJson(sc.get("steps"), t);
                }
            }
            if (!loadedSteps.isEmpty()) {
                scriptModel.clear();
                for (BlockInstance bi : loadedSteps) scriptModel.addElement(bi);
                if (!scriptModel.isEmpty()) scriptList.setSelectedIndex(0);
            }
            if (variablesPanel != null) {
                Runnable rebuild = (Runnable) variablesPanel.getClientProperty("vars_rebuild");
                if (rebuild != null) rebuild.run();
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private void loadVariablesFromDefaultOnly() {
        try {
            java.io.InputStream is = ScriptBuilderPanel.class.getResourceAsStream("/net/runelite/client/plugins/microbot/scriptbuilder/default.json");
            if (is == null) {
                File src = new File("runelite-client/src/main/java/net/runelite/client/plugins/microbot/scriptbuilder/default.json");
                if (src.exists()) is = new java.io.FileInputStream(src);
            }
            if (is == null) return;
            try (java.io.InputStreamReader reader = new java.io.InputStreamReader(is)) {
                com.google.gson.JsonObject root = new com.google.gson.JsonParser().parse(reader).getAsJsonObject();
                String key = scriptVarKey;
                if (root.has("scriptKey") && !root.get("scriptKey").isJsonNull()) key = root.get("scriptKey").getAsString();
                if (root.has("variables") && root.get("variables").isJsonObject()) {
                    com.google.gson.JsonObject vars = root.getAsJsonObject("variables");
                    if (vars.has("definitions") && vars.get("definitions").isJsonArray()) {
                        for (com.google.gson.JsonElement el : vars.getAsJsonArray("definitions")) {
                            if (!el.isJsonObject()) continue;
                            com.google.gson.JsonObject d = el.getAsJsonObject();
                            String name = d.has("name") ? d.get("name").getAsString() : null;
                            String type = d.has("type") ? d.get("type").getAsString() : null;
                            String label = d.has("label") && !d.get("label").isJsonNull() ? d.get("label").getAsString() : (name != null ? name : "");
                            if (name == null || type == null) continue;
                            ScriptVarType t;
                            try { t = ScriptVarType.valueOf(type); } catch (Exception ex) { continue; }
                            switch (t) {
                                case BOOLEAN: {
                                    boolean dv = d.has("default") && !d.get("default").isJsonNull() && d.get("default").getAsBoolean();
                                    ScriptVars.register(key, ScriptVarDef.bool(name, dv, label));
                                    break; }
                                case INTEGER: {
                                    int dv = 0; try { if (d.has("default") && !d.get("default").isJsonNull()) dv = d.get("default").getAsInt(); } catch (Exception ignored) {}
                                    ScriptVars.register(key, ScriptVarDef.integer(name, dv, label));
                                    break; }
                                case DOUBLE: {
                                    double dv = 0d; try { if (d.has("default") && !d.get("default").isJsonNull()) dv = d.get("default").getAsDouble(); } catch (Exception ignored) {}
                                    ScriptVars.register(key, ScriptVarDef.dbl(name, dv, label));
                                    break; }
                                case ENUM: {
                                    java.util.List<String> opts = new java.util.ArrayList<>();
                                    if (d.has("options") && d.get("options").isJsonArray()) for (com.google.gson.JsonElement oe : d.getAsJsonArray("options")) opts.add(oe.getAsString());
                                    String dv = opts.isEmpty() ? "" : opts.get(0);
                                    if (d.has("default") && !d.get("default").isJsonNull()) dv = d.get("default").getAsString();
                                    ScriptVars.register(key, ScriptVarDef.enm(name, opts, dv, label));
                                    break; }
                                case STRING:
                                default: {
                                    String dv = d.has("default") && !d.get("default").isJsonNull() ? d.get("default").getAsString() : "";
                                    ScriptVars.register(key, ScriptVarDef.string(name, dv, label));
                                    break; }
                            }
                        }
                    }
                    if (vars.has("values") && vars.get("values").isJsonObject()) {
                        com.google.gson.JsonObject vals = vars.getAsJsonObject("values");
                        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : vals.entrySet()) {
                            String fullKey = key + "." + e.getKey();
                            com.google.gson.JsonElement v = e.getValue();
                            if (v == null || v.isJsonNull()) continue;
                            if (v.isJsonPrimitive()) {
                                com.google.gson.JsonPrimitive p = v.getAsJsonPrimitive();
                            if (p.isBoolean()) ScriptVars.set(fullKey, p.getAsBoolean());
                            else if (p.isNumber()) ScriptVars.set(fullKey, p.getAsNumber());
                            else ScriptVars.set(fullKey, p.getAsString());
                            } else {
                            ScriptVars.set(fullKey, v.getAsString());
                            }
                        }
                    }
                    if (variablesPanel != null) {
                        Runnable rebuild = (Runnable) variablesPanel.getClientProperty("vars_rebuild");
                        if (rebuild != null) rebuild.run();
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private com.google.gson.JsonObject buildCombinedJson() {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.addProperty("scriptKey", scriptVarKey);
        // variables
        com.google.gson.JsonObject vars = new com.google.gson.JsonObject();
        com.google.gson.JsonArray defs = new com.google.gson.JsonArray();
        ScriptVarRegistry reg = ScriptVarRegistry.forKey(scriptVarKey);
        for (ScriptVarDef d : reg.definitions()) {
            com.google.gson.JsonObject jd = new com.google.gson.JsonObject();
            jd.addProperty("name", d.getName());
            jd.addProperty("type", d.getType().name());
            if (d.getLabel() != null) jd.addProperty("label", d.getLabel());
            Object def = d.getDefaultValue();
            if (def != null) {
                if (def instanceof Number) jd.addProperty("default", (Number) def);
                else if (def instanceof Boolean) jd.addProperty("default", (Boolean) def);
                else jd.addProperty("default", String.valueOf(def));
            }
            if (d.getType() == ScriptVarType.ENUM && d.getEnumOptions() != null) {
                com.google.gson.JsonArray opts = new com.google.gson.JsonArray();
                for (String o : d.getEnumOptions()) opts.add(o);
                jd.add("options", opts);
            }
            defs.add(jd);
        }
        vars.add("definitions", defs);
        com.google.gson.JsonObject values = new com.google.gson.JsonObject();
        for (ScriptVarDef d : reg.definitions()) {
            String fullKey = scriptVarKey + "." + d.getName();
                    Object v = ScriptVars.get(fullKey);
            if (v == null) continue;
            if (v instanceof Number) values.addProperty(d.getName(), (Number) v);
            else if (v instanceof Boolean) values.addProperty(d.getName(), (Boolean) v);
            else values.addProperty(d.getName(), String.valueOf(v));
        }
        vars.add("values", values);
        root.add("variables", vars);
        // script
        java.util.List<BlockInstance> list = new ArrayList<>();
        for (int i = 0; i < scriptModel.size(); i++) list.add(scriptModel.get(i));
        com.google.gson.JsonObject sc = new com.google.gson.JsonObject();
        sc.add("steps", new com.google.gson.Gson().toJsonTree(list));
        root.add("script", sc);
        return root;
    }

    private void openConsoleWindow() {
        if (execDialog != null && execDialog.isShowing()) {
            execDialog.toFront();
            execDialog.requestFocus();
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(this);
        execDialog = new JDialog(owner, "Console", Dialog.ModalityType.MODELESS);
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(8,8,8,8));
        JLabel title = new JLabel("Console");
        title.setHorizontalAlignment(SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(ColorScheme.DARK_GRAY_COLOR);
        header.add(title, BorderLayout.CENTER);
        panel.add(header, BorderLayout.NORTH);

        logArea.setEditable(false);
        JScrollPane scroll = new JScrollPane(logArea);
        panel.add(scroll, BorderLayout.CENTER);

        JCheckBox verboseCb = new JCheckBox("Verbose");
        verboseCb.setOpaque(false);
        verboseCb.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        verboseCb.addActionListener(e -> runner.setVerbose(verboseCb.isSelected()));
        header.add(verboseCb, BorderLayout.EAST);

        execDialog.setContentPane(panel);
        execDialog.setSize(new Dimension(700, 400));
        execDialog.setLocationRelativeTo(owner);
        execDialog.setVisible(true);
    }

    private void openQuickSearchDialog() {
        if (QS_OPEN) return;
        QS_PENDING = false;
        QS_OPEN = true;
        JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), "Search Blocks", Dialog.ModalityType.APPLICATION_MODAL);
        JPanel panel = new JPanel(new BorderLayout(6,6));
        panel.setBorder(new EmptyBorder(8,8,8,8));
        JTextField query = new JTextField();
        DefaultListModel<BlockDefinition> model = new DefaultListModel<>();
        java.util.List<BlockDefinition> all = registry.all();
        all.forEach(model::addElement);
        JList<BlockDefinition> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                BlockDefinition d = (BlockDefinition) value;
                c.setText(d.getDisplayName());
                return c;
            }
        });
        JScrollPane sp = new JScrollPane(list);
        panel.add(query, BorderLayout.NORTH);
        panel.add(sp, BorderLayout.CENTER);
        Runnable filter = () -> {
            String q = query.getText().trim().toLowerCase();
            model.clear();
            for (BlockDefinition d : all) {
                String dn = d.getDisplayName() != null ? d.getDisplayName().toLowerCase() : "";
                String gn = d.getGroup() != null ? d.getGroup().toLowerCase() : "";
                String mn = d.getMethod() != null ? d.getMethod().getName().toLowerCase() : "";
                if (q.isEmpty() || dn.contains(q) || gn.contains(q) || mn.contains(q)) model.addElement(d);
            }
            if (!model.isEmpty()) list.setSelectedIndex(0);
        };
        query.getDocument().addDocumentListener(new SimpleDocumentListener(filter));
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    BlockDefinition d = list.getSelectedValue();
                    if (d != null) {
                        addBlockInstance(d);
                        QS_OPEN = false;
                        QS_PENDING = false;
                        QS_SUPPRESS_UNTIL = System.currentTimeMillis() + 600;
                        QS_LAST_SHIFT_TIME = 0L;
                        QS_SHIFT_TAPS = 0;
                        dlg.dispose();
                    }
                }
            }
        });
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "pick");
        list.getActionMap().put("pick", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) {
            BlockDefinition d = list.getSelectedValue();
            if (d != null) {
                addBlockInstance(d);
                QS_OPEN = false;
                QS_PENDING = false;
                QS_SUPPRESS_UNTIL = System.currentTimeMillis() + 600;
                QS_LAST_SHIFT_TIME = 0L;
                QS_SHIFT_TAPS = 0;
                dlg.dispose();
            }
        }});
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "close");
        list.getActionMap().put("close", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) {
            QS_OPEN = false;
            QS_PENDING = false;
            QS_SUPPRESS_UNTIL = System.currentTimeMillis() + 300;
            QS_LAST_SHIFT_TIME = 0L;
            QS_SHIFT_TAPS = 0;
            dlg.dispose();
        }});
        dlg.setContentPane(panel);
        dlg.setSize(new Dimension(520, 420));
        dlg.setLocationRelativeTo(this);
        dlg.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                QS_OPEN = false;
                QS_PENDING = false;
                QS_SUPPRESS_UNTIL = System.currentTimeMillis() + 300;
                QS_LAST_SHIFT_TIME = 0L;
                QS_SHIFT_TAPS = 0;
            }
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                QS_OPEN = false;
                QS_PENDING = false;
                QS_SUPPRESS_UNTIL = System.currentTimeMillis() + 300;
                QS_LAST_SHIFT_TIME = 0L;
                QS_SHIFT_TAPS = 0;
            }
        });
        SwingUtilities.invokeLater(query::requestFocusInWindow);
        dlg.setVisible(true);
    }

    private JPopupMenu buildCategoryPopup(Section s) {
        JPopupMenu popup = new JPopupMenu();
        DefaultListModel<BlockDefinition> model = new DefaultListModel<>();
        for (BlockDefinition d : s.allDefs) model.addElement(d);
        JList<BlockDefinition> list = new JList<>(model);
        list.setVisibleRowCount(-1);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                BlockDefinition d = (BlockDefinition) value;
                c.setText(d.getDisplayName());
                return c;
            }
        });
        list.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int idx = list.locationToIndex(e.getPoint());
                if (idx >= 0) list.setSelectedIndex(idx);
            }
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() >= 1) {
                    BlockDefinition d = list.getSelectedValue();
                    if (d != null) addBlockInstance(d);
                    popup.setVisible(false);
                }
            }
        });
        JScrollPane sp = new JScrollPane(list);
        int rowH = Math.max(list.getFixedCellHeight() != -1 ? list.getFixedCellHeight() : 22, 20);
        int maxH = Math.min(320, Math.max(120, s.allDefs.size() * rowH + 4));
        sp.setPreferredSize(new Dimension(260, maxH));
        popup.add(sp);
        popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {}
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { if (openPopup == popup) openPopup = null; }
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { if (openPopup == popup) openPopup = null; }
        });
        return popup;
    }

    private void styleActionButton(JButton btn, Color base, Color hover) {
        btn.setForeground(Color.WHITE);
        btn.setBackground(base);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        final javax.swing.border.Border pad = BorderFactory.createEmptyBorder(4,8,4,8);
        java.util.function.Function<Color, javax.swing.border.Border> makeBorder = c ->
                BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(c.darker()), pad);
        btn.setBorder(makeBorder.apply(base));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setRolloverEnabled(true);
        btn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                btn.setBackground(hover);
                btn.setBorder(makeBorder.apply(hover));
            }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                btn.setBackground(base);
                btn.setBorder(makeBorder.apply(base));
            }
        });
    }

    

    private void addSection(String title, List<BlockDefinition> defs) {
        JPanel section = new JPanel(new BorderLayout());
        section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        JToggleButton header = new JToggleButton(title);
        header.setSelected(true);
        header.setFocusPainted(false);
        header.setHorizontalAlignment(SwingConstants.LEFT);
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        section.add(header, BorderLayout.NORTH);
        DefaultListModel<BlockDefinition> model = new DefaultListModel<>();
        defs.forEach(model::addElement);
        JList<BlockDefinition> list = new JList<>(model);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                BlockDefinition d = (BlockDefinition) value;
                c.setText(d.getDisplayName());
                return c;
            }
        });
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    BlockDefinition d = list.getSelectedValue();
                    if (d != null) addBlockInstance(d);
                }
            }
        });
        availableLists.add(list);
        JScrollPane scroller = new JScrollPane(list);
        section.add(scroller, BorderLayout.CENTER);
        header.addActionListener(e -> {
            scroller.setVisible(header.isSelected());
            section.revalidate();
        });
        availableContainer.add(section);
        sections.add(new Section(title, defs, model, list, scroller, header, section));
    }

    private void addLogicSection() {
        List<BlockDefinition> logicDefs = new ArrayList<>();
        logicDefs.add(BlockDefinition.builder().id("LOGIC.IF").displayName("If ... End If").group("Logic").build());
        logicDefs.add(BlockDefinition.builder().id("LOGIC.ELSE").displayName("Else").group("Logic").build());
        addSection("Logic", logicDefs);
    }

    private void addBlockInstance(BlockDefinition d) {
        pushSnapshot();
        int insertAt = scriptList.getSelectedIndex() >= 0 ? scriptList.getSelectedIndex() + 1 : scriptModel.size();
        if (d.getId().startsWith("LOGIC.")) {
            BlockInstance.Kind k;
            switch (d.getId()) {
                case "LOGIC.IF": k = BlockInstance.Kind.IF; break;
                case "LOGIC.ELSE": k = BlockInstance.Kind.ELSE; break;
                case "LOGIC.ENDIF": k = BlockInstance.Kind.ENDIF; break;
                default: k = BlockInstance.Kind.ACTION;
            }
            BlockInstance inst = new BlockInstance();
            inst.setKind(k);
            if (k == BlockInstance.Kind.IF) {
                scriptModel.add(insertAt, inst);
                BlockInstance end = new BlockInstance();
                end.setKind(BlockInstance.Kind.ENDIF);
                scriptModel.add(insertAt + 1, end);
                scriptList.setSelectedIndex(insertAt);
                return;
            } else {
                scriptModel.add(insertAt, inst);
            }
        } else {
            BlockInstance inst = new BlockInstance();
            inst.setKind(BlockInstance.Kind.ACTION);
            inst.setDefinitionId(d.getId());
            for (int i = 0; i < d.getParams().size(); i++) inst.getArgs().add("");
            scriptModel.add(insertAt, inst);
        }
        scriptList.setSelectedIndex(insertAt);
    }

    private void addSelectedFromAnySection() {
        for (JList<BlockDefinition> l : availableLists) {
            BlockDefinition d = l.getSelectedValue();
            if (d != null) { addBlockInstance(d); return; }
        }
    }

    private int findMatchingEndIf(int ifIdx) {
        int depth = 1;
        for (int i = ifIdx + 1; i < scriptModel.size(); i++) {
            BlockInstance k = scriptModel.get(i);
            if (k.getKind() == BlockInstance.Kind.IF) depth++;
            else if (k.getKind() == BlockInstance.Kind.ENDIF) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

private int findMatchingIf(int endIfIdx) {
        int depth = 1;
        for (int i = endIfIdx - 1; i >= 0; i--) {
            BlockInstance k = scriptModel.get(i);
            if (k.getKind() == BlockInstance.Kind.ENDIF) depth++;
            else if (k.getKind() == BlockInstance.Kind.IF) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private void removeRange(int start, int end) {
        for (int i = end; i >= start; i--) scriptModel.remove(i);
    }

    private void moveRange(int start, int end, int insertAt) {
        java.util.List<BlockInstance> segment = new java.util.ArrayList<>();
        for (int i = start; i <= end; i++) segment.add(scriptModel.get(i));
        for (int i = end; i >= start; i--) scriptModel.remove(i);
        if (insertAt > start) insertAt -= (end - start + 1);
        for (int i = 0; i < segment.size(); i++) scriptModel.add(insertAt + i, segment.get(i));
    }

    private void wrapActionInIf(BlockInstance inst, boolean negated) {
        pushSnapshot();
        int idx = -1;
        for (int i = 0; i < scriptModel.size(); i++) {
            if (scriptModel.get(i) == inst) { idx = i; break; }
        }
        if (idx < 0) return;
        BlockInstance ifInst = new BlockInstance();
        ifInst.setKind(BlockInstance.Kind.IF);
        ifInst.setNegateCondition(negated);
        BlockDefinition actionDef = registry.get(inst.getDefinitionId());
        BlockDefinition condDef = guessConditionForAction(actionDef);
        String condId = condDef != null ? condDef.getId() : null;
        ifInst.setConditionDefinitionId(condId);
        int pcount = condDef != null ? condDef.getParams().size() : 0;
        for (int i = 0; i < pcount; i++) {
            String v = i < inst.getArgs().size() ? inst.getArgs().get(i) : "";
            ifInst.getConditionArgs().add(v);
        }
        scriptModel.remove(idx);
        scriptModel.add(idx, ifInst);
        BlockInstance end = new BlockInstance();
        end.setKind(BlockInstance.Kind.ENDIF);
        scriptModel.add(idx + 1, end);
        scriptList.setSelectedIndex(idx);
        openParamEditor(ifInst);
    }

    private BlockDefinition guessConditionForAction(BlockDefinition actionDef) {
        if (actionDef == null || actionDef.getMethod() == null) return null;
        String group = actionDef.getGroup();
        String name = actionDef.getMethod().getName();
        java.util.List<BlockDefinition> conds = registry.conditions();
        BlockDefinition best = null;
        for (BlockDefinition d : conds) {
            if (group != null && !group.equals(d.getGroup())) continue;
            if (d.getMethod() == null) continue;
            String cn = d.getMethod().getName();
            if (cn.equalsIgnoreCase(name)) return d;
        }
        if (name.startsWith("find") && name.length() > 4) {
            String tail = name.substring(4);
            String target = "has" + tail;
            for (BlockDefinition d : conds) {
                if (group != null && !group.equals(d.getGroup())) continue;
                if (d.getMethod() == null) continue;
                String cn = d.getMethod().getName();
                if (cn.equalsIgnoreCase(target)) return d;
            }
        }
        for (BlockDefinition d : conds) {
            if (group != null && !group.equals(d.getGroup())) continue;
            if (d.getMethod() == null) continue;
            String cn = d.getMethod().getName();
            if (cn.contains("has") && (name.contains("item") == cn.contains("item"))) {
                best = d; break;
            }
        }
        return best;
    }

    private void moveSelected(int delta) {
        pushSnapshot();
        int idx = scriptList.getSelectedIndex();
        if (idx < 0) return;
        int newIdx = Math.max(0, Math.min(scriptModel.size() - 1, idx + delta));
        if (newIdx == idx) return;
        BlockInstance val = scriptModel.remove(idx);
        scriptModel.add(newIdx, val);
        scriptList.setSelectedIndex(newIdx);
        updatePairHighlight();
    }

    private void openParamEditor(BlockInstance inst) {
        if (inst.getKind() == BlockInstance.Kind.COMMENT) {
            pushSnapshot();
            String cur = inst.getComment() == null ? "" : inst.getComment();
            String text = JOptionPane.showInputDialog(this, "Comment text", cur);
            if (text != null) inst.setComment(text);
            scriptList.repaint();
        } else if (inst.getKind() == null || inst.getKind() == BlockInstance.Kind.ACTION) {
            pushSnapshot();
            BlockDefinition def = registry.get(inst.getDefinitionId());
            if (def == null) return;
            JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), def.getDisplayName(), Dialog.ModalityType.APPLICATION_MODAL);
            JPanel content = new JPanel(new GridBagLayout());
            content.setBorder(new EmptyBorder(8,8,8,8));
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(4,4,4,4);
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.gridy = 0;
            JCheckBox wrapIf = new JCheckBox("If");
            JCheckBox negIf = new JCheckBox("Negated If");
            negIf.setEnabled(false);
            wrapIf.addActionListener(e -> negIf.setEnabled(wrapIf.isSelected()));
            gc.gridx = 0; gc.gridwidth = 1; content.add(wrapIf, gc);
            gc.gridx = 1; gc.gridwidth = 1; content.add(negIf, gc); gc.gridy++;

            if ("SLEEPS.SLEEPUNTIL".equals(def.getId())) {
                List<BlockDefinition> conds = registry.conditions();
                JLabel condLbl = new JLabel("Condition");
                gc.gridx = 0; gc.gridwidth = 2; content.add(condLbl, gc); gc.gridy++;
                JComboBox<BlockDefinition> condBox = new JComboBox<>(conds.toArray(new BlockDefinition[0]));
                condBox.setRenderer(new DefaultListCellRenderer() {
                    @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                        JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                        if (value instanceof BlockDefinition) c.setText(((BlockDefinition) value).getDisplayName());
                        return c;
                    }
                });
                BlockDefinition selected = null;
                for (BlockDefinition d : conds) if (d.getId().equals(inst.getConditionDefinitionId())) { selected = d; break; }
                if (selected != null) condBox.setSelectedItem(selected);
                gc.gridx = 0; gc.gridwidth = 2; content.add(condBox, gc); gc.gridy++;

                JPanel paramsPanel = new JPanel(new GridBagLayout());
                gc.gridx = 0; gc.gridwidth = 2; content.add(paramsPanel, gc); gc.gridy++;

                JCheckBox negate = new JCheckBox("Negate condition");
                negate.setSelected(inst.isNegateCondition());
                negate.addActionListener(e -> inst.setNegateCondition(negate.isSelected()));
                gc.gridx = 0; gc.gridwidth = 2; content.add(negate, gc); gc.gridy++;

                Runnable rebuild = () -> {
                    paramsPanel.removeAll();
                    BlockDefinition cdef = (BlockDefinition) condBox.getSelectedItem();
                    if (cdef != null) {
                        inst.setConditionDefinitionId(cdef.getId());
                        while (inst.getConditionArgs().size() < cdef.getParams().size()) inst.getConditionArgs().add("");
                        while (inst.getConditionArgs().size() > cdef.getParams().size()) inst.getConditionArgs().remove(inst.getConditionArgs().size()-1);
                        GridBagConstraints pgc = new GridBagConstraints();
                        pgc.insets = new Insets(4,4,4,4);
                        pgc.fill = GridBagConstraints.HORIZONTAL;
                        pgc.gridy = 0;
                        for (int i = 0; i < cdef.getParams().size(); i++) {
                            JLabel l = new JLabel(labelForParam(cdef, i, true));
                            JComponent field = paramField(cdef, cdef.getParams().get(i).getType(), inst, i, true);
                            pgc.gridx = 0; pgc.weightx = 0; paramsPanel.add(l, pgc);
                            pgc.gridx = 1; pgc.weightx = 1; paramsPanel.add(field, pgc);
                            pgc.gridy++;
                        }
                    }
                    paramsPanel.revalidate();
                    paramsPanel.repaint();
                };
                condBox.addActionListener(e -> rebuild.run());
                rebuild.run();
            }
            gc.gridwidth = 1;
            for (int i = 0; i < def.getParams().size(); i++) {
                JLabel l = new JLabel(labelForParam(def, i, false));
                JComponent field = paramField(def, def.getParams().get(i).getType(), inst, i, false);
                gc.gridx = 0; gc.weightx = 0; content.add(l, gc);
                gc.gridx = 1; gc.weightx = 1; content.add(field, gc);
                gc.gridy++;
            }
            JButton close = new JButton("Save");
            close.addActionListener(e -> {
                if (wrapIf.isSelected()) wrapActionInIf(inst, negIf.isSelected());
                dlg.dispose();
            });
            gc.gridx = 0; gc.gridwidth = 2; content.add(close, gc);
            dlg.setContentPane(content);
            dlg.pack();
            dlg.setLocationRelativeTo(this);
            dlg.setVisible(true);
            scriptList.repaint();
        } else if (inst.getKind() == BlockInstance.Kind.IF) {
            pushSnapshot();
            List<BlockDefinition> conds = registry.conditions();
            JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), "If Condition", Dialog.ModalityType.APPLICATION_MODAL);
            JPanel content = new JPanel(new GridBagLayout());
            content.setBorder(new EmptyBorder(8,8,8,8));
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(4,4,4,4);
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.gridy = 0;

            JComboBox<BlockDefinition> condBox = new JComboBox<>(conds.toArray(new BlockDefinition[0]));
            condBox.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                    if (value instanceof BlockDefinition) c.setText(((BlockDefinition) value).getDisplayName());
                    return c;
                }
            });

            BlockDefinition selected = null;
            for (BlockDefinition d : conds) if (d.getId().equals(inst.getConditionDefinitionId())) { selected = d; break; }
            if (selected != null) condBox.setSelectedItem(selected);

            gc.gridx = 0; gc.gridwidth = 2; content.add(new JLabel("Condition"), gc); gc.gridy++;
            gc.gridx = 0; gc.gridwidth = 2; content.add(condBox, gc); gc.gridy++;

            JPanel paramsPanel = new JPanel(new GridBagLayout());
            gc.gridx = 0; gc.gridwidth = 2; content.add(paramsPanel, gc); gc.gridy++;

            JCheckBox negate = new JCheckBox("Negate condition");
            negate.setSelected(inst.isNegateCondition());
            negate.addActionListener(e -> inst.setNegateCondition(negate.isSelected()));
            gc.gridx = 0; gc.gridwidth = 2; content.add(negate, gc); gc.gridy++;

            Runnable rebuild = () -> {
                paramsPanel.removeAll();
                BlockDefinition def = (BlockDefinition) condBox.getSelectedItem();
                if (def != null) {
                    inst.setConditionDefinitionId(def.getId());
                    // resize args list to correct size
                    while (inst.getConditionArgs().size() < def.getParams().size()) inst.getConditionArgs().add("");
                    while (inst.getConditionArgs().size() > def.getParams().size()) inst.getConditionArgs().remove(inst.getConditionArgs().size()-1);
                    GridBagConstraints pgc = new GridBagConstraints();
                    pgc.insets = new Insets(4,4,4,4);
                    pgc.fill = GridBagConstraints.HORIZONTAL;
                    pgc.gridy = 0;
                    for (int i = 0; i < def.getParams().size(); i++) {
                        JLabel l = new JLabel(labelForParam(def, i, true));
                        JComponent field = paramField(def, def.getParams().get(i).getType(), inst, i, true);
                        pgc.gridx = 0; pgc.weightx = 0; paramsPanel.add(l, pgc);
                        pgc.gridx = 1; pgc.weightx = 1; paramsPanel.add(field, pgc);
                        pgc.gridy++;
                    }
                }
                paramsPanel.revalidate();
                paramsPanel.repaint();
            };

            condBox.addActionListener(e -> rebuild.run());
            rebuild.run();

            JButton save = new JButton("Save");
            save.addActionListener(e -> dlg.dispose());
            gc.gridx = 0; gc.gridwidth = 2; content.add(save, gc);

            dlg.setContentPane(content);
            dlg.pack();
            dlg.setLocationRelativeTo(this);
            dlg.setVisible(true);
            scriptList.repaint();
        }
    }

    private void updatePairHighlight() {
        int idx = scriptList.getSelectedIndex();
        int match = -1;
        if (idx >= 0 && idx < scriptModel.size()) {
            BlockInstance b = scriptModel.get(idx);
            if (b.getKind() == BlockInstance.Kind.IF) {
                match = findMatchingEndIf(idx);
            } else if (b.getKind() == BlockInstance.Kind.ENDIF) {
                match = findMatchingIf(idx);
            }
        }
        pairHighlightIndex = match;
        scriptList.repaint();
    }

    private JComponent paramField(BlockDefinition def, Class<?> type, BlockInstance inst, int index, boolean isCondition) {
        if (type == boolean.class || type == Boolean.class) {
            JPanel panel = new JPanel(new BorderLayout(4,0));
            JComboBox<String> varBox = variableSelectorFor(ScriptVarType.BOOLEAN);
            JCheckBox cb = new JCheckBox();
            // Init from existing value
            String raw = isCondition ? safeCondArg(inst, index) : safeArg(inst, index);
            String selVar = parseVariableName(raw);
            if (selVar != null) {
                varBox.setSelectedItem(selVar);
            } else {
                cb.setSelected(Boolean.parseBoolean(raw));
            }
            // toggle manual editor visibility
            Runnable syncVis = () -> {
                boolean manual = isManualSelected(varBox);
                cb.setVisible(manual);
                panel.revalidate();
                panel.repaint();
                repack(panel);
            };
            varBox.addActionListener(e -> {
                String name = (String) varBox.getSelectedItem();
                if (name == null || name.equals("Custom")) {
                    setArg(inst, index, isCondition, Boolean.toString(cb.isSelected()));
                } else {
                    setArg(inst, index, isCondition, variableToken(name));
                }
                syncVis.run();
            });
            cb.addActionListener(a -> {
                if (isManualSelected(varBox)) setArg(inst, index, isCondition, Boolean.toString(cb.isSelected()));
            });
            panel.add(varBox, BorderLayout.WEST);
            panel.add(cb, BorderLayout.CENTER);
            syncVis.run();
            return panel;
        }
        if (type.isEnum()) {
            Object[] values = type.getEnumConstants();
            JComboBox<Object> box = new JComboBox<>(values);
            String cur = isCondition ? safeCondArg(inst, index) : safeArg(inst, index);
            if (cur != null && !cur.isEmpty()) {
                for (int i = 0; i < values.length; i++) if (values[i].toString().equals(cur)) box.setSelectedIndex(i);
            }
            box.addActionListener(a -> {
                if (isCondition) inst.getConditionArgs().set(index, box.getSelectedItem().toString());
                else inst.getArgs().set(index, box.getSelectedItem().toString());
            });
            return box;
        }
        // String
        String label = labelForParam(def, index, isCondition);
        if ("action".equalsIgnoreCase(label)) {
            String[] actions = new String[]{"Use","Wear","Equip","Eat","Drink","Wield","Drop","Cast","Bury","Light","Examine"};
            JComboBox<String> manual = new JComboBox<>(actions);
            manual.setEditable(true);
            JPanel panel = new JPanel(new BorderLayout(4,0));
            JComboBox<String> varBox = variableSelectorFor(ScriptVarType.STRING);
            String cur = isCondition ? safeCondArg(inst, index) : safeArg(inst, index);
            String selVar = parseVariableName(cur);
            if (selVar != null) {
                varBox.setSelectedItem(selVar);
            } else if (cur != null && !cur.isEmpty()) manual.setSelectedItem(cur);
            Runnable syncVis2 = () -> {
                manual.setVisible(isManualSelected(varBox));
                panel.revalidate();
                panel.repaint();
                repack(panel);
            };
            varBox.addActionListener(e -> {
                String name = (String) varBox.getSelectedItem();
                if (name == null || name.equals("Custom")) {
                    String val = String.valueOf(manual.getSelectedItem());
                    setArg(inst, index, isCondition, val);
                } else {
                    setArg(inst, index, isCondition, variableToken(name));
                }
                syncVis2.run();
            });
            manual.addActionListener(a -> {
                if (isManualSelected(varBox)) {
                    String val = String.valueOf(manual.getSelectedItem());
                    setArg(inst, index, isCondition, val);
                }
            });
            panel.add(varBox, BorderLayout.WEST);
            panel.add(manual, BorderLayout.CENTER);
            syncVis2.run();
            return panel;
        }
        // General String or numeric types
        if (type == String.class) {
            JPanel panel = new JPanel(new BorderLayout(4,0));
            JComboBox<String> varBox = variableSelectorFor(ScriptVarType.STRING);
            JTextField tf = new JTextField(isCondition ? safeCondArg(inst, index) : safeArg(inst, index));
            String selVar = parseVariableName(tf.getText());
            if (selVar != null) varBox.setSelectedItem(selVar);
            tf.setToolTipText(label);
            tf.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
                if (isManualSelected(varBox)) setArg(inst, index, isCondition, tf.getText());
            }));
            varBox.addActionListener(e -> {
                String name = (String) varBox.getSelectedItem();
                if (name == null || name.equals("Custom")) {
                    setArg(inst, index, isCondition, tf.getText());
                } else {
                    setArg(inst, index, isCondition, variableToken(name));
                }
                tf.setVisible(isManualSelected(varBox));
                panel.revalidate();
                panel.repaint();
                repack(panel);
            });
            panel.add(varBox, BorderLayout.WEST);
            panel.add(tf, BorderLayout.CENTER);
            tf.setVisible(isManualSelected(varBox));
            return panel;
        }
        if (type == int.class || type == Integer.class) {
            JPanel panel = new JPanel(new BorderLayout(4,0));
            JComboBox<String> varBox = variableSelectorFor(ScriptVarType.INTEGER);
            int cur = 0; try { cur = Integer.parseInt(isCondition ? safeCondArg(inst, index) : safeArg(inst, index)); } catch (Exception ignored) {}
            JSpinner sp = new JSpinner(new SpinnerNumberModel(cur, Integer.MIN_VALUE, Integer.MAX_VALUE, 1));
            String selVar = parseVariableName(isCondition ? safeCondArg(inst, index) : safeArg(inst, index));
            if (selVar != null) varBox.setSelectedItem(selVar);
            sp.addChangeListener(e -> { if (isManualSelected(varBox)) setArg(inst, index, isCondition, String.valueOf(((Number) sp.getValue()).intValue())); });
            varBox.addActionListener(e -> {
                String name = (String) varBox.getSelectedItem();
                if (name == null || name.equals("Custom")) setArg(inst, index, isCondition, String.valueOf(((Number) sp.getValue()).intValue()));
                else setArg(inst, index, isCondition, variableToken(name));
                sp.setVisible(isManualSelected(varBox));
                panel.revalidate();
                panel.repaint();
                repack(panel);
            });
            panel.add(varBox, BorderLayout.WEST);
            panel.add(sp, BorderLayout.CENTER);
            sp.setVisible(isManualSelected(varBox));
            return panel;
        }
        if (type == double.class || type == Double.class) {
            JPanel panel = new JPanel(new BorderLayout(4,0));
            JComboBox<String> varBox = variableSelectorFor(ScriptVarType.DOUBLE);
            double cur = 0; try { cur = Double.parseDouble(isCondition ? safeCondArg(inst, index) : safeArg(inst, index)); } catch (Exception ignored) {}
            JSpinner sp = new JSpinner(new SpinnerNumberModel(cur, -1e9, 1e9, 0.1));
            String selVar = parseVariableName(isCondition ? safeCondArg(inst, index) : safeArg(inst, index));
            if (selVar != null) varBox.setSelectedItem(selVar);
            sp.addChangeListener(e -> { if (isManualSelected(varBox)) setArg(inst, index, isCondition, String.valueOf(((Number) sp.getValue()).doubleValue())); });
            varBox.addActionListener(e -> {
                String name = (String) varBox.getSelectedItem();
                if (name == null || name.equals("Custom")) setArg(inst, index, isCondition, String.valueOf(((Number) sp.getValue()).doubleValue()));
                else setArg(inst, index, isCondition, variableToken(name));
                sp.setVisible(isManualSelected(varBox));
                panel.revalidate();
                panel.repaint();
                repack(panel);
            });
            panel.add(varBox, BorderLayout.WEST);
            panel.add(sp, BorderLayout.CENTER);
            sp.setVisible(isManualSelected(varBox));
            return panel;
        }
        // Fallback string editor
        JTextField tf = new JTextField(isCondition ? safeCondArg(inst, index) : safeArg(inst, index));
        tf.setToolTipText(label);
        tf.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            if (isCondition) inst.getConditionArgs().set(index, tf.getText());
            else inst.getArgs().set(index, tf.getText());
        }));
        return tf;
    }

    private boolean isManualSelected(JComboBox<String> varBox) {
        Object it = varBox.getSelectedItem();
        return it == null || "Custom".equals(it.toString());
    }
    private void repack(Component c) {
        Window w = SwingUtilities.getWindowAncestor(c);
        if (w != null) {
            w.pack();
        }
    }
    private String variableToken(String name) {
        return "var:" + name;
    }
    private String parseVariableName(String raw) {
        if (raw == null) return null;
        if (raw.startsWith("${var:") && raw.endsWith("}")) {
            String key = raw.substring(6, raw.length() - 1);
            if (key.startsWith("var:")) key = key.substring(4);
            if (key.startsWith(scriptVarKey + ".")) return key.substring(scriptVarKey.length() + 1);
            if (!key.contains(".")) return key;
        } else if (raw.startsWith("var:")) {
            String key = raw.substring(4);
            if (key.startsWith(scriptVarKey + ".")) return key.substring(scriptVarKey.length() + 1);
            if (!key.contains(".")) return key;
        }
        return null;
    }
    private JComboBox<String> variableSelectorFor(ScriptVarType t) {
        java.util.List<String> items = new java.util.ArrayList<>();
        items.add("Custom");
        for (ScriptVarDef d : ScriptVarRegistry.forKey(scriptVarKey).definitions()) {
            if (d.getType() == t) items.add(d.getName());
        }
        JComboBox<String> box = new JComboBox<>(items.toArray(new String[0]));
        return box;
    }

    private void setArg(BlockInstance inst, int index, boolean isCondition, String value) {
        java.util.List<String> list = isCondition ? inst.getConditionArgs() : inst.getArgs();
        while (list.size() <= index) list.add("");
        list.set(index, value == null ? "" : value);
    }

    private String safeArg(BlockInstance inst, int idx) {
        return idx < inst.getArgs().size() ? inst.getArgs().get(idx) : "";
    }

    private String safeCondArg(BlockInstance inst, int idx) {
        return idx < inst.getConditionArgs().size() ? inst.getConditionArgs().get(idx) : "";
    }

    private int computeIndent(int index, BlockInstance current) {
        int n = scriptModel.size();
        int[] preDepth = new int[n];
        int[] endToIf = new int[n];
        java.util.Arrays.fill(endToIf, -1);
        java.util.Deque<Integer> stack = new java.util.ArrayDeque<>();
        int depth = 0;
        for (int i = 0; i < n; i++) {
            preDepth[i] = Math.max(depth, 0);
            BlockInstance b = scriptModel.get(i);
            if (b.getKind() == BlockInstance.Kind.IF) {
                stack.push(i);
                depth++;
            } else if (b.getKind() == BlockInstance.Kind.ENDIF) {
                if (!stack.isEmpty()) {
                    int ifIdx = stack.pop();
                    endToIf[i] = ifIdx;
                }
                depth = Math.max(depth - 1, 0);
            }
        }

        BlockInstance.Kind kind = current != null ? current.getKind() : null;
        if (kind == BlockInstance.Kind.ENDIF) {
            int ifIdx = endToIf[index];
            if (ifIdx >= 0) return preDepth[ifIdx];
            return Math.max(preDepth[index] - 1, 0);
        } else if (kind == BlockInstance.Kind.ELSE) {
            return Math.max(preDepth[index] - 1, 0);
        } else {
            return preDepth[index];
        }
    }

    private String formatArgs(BlockDefinition def, List<String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (int i = 0; i < def.getParams().size(); i++) {
            Class<?> t = def.getParams().get(i).getType();
            String v = i < values.size() ? values.get(i) : null;
            if (i > 0) sb.append(", ");
            if (v == null || v.isEmpty()) {
                sb.append("?");
            } else if (t == String.class) {
                String disp = v;
                if (v.startsWith("${var:") && v.endsWith("}")) {
                    String key = v.substring(6, v.length() - 1);
                    if (key.startsWith("var:")) key = key.substring(4);
                    if (key.startsWith(scriptVarKey + ".")) key = key.substring(scriptVarKey.length() + 1);
                    disp = "var:" + key;
                }
                if (disp.startsWith("var:")) {
                    sb.append(disp);
                } else {
                    sb.append('"').append(disp).append('"');
                }
            } else if (t.isEnum()) {
                sb.append(t.getSimpleName()).append('.').append(v);
            } else {
                sb.append(v);
            }
        }
        sb.append(')');
        return sb.toString();
    }

    private String labelForParam(BlockDefinition def, int paramIndex, boolean isCondition) {
        BlockParam bp = def.getParams().get(paramIndex);
        String name = bp.getName();
        if (name != null && !name.startsWith("arg")) return name;
        String base = bp.getType().getSimpleName();
        if (def.getMethod() != null) {
            String m = def.getMethod().getName();
            Class<?> t = bp.getType();
            if (t == String.class) {
                if (m.toLowerCase().contains("item")) return "name";
            }
            if ((t == boolean.class || t == Boolean.class)) {
                if (m.equals("hasItem") || m.toLowerCase().contains("hasitem")) return "exact";
            }
        }
        return base;
    }

    private void runScript() {
        List<BlockInstance> list = new ArrayList<>();
        for (int i = 0; i < scriptModel.size(); i++) list.add(scriptModel.get(i));
        runner.load(list);
        runner.setLogSink(s -> SwingUtilities.invokeLater(() -> {
            if (logArea.getText().length() > 100000) logArea.setText("");
            logArea.append(s + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }));
        runner.start();
    }

    private void filterAvailable(String q) {
        String query = q == null ? "" : q.trim().toLowerCase();
        for (Section s : sections) {
            s.model.clear();
            for (BlockDefinition d : s.allDefs) {
                String name = d.getMethod() != null ? d.getMethod().getName().toLowerCase() : d.getDisplayName().toLowerCase();
                String group = d.getGroup() == null ? "" : d.getGroup().toLowerCase();
                if (query.isEmpty() || name.contains(query) || group.contains(query)) {
                    s.model.addElement(d);
                }
            }
            s.list.clearSelection();
        }
        availableContainer.revalidate();
        availableContainer.repaint();
    }

    private void saveScript() {
        String name = JOptionPane.showInputDialog(this, "Script name");
        if (name == null || name.trim().isEmpty()) return;
        File f = new File(ScriptRunner.scriptsDir(), name + ".json");
        try {
            com.google.gson.JsonObject root = buildCombinedJson();
            try (java.io.FileWriter fw = new java.io.FileWriter(f)) {
                fw.write(new com.google.gson.Gson().toJson(root));
            }
            JOptionPane.showMessageDialog(this, "Saved: " + f.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
        }
    }

    private void loadScript() {
        pushSnapshot();
        JFileChooser fc = new JFileChooser(ScriptRunner.scriptsDir());
        int res = fc.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            try {
                if (!loadCombinedFromFile(fc.getSelectedFile())) {
                    List<BlockInstance> list = ScriptRunner.loadFromFile(fc.getSelectedFile());
                    scriptModel.clear();
                    for (BlockInstance bi : list) scriptModel.addElement(bi);
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Load failed: " + ex.getMessage());
            }
        }
    }

    private static class SimpleDocumentListener implements javax.swing.event.DocumentListener {
        private final Runnable cb;
        SimpleDocumentListener(Runnable cb) { this.cb = cb; }
        public void insertUpdate(javax.swing.event.DocumentEvent e) { cb.run(); }
        public void removeUpdate(javax.swing.event.DocumentEvent e) { cb.run(); }
        public void changedUpdate(javax.swing.event.DocumentEvent e) { cb.run(); }
    }

    private static class DataHandlerWrapper implements Transferable {
        private final Object value;
        DataHandlerWrapper(Object v) { this.value = v; }
        public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.stringFlavor}; }
        public boolean isDataFlavorSupported(DataFlavor flavor) { return flavor.equals(DataFlavor.stringFlavor); }
        public Object getTransferData(DataFlavor flavor) { return value.toString(); }
    }

    private static class Section {
        final String title;
        final List<BlockDefinition> allDefs;
        final DefaultListModel<BlockDefinition> model;
        final JList<BlockDefinition> list;
        final JScrollPane scroller;
        final JToggleButton header;
        final JPanel panel;
        Section(String title, List<BlockDefinition> allDefs, DefaultListModel<BlockDefinition> model,
                JList<BlockDefinition> list, JScrollPane scroller, JToggleButton header, JPanel panel) {
            this.title = title;
            this.allDefs = allDefs;
            this.model = model;
            this.list = list;
            this.scroller = scroller;
            this.header = header;
            this.panel = panel;
        }
    }

    private static final class UndoAction extends AbstractAction {
        private final ScriptBuilderPanel panel;
        UndoAction(ScriptBuilderPanel p) { this.panel = p; }
        @Override public void actionPerformed(java.awt.event.ActionEvent e) { panel.undo(); }
    }

    private static final class RedoAction extends AbstractAction {
        private final ScriptBuilderPanel panel;
        RedoAction(ScriptBuilderPanel p) { this.panel = p; }
        @Override public void actionPerformed(java.awt.event.ActionEvent e) { panel.redo(); }
    }

    private void pushSnapshot() {
        if (applyingUndo || applyingRedo) return;
        history.push(snapshotCurrent());
        redoStack.clear();
    }

    private List<BlockInstance> snapshotCurrent() {
        List<BlockInstance> snap = new ArrayList<>();
        for (int i = 0; i < scriptModel.size(); i++) snap.add(deepCopy(scriptModel.get(i)));
        return snap;
    }

    private void restoreSnapshot(List<BlockInstance> snap) {
        scriptModel.clear();
        for (BlockInstance bi : snap) scriptModel.addElement(deepCopy(bi));
        if (!scriptModel.isEmpty()) scriptList.setSelectedIndex(Math.min(scriptList.getSelectedIndex(), scriptModel.size()-1));
    }

    private void undo() {
        if (history.isEmpty()) return;
        applyingUndo = true;
        try {
            redoStack.push(snapshotCurrent());
            List<BlockInstance> snap = history.pop();
            restoreSnapshot(snap);
        } finally {
            applyingUndo = false;
        }
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        applyingRedo = true;
        try {
            history.push(snapshotCurrent());
            List<BlockInstance> snap = redoStack.pop();
            restoreSnapshot(snap);
        } finally {
            applyingRedo = false;
        }
    }

    private static BlockInstance deepCopy(BlockInstance x) {
        BlockInstance y = new BlockInstance();
        y.setKind(x.getKind());
        y.setDefinitionId(x.getDefinitionId());
        y.setArgs(new ArrayList<>(x.getArgs()));
        y.setConditionDefinitionId(x.getConditionDefinitionId());
        y.setConditionArgs(new ArrayList<>(x.getConditionArgs()));
        y.setNegateCondition(x.isNegateCondition());
        y.setComment(x.getComment());
        return y;
    }
    private void duplicateSelected() {
        pushSnapshot();
        int idx = scriptList.getSelectedIndex();
        if (idx < 0) return;
        int start = idx, end = idx;
        BlockInstance b = scriptModel.get(idx);
        if (b.getKind() == BlockInstance.Kind.IF) {
            int e = findMatchingEndIf(idx);
            if (e > idx) end = e;
        } else if (b.getKind() == BlockInstance.Kind.ENDIF) {
            int s = findMatchingIf(idx);
            if (s >= 0) start = s;
        }
        int insertAt = idx + 1;
        for (int i = start; i <= end; i++) {
            scriptModel.add(insertAt + (i - start), deepCopy(scriptModel.get(i)));
        }
        scriptList.setSelectedIndex(insertAt);
    }
}
