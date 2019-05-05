/*
 * Copyright 2004 - 2008 Christian Sprajc. All rights reserved.
 *
 * This file is part of PowerFolder.
 *
 * PowerFolder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation.
 *
 * PowerFolder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PowerFolder. If not, see <http://www.gnu.org/licenses/>.
 *
 * $Id: SettingsTab.java 5457 2008-10-17 14:25:41Z harry $
 */
package de.dal33t.powerfolder.ui.information.folder.settings;

import com.jgoodies.binding.adapter.BasicComponentFactory;
import com.jgoodies.binding.value.ValueHolder;
import com.jgoodies.binding.value.ValueModel;
import com.jgoodies.forms.builder.DefaultFormBuilder;
import com.jgoodies.forms.builder.PanelBuilder;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.PreferencesEntry;
import de.dal33t.powerfolder.clientserver.FolderService;
import de.dal33t.powerfolder.clientserver.ServerClient;
import de.dal33t.powerfolder.clientserver.ServerClientEvent;
import de.dal33t.powerfolder.clientserver.ServerClientListener;
import de.dal33t.powerfolder.disk.Folder;
import de.dal33t.powerfolder.event.DiskItemFilterListener;
import de.dal33t.powerfolder.event.FolderMembershipEvent;
import de.dal33t.powerfolder.event.FolderMembershipListener;
import de.dal33t.powerfolder.event.PatternChangedEvent;
import de.dal33t.powerfolder.light.FolderInfo;
import de.dal33t.powerfolder.message.FolderDBMaintCommando;
import de.dal33t.powerfolder.security.FolderPermission;
import de.dal33t.powerfolder.ui.PFUIComponent;
import de.dal33t.powerfolder.ui.WikiLinks;
import de.dal33t.powerfolder.ui.action.BaseAction;
import de.dal33t.powerfolder.ui.dialog.DialogFactory;
import de.dal33t.powerfolder.ui.dialog.FolderRemoveDialog;
import de.dal33t.powerfolder.ui.dialog.GenericDialogType;
import de.dal33t.powerfolder.ui.event.SelectionChangeEvent;
import de.dal33t.powerfolder.ui.event.SelectionChangeListener;
import de.dal33t.powerfolder.ui.event.SelectionModel;
import de.dal33t.powerfolder.ui.panel.ArchiveModeSelectorPanel;
import de.dal33t.powerfolder.ui.panel.SyncProfileSelectorPanel;
import de.dal33t.powerfolder.ui.util.Help;
import de.dal33t.powerfolder.ui.util.Icons;
import de.dal33t.powerfolder.ui.util.UIUtil;
import de.dal33t.powerfolder.ui.widget.ActionLabel;
import de.dal33t.powerfolder.ui.widget.JButtonMini;
import de.dal33t.powerfolder.ui.wizard.PFWizard;
import de.dal33t.powerfolder.util.Reject;
import de.dal33t.powerfolder.util.StringUtils;
import de.dal33t.powerfolder.util.Translation;
import de.dal33t.powerfolder.util.pattern.Pattern;
import de.dal33t.powerfolder.util.pattern.PatternFactory;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * UI component for the information settings tab
 */
public class SettingsTab extends PFUIComponent {

    // Model and other stuff
    private final ServerClient serverClient;
    private Folder folder;
    private ValueModel localVersionModel;
    private ValueModel onlineVersionModel;
    private final ValueModel scriptModel;
    private DefaultListModel<String> patternsListModel = new DefaultListModel<>();
    private final SelectionModel selectionModel;
    private FolderMembershipListener membershipListener;
    private final DiskItemFilterListener patternChangeListener;
    private volatile boolean updatingOnlineArchiveMode;

    /**
     * Folders with this setting will backup files before replacing them with
     * newer downloaded ones.
     */
    private final RemoveFolderAction removeFolderAction;

    // UI Components
    private JPanel uiComponent;
    private final SyncProfileSelectorPanel transferModeSelectorPanel;
    private final ArchiveModeSelectorPanel localArchiveModeSelectorPanel;
    private final ArchiveModeSelectorPanel onlineArchiveModeSelectorPanel;
    private JList<String> patternsList;
    private final JTextField localFolderField;
    private final JButton localFolderButton;
    private ActionLabel confOSActionLabel;
    private BaseAction confOSAction;
    private Action maintainDBAction;
    private JButtonMini editButton;
    private JButtonMini removeButton;
    private boolean settingFolder;
    private JLabel onlineLabel;
    private JCheckBox syncPatternsCheckBox;

    /**
     * Constructor
     *
     * @param controller
     */
    public SettingsTab(Controller controller) {
        super(controller);
        serverClient = controller.getOSClient();
        transferModeSelectorPanel = new SyncProfileSelectorPanel(
                getController());
        MyActionListener myActionListener = new MyActionListener();
        selectionModel = new SelectionModel();
        localFolderField = new JTextField();
        localFolderField.setEditable(false);
        localFolderButton = new JButtonMini(Icons.getIconById(Icons.DIRECTORY),
                Translation.get("settings_tab.select_directory.text"));
        localFolderButton.setEnabled(false);
        localFolderButton.addActionListener(myActionListener);
        patternChangeListener = new MyPatternChangeListener();
        patternsListModel = new DefaultListModel<>();
        removeFolderAction = new RemoveFolderAction(getController());
        maintainDBAction = new MaintainFolderAction(getController());
        serverClient.addListener(new MyServerClientListener());
        membershipListener = new MyFolderMembershipListener();
        scriptModel = new ValueHolder(null, false);

        MyLocalValueChangeListener localListener = new MyLocalValueChangeListener();
        localVersionModel = new ValueHolder(); // <Integer>
        localVersionModel.addValueChangeListener(localListener);

        MyOnlineValueChangeListener onlineListener = new MyOnlineValueChangeListener();
        onlineVersionModel = new ValueHolder(); // <Integer>
        onlineVersionModel.addValueChangeListener(onlineListener);

        List<ValueModel> localVersionModels = new ArrayList<ValueModel>();
        List<ValueModel> onlineVersionModels = new ArrayList<ValueModel>();

        if (PreferencesEntry.EXPERT_MODE.getValueBoolean(controller)) {
            // Expert gets separate archive mode panels - local and online.
            localVersionModels.add(localVersionModel);
            onlineVersionModels.add(onlineVersionModel);
        } else {
            // Non-expert gets one archive mode panel,
            // which simultaneously updates both local and online.
            localVersionModels.add(localVersionModel);
            localVersionModels.add(onlineVersionModel);
        }

        localArchiveModeSelectorPanel = new ArchiveModeSelectorPanel(
                controller, localVersionModels,
                new LocalPurgeListener());
        onlineArchiveModeSelectorPanel = new ArchiveModeSelectorPanel(
                controller, onlineVersionModels,
                new OnlinePurgeListener());
        onlineLabel = new JLabel(
                Translation.get("general.online_archive_mode"));
        onlineLabel.setVisible(false);
        onlineArchiveModeSelectorPanel.getUIComponent().setVisible(false);
    }

    /**
     * Set the tab with details for a folder.
     *
     * @param folderInfo
     */
    public void setFolderInfo(FolderInfo folderInfo) {
        if (folder != null) {
            folder.getDiskItemFilter().removeListener(patternChangeListener);
            folder.removeMembershipListener(membershipListener);
        }
        settingFolder = true;
        folder = getController().getFolderRepository().getFolder(folderInfo);
        folder.getDiskItemFilter().addListener(patternChangeListener);
        folder.addMembershipListener(membershipListener);
        transferModeSelectorPanel.setUpdateableFolder(folder);
        scriptModel.setValue(folder.getDownloadScript());
        localArchiveModeSelectorPanel.setArchiveMode(folder.getFileArchiver().getVersionsPerFile());
        syncPatternsCheckBox.setSelected(folder.isSyncPatterns());
        settingFolder = false;
        update();
        enableConfigOSAction();
        loadOnlineArchiveMode();
    }

    private void loadOnlineArchiveMode() {
        // Do this offline so it does not slow the main display.
        FolderInfo fi = folder == null ? null : folder.getInfo();
        if (serverClient.isConnected() && serverClient.joinedByServer(fi)) {
            new MyServerModeSwingWorker(fi).execute();
        } else {
            onlineArchiveModeSelectorPanel.getUIComponent().setVisible(false);
            onlineLabel.setVisible(false);
        }
    }

    /**
     * @return the ui component
     */
    public JPanel getUIComponent() {
        if (uiComponent == null) {
            buildUIComponent();
        }
        return uiComponent;
    }

    /**
     * Builds the ui component.
     */
    private void buildUIComponent() {
        // label folder butn padding
        FormLayout layout = new FormLayout(
                "3dlu, right:pref, 3dlu, 140dlu, 3dlu, pref, pref:grow",
                "3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 12dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu");
        DefaultFormBuilder builder = new DefaultFormBuilder(layout);
        CellConstraints cc = new CellConstraints();

        int row = 2;
        Boolean expertMode =
                PreferencesEntry.EXPERT_MODE.getValueBoolean(getController());
        if (expertMode) {
            builder.add(
                    new JLabel(Translation.get("general.transfer_mode")),
                    cc.xy(2, row));
            builder.add(transferModeSelectorPanel.getUIComponent(),
                    cc.xyw(4, row, 4));
        } else {
            transferModeSelectorPanel.getUIComponent();
        }

        row += 2;
        builder.add(
                new JLabel(Translation.get(
                        "settings_tab.local_folder_location")), cc.xy(2, row));
        builder.add(localFolderField, cc.xy(4, row));
        builder.add(localFolderButton, cc.xy(6, row));

        row += 2;
        builder.add(new JLabel(Translation.get(
                "general.local_archive_mode")), cc.xy(2, row));
        builder.add(localArchiveModeSelectorPanel.getUIComponent(),
                cc.xyw(4, row, 4));

        if (expertMode) {
            row += 2;
            builder.add(onlineLabel, cc.xy(2, row));
            builder.add(onlineArchiveModeSelectorPanel.getUIComponent(),
                    cc.xyw(4, row, 4));
        }

        row += 2;
        if (expertMode) {
            builder.addLabel(
                    Translation.get("exp.settings_tab.download_script"),
                    cc.xy(2, row));
            builder.add(createScriptField(), cc.xyw(4, row, 4));
        }

        row += 2;
        if (expertMode) {
            builder.add(new JLabel(Translation
                    .get("exp.settings_tab.ignore_patterns")), cc.xy(2,
                    row, "right, top"));
            builder.add(createPatternsPanel(), cc.xyw(4, row, 4));
            row += 2;
            builder.add(createConfigurePanel(), cc.xy(4, row));
        } else {
            createPatternsPanel();
            createConfigurePanel();
            row += 2;
        }

        row += 2;
        builder.add(createDeletePanel(), cc.xy(4, row));

        if (expertMode) {
            row += 2;
            builder.add(createMaintainPanel(), cc.xy(4, row));
        }

        addSelectionListener();

        uiComponent = builder.getPanel();
    }

    private void addSelectionListener() {
        selectionModel.addSelectionChangeListener(new SelectionChangeListener() {
            public void selectionChanged(SelectionChangeEvent event) {
                int selectionsLength = selectionModel.getSelections() == null
                        ? 0
                        : selectionModel.getSelections().length;
                editButton.setEnabled(selectionsLength > 0);
                removeButton.setEnabled(selectionsLength > 0);
            }
        });
    }

    private JPanel createDeletePanel() {
        FormLayout layout = new FormLayout("pref", "pref");
        PanelBuilder builder = new PanelBuilder(layout);
        CellConstraints cc = new CellConstraints();
        ActionLabel label = new ActionLabel(getController(), removeFolderAction);
        builder.add(label.getUIComponent(), cc.xy(1, 1));
        label.convertToBigLabel();
        return builder.getPanel();
    }

    private JPanel createMaintainPanel() {
        FormLayout layout = new FormLayout("pref", "pref");
        PanelBuilder builder = new PanelBuilder(layout);
        CellConstraints cc = new CellConstraints();

        ActionLabel label = new ActionLabel(getController(), maintainDBAction);
        builder.add(label.getUIComponent(), cc.xy(1, 1));
        label.convertToBigLabel();
        return builder.getPanel();
    }

    private JPanel createConfigurePanel() {
        FormLayout layout = new FormLayout("pref", "pref");
        PanelBuilder builder = new PanelBuilder(layout);
        CellConstraints cc = new CellConstraints();
        confOSAction = new FolderOnlineStorageAction(getController());
        // Permission setting done later enableConfigOSAction. This causes
        // NPE:
        // confOSAction.allowWith(FolderPermission.admin(folder.getInfo()));
        confOSActionLabel = new ActionLabel(getController(), confOSAction);
        confOSActionLabel.convertToBigLabel();
        builder.add(confOSActionLabel.getUIComponent(), cc.xy(1, 1));
        return builder.getPanel();
    }

    private JPanel createPatternsPanel() {
        patternsList = new JList<>(patternsListModel);
        patternsList.addListSelectionListener(new ListSelectionListener() {

            public void valueChanged(ListSelectionEvent e) {
                selectionModel.setSelection(patternsList.getSelectedValue());
            }

        });

        Dimension size = new Dimension(200, 100);

        JScrollPane scroller = new JScrollPane(patternsList);
        scroller.setPreferredSize(size);

        FormLayout layout = new FormLayout("140dlu", "pref, 3dlu, pref");

        PanelBuilder builder = new PanelBuilder(layout);
        CellConstraints cc = new CellConstraints();

        builder.add(scroller, cc.xy(1, 1));
        builder.add(createButtonBar(), cc.xy(1, 3));
        return builder.getPanel();
    }

    /**
     * Creates a pair of location text field and button.
     *
     * @return
     */
    private JComponent createScriptField() {
        scriptModel.addValueChangeListener(new PropertyChangeListener() {
            public void propertyChange(PropertyChangeEvent evt) {
                folder.setDownloadScript((String) evt.getNewValue());
            }
        });

        FormLayout layout = new FormLayout("140dlu, 3dlu, pref, pref", "pref");

        PanelBuilder builder = new PanelBuilder(layout);
        CellConstraints cc = new CellConstraints();

        JTextField locationTF = BasicComponentFactory.createTextField(
                scriptModel, false);
        locationTF.setEditable(true);
        builder.add(locationTF, cc.xy(1, 1));

        JButton locationButton = new JButtonMini(
                Icons.getIconById(Icons.DIRECTORY),
                Translation.get("exp.settings_tab.download_script"));
        locationButton.addActionListener(new SelectScriptAction());
        builder.add(locationButton, cc.xy(3, 1));
        builder.add(Help.createWikiLinkButton(getController(),
                WikiLinks.SCRIPT_EXECUTION), cc.xy(4, 1));
        return builder.getPanel();
    }

    /**
     * refreshes the UI elements with the current data
     */
    private void update() {
        rebuildPatterns();
        localFolderField
                .setText(folder.getCommitOrLocalDir().toAbsolutePath().toString());
        localFolderButton.setEnabled(true);
    }

    private void rebuildPatterns() {
        patternsListModel.clear();
        List<String> stringList = folder.getDiskItemFilter().getPatterns();
        for (String s : stringList) {
            patternsListModel.addElement(s);
        }
    }

    private JPanel createButtonBar() {
        AddAction addAction = new AddAction(getController());
        EditAction editAction = new EditAction(getController());
        RemoveAction removeAction = new RemoveAction(getController());

        FormLayout layout = new FormLayout("pref, pref, pref, pref, pref:grow",
                "pref");
        PanelBuilder bar = new PanelBuilder(layout);
        CellConstraints cc = new CellConstraints();

        editButton = new JButtonMini(editAction);
        removeButton = new JButtonMini(removeAction);

        editButton.setEnabled(false);
        removeButton.setEnabled(false);

        bar.add(new JButtonMini(addAction), cc.xy(1, 1));
        bar.add(editButton, cc.xy(2, 1));
        bar.add(removeButton, cc.xy(3, 1));
        bar.add(Help.createWikiLinkButton(getController(),
                WikiLinks.EXCLUDING_FILES_FROM_SYNCHRONIZATION), cc.xy(4, 1));
        syncPatternsCheckBox = new JCheckBox(
                Translation.get("exp.settings_tab.sync_patterns"));
        syncPatternsCheckBox.setToolTipText(Translation
                .get("exp.settings_tab.sync_patterns.tip"));
        syncPatternsCheckBox.addActionListener(new MyActionListener());
        bar.add(syncPatternsCheckBox, cc.xy(5, 1));
        syncPatternsCheckBox.setVisible(true);

        return bar.getPanel();
    }

    /**
     * Removes any patterns for this file name. Directories should have "/*"
     * added to the name.
     *
     * @param patterns
     */
    public void removePatterns(String patterns) {

        String[] options = {
                Translation.get("remove_pattern.remove"),
                Translation.get("remove_pattern.dont"),
                Translation.get("general.cancel")};

        String[] patternArray = patterns.split("\\n");
        for (String pattern : patternArray) {

            // Match any patterns for this file.
            Pattern patternMatch = PatternFactory.createPattern(pattern);
            for (String blackListPattern : folder.getDiskItemFilter()
                    .getPatterns()) {
                if (patternMatch.isMatch(blackListPattern)) {
                    // Confirm that the user wants to remove this.
                    int result = DialogFactory.genericDialog(getController(),
                            Translation.get("remove_pattern.title"),
                            Translation.get("remove_pattern.prompt",
                                    pattern), options, 0, GenericDialogType.INFO);
                    // Default is remove.
                    if (result == 0) { // Remove
                        // Remove pattern and update.
                        folder.removePattern(blackListPattern);
                    } else if (result == 2) { // Cancel
                        // Abort for all other patterns.
                        break;
                    }
                }
            }
        }

        // Trigger resync
        getController().getFolderRepository().getFileRequestor()
                .triggerFileRequesting(folder.getInfo());
    }

    public void showAddPane(String initialPatterns) {

        Reject.ifNull(initialPatterns, "Patterns required");

        String[] patternArray = initialPatterns.split("\\n");
        if (patternArray.length == 1) {
            String pattern = patternArray[0];
            String title = Translation
                    .get("exp.settings_tab.add_a_pattern.title");
            String text = Translation
                    .get("exp.settings_tab.add_a_pattern.text");
            String patternResult = (String) JOptionPane.showInputDialog(
                    getUIController().getActiveFrame(), text, title,
                    JOptionPane.PLAIN_MESSAGE, null, null, pattern);
            if (!StringUtils.isBlank(patternResult)) {
                folder.addPattern(patternResult);
                getController().getTransferManager()
                        .checkActiveTranfersForExcludes();
            }

        } else {
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (String pattern : patternArray) {
                sb.append("    ");
                if (count++ >= 10) {
                    // Too many selections - enough!!!
                    sb.append(Translation
                            .get("general.more.lower_case") + "...\n");
                    break;
                }
                sb.append(pattern + '\n');
            }
            String message = Translation
                    .get("exp.settings_tab.add_patterns.text_1")
                    + "\n\n"
                    + sb.toString();
            String title = Translation
                    .get("exp.settings_tab.add_patterns.title");
            int result = DialogFactory.genericDialog(getController(), title,
                    message, new String[]{Translation.get("general.ok"),
                            Translation.get("general.cancel")}, 0,
                    GenericDialogType.QUESTION);
            if (result == 0) {
                for (String pattern : patternArray) {
                    folder.addPattern(pattern);
                }
            }
        }

        patternsList.getSelectionModel().clearSelection();
    }

    /**
     * Listen to changes in onlineStorage / folder and enable the configOS
     * button as required. Also config action on whether already joined OS.
     */
    private void enableConfigOSAction() {

        boolean enabled = false;
        if (folder != null && serverClient.isConnected()
                && serverClient.isLoggedIn()) {
            enabled = true;
            boolean osConfigured = serverClient.joinedByCloud(folder);
            if (osConfigured) {
                confOSActionLabel.setText(Translation
                        .get("exp.action_stop_online_storage.name"));
                confOSActionLabel.setToolTipText(Translation
                        .get("exp.action_stop_online_storage.description"));
            } else {
                confOSActionLabel.setText(Translation
                        .get("exp.action_backup_online_storage.name"));
                confOSActionLabel
                        .setToolTipText(Translation
                                .get("exp.action_backup_online_storage.description"));
            }
        }
        confOSAction.allowWith(FolderPermission.admin(folder.getInfo()));
        confOSActionLabel.getUIComponent().setVisible(enabled);
    }

    private void updateLocalArchiveMode(Object oldValue, final Object newValue) {
        Integer versions = (Integer) localVersionModel.getValue();
        folder.setArchiveVersions(versions);

        // If the versions is reduced, offer to delete excess.
        if (newValue != null && oldValue != null
                && newValue instanceof Integer && oldValue instanceof Integer
                && (Integer) newValue < (Integer) oldValue
                && (Integer) newValue > -1) {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    int i = DialogFactory.genericDialog(
                            getController(),
                            Translation
                                    .get("exp.settings_tab.offer_maintenance.title"),
                            Translation
                                    .get("exp.settings_tab.offer_maintenance.text"),
                            new String[]{
                                    Translation
                                            .get("exp.settings_tab.offer_maintenance.cleanup_button"),
                                    Translation.get("general.cancel")},
                            0, GenericDialogType.QUESTION);
                    if (i == 0) {
                        SwingWorker worker = new MyMaintainSwingWorker();
                        worker.execute();
                    }
                }
            });
        }
    }

    private void purgeLocalArchive() {
        if (folder == null) {
            logSevere("Calling purgeArchive with no folder???");
        } else {
            int result = DialogFactory.genericDialog(
                    getController(),
                    Translation.get("settings_tab.purge_archive_title"),
                    Translation
                            .get("settings_tab.purge_archive_message"),
                    new String[]{
                            Translation
                                    .get("settings_tab.purge_archive_purge"),
                            Translation.get("general.cancel")}, 0,
                    GenericDialogType.WARN);

            if (result == 0) { // Purge
                try {
                    folder.getFileArchiver().purge(folder, serverClient.getAccount());
                } catch (IOException e) {
                    logSevere(e);
                    DialogFactory
                            .genericDialog(
                                    getController(),
                                    Translation
                                            .get("settings_tab.purge_archive_title"),
                                    Translation
                                            .get("settings_tab.purge_archive_problem"),
                                    GenericDialogType.ERROR);

                }
            }
        }
    }

    private void purgeOnlineArchive() {
        if (folder == null) {
            logSevere("Calling purgeArchive with no folder???");
        } else {
            int result = DialogFactory.genericDialog(
                    getController(),
                    Translation.get("settings_tab.purge_archive_title"),
                    Translation
                            .get("settings_tab.purge_archive_message"),
                    new String[]{
                            Translation
                                    .get("settings_tab.purge_archive_purge"),
                            Translation.get("general.cancel")}, 0,
                    GenericDialogType.WARN);

            if (result == 0) { // Purge
                try {
                    if (serverClient.getFolderService(folder.getInfo()).purgeArchive(folder.getInfo())) {
                        logInfo("Successfully cleared online versioning of folder " + folder.getName());
                    }
                } catch (Exception e) {
                    logSevere(e);
                    DialogFactory
                            .genericDialog(
                                    getController(),
                                    Translation
                                            .get("settings_tab.purge_archive_title"),
                                    Translation
                                            .get("settings_tab.purge_archive_problem"),
                                    GenericDialogType.ERROR);

                }
            }
        }
    }

    // ////////////////
    // Inner Classes //
    // ////////////////

    /**
     * Local class to handle action events.
     */
    private class MyActionListener implements ActionListener {
        public void actionPerformed(ActionEvent e) {
            if (!settingFolder) {
                if (e.getSource().equals(localFolderButton)) {
                    getController().getUIController().getApplicationModel()
                            .moveLocalFolder(folder);
                } else if (e.getSource().equals(syncPatternsCheckBox)) {
                    folder.setSyncPatterns(syncPatternsCheckBox.isSelected());
                }
            }
        }
    }

    private class RemoveFolderAction extends BaseAction {

        private RemoveFolderAction(Controller controller) {
            super("action_remove_folder", controller);
        }

        public void actionPerformed(ActionEvent e) {
            FolderRemoveDialog panel = new FolderRemoveDialog(getController(),
                    folder.getInfo());
            panel.open();
        }
    }

    private class FolderOnlineStorageAction extends BaseAction {

        private FolderOnlineStorageAction(Controller controller) {
            super("exp.action_backup_online_storage", controller);
        }

        public void actionPerformed(ActionEvent e) {
            // FolderOnlineStoragePanel knows if folder already joined :-)
            PFWizard.openMirrorFolderWizard(getController(), folder);
        }
    }

    private class MaintainFolderAction extends BaseAction {

        private MaintainFolderAction(Controller controller) {
            super("exp.action_maintain_folder_db", controller);
        }

        public void actionPerformed(ActionEvent e) {
            MaintainFolderAction.this.setEnabled(false);
            getController().getIOProvider().startIO(new Runnable() {
                public void run() {
                    folder.broadcastMessages(new FolderDBMaintCommando(folder
                            .getInfo(), new Date()));
                    folder.maintainFolderDB(System.currentTimeMillis());
                    EventQueue.invokeLater(new Runnable() {
                        public void run() {
                            setEnabled(true);
                            //TODO: Add dialog
                            DialogFactory.genericDialog(getController(), "Cleanup Database", Translation.get("database_cleanup_finished"), GenericDialogType.INFO);
                        }
                    });
                }
            });
        }
    }

    private class MyPatternChangeListener implements DiskItemFilterListener {

        public void patternAdded(PatternChangedEvent e) {
            rebuildPatterns();
        }

        public void patternRemoved(PatternChangedEvent e) {
            rebuildPatterns();
        }

        public boolean fireInEventDispatchThread() {
            return true;
        }
    }

    /**
     * Listener to ServerClient for connection changes.
     */
    private class MyServerClientListener implements ServerClientListener {

        public void login(ServerClientEvent event) {
            enableConfigOSAction();
            loadOnlineArchiveMode();
        }

        public void accountUpdated(ServerClientEvent event) {
            enableConfigOSAction();
            loadOnlineArchiveMode();
        }

        public void serverConnected(ServerClientEvent event) {
            enableConfigOSAction();
            loadOnlineArchiveMode();
        }

        public void serverDisconnected(ServerClientEvent event) {
            enableConfigOSAction();
            loadOnlineArchiveMode();
        }

        public void nodeServerStatusChanged(ServerClientEvent event) {
            enableConfigOSAction();
            loadOnlineArchiveMode();
        }

        @Override
        public void childClientSpawned(ServerClientEvent event) {
            enableConfigOSAction();
            loadOnlineArchiveMode();
        }

        public boolean fireInEventDispatchThread() {
            return true;
        }
    }

    private class MyFolderMembershipListener implements
            FolderMembershipListener {

        public void memberJoined(FolderMembershipEvent folderEvent) {
            if (folderEvent.getMember().isServer()) {
                enableConfigOSAction();
            }
        }

        public void memberLeft(FolderMembershipEvent folderEvent) {
            if (folderEvent.getMember().isServer()) {
                enableConfigOSAction();
            }
        }

        public boolean fireInEventDispatchThread() {
            return true;
        }

    }

    /**
     * opens a popup, input dialog to edit the selected pattern
     */
    private class EditAction extends BaseAction {
        EditAction(Controller controller) {
            super("action_edit_ignore", controller);
        }

        public void actionPerformed(ActionEvent e) {
            String text = Translation
                    .get("exp.settings_tab.edit_a_pattern.text");
            String title = Translation
                    .get("exp.settings_tab.edit_a_pattern.title");

            String pattern = (String) JOptionPane.showInputDialog(
                    UIUtil.getParentWindow(e), text, title,
                    JOptionPane.PLAIN_MESSAGE, null, null,
                    // the text to edit:
                    selectionModel.getSelection());
            if (!StringUtils.isBlank(pattern)) {
                folder.removePattern((String) selectionModel.getSelection());
                folder.addPattern(pattern);
                getController().getTransferManager()
                        .checkActiveTranfersForExcludes();
                // Trigger resync
                getController().getFolderRepository().getFileRequestor()
                        .triggerFileRequesting(folder.getInfo());
            }
            patternsList.getSelectionModel().clearSelection();
        }
    }

    /**
     * Add a pattern to the backlist, opens a input dialog so user can enter
     * one.
     */
    private class AddAction extends BaseAction {
        private AddAction(Controller controller) {
            super("action_add_ignore", controller);
            // #2054
            setIcon(Icons.getIconById("action_remove_ignore.icon"));
        }

        public void actionPerformed(ActionEvent e) {
            showAddPane(Translation
                    .get("exp.settings_tab.add_a_pattern.example"));
        }
    }

    /**
     * removes the selected pattern from the blacklist
     */
    private class RemoveAction extends BaseAction {
        private RemoveAction(Controller controller) {
            super("action_remove_ignore", controller);
            // #2054
            setIcon(Icons.getIconById("action_add_ignore.icon"));
        }

        public void actionPerformed(ActionEvent e) {
            for (Object object : selectionModel.getSelections()) {
                String selection = (String) object;
                folder.removePattern(selection);
                // Trigger resync
                getController().getFolderRepository().getFileRequestor()
                        .triggerFileRequesting(folder.getInfo());
            }
            patternsList.getSelectionModel().clearSelection();
        }
    }

    /**
     * Action listener for the location button. Opens a choose dir dialog and
     * sets the location model with the result.
     */
    private class SelectScriptAction extends AbstractAction {
        public void actionPerformed(ActionEvent e) {
            String initial = (String) scriptModel.getValue();
            JFileChooser chooser = DialogFactory.createFileChooser();
            chooser.setSelectedFile(Paths.get(initial).toFile());
            int res = chooser
                    .showDialog(getUIController().getMainFrame().getUIComponent(),
                            Translation.get("general.select"));

            if (res == JFileChooser.APPROVE_OPTION) {
                String script = chooser.getSelectedFile().getAbsolutePath();
                scriptModel.setValue(script);
            }
        }
    }

    private class MyLocalValueChangeListener implements PropertyChangeListener {
        public void propertyChange(PropertyChangeEvent evt) {
            if (evt.getSource() == localVersionModel) {
                if (!settingFolder) {
                    updateLocalArchiveMode(evt.getOldValue(), evt.getNewValue());
                }
            }
        }
    }

    private class MyOnlineValueChangeListener implements PropertyChangeListener {
        public void propertyChange(PropertyChangeEvent evt) {
            if (evt.getSource() == onlineVersionModel) {
                if (!updatingOnlineArchiveMode) {
                    SwingWorker worker = new MyUpdaterSwingWorker();
                    worker.execute();
                }
            }
        }
    }

    /**
     * Maintain the folder archive.
     */
    private class MyMaintainSwingWorker extends SwingWorker {
        public Object doInBackground() {
            try {
                return folder.getFileArchiver().maintain();
            } catch (Exception e) {
                logSevere(e);
                return null;
            }
        }
    }

    /**
     * Update the online archive version details.
     */
    private class MyUpdaterSwingWorker extends SwingWorker {
        protected Object doInBackground() throws Exception {
            try {
                if (folder == null || settingFolder) {
                    return null;
                }
                FolderInfo folderInfo = folder.getInfo();
                FolderService folderService = serverClient.isLoggedIn()
                        && serverClient.isConnected() ? serverClient
                        .getFolderService(folderInfo) : null;
                if (serverClient.getAccount().hasAdminPermission(folderInfo)) {
                    Integer versions = (Integer) onlineVersionModel.getValue();
                    folderService.setArchiveMode(folderInfo, versions);
                } else {
                    logWarning("Permission denied to " + serverClient.getAccount().getUsername() + ". FolderAdminPermission on " + folderInfo);
                    new MyServerModeSwingWorker(folderInfo).execute();
                }
            } catch (Exception e) {
                logWarning(e);
            }
            return null;
        }
    }

    /**
     * Update the online archive component with online details.
     */
    private class MyServerModeSwingWorker extends SwingWorker {

        private final FolderInfo folderInfo;

        private MyServerModeSwingWorker(FolderInfo folderInfo) {
            this.folderInfo = folderInfo;
        }

        public Object doInBackground() {
            try {
                onlineArchiveModeSelectorPanel.getUIComponent().setVisible(false);
                onlineLabel.setVisible(false);
                if (folderInfo != null) {
                    FolderService folderService = serverClient.getFolderService(folderInfo);
                    int perFile = folderService.getVersionsPerFile(folderInfo);
                    updatingOnlineArchiveMode = true;
                    onlineArchiveModeSelectorPanel.setArchiveMode(perFile);
                    onlineArchiveModeSelectorPanel.getUIComponent().setVisible(true);
                    boolean hasAdminPermission =
                            serverClient.getSecurityService().hasPermission(serverClient.getAccountInfo(),
                                    FolderPermission.admin(folderInfo));
                    onlineArchiveModeSelectorPanel.setChangeable(hasAdminPermission);
                    onlineLabel.setVisible(true);
                }
            } catch (Exception e) {
                logWarning(e.toString());
                return null;
            } finally {
                updatingOnlineArchiveMode = false;
            }
            return null;
        }
    }

    private class LocalPurgeListener implements ActionListener {
        public void actionPerformed(ActionEvent e) {
            purgeLocalArchive();
        }
    }

    private class OnlinePurgeListener implements ActionListener {
        public void actionPerformed(ActionEvent e) {
            new SwingWorker() {
                protected Object doInBackground() throws Exception {
                    purgeOnlineArchive();
                    return null;
                }
            }.execute();
        }
    }
}
