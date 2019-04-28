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
 * $Id: FoldersTab.java 5495 2008-10-24 04:59:13Z harry $
 */
package de.dal33t.powerfolder.ui.folders;

import java.awt.event.ActionEvent;
import java.util.concurrent.TimeUnit;

import javax.swing.AbstractAction;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import com.jgoodies.forms.builder.PanelBuilder;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;

import de.dal33t.powerfolder.ConfigurationEntry;
import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.PreferencesEntry;
import de.dal33t.powerfolder.clientserver.ServerClient;
import de.dal33t.powerfolder.clientserver.ServerClientEvent;
import de.dal33t.powerfolder.clientserver.ServerClientListener;
import de.dal33t.powerfolder.ui.PFUIComponent;
import de.dal33t.powerfolder.ui.util.UIUtil;
import de.dal33t.powerfolder.ui.widget.ActionLabel;
import de.dal33t.powerfolder.ui.wizard.PFWizard;
import de.dal33t.powerfolder.util.Translation;

/**
 * Class to display the forders tab.
 */
public class FoldersTab extends PFUIComponent {

    private JPanel uiComponent;
    private FoldersList foldersList;
    private JScrollPane scrollPane;
    private JLabel connectingLabel;
    private JLabel couldNotConnect;
    private JLabel notLoggedInLabel;
    private ActionLabel loginActionLabel;
    private ActionLabel newFolderLink;
    private JLabel noFoldersFoundLabel;
    private ActionLabel folderWizardActionLabel;
    private ActionLabel newFolderActionLabel;
    private JPanel emptyPanelOuter;
    private ServerClient client;

    /**
     * Constructor
     *
     * @param controller
     */
    public FoldersTab(Controller controller) {
        super(controller);
        connectingLabel = new JLabel(
            Translation.get("folders_tab.connecting"));
        couldNotConnect = new JLabel(
            Translation.get("folders_tab.could_not_connect"));
        notLoggedInLabel = new JLabel(
            Translation.get("folders_tab.not_logged_in"));
        loginActionLabel = new ActionLabel(getController(), new MyLoginAction());
        loginActionLabel.setText(Translation
            .get("folders_tab.login"));
        noFoldersFoundLabel = new JLabel(
            Translation.get("folders_tab.no_folders_found"));
        foldersList = new FoldersList(getController(), this);
        folderWizardActionLabel = new ActionLabel(getController(),
            getApplicationModel().getActionModel().getFolderWizardAction());
        folderWizardActionLabel.setText(Translation
            .get("folders_tab.folder_wizard"));
        folderWizardActionLabel.setVisible(false);
        newFolderActionLabel = new ActionLabel(getController(),
            getApplicationModel().getActionModel().getNewFolderAction());
        newFolderActionLabel.setText(Translation
            .get("folders_tab.new_folder"));
        newFolderActionLabel.setVisible(false);
        client = getApplicationModel().getServerClientModel().getClient();

        controller.getThreadPool().scheduleWithFixedDelay(() -> {
            SwingUtilities.invokeLater(() -> {
                updateEmptyLabel();
            });
        } , 0, 30, TimeUnit.SECONDS);
    }

    public FoldersList getFoldersList() {
        return foldersList;
    }

    /**
     * Returns the ui component.
     *
     * @return
     */
    public JPanel getUIComponent() {
        if (uiComponent == null) {
            buildUI();
        }
        return uiComponent;
    }

    /**
     * Builds the ui component.
     */
    private void buildUI() {
        // Build ui
        FormLayout layout = new FormLayout("pref:grow",
            "pref, 3dlu, fill:0:grow");
        PanelBuilder builder = new PanelBuilder(layout);
        CellConstraints cc = new CellConstraints();

        int row = 1;
        builder.addSeparator(null, cc.xy(1, row));
        scrollPane = new JScrollPane(foldersList.getUIComponent());
        scrollPane.getVerticalScrollBar().setUnitIncrement(10);
        foldersList.setScroller(scrollPane);
        UIUtil.removeBorder(scrollPane);

        // emptyLabel and scrollPane occupy the same slot.
        row += 2;
        buildEmptyPanel();
        builder.add(emptyPanelOuter, cc.xywh(1,  row, 1, 1));
        builder.add(scrollPane, cc.xywh(1,  row, 1, 1));

        if (!PreferencesEntry.EXPERT_MODE.getValueBoolean(getController())) {
            builder.appendRow("3dlu");
            builder.appendRow("pref");
            row += 2;
            builder.addSeparator("", cc.xy(1, row));
        }

        uiComponent = builder.getPanel();

        updateEmptyLabel();

    }

    private void buildEmptyPanel() {
        FormLayout layoutOuter = new FormLayout("center:pref:grow",
            "center:pref:grow");
        PanelBuilder builderOuter = new PanelBuilder(layoutOuter);
        FormLayout layoutInner = new FormLayout(
            "fill:pref:grow, 3dlu, fill:pref:grow, 3dlu, fill:pref:grow",
            "pref");
        PanelBuilder builderInner = new PanelBuilder(layoutInner);
        CellConstraints cc = new CellConstraints();
        builderInner.add(connectingLabel, cc.xy(1, 1));
        builderInner.add(couldNotConnect, cc.xy(1, 1));
        builderInner.add(notLoggedInLabel, cc.xy(1, 1));
        builderInner.add(loginActionLabel.getUIComponent(), cc.xy(3, 1));
        builderInner.add(noFoldersFoundLabel, cc.xy(1, 1));
        builderInner.add(newFolderActionLabel.getUIComponent(), cc.xy(3, 1));
        if (PreferencesEntry.EXPERT_MODE.getValueBoolean(getController())) {
            builderInner.add(folderWizardActionLabel.getUIComponent(),
                cc.xy(5, 1));
        }
        JPanel emptyPanelInner = builderInner.getPanel();
        builderOuter.add(emptyPanelInner, cc.xy(1, 1));
        emptyPanelOuter = builderOuter.getPanel();
    }

    public void updateEmptyLabel() {
        if (foldersList == null) {
            return;
        }
        if (emptyPanelOuter != null) {
            if (foldersList.isEmpty()) {
                String username = client.getUsername();
                if (client.getServer().isUnableToConnect()
                    && !client.isConnected())
                {
                    connectingLabel.setVisible(false);
                    couldNotConnect.setVisible(true);
                    notLoggedInLabel.setVisible(false);
                    loginActionLabel.setVisible(false);
                    noFoldersFoundLabel.setVisible(false);
                    if (getController().getOSClient()
                        .isAllowedToCreateFolders())
                    {
                        folderWizardActionLabel.setVisible(false);
                        newFolderActionLabel.setVisible(false);
                        if (newFolderLink != null) {
                            newFolderLink.setEnabled(false);
                        }
                    }
                } else if (!client.isConnected()) {
                    connectingLabel.setVisible(true);
                    couldNotConnect.setVisible(false);
                    notLoggedInLabel.setVisible(false);
                    loginActionLabel.setVisible(false);
                    noFoldersFoundLabel.setVisible(false);
                    if (getController().getOSClient()
                        .isAllowedToCreateFolders())
                    {
                        folderWizardActionLabel.setVisible(false);
                        newFolderActionLabel.setVisible(false);
                        if (newFolderLink != null) {
                            newFolderLink.setEnabled(false);
                        }
                    }
                } else if (username == null
                    || username.trim().length() == 0
                    || (client.isPasswordRequired() && client.isPasswordEmpty())
                    || !client.isLoggedIn())
                {
                    connectingLabel.setVisible(false);
                    couldNotConnect.setVisible(false);
                    notLoggedInLabel.setVisible(true);
                    loginActionLabel.setVisible(true);
                    noFoldersFoundLabel.setVisible(false);
                    if (getController().getOSClient()
                        .isAllowedToCreateFolders())
                    {
                        folderWizardActionLabel.setVisible(false);
                        newFolderActionLabel.setVisible(false);
                        if (newFolderLink != null) {
                            newFolderLink.setEnabled(false);
                        }
                    }
                } else {
                    connectingLabel.setVisible(false);
                    couldNotConnect.setVisible(false);
                    notLoggedInLabel.setVisible(false);
                    loginActionLabel.setVisible(false);
                    noFoldersFoundLabel.setVisible(true);
                    if (getController().getOSClient()
                        .isAllowedToCreateFolders())
                    {
                        folderWizardActionLabel.setVisible(true);
                        newFolderActionLabel.setVisible(true);
                        if (newFolderLink != null) {
                            newFolderLink.setEnabled(true);
                        }
                    } else {
                        folderWizardActionLabel.setVisible(false);
                        newFolderActionLabel.setVisible(false);
                        if (newFolderLink != null) {
                            newFolderLink.setEnabled(false);
                        }
                    }
                }
            }
            emptyPanelOuter.setVisible(foldersList.isEmpty());
        }
        if (scrollPane != null) {
            scrollPane.setVisible(!foldersList.isEmpty());
        }
    }

    /**
     * Populates the folders in the list.
     */
    public void populate() {
        foldersList.populate();
    }

    // ////////////////
    // Inner classes //
    // ////////////////

    private class MyLoginAction extends AbstractAction {
        MyLoginAction() {
            boolean changeLoginAllowed = ConfigurationEntry.SERVER_CONNECT_CHANGE_LOGIN_ALLOWED
                .getValueBoolean(getController());
            setEnabled(changeLoginAllowed);
        }

        public void actionPerformed(ActionEvent e) {
            boolean changeLoginAllowed = ConfigurationEntry.SERVER_CONNECT_CHANGE_LOGIN_ALLOWED
                .getValueBoolean(getController());
            if (changeLoginAllowed) {
                PFWizard.openLoginWizard(getController(), getController()
                    .getOSClient());
            }
        }
    }

    private class MyServerClientListener implements ServerClientListener {
        ActionLabel label;

        MyServerClientListener(ActionLabel label) {
            this.label = label;
        }

        public void accountUpdated(ServerClientEvent event) {
            label.setEnabled(getController().getOSClient()
                .isAllowedToCreateFolders());
            updateEmptyLabel();
        }

        public void login(ServerClientEvent event) {
            label.setEnabled(getController().getOSClient()
                .isAllowedToCreateFolders());
            updateEmptyLabel();
        }

        public void nodeServerStatusChanged(ServerClientEvent event) {
            label.setEnabled(getController().getOSClient()
                .isAllowedToCreateFolders());
            updateEmptyLabel();
        }

        @Override
        public void childClientSpawned(ServerClientEvent event) {
        }

        public void serverConnected(ServerClientEvent event) {
            label.setEnabled(getController().getOSClient()
                .isAllowedToCreateFolders());
            updateEmptyLabel();
        }

        public void serverDisconnected(ServerClientEvent event) {
            label.setEnabled(getController().getOSClient()
                .isAllowedToCreateFolders());
            updateEmptyLabel();
        }

        public boolean fireInEventDispatchThread() {
            return true;
        }
    }

}
