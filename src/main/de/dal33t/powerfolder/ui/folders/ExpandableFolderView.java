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
 * $Id: ExpandableFolderView.java 5495 2008-10-24 04:59:13Z harry $
 */
package de.dal33t.powerfolder.ui.folders;

import com.jgoodies.forms.builder.PanelBuilder;
import com.jgoodies.forms.factories.Borders;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import de.dal33t.powerfolder.ConfigurationEntry;
import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.PreferencesEntry;
import de.dal33t.powerfolder.clientserver.RemoteCallException;
import de.dal33t.powerfolder.clientserver.ServerClient;
import de.dal33t.powerfolder.clientserver.ServerClientEvent;
import de.dal33t.powerfolder.clientserver.ServerClientListener;
import de.dal33t.powerfolder.disk.Folder;
import de.dal33t.powerfolder.disk.FolderStatistic;
import de.dal33t.powerfolder.disk.SyncProfile;
import de.dal33t.powerfolder.disk.problem.ResolvableProblem;
import de.dal33t.powerfolder.event.*;
import de.dal33t.powerfolder.light.FileInfo;
import de.dal33t.powerfolder.light.FolderInfo;
import de.dal33t.powerfolder.message.clientserver.AccountDetails;
import de.dal33t.powerfolder.security.FolderPermission;
import de.dal33t.powerfolder.security.FolderRemovePermission;
import de.dal33t.powerfolder.security.Permission;
import de.dal33t.powerfolder.transfer.DownloadManager;
import de.dal33t.powerfolder.transfer.TransferManager;
import de.dal33t.powerfolder.ui.ExpandableView;
import de.dal33t.powerfolder.ui.PFUIComponent;
import de.dal33t.powerfolder.ui.WikiLinks;
import de.dal33t.powerfolder.ui.action.BaseAction;
import de.dal33t.powerfolder.ui.dialog.DialogFactory;
import de.dal33t.powerfolder.ui.dialog.FolderRemoveDialog;
import de.dal33t.powerfolder.ui.dialog.GenericDialogType;
import de.dal33t.powerfolder.ui.event.ExpansionEvent;
import de.dal33t.powerfolder.ui.event.ExpansionListener;
import de.dal33t.powerfolder.ui.folders.ExpandableFolderModel.Type;
import de.dal33t.powerfolder.ui.util.*;
import de.dal33t.powerfolder.ui.widget.ActionLabel;
import de.dal33t.powerfolder.ui.widget.ActivityVisualizationWorker;
import de.dal33t.powerfolder.ui.widget.JButtonMini;
import de.dal33t.powerfolder.ui.widget.ResizingJLabel;
import de.dal33t.powerfolder.ui.wizard.PFWizard;
import de.dal33t.powerfolder.util.*;
import de.dal33t.powerfolder.util.BrowserLauncher.URLProducer;
import de.dal33t.powerfolder.util.os.LinuxUtil;
import de.dal33t.powerfolder.util.os.OSUtil;
import de.dal33t.powerfolder.util.os.Win32.WinUtils;

import javax.swing.*;
import javax.swing.SwingWorker;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import static de.dal33t.powerfolder.disk.FolderStatistic.UNKNOWN_SYNC_STATUS;

/**
 * Class to render expandable view of a folder.
 */
public class ExpandableFolderView extends PFUIComponent implements
    ExpandableView
{

    private FolderInfo folderInfo;
    private Folder folder;
    private Type type;
    private boolean online;
    private boolean admin;
    private final AtomicBoolean focus = new AtomicBoolean();

    private final AtomicBoolean showing100Sync = new AtomicBoolean();

    private ResizingJLabel nameLabel;
    private JButtonMini openSettingsInformationButton;
    private JButtonMini openFilesInformationButton;
    private JButtonMini inviteButton;
    private ActionLabel membersLabel;
    private ActionLabel upperSyncPercentageLabel;

    private JPanel uiComponent;
    private JPanel borderPanel;
    private JPanel lowerOuterPanel;
    private AtomicBoolean expanded;
    private AtomicBoolean mouseOver;

    private ActionLabel filesLabel;
    private ActionLabel deletedFilesLabel;
    private ActionLabel transferModeLabel;
    private ActionLabel localDirectoryLabel;
    private JLabel syncPercentLabel;
    private ActionLabel syncDateLabel;
    private JLabel localSizeLabel;
    private JLabel totalSizeLabel;
    // private ActionLabel filesAvailableLabel;
    private JPanel upperPanel;
    private JButtonMini primaryButton;
    private SyncIconButtonMini upperSyncFolderButton;
    private JButtonMini lowerSyncFolderButton;

    private MyFolderListener myFolderListener;
    private MyFolderMembershipListener myFolderMembershipListener;

    // private MyServerClientListener myServerClientListener;
    private MyTransferManagerListener myTransferManagerListener;
    private MyFolderRepositoryListener myFolderRepositoryListener;
    private MyNodeManagerListener myNodeManagerListener;
    private ToSListener myToSListener;

    private ExpansionListener listenerSupport;

    private OnlineStorageComponent osComponent;
    private ServerClient serverClient;

    private MySyncFolderAction syncFolderAction;
    private MyOpenFilesInformationAction openFilesInformationAction;
    private MyOpenSettingsInformationAction openSettingsInformationAction;
    private MyInviteAction inviteAction;
    private MyOpenMembersInformationAction openMembersInformationAction;
    private MyMostRecentChangesAction mostRecentChangesAction;
    private MyClearCompletedDownloadsAction clearCompletedDownloadsAction;
    private MyOpenExplorerAction openExplorerAction;
    private MoveFolderAction moveFolderLocalAction;
    private FolderRemoveAction removeFolderLocalAction;
    private FolderOnlineRemoveAction removeFolderOnlineAction;
    private BackupOnlineStorageAction backupOnlineStorageAction;
    private StopOnlineStorageAction stopOnlineStorageAction;
    private WebdavAction webdavAction;
    private WebViewAction webViewAction;

    // Performance stuff
    private DelayedUpdater syncUpdater;
    private DelayedUpdater folderUpdater;
    private DelayedUpdater folderDetailsUpdater;

    private String webDAVURL;
    private String ownerDisplayname;
    private String removeLabel;

    /**
     * Constructor
     * 
     * @param controller
     * @param folderInfo
     */
    public ExpandableFolderView(Controller controller, FolderInfo folderInfo) {
        super(controller);
        serverClient = controller.getOSClient();
        this.folderInfo = folderInfo;
        listenerSupport = ListenerSupportFactory
            .createListenerSupport(ExpansionListener.class);
        admin = getController().getOSClient().getAccount()
            .hasAdminPermission(folderInfo);
        initComponent();
        buildUI();
    }

    /**
     * Set the folder for this view. May be null if online storage only, so
     * update visual components if null --> folder or folder --> null
     * 
     * @param folderModel
     */
    public void configure(ExpandableFolderModel folderModel) {
        boolean changed = false;
        Folder beanFolder = folderModel.getFolder();
        FolderInfo beanFolderInfo = folderModel.getFolderInfo().intern();
        Type beanType = folderModel.getType();
        boolean beanOnline = folderModel.isOnline();
        if (beanFolder != null && folder == null) {
            changed = true;
        } else if (beanFolder == null && folder != null) {
            changed = true;
        } else if (beanFolder != null && !folder.equals(beanFolder)) {
            changed = true;
        } else if (!folderInfo.getName().equals(beanFolderInfo.getName())) {
            changed = true;
        } else if (beanType != type) {
            changed = true;
        } else if (beanOnline ^ online) {
            changed = true;
        }

        if (!changed) {
            return;
        }

        // Something changed - change details.
        unregisterFolderListeners();

        type = beanType;
        folder = beanFolder;
        folderInfo = beanFolderInfo;
        online = beanOnline;
        osComponent.setFolder(beanFolder);

        updateStatsDetails();
        updateNumberOfFiles();
        updateTransferMode();
        updateIconAndOS();
        updateLocalButtons();
        updateNameLabel();
        updatePermissions();
        updateDeletedFiles();

        registerFolderListeners();
    }

    /**
     * Expand this view if collapsed.
     */
    public void expand() {
        // Only actually expand local folders in advanced mode,
        // but we still need to fire the reset to clear others' focus.
        if (PreferencesEntry.EXPERT_MODE.getValueBoolean(getController())
            && type == Type.Local)
        {
            expanded.set(true);
            retrieveAdditionalInfosFromServer();
            if (PreferencesEntry.EXPERT_MODE
                    .getValueBoolean(getController()))
            {
                upperPanel.setToolTipText(Translation
                    .get("exp_folder_view.collapse"));
            } else {
                upperPanel.setToolTipText(Translation
                    .get("exp_folder_view.remove"));
            }
            updateNameLabel();
            lowerOuterPanel.setVisible(true);
        }
        listenerSupport.resetAllButSource(new ExpansionEvent(this));
    }

    /**
     * Collapse this view if expanded.
     */
    public void collapse() {
        expanded.set(false);
        retrieveAdditionalInfosFromServer();
        if (PreferencesEntry.EXPERT_MODE.getValueBoolean(getController()))
        {
            upperPanel.setToolTipText(Translation
                .get("exp_folder_view.expand"));
        } else {
            upperPanel.setToolTipText(Translation
                .get("exp_folder_view.create"));
        }
        updateNameLabel();
        lowerOuterPanel.setVisible(false);
    }

    public void setFocus(boolean focus) {
        this.focus.set(focus);
        updateBorderPanel();
    }

    public boolean hasFocus() {
        return focus.get();
    }

    private void updateBorderPanel() {
        if (focus.get()) {
            borderPanel.setBorder(BorderFactory.createEtchedBorder());
        } else {
            borderPanel.setBorder(BorderFactory.createEmptyBorder());
        }
    }

    // PFC-2850
    private final AtomicBoolean retrieving = new AtomicBoolean(false);
    
    private void retrieveAdditionalInfosFromServer() {
        SwingWorker<Object, Void> worker = new SwingWorker<Object, Void>() {
            protected Object doInBackground() throws Exception {
                if (retrieving.compareAndSet(false, true)) {
                    try {
                        retrieveWebDAVURL();
                        retrieveOwnerDisplayname();
                    } finally {
                        retrieving.set(false);
                    }
                }
                return null;
            }
        };
        worker.execute();
    }

    private synchronized String retrieveWebDAVURL() {
        if (!serverClient.isConnected() || !serverClient.isLoggedIn()) {
            return null;
        }
        if (webDAVURL == null) {
            webDAVURL = serverClient.getFolderService(folderInfo).getWebDAVURL(folderInfo);
            if (webDAVURL == null) {
                // Don't fetch again. It's simply not available.
                webDAVURL = "";
            }
        }
        return webDAVURL;
    }

    private String retrieveOwnerDisplayname() {
        if (!serverClient.isConnected() || !serverClient.isLoggedIn()) {
            return null;
        }
        if (serverClient.getAccount().hasOwnerPermission(folderInfo)) {
            return null;
        }
        if (ownerDisplayname == null) {
            try {
                ownerDisplayname = serverClient.getFolderService(folderInfo).getOwnerDisplayname(folderInfo);
            } catch (RemoteCallException e) {
                logFine("Unsupported/Old server. Not able to retrieve owner name of "
                    + folderInfo.getName() + ". " + e);
            }
            if (ownerDisplayname == null) {
                // Don't fetch again. It's simply not available.
                ownerDisplayname = "";
            } else {
                if (isFine()) {
                    logFine("Owner of " + folderInfo.getName() + ": "
                        + ownerDisplayname);
                }
                updateNameLabel();
            }
        }
        return ownerDisplayname;
    }

    /**
     * Gets the ui component, building if required.
     * 
     * @return
     */
    public JPanel getUIComponent() {
        return uiComponent;
    }

    /**
     * Builds the ui component.
     */
    private void buildUI() {

        // Build ui
        // icon name #-files webdav open
        FormLayout upperLayout = new FormLayout(
        // "pref, 3dlu, pref:grow, 3dlu, pref, 3dlu, pref, 3dlu", "pref");
            "pref, 3dlu, pref:grow, 3dlu, pref, 3dlu", "pref");
        PanelBuilder upperBuilder = new PanelBuilder(upperLayout);
        CellConstraints cc = new CellConstraints();
        updateIconAndOS();

        // Primary and upperSyncFolder buttons share the same slot.
        upperBuilder.add(primaryButton, cc.xy(1, 1));
        upperBuilder.add(upperSyncFolderButton, cc.xy(1, 1));

        MouseAdapter mca = new MyMouseClickAdapter();
        MouseAdapter moa = new MyMouseOverAdapter();
        nameLabel = new ResizingJLabel();
        upperBuilder.add(nameLabel, cc.xy(3, 1));
        nameLabel.addMouseListener(moa);
        nameLabel.addMouseListener(mca); // Because this is the biggest blank
                                         // area where the user might click.
        upperBuilder
            .add(upperSyncPercentageLabel.getUIComponent(), cc.xy(5, 1));
        // upperBuilder.add(filesAvailableLabel.getUIComponent(), cc.xy(7, 1));
        // filesAvailableLabel.getUIComponent().addMouseListener(moa);

        upperPanel = upperBuilder.getPanel();
        upperPanel.setOpaque(false);
        if (type == Type.Local) {
            upperPanel.setToolTipText(Translation
                .get("exp_folder_view.expand"));
        }
        CursorUtils.setHandCursor(upperPanel);
        upperPanel.addMouseListener(moa);
        upperPanel.addMouseListener(mca);

        // Build lower detials with line border.
        FormLayout lowerLayout;
        if (getController().isBackupOnly()) {
            // Skip computers stuff
            lowerLayout = new FormLayout(
                "3dlu, pref, pref:grow, 3dlu, pref, 3dlu",
                "pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, pref");
        } else {
            lowerLayout = new FormLayout(
                "3dlu, pref, pref:grow, 3dlu, pref, 3dlu",
                "pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, 3dlu, pref, pref");
        }
        PanelBuilder lowerBuilder = new PanelBuilder(lowerLayout);

        int row = 1;

        lowerBuilder.addSeparator(null, cc.xywh(1, row, 6, 1));

        row += 2;

        lowerBuilder.add(syncDateLabel.getUIComponent(), cc.xy(2, row));
        lowerBuilder.add(lowerSyncFolderButton, cc.xy(5, row));

        row += 2;

        lowerBuilder.add(syncPercentLabel, cc.xy(2, row));
        lowerBuilder.add(openFilesInformationButton, cc.xy(5, row));

        row += 2;

        lowerBuilder.add(filesLabel.getUIComponent(), cc.xy(2, row));

        row += 2;

        lowerBuilder.add(localSizeLabel, cc.xy(2, row));

        row += 2;

        lowerBuilder.add(totalSizeLabel, cc.xy(2, row));

        row += 2;

        lowerBuilder.add(deletedFilesLabel.getUIComponent(), cc.xy(2, row));

        row += 2;

        lowerBuilder.addSeparator(null, cc.xywh(2, row, 4, 1));

        row += 2;

        // No computers stuff if backup mode.
        if (getController().isBackupOnly()) {
            lowerBuilder.add(transferModeLabel.getUIComponent(), cc.xy(2, row));
            lowerBuilder.add(openSettingsInformationButton, cc.xy(5, row));

            row += 2;

            lowerBuilder.add(localDirectoryLabel.getUIComponent(),
                cc.xy(2, row));

        } else {
            if (ConfigurationEntry.MEMBERS_ENABLED
                .getValueBoolean(getController()))
            {
                lowerBuilder.add(membersLabel.getUIComponent(), cc.xy(2, row));
            }
            if (ConfigurationEntry.SERVER_INVITE_ENABLED
                .getValueBoolean(getController()))
            {
                lowerBuilder.add(inviteButton, cc.xy(5, row));
            }

            if (ConfigurationEntry.MEMBERS_ENABLED
                .getValueBoolean(getController())
                || ConfigurationEntry.SERVER_INVITE_ENABLED
                    .getValueBoolean(getController()))
            {
                row += 2;
                lowerBuilder.addSeparator(null, cc.xywh(2, row, 4, 1));
            }

            if (!PreferencesEntry.EXPERT_MODE
                    .getValueBoolean(getController()))
            {
                row += 2;
                lowerBuilder.add(transferModeLabel.getUIComponent(),
                    cc.xy(2, row));
                lowerBuilder.add(openSettingsInformationButton, cc.xy(5, row));

                row += 2;

                lowerBuilder.add(localDirectoryLabel.getUIComponent(),
                    cc.xy(2, row));
            }

        }

        row++; // Just add one.

        lowerBuilder.add(osComponent.getUIComponent(), cc.xywh(2, row, 4, 1));

        JPanel lowerPanel = lowerBuilder.getPanel();
        lowerPanel.setOpaque(false);

        // Build spacer then lower outer with lower panel
        FormLayout lowerOuterLayout = new FormLayout("pref:grow", "3dlu, pref");
        PanelBuilder lowerOuterBuilder = new PanelBuilder(lowerOuterLayout);
        lowerOuterPanel = lowerOuterBuilder.getPanel();
        lowerOuterPanel.setVisible(false);
        lowerOuterBuilder.add(lowerPanel, cc.xy(1, 2));

        // Build border around upper and lower
        FormLayout borderLayout = new FormLayout("3dlu, pref:grow, 3dlu",
            "3dlu, pref, pref, 3dlu");
        PanelBuilder borderBuilder = new PanelBuilder(borderLayout);
        borderBuilder.add(upperPanel, cc.xy(2, 2));
        JPanel panel = lowerOuterBuilder.getPanel();
        panel.setOpaque(false);
        borderBuilder.add(panel, cc.xy(2, 3));
        borderPanel = borderBuilder.getPanel();
        borderPanel.setOpaque(false);

        // Build ui with vertical space before the next one
        FormLayout outerLayout = new FormLayout("3dlu, pref:grow, 3dlu",
            "pref, 3dlu");
        PanelBuilder outerBuilder = new PanelBuilder(outerLayout);
        outerBuilder.add(borderPanel, cc.xy(2, 1));

        uiComponent = outerBuilder.getPanel();
        uiComponent.setOpaque(false);
    }

    /**
     * Initializes the components.
     */
    private void initComponent() {

        syncUpdater = new DelayedUpdater(getController(), 1000L);
        folderDetailsUpdater = new DelayedUpdater(getController());
        folderUpdater = new DelayedUpdater(getController());

        openFilesInformationAction = new MyOpenFilesInformationAction(
            getController());
        inviteAction = new MyInviteAction(getController());
        inviteAction.allowWith(FolderPermission.admin(folderInfo));

        openSettingsInformationAction = new MyOpenSettingsInformationAction(
            getController());
        openSettingsInformationAction.setEnabled(!getController()
            .isBackupOnly());
        MyMoveLocalFolderAction moveLocalFolderAction = new MyMoveLocalFolderAction(
            getController());
        moveLocalFolderAction.setEnabled(!getController().isBackupOnly());
        openMembersInformationAction = new MyOpenMembersInformationAction(
            getController());
        mostRecentChangesAction = new MyMostRecentChangesAction(getController());
        clearCompletedDownloadsAction = new MyClearCompletedDownloadsAction(
            getController());
        openExplorerAction = new MyOpenExplorerAction(getController());

        moveFolderLocalAction = new MoveFolderAction(getController());
        // Allow to stop local sync even if no folder remove permissions was
        // given.
        removeFolderLocalAction = new FolderRemoveAction(getController());

        if (admin) {
            removeLabel = "action_remove_online_folder_admin";
        } else {
            removeLabel = "action_remove_online_folder";
        };
        // Don't allow to choose action at all if online folder only.
        removeFolderOnlineAction = new FolderOnlineRemoveAction(getController());
        removeFolderOnlineAction.allowWith(FolderRemovePermission.INSTANCE);

        backupOnlineStorageAction = new BackupOnlineStorageAction(
            getController());
        stopOnlineStorageAction = new StopOnlineStorageAction(getController());

        syncFolderAction = new MySyncFolderAction(getController());

        webdavAction = new WebdavAction(getController());
        webViewAction = new WebViewAction(getController());

        expanded = new AtomicBoolean();
        mouseOver = new AtomicBoolean();

        osComponent = new OnlineStorageComponent(getController(), folder);

        primaryButton = new JButtonMini(Icons.getIconById(Icons.BLANK), "");
        primaryButton.addActionListener(new PrimaryButtonActionListener());
        openSettingsInformationButton = new JButtonMini(
            openSettingsInformationAction);

        upperSyncPercentageLabel = new ActionLabel(getController(),
            new MyOpenFilesUnsyncedAction(getController()));
        if (!ConfigurationEntry.FILES_ENABLED.getValueBoolean(getController()))
        {
            upperSyncPercentageLabel.setNeverUnderline(true);
        }
        openFilesInformationButton = new JButtonMini(openFilesInformationAction);
        openFilesInformationButton.setVisible(ConfigurationEntry.FILES_ENABLED
            .getValueBoolean(getController()));

        inviteButton = new JButtonMini(inviteAction);

        upperSyncFolderButton = new SyncIconButtonMini(getController());
        upperSyncFolderButton
            .addActionListener(new PrimaryButtonActionListener());
        upperSyncFolderButton.setVisible(false);

        Icon pIcon = Icons.getIconById(Icons.SYNC_COMPLETE);
        Icon sIcon = Icons.getIconById(Icons.SYNC_ANIMATION[0]);
        if (pIcon.getIconHeight() > sIcon.getIconHeight()) {
            // HACK(tm) when mixing 16x16 sync icon with 24x24 icons
            upperSyncFolderButton.setBorder(Borders
                .createEmptyBorder("6, 6, 6, 6"));
        }

        lowerSyncFolderButton = new JButtonMini(syncFolderAction);

        filesLabel = new ActionLabel(getController(),
            openFilesInformationAction);
        transferModeLabel = new ActionLabel(getController(),
            openSettingsInformationAction);
        localDirectoryLabel = new ActionLabel(getController(),
            moveLocalFolderAction);
        syncPercentLabel = new JLabel();
        syncDateLabel = new ActionLabel(getController(),
            mostRecentChangesAction);
        localSizeLabel = new JLabel();
        totalSizeLabel = new JLabel();
        membersLabel = new ActionLabel(getController(),
            openMembersInformationAction);
        // filesAvailableLabel = new ActionLabel(getController(),
        // new MyFilesAvailableAction());
        deletedFilesLabel = new ActionLabel(getController(),
            new MyDeletedFilesAction());
        updateNumberOfFiles();
        updateStatsDetails();
        updateTransferMode();
        updateLocalButtons();
        updateIconAndOS();
        updatePermissions();

        registerListeners();
    }

    private void updatePermissions() {
        if (getController().isBackupOnly()) {
            backupOnlineStorageAction.setEnabled(false);
            stopOnlineStorageAction.setEnabled(false);
            inviteAction.setEnabled(false);
            return;
        }
        // Update permissions
        Permission folderAdmin = FolderPermission.admin(folderInfo);
        backupOnlineStorageAction.allowWith(folderAdmin);
        stopOnlineStorageAction.allowWith(folderAdmin);
        inviteAction.allowWith(folderAdmin);

        admin = getController().getOSClient().getAccount()
            .hasAdminPermission(folderInfo);
    }

    private void updateLocalButtons() {
        boolean enabled = type == Type.Local;

        openSettingsInformationButton.setEnabled(enabled
            && !getController().isBackupOnly());
        transferModeLabel
            .setEnabled(enabled && !getController().isBackupOnly());
        localDirectoryLabel.setEnabled(enabled
            && !getController().isBackupOnly());
        openSettingsInformationAction.setEnabled(enabled
            && !getController().isBackupOnly());

        openFilesInformationButton.setEnabled(enabled);
        openFilesInformationAction.setEnabled(enabled);

        inviteButton.setEnabled(enabled && !getController().isBackupOnly());
        inviteAction.setEnabled(enabled && !getController().isBackupOnly());

        syncDateLabel.setEnabled(enabled);
        mostRecentChangesAction.setEnabled(enabled);

        membersLabel.setEnabled(enabled);
        openMembersInformationAction.setEnabled(enabled
            && !getController().isBackupOnly());

        openExplorerAction.setEnabled(enabled);

        // Controlled by permission system.
        // removeFolderAction.setEnabled(true);

        updateSyncButton();
    }

    private void updateSyncButton() {
        if (type != Type.Local) {
            upperSyncFolderButton.setVisible(false);
            upperSyncFolderButton.spin(false);
            primaryButton.setVisible(true);
            return;
        }

        // Do Local updates later.
        syncUpdater.schedule(new Runnable() {
            public void run() {
                if (folder == null) {
                    return;
                }
                if (folder.isTransferring()) {
                    primaryButton.setVisible(false);
                    upperSyncFolderButton.setVisible(true);
                    upperSyncFolderButton.spin(true);
                } else {
                    primaryButton.setVisible(true);
                    upperSyncFolderButton.setVisible(false);
                    upperSyncFolderButton.spin(false);
                }
            }
        });
    }

    private void registerListeners() {
        myNodeManagerListener = new MyNodeManagerListener();
        getController().getNodeManager().addNodeManagerListener(
            myNodeManagerListener);

        myTransferManagerListener = new MyTransferManagerListener();
        getController().getTransferManager().addListener(
            myTransferManagerListener);

        myFolderRepositoryListener = new MyFolderRepositoryListener();
        getController().getFolderRepository().addFolderRepositoryListener(
            myFolderRepositoryListener);

        myToSListener = new ToSListener();
        getController().getOSClient().addListener(myToSListener);
    }

    /**
     * Call if this object is being discarded, so that listeners are not
     * orphaned.
     */
    public void unregisterListeners() {
        if (myToSListener != null) {
            getController().getOSClient().removeListener(myToSListener);
        }
        if (myNodeManagerListener != null) {
            getController().getNodeManager().removeNodeManagerListener(
                myNodeManagerListener);
            myNodeManagerListener = null;
        }
        if (myTransferManagerListener != null) {
            getController().getTransferManager().removeListener(
                myTransferManagerListener);
            myTransferManagerListener = null;
        }
        if (myFolderRepositoryListener != null) {
            getController().getFolderRepository()
                .removeFolderRepositoryListener(myFolderRepositoryListener);
            myFolderRepositoryListener = null;
        }
        unregisterFolderListeners();
    }

    /**
     * Register listeners of the folder.
     */
    private void registerFolderListeners() {
        if (folder != null) {
            myFolderListener = new MyFolderListener();
            folder.addFolderListener(myFolderListener);

            myFolderMembershipListener = new MyFolderMembershipListener();
            folder.addMembershipListener(myFolderMembershipListener);
        }
    }

    /**
     * Unregister listeners of the folder.
     */
    private void unregisterFolderListeners() {
        if (folder != null) {
            if (myFolderListener != null) {
                folder.removeFolderListener(myFolderListener);
                myFolderListener = null;
            }
            if (myFolderMembershipListener != null) {
                folder.removeMembershipListener(myFolderMembershipListener);
                myFolderMembershipListener = null;
            }
        }
    }

    /**
     * @return the Info of the associated folder.
     */
    public FolderInfo getFolderInfo() {
        return folderInfo;
    }

    /**
     * Updates the statistics details of the folder.
     */
    private void updateStatsDetails() {
        String syncPercentText;
        String syncPercentTip = null;
        String syncDateText;
        String localSizeString;
        String totalSizeString;
        if (type == Type.Local) {

            Date lastSyncDate = folder.getLastSyncDate();

            if (lastSyncDate == null) {
                syncDateText = Translation
                    .get("exp_folder_view.never_synchronized");
            } else {
                String formattedDate = Format.formatDateShort(lastSyncDate);
                syncDateText = Translation.get(
                    "exp_folder_view.last_synchronized", formattedDate);
            }

            if (folder.hasOwnDatabase()) {
                FolderStatistic statistic = folder.getStatistic();
                double sync = statistic.getHarmonizedSyncPercentage();
                if (sync < UNKNOWN_SYNC_STATUS) {
                    sync = UNKNOWN_SYNC_STATUS;
                }
                if (sync > 100) {
                    sync = 100;
                }

                // Sync in progress? Rewrite date as estimate.
                if (Double.compare(sync, 100.0) < 0
                    && Double.compare(sync, UNKNOWN_SYNC_STATUS) > 0)
                {
                    Date date = folder.getStatistic().getEstimatedSyncDate();
                    if (date != null) {
                        // If ETA sync > 2 days show text:
                        // "Estimated sync: Unknown"
                        // If ETA sync > 20 hours show text:
                        // "Estimated sync: in X days"
                        // If ETA sync > 45 minutes show text:
                        // "Estimated sync: in X hours"
                        // If ETA sync < 45 minutes show text:
                        // "Estimated sync: in X minutes"
                        if (DateUtil.isDateMoreThanNDaysInFuture(date, 2)) {
                            syncDateText = Translation
                                .get("main_frame.sync_eta_unknown");
                        } else if (DateUtil.isDateMoreThanNHoursInFuture(date,
                            20))
                        {
                            int days = DateUtil.getDaysInFuture(date);
                            if (days <= 1) {
                                syncDateText = Translation
                                    .get("exp_folder_view.sync_eta_one_day");
                            } else {
                                syncDateText = Translation.get(
                                    "exp_folder_view.sync_eta_days",
                                    String.valueOf(days));
                            }
                        } else if (DateUtil.isDateMoreThanNMinutesInFuture(
                            date, 45))
                        {
                            int hours = DateUtil.getDaysInFuture(date);
                            if (hours <= 1) {
                                syncDateText = Translation
                                    .get("exp_folder_view.sync_eta_one_hour");
                            } else {
                                syncDateText = Translation.get(
                                    "exp_folder_view.sync_eta_hours",
                                    String.valueOf(hours));
                            }
                        } else {
                            int minutes = DateUtil.getHoursInFuture(date);
                            if (minutes <= 1) {
                                syncDateText = Translation
                                    .get("exp_folder_view.sync_eta_one_minute");
                            } else {
                                syncDateText = Translation.get(
                                    "exp_folder_view.sync_eta_minutes",
                                    String.valueOf(minutes));

                            }
                        }
                    }
                }

                if (lastSyncDate == null
                    && (Double.compare(sync, 100.0) == 0 || Double.compare(
                        sync, UNKNOWN_SYNC_STATUS) == 0))
                {
                    // Never synced with others.
                    syncPercentText = Translation
                        .get("exp_folder_view.unsynchronized");
                    showing100Sync.set(false);
                } else {
                    showing100Sync.set(Double.compare(sync, 100) == 0);
                    if (Double.compare(sync, UNKNOWN_SYNC_STATUS) == 0) {
                        if (folder.getCompletelyConnectedMembersCount() >= 1) {
                            syncPercentText = Translation
                                .get("exp_folder_view.unsynchronized");
                            syncPercentTip = Translation
                                .get("exp_folder_view.unsynchronized.tip");
                        } else {
                            syncPercentText = "";
                            syncPercentTip = "";
                        }
                    } else {
                        syncPercentText = Translation.get(
                            "exp_folder_view.synchronized",
                            Format.formatPercent(sync));
                        // Workaround: Prevent double %%
                        syncPercentText = syncPercentText.replace("%%", "%");
                    }
                }

                if (lastSyncDate != null && Double.compare(sync, 100.0) == 0) {
                    // 100% sync - remove any sync problem.
                    folder.checkSync();
                }

                long localSize = statistic.getLocalSize();
                localSizeString = Format.formatBytesShort(localSize);

                long totalSize = statistic.getTotalSize();
                totalSizeString = Format.formatBytesShort(totalSize);

                if (sync >= 0 && sync < 100) {
                    upperSyncPercentageLabel
                        .setText(Format.formatPercent(sync));
                } else {
                    upperSyncPercentageLabel.setText("");
                }
            } else {
                upperSyncPercentageLabel.setText("");
                showing100Sync.set(false);
                syncPercentText = Translation
                    .get("exp_folder_view.not_yet_scanned");
                localSizeString = "?";
                totalSizeString = "?";
            }
        } else {
            upperSyncPercentageLabel.setText("");
            syncPercentText = Translation.get(
                "exp_folder_view.synchronized", "?");
            syncDateText = Translation.get(
                "exp_folder_view.last_synchronized", "?");
            localSizeString = "?";
            totalSizeString = "?";
        }

        syncPercentLabel.setText(syncPercentText);
        syncPercentLabel.setToolTipText(syncPercentTip);
        syncDateLabel.setText(syncDateText);
        localSizeLabel.setText(Translation.get(
            "exp_folder_view.local", localSizeString));
        totalSizeLabel.setText(Translation.get(
            "exp_folder_view.total", totalSizeString));
        // Maybe change visibility of upperSyncLink.
        retrieveAdditionalInfosFromServer();
    }

    /**
     * Updates the number of files details of the folder.
     */
    private void updateNumberOfFiles() {
        String filesText;
        if (type == Type.Local) {
            // FIXME: Returns # of files + # of directories
            filesText = Translation.get("exp_folder_view.files",
                String.valueOf(folder.getStatistic().getLocalFilesCount()));
        } else {
            filesText = Translation
                .get("exp_folder_view.files", "?");
        }
        filesLabel.setText(filesText);
    }

    private void updateDeletedFiles() {
        String deletedFileText;
        if (type == Type.Local) {
            Collection<FileInfo> allFiles = folder.getDAO().findAllFiles(
                getController().getMySelf().getId());
            int deletedCount = 0;
            for (FileInfo file : allFiles) {
                if (file.isDeleted()) {
                    deletedCount++;
                }
            }
            deletedFileText = Translation.get(
                "exp_folder_view.deleted_files", String.valueOf(deletedCount));
        } else {
            deletedFileText = Translation.get(
                "exp_folder_view.deleted_files", "?");
        }
        deletedFilesLabel.setText(deletedFileText);
    }

    /**
     * Updates transfer mode of the folder.
     */
    private void updateTransferMode() {
        String transferMode;
        if (type == Type.Local) {
            transferMode = Translation.get(
                "exp_folder_view.transfer_mode", folder.getSyncProfile()
                    .getName());
            String path = folder.getCommitOrLocalDir().toAbsolutePath()
                .toString();
            if (path.length() >= 35) {
                path = path.substring(0, 15) + "..."
                    + path.substring(path.length() - 15, path.length());
            }
            localDirectoryLabel.setVisible(true);
            localDirectoryLabel.setText(path);
        } else {
            transferMode = Translation.get(
                "exp_folder_view.transfer_mode", "?");
            localDirectoryLabel.setVisible(false);
        }
        transferModeLabel.setText(transferMode);
    }

    /**
     * Gets called externally to update the display of problems.
     */
    public void updateIconAndOS() {
        boolean osComponentVisible = getController().getOSClient()
            .isBackupByDefault() && !getController().isBackupOnly();
        if (type == Type.Local) {

            double sync = folder.getStatistic().getHarmonizedSyncPercentage();
            if (folder != null && folder.countProblems() > 0) {
                // Got a problem.
                primaryButton.setIcon(Icons.getIconById(Icons.PROBLEMS));
                primaryButton.setToolTipText(Translation
                    .get("exp_folder_view.folder_problem_text"));
            } else if (getController().isPaused()
                && Double.compare(sync, 100.0d) < 0
                && sync != FolderStatistic.UNKNOWN_SYNC_STATUS)
            {
                // Sync is in pause
                primaryButton.setIcon(Icons.getIconById(Icons.PAUSE));
                primaryButton.setToolTipText(Translation
                    .get("exp_folder_view.folder_sync_paused"));
            } else if (Double.compare(sync, 100.0d) < 0) {
                // Not synced and not syncing.
                primaryButton.setIcon(Icons.getIconById(Icons.SYNC_INCOMPLETE));
                primaryButton.setToolTipText(Translation
                    .get("exp_folder_view.folder_sync_incomplete"));
            } else {
                // We are in sync.
                primaryButton.setIcon(Icons.getIconById(Icons.LOCAL_FOLDER));
                if (PreferencesEntry.EXPERT_MODE
                        .getValueBoolean(getController()))
                {
                    primaryButton
                    .setToolTipText(Translation
                        .get("exp_folder_view.folder_sync_complete"));
                } else {
                    primaryButton.setToolTipText(Translation
                        .get("exp_folder_view.explore"));
                }
            }
        } else if (type == Type.Typical) {
            primaryButton.setIcon(Icons.getIconById(Icons.TYPICAL_FOLDER));
            primaryButton.setToolTipText(Translation
                .get("exp_folder_view.folder_typical_text"));
            osComponent.getUIComponent().setVisible(false);
        } else { // CloudOnly
            primaryButton.setIcon(Icons.getIconById(Icons.ONLINE_FOLDER));
            primaryButton.setToolTipText(Translation
                .get("exp_folder_view.folder_online_text"));
            osComponent.getUIComponent().setVisible(osComponentVisible);
        }

        osComponent.getUIComponent().setVisible(osComponentVisible);
        if (osComponentVisible) {
            double sync = 0;
            if (folder != null) {
                sync = folder.getStatistic().getServerSyncPercentage();
            }
            boolean warned = serverClient.getAccountDetails().getAccount()
                    .getOSSubscription().isDisabledUsage();
            boolean joined = folder != null
                    && serverClient.joinedByCloud(folder);
            osComponent.setSyncPercentage(sync, warned, joined);
        }
    }

    public void addExpansionListener(ExpansionListener listener) {
        ListenerSupportFactory.addListener(listenerSupport, listener);
    }

    public void removeExpansionListener(ExpansionListener listener) {
        ListenerSupportFactory.removeListener(listenerSupport, listener);
    }

    /**
     * Is the view expanded?
     * 
     * @return
     */
    public boolean isExpanded() {
        return expanded.get();
    }

    public JPopupMenu createPopupMenu() {
        JPopupMenu contextMenu = new JPopupMenu();
        if (type == Type.CloudOnly) {
            // Cloud-only folder popup
            retrieveWebDAVURL();
            if (StringUtils.isNotBlank(webDAVURL)) {
                if (serverClient.supportsWebDAV()) {
                    contextMenu.add(webdavAction).setIcon(null);
                }
                if (serverClient.supportsWebLogin()) {
                    contextMenu.add(webViewAction).setIcon(null);
                }
            }
            contextMenu.add(removeFolderOnlineAction).setIcon(null);
        } else {
            // Local folder popup
            contextMenu.add(openExplorerAction).setIcon(null);
            contextMenu.addSeparator();
            boolean expert = PreferencesEntry.EXPERT_MODE
                .getValueBoolean(getController());
            if (expert) {
                if (!folder.getSyncProfile().equals(SyncProfile.AUTOMATIC_SYNCHRONIZATION)) {
                    contextMenu.add(syncFolderAction).setIcon(null);
                }
                contextMenu.add(openFilesInformationAction).setIcon(null);
                contextMenu.add(mostRecentChangesAction).setIcon(null);
                contextMenu.add(clearCompletedDownloadsAction).setIcon(null);
            }
            if (!getController().isBackupOnly()) {
                boolean addedSeparator = false;
                if (ConfigurationEntry.SERVER_INVITE_ENABLED
                    .getValueBoolean(getController()) && admin)
                {
                    contextMenu.addSeparator();
                    addedSeparator = true;
                    contextMenu.add(inviteAction).setIcon(null);
                }
                if (expert) {
                    if (!addedSeparator) {
                        contextMenu.addSeparator();
                    }
                    contextMenu.add(openMembersInformationAction).setIcon(null);
                }
            }
            contextMenu.addSeparator();
            if (ConfigurationEntry.SETTINGS_ENABLED
                .getValueBoolean(getController()))
            {
                contextMenu.add(openSettingsInformationAction).setIcon(null);
            }
            contextMenu.add(moveFolderLocalAction).setIcon(null);
            if (getController().getOSClient().isAllowedToRemoveFolders()) {
                contextMenu.add(removeFolderLocalAction).setIcon(null);
            }
            if (expert && serverClient.isConnected()
                && serverClient.isLoggedIn())
            {
                boolean osConfigured = serverClient.joinedByCloud(folder);
                if (osConfigured) {
                    contextMenu.add(stopOnlineStorageAction).setIcon(null);
                } else {
                    contextMenu.add(backupOnlineStorageAction).setIcon(null);
                }
            }
        }
        return contextMenu;
    }

    private void openExplorer() {
        // PFC-2349 : Don't freeze UI
        getController().getIOProvider().startIO(new Runnable() {
            public void run() {
                PathUtils.openFile(folder.getCommitOrLocalDir());
            }
        });
    }

    /**
     * Downloads added or removed for this folder. Recalculate new files status.
     * Or if expanded / collapsed - might need to change tool tip.
     */
    public void updateNameLabel() {

        boolean newFiles = false;
        String newCountString = "";
        String ownerAddition = "";

        if (folder != null) {
            int newCount = getController().getTransferManager()
                .countCompletedDownloads(folder);
            // #PFC-2497 Do not show new Files in Beginner mode
            if (PreferencesEntry.EXPERT_MODE
                .getValueBoolean(getController()))
            {
                newFiles = newCount > 0;
            }
            if (newFiles) {
                newCountString = " (" + newCount + ')';
                nameLabel.setToolTipText(Translation.get(
                    "exp_folder_view.new_files_tip_text",
                    String.valueOf(newCount)));
            }
        }

        if (!newFiles && folder != null) {
            if (expanded.get()) {
                nameLabel.setToolTipText(Translation
                    .get("exp_folder_view.collapse"));
            } else {
                if (PreferencesEntry.EXPERT_MODE
                        .getValueBoolean(getController()))
                {
                    nameLabel.setToolTipText(Translation
                        .get("exp_folder_view.expand"));
                } else {
                    nameLabel.setToolTipText(Translation
                        .get("exp_folder_view.remove"));
                }
            }
        }

        if (folder == null) {
            nameLabel.setToolTipText(Translation
                .get("exp_folder_view.folder_online_text"));
        }

        String folderName = folderInfo.getLocalizedName();

        if (StringUtils.isNotBlank(ownerDisplayname)) {
            ownerAddition += " (" + ownerDisplayname + ")";
        }

        nameLabel.setText(folderName + newCountString + ownerAddition);
        nameLabel.setFont(new Font(nameLabel.getFont().getName(), newFiles
            ? Font.BOLD
            : Font.PLAIN, nameLabel.getFont().getSize()));
        clearCompletedDownloadsAction.setEnabled(newFiles);
    }

    /**
     * Create a WebDAV connection to this folder. Should be something like 'net
     * use * "https://my.powerfolder.com/webdav/afolder"
     * /User:bob@powerfolder.com pazzword'
     */
    private void createWebdavConnection() {
        ActivityVisualizationWorker worker = new ActivityVisualizationWorker(
            getUIController())
        {
            protected String getTitle() {
                return Translation
                    .get("exp_folder_view.webdav_title");
            }

            protected String getWorkingText() {
                return Translation
                    .get("exp_folder_view.webdav_working_text");
            }

            public Object construct() throws Throwable {
                try {
                    retrieveWebDAVURL();

                    /* Handle different OSes */
                    if(OSUtil.isLinux()) {
                        return LinuxUtil.mountWebDAV(serverClient, webDAVURL);
                    } else {
                        WinUtils util = WinUtils.getInstance();

                        if (util != null) {
                            return util.mountWebDAV(serverClient, webDAVURL);
                        }
                    }

                    return 'N';
                } catch (Exception e) {
                    // Looks like the link failed, badly :-(
                    logSevere(e.getMessage(), e);
                    return 'N' + e.getMessage();
                }
            }

            public void finished() {

                // See what happened.
                String result = (String) get();
                if (result != null) {
                    if (result.startsWith("Y")) {
                        String[] parts = result.substring(1).split("\\s");
                        for (final String part : parts) {
                            if (part.length() == 2 && part.charAt(1) == ':') {
                                // Probably the new drive name, so open it.
                                getController().getIOProvider().startIO(
                                    new Runnable() {
                                        public void run() {
                                            PathUtils.openFile(Paths.get(part));
                                        }
                                    });

                                break;
                            }
                        }
                    } else if (result.startsWith("N")) {
                        String[] ops;
                        if (Help.hasWiki(getController())) {
                            ops = new String[]{
                                Translation.get("general.ok"),
                                Translation.get("general.help")};
                        } else {
                            ops = new String[]{Translation
                                .get("general.ok")};
                        }
                        int op = DialogFactory
                            .genericDialog(
                                getController(),
                                Translation
                                    .get("exp_folder_view.webdav_failure_title"),
                                Translation.get(
                                    "exp_folder_view.webdav_failure_text",
                                    result.substring(1)), ops, 0,
                                GenericDialogType.ERROR);
                        if (op == 1) {
                            Help.openWikiArticle(getController(),
                                WikiLinks.WEBDAV);
                        }
                    }
                }
            }
        };
        worker.start();
    }

    /**
     * See if user wants to create this typical folder.
     */
    private void askToCreateFolder() {
        if (type != Type.Typical) {
            logSevere("Folder " + folderInfo.getName() + " is not Typical");
            return;
        }
        PFWizard.openTypicalFolderJoinWizard(getController(), folderInfo);
    }

    // ////////////////
    // Inner Classes //
    // ////////////////

    private class MyNodeManagerListener extends NodeManagerAdapter {
        private void updateIfRequired(NodeManagerEvent e) {
            if (folder != null && folder.hasMember(e.getNode())) {
                doFolderChanges(folder);
            }
        }

        public void nodeConnected(NodeManagerEvent e) {
            updateIfRequired(e);
        }

        public void nodeDisconnected(NodeManagerEvent e) {
            updateIfRequired(e);
        }

        public void friendAdded(NodeManagerEvent e) {
            updateIfRequired(e);
        }

        public void friendRemoved(NodeManagerEvent e) {
            updateIfRequired(e);
        }

        @Override
        public void settingsChanged(NodeManagerEvent e) {
            updateIfRequired(e);
        }

        public boolean fireInEventDispatchThread() {
            return true;
        }
    }

    private void doFolderChanges(Folder eventFolder) {
        if (folder == null || folder.equals(eventFolder)) {
            folderUpdater.schedule(new Runnable() {
                public void run() {
                    updateNumberOfFiles();
                    updateDeletedFiles();
                    updateStatsDetails();
                    updateIconAndOS();
                    updateLocalButtons();
                    updateTransferMode();
                    updatePermissions();
                }
            });
        }
    }

    /**
     * Class to respond to folder events.
     */
    private class MyFolderListener implements FolderListener {

        public void statisticsCalculated(FolderEvent folderEvent) {
            doFolderChanges(folderEvent.getFolder());
        }

        public void fileChanged(FolderEvent folderEvent) {
            doFolderChanges(folderEvent.getFolder());
        }

        public void filesDeleted(FolderEvent folderEvent) {
            doFolderChanges(folderEvent.getFolder());
        }

        public void remoteContentsChanged(FolderEvent folderEvent) {
            if (folderEvent.getMember().hasCompleteFileListFor(
                folderEvent.getFolder().getInfo()))
            {
                doFolderChanges(folderEvent.getFolder());
            }
        }

        public void scanResultCommitted(FolderEvent folderEvent) {
            if (folderEvent.getScanResult().isChangeDetected()) {
                doFolderChanges(folderEvent.getFolder());
            }
        }

        public void syncProfileChanged(FolderEvent folderEvent) {
            doFolderChanges(folderEvent.getFolder());
        }

        public void archiveSettingsChanged(FolderEvent folderEvent) {
            doFolderChanges(folderEvent.getFolder());
        }

        @Override
        public void archivePurged(FolderEvent folderEvent) {
            doFolderChanges(folderEvent.getFolder());
        }

        public boolean fireInEventDispatchThread() {
            return true;
        }
    }

    /**
     * Class to respond to folder membership events.
     */
    private class MyFolderMembershipListener implements
        FolderMembershipListener
    {

        public void memberJoined(FolderMembershipEvent folderEvent) {
            doFolderChanges(folder);
        }

        public void memberLeft(FolderMembershipEvent folderEvent) {
            doFolderChanges(folder);
        }

        public boolean fireInEventDispatchThread() {
            return true;
        }
    }

    private class MyFolderRepositoryListener extends FolderRepositoryAdapter {

        private void updateIfRequired(FolderRepositoryEvent e) {
            if (folder == null || !folder.equals(e.getFolder())) {
                return;
            }
            updateSyncButton();
            updateIconAndOS();
        }

        public void maintenanceFinished(FolderRepositoryEvent e) {
            updateIfRequired(e);
        }

        public void maintenanceStarted(FolderRepositoryEvent e) {
            updateIfRequired(e);
        }

        public boolean fireInEventDispatchThread() {
            return true;
        }
    }

    private class MyTransferManagerListener extends TransferManagerAdapter {

        private void updateIfRequired(TransferManagerEvent event) {
            if (folder == null
                || !folderInfo.equals(event.getFile().getFolderInfo()))
            {
                return;
            }
            updateSyncButton();
            updateIconAndOS();
        }

        @Override
        public void downloadAborted(TransferManagerEvent event) {
            updateIfRequired(event);
        }

        @Override
        public void downloadBroken(TransferManagerEvent event) {
            updateIfRequired(event);
        }

        @Override
        public void downloadCompleted(TransferManagerEvent event) {
            updateIfRequired(event);
        }

        @Override
        public void downloadQueued(TransferManagerEvent event) {
            updateIfRequired(event);
        }

        @Override
        public void downloadRequested(TransferManagerEvent event) {
            updateIfRequired(event);
        }

        @Override
        public void downloadStarted(TransferManagerEvent event) {
            updateIfRequired(event);
        }

        @Override
        public void uploadAborted(TransferManagerEvent event) {
            updateIfRequired(event);
        }

        @Override
        public void uploadBroken(TransferManagerEvent event) {
            updateIfRequired(event);
        }

        @Override
        public void uploadCompleted(TransferManagerEvent event) {
            updateIfRequired(event);
        }

        @Override
        public void uploadRequested(TransferManagerEvent event) {
            updateIfRequired(event);
        }

        @Override
        public void uploadStarted(TransferManagerEvent event) {
            updateIfRequired(event);
        }

        public boolean fireInEventDispatchThread() {
            return true;
        }

    }

    private class ToSListener implements ServerClientListener {
        @Override
        public boolean fireInEventDispatchThread() {
            return false;
        }

        @Override
        public void login(ServerClientEvent event) {
            updateSynableItems();
        }

        @Override
        public void accountUpdated(ServerClientEvent event) {
            updateSynableItems();
        }

        @Override
        public void serverConnected(ServerClientEvent event) {
            updateSynableItems();
        }

        @Override
        public void serverDisconnected(ServerClientEvent event) {
            updateSynableItems();
        }

        @Override
        public void nodeServerStatusChanged(ServerClientEvent event) {
            updateSynableItems();
        }

        private void updateSynableItems() {
            AccountDetails ad = getController().getOSClient()
                .getAccountDetails();
            if (ad != null && ad.needsToAgreeToS()) {
                upperSyncFolderButton.setEnabled(false);
                lowerSyncFolderButton.setEnabled(false);
                syncFolderAction.setEnabled(false);
                primaryButton.setEnabled(false);
            } else {
                upperSyncFolderButton.setEnabled(true);
                lowerSyncFolderButton.setEnabled(true);
                syncFolderAction.setEnabled(true);
                primaryButton.setEnabled(true);
            }
        }
    }

    /** Hover over any component in the upper panel should expand / collapse. */
    private class MyMouseOverAdapter extends MouseAdapter {

        // Auto expand if user hovers for two seconds.
        public void mouseEntered(MouseEvent e) {
            mouseOver.set(true);
            if (PreferencesEntry.AUTO_EXPAND.getValueBoolean(getController())) {
                if (!expanded.get()) {
                    getController().schedule(() -> {
                        if (mouseOver.get()) {
                            if (!expanded.get()) {
                                expand();
                                PreferencesEntry.AUTO_EXPAND
                                    .setValue(getController(), Boolean.FALSE);
                            }
                        }
                    } , 2000);
                }
            }
            retrieveAdditionalInfosFromServer();
        }

        public void mouseExited(MouseEvent e) {
            mouseOver.set(false);
            retrieveAdditionalInfosFromServer();
        }
    }

    /** Click on the upper panel should expand or display context menu */
    private class MyMouseClickAdapter extends MouseAdapter {

        public void mousePressed(MouseEvent e) {
            if (e.isPopupTrigger()) {
                showContextMenu(e);
            }
        }

        public void mouseReleased(MouseEvent e) {
            if (e.isPopupTrigger()) {
                showContextMenu(e);
            }
        }

        private void showContextMenu(MouseEvent evt) {
            Cursor c = CursorUtils.setWaitCursor(upperPanel);
            try {
                createPopupMenu().show(evt.getComponent(), evt.getX(),
                    evt.getY());
            } finally {
                CursorUtils.returnToOriginal(upperPanel, c);
            }
        }

        public void mouseClicked(MouseEvent e) {
            if (e.getButton() == MouseEvent.BUTTON1) {
                setFocus(true);
                if (expanded.get()) {
                    collapse();
                } else {
                    expand();
                    if (type == Type.Local) {
                        boolean openedTab = getController().getUIController()
                            .openFilesInformation(folderInfo);
                        if (!openedTab) {
                            openExplorer();
                        }
                    }
                    if (type == Type.CloudOnly && folderInfo != null) {
                        PFWizard.openOnlineStorageJoinWizard(getController(),
                            Collections.singletonList(folderInfo));
                    }
                    if (type == Type.Typical) {
                        askToCreateFolder();
                    }
                }
            }
        }
    }

    // Action to invite friend.
    @SuppressWarnings("serial")
    private class MyInviteAction extends BaseAction {

        private MyInviteAction(Controller controller) {
            super("action_invite_friend", controller);
        }

        public void actionPerformed(ActionEvent e) {
            PFWizard.openSendInvitationWizard(getController(), folderInfo);
        }
    }

    @SuppressWarnings("serial")
    private class MyOpenSettingsInformationAction extends BaseAction {
        private MyOpenSettingsInformationAction(Controller controller) {
            super("action_open_settings_information", controller);
        }

        public void actionPerformed(ActionEvent e) {
            getController().getUIController().openSettingsInformation(
                folderInfo);
        }
    }

    @SuppressWarnings("serial")
    private class MyMoveLocalFolderAction extends BaseAction {
        private MyMoveLocalFolderAction(Controller controller) {
            super("action_move_local_folder", controller);
        }

        public void actionPerformed(ActionEvent e) {
            getController().getUIController().moveLocalFolder(folderInfo);
        }
    }

    @SuppressWarnings("serial")
    private class MyOpenFilesInformationAction extends BaseAction {

        MyOpenFilesInformationAction(Controller controller) {
            super("action_open_files_information", controller);
        }

        public void actionPerformed(ActionEvent e) {
            getController().getUIController().openFilesInformation(folderInfo);
        }
    }

    @SuppressWarnings("serial")
    private class MyOpenFilesUnsyncedAction extends BaseAction {

        MyOpenFilesUnsyncedAction(Controller controller) {
            super("action_open_files_unsynced", controller);
        }

        public void actionPerformed(ActionEvent e) {
            getController().getUIController().openFilesInformationUnsynced(
                folderInfo);
        }
    }

    @SuppressWarnings("serial")
    private class MyOpenMembersInformationAction extends BaseAction {

        MyOpenMembersInformationAction(Controller controller) {
            super("action_open_members_information", controller);
        }

        public void actionPerformed(ActionEvent e) {
            getController().getUIController()
                .openMembersInformation(folderInfo);
        }
    }

    @SuppressWarnings("serial")
    private class MySyncFolderAction extends BaseAction {

        private MySyncFolderAction(Controller controller) {
            super("action_sync_folder", controller);
        }

        public void actionPerformed(ActionEvent e) {
            getApplicationModel().syncFolder(folder);
        }
    }

    @SuppressWarnings("serial")
    private class FolderRemoveAction extends BaseAction {

        private FolderRemoveAction(Controller controller) {
            super("action_remove_folder", controller);
        }

        public void actionPerformed(ActionEvent e) {
            FolderRemoveDialog panel = new FolderRemoveDialog(getController(),
                folderInfo);
            panel.open();
        }
    }

    @SuppressWarnings("serial")
    private class MoveFolderAction extends BaseAction {
        private MoveFolderAction(Controller controller) {
            super("action_move_folder", controller);
        }

        public void actionPerformed(ActionEvent e) {
            getController().getUIController().getApplicationModel()
                .moveLocalFolder(folder);
        }
    }

    @SuppressWarnings("serial")
    private class FolderOnlineRemoveAction extends BaseAction {

        private FolderOnlineRemoveAction(Controller controller) {
            super(removeLabel, controller);
        }

        public void actionPerformed(ActionEvent e) {
            FolderRemoveDialog panel = new FolderRemoveDialog(getController(),
                folderInfo);
            panel.open();
        }
    }

    // private class MyProblemAction extends BaseAction {
    //
    // private MyProblemAction(Controller controller) {
    // super("action_folder_problem", controller);
    // }
    //
    // public void actionPerformed(ActionEvent e) {
    // getController().getUIController().openProblemsInformation(
    // folderInfo);
    // }
    // }
    //
    @SuppressWarnings("serial")
    private class MyClearCompletedDownloadsAction extends BaseAction {

        private MyClearCompletedDownloadsAction(Controller controller) {
            super("exp.action_clear_completed_downloads", controller);
        }

        public void actionPerformed(ActionEvent e) {
            TransferManager transferManager = getController()
                .getTransferManager();
            for (DownloadManager dlMan : transferManager
                .getCompletedDownloadsCollection())
            {
                if (dlMan.getFileInfo().getFolderInfo()
                    .equals(folder.getInfo()))
                {
                    transferManager.clearCompletedDownload(dlMan);
                }
            }
        }
    }

    @SuppressWarnings("serial")
    private class MyMostRecentChangesAction extends BaseAction {

        private MyMostRecentChangesAction(Controller controller) {
            super("action_most_recent_changes", controller);
        }

        public void actionPerformed(ActionEvent e) {
            getController().getUIController().openFilesInformationLatest(
                folderInfo);
        }
    }

    // private class MyFilesAvailableAction extends AbstractAction {
    //
    // public void actionPerformed(ActionEvent e) {
    // getController().getUIController().openFilesInformationIncoming(
    // folderInfo);
    // }
    // }

    @SuppressWarnings("serial")
    private class MyDeletedFilesAction extends AbstractAction {

        public void actionPerformed(ActionEvent e) {
            getController().getUIController().openFilesInformationDeleted(
                folderInfo);
        }
    }

    @SuppressWarnings("serial")
    private class MyOpenExplorerAction extends BaseAction {

        private MyOpenExplorerAction(Controller controller) {
            super("action_open_explorer", controller);
        }

        public void actionPerformed(ActionEvent e) {
            openExplorer();
        }
    }

    @SuppressWarnings("serial")
    private class BackupOnlineStorageAction extends BaseAction {
        private BackupOnlineStorageAction(Controller controller) {
            super("exp.action_backup_online_storage", controller);
        }

        public void actionPerformed(ActionEvent e) {
            // FolderOnlineStoragePanel knows if folder already joined :-)
            getUIController().getApplicationModel().getServerClientModel()
                .checkAndSetupAccount();
            PFWizard.openMirrorFolderWizard(getController(), folder);
        }
    }

    @SuppressWarnings("serial")
    private class StopOnlineStorageAction extends BaseAction {
        private StopOnlineStorageAction(Controller controller) {
            super("exp.action_stop_online_storage", controller);
        }

        public void actionPerformed(ActionEvent e) {
            // FolderOnlineStoragePanel knows if folder already joined :-)
            getUIController().getApplicationModel().getServerClientModel()
                .checkAndSetupAccount();
            PFWizard.openMirrorFolderWizard(getController(), folder);
        }
    }

    private class PrimaryButtonActionListener implements ActionListener {
        public void actionPerformed(ActionEvent e) {
            if (type == Type.Local && folder != null
                && folder.countProblems() > 0)
            {
                if (!ConfigurationEntry.PROBLEMS_ENABLED
                    .getValueBoolean(getController()))
                {
                    ResolvableProblem prob = (ResolvableProblem) folder
                        .getProblems().get(0);
                    DialogFactory.genericDialog(getController(),
                        prob.getDescription(), prob.getDescription(),
                        GenericDialogType.WARN);
                    SwingUtilities
                        .invokeLater(prob.resolution(getController()));
                    folder.removeProblem(prob);
                } else {
                    // Display the problem.
                    getController().getUIController().openProblemsInformation(
                        folderInfo);
                }
            } else if (type == Type.CloudOnly) {
                // Join the folder locally.
                PFWizard.openOnlineStorageJoinWizard(getController(),
                    Collections.singletonList(folderInfo));
            } else if (type == Type.Local) {
                // Local - open it
                openExplorer();
            } else {
                // Typical - ask to create.
                askToCreateFolder();
            }
        }
    }

    @SuppressWarnings("serial")
    private class WebdavAction extends BaseAction {
        private WebdavAction(Controller controller) {
            super("action_webdav", controller);
        }

        public void actionPerformed(ActionEvent e) {
            createWebdavConnection();
        }
    }

    @SuppressWarnings("serial")
    private class WebViewAction extends BaseAction {

        private WebViewAction(Controller controller) {
            super("action_webview", controller);
        }

        public void actionPerformed(ActionEvent e) {
            final ServerClient client = getController().getOSClient();
            if (client.supportsWebLogin()) {
                BrowserLauncher.open(getController(), new URLProducer() {
                    public String url() {
                        FolderInfo info;
                        if (folder != null) {
                            info = folder.getInfo();
                        } else {
                            info = folderInfo;
                        }
                        return client.getFolderURLWithCredentials(info);
                    }
                });
            }
        }
    }

    void dispose() {
        removeFolderLocalAction.dispose();
        removeFolderOnlineAction.dispose();
        backupOnlineStorageAction.dispose();
        stopOnlineStorageAction.dispose();
        inviteAction.dispose();
    }
}
