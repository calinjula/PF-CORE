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
 * $Id$
 */
package de.dal33t.powerfolder.ui.model;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

import javax.swing.Action;

import com.jgoodies.binding.value.ValueHolder;
import com.jgoodies.binding.value.ValueModel;

import de.dal33t.powerfolder.ConfigurationEntry;
import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.PFUIComponent;
import de.dal33t.powerfolder.PreferencesEntry;
import de.dal33t.powerfolder.ui.action.ActionModel;
import de.dal33t.powerfolder.ui.chat.ChatModel;

/**
 * Contains all core models for the application.
 * 
 * @author <a href="mailto:sprajc@riege.com">Christian Sprajc</a>
 * @version $Revision: 1.5 $
 */
public class ApplicationModel extends PFUIComponent {

    private ActionModel actionModel;
    private ChatModel chatModel;
    private NodeManagerModel nodeManagerModel;
    private TransferManagerModel transferManagerModel;
    private ServerClientModel serverClientModel;
    private ReceivedInvitationsModel receivedInvitationsModel;
    private ReceivedAskedForFriendshipModel receivedAskedForFriendshipModel;
    private ReceivedSingleFileOffersModel receivedSingleFileOffersModel;
    private WarningModel warningsModel;
    private ValueModel chatNotificationsValueModel;
    private ValueModel systemNotificationsValueModel;
    private ValueModel useOSModel;
    private LicenseModel licenseModel;

    /**
     * Constructs a non-initialized application model. Before the model can used
     * call {@link #initialize()}
     * 
     * @param controller
     * @see #initialize()
     */
    public ApplicationModel(final Controller controller) {
        super(controller);
        actionModel = new ActionModel(getController());
        chatModel = new ChatModel(getController());
        nodeManagerModel = new NodeManagerModel(getController());
        transferManagerModel = new TransferManagerModel(getController()
            .getTransferManager());
        serverClientModel = new ServerClientModel(getController(),
            getController().getOSClient());
        receivedInvitationsModel = new ReceivedInvitationsModel(getController());
        receivedAskedForFriendshipModel = new ReceivedAskedForFriendshipModel(
            getController());
        receivedSingleFileOffersModel = new ReceivedSingleFileOffersModel(
            getController());
        warningsModel = new WarningModel(getController());

        chatNotificationsValueModel = new ValueHolder(
            ConfigurationEntry.SHOW_CHAT_NOTIFICATIONS
                .getValueBoolean(controller));
        chatNotificationsValueModel
            .addValueChangeListener(new PropertyChangeListener() {
                public void propertyChange(PropertyChangeEvent evt) {
                    ConfigurationEntry.SHOW_CHAT_NOTIFICATIONS.setValue(
                        controller, evt.getNewValue().toString());
                    controller.saveConfig();
                }
            });
        systemNotificationsValueModel = new ValueHolder(
            ConfigurationEntry.SHOW_SYSTEM_NOTIFICATIONS
                .getValueBoolean(controller));
        systemNotificationsValueModel
            .addValueChangeListener(new PropertyChangeListener() {
                public void propertyChange(PropertyChangeEvent evt) {
                    ConfigurationEntry.SHOW_SYSTEM_NOTIFICATIONS.setValue(
                        controller, evt.getNewValue().toString());
                    controller.saveConfig();
                }
            });

        useOSModel = PreferencesEntry.USE_ONLINE_STORAGE
            .getModel(getController());
        useOSModel.addValueChangeListener(new PropertyChangeListener() {
            public void propertyChange(PropertyChangeEvent evt) {
                // User will not want to back up to OS.
                PreferencesEntry.BACKUP_OS.setValue(getController(),
                    (Boolean) evt.getNewValue());
            }
        });

        licenseModel = new LicenseModel();
    }

    /**
     * Initializes this and all submodels
     */
    public void initialize() {
        transferManagerModel.initialize();
    }

    // Exposing ***************************************************************

    public ActionModel getActionModel() {
        return actionModel;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public NodeManagerModel getNodeManagerModel() {
        return nodeManagerModel;
    }

    public TransferManagerModel getTransferManagerModel() {
        return transferManagerModel;
    }

    public ServerClientModel getServerClientModel() {
        return serverClientModel;
    }

    public ReceivedInvitationsModel getReceivedInvitationsModel() {
        return receivedInvitationsModel;
    }

    public ReceivedAskedForFriendshipModel getReceivedAskedForFriendshipModel()
    {
        return receivedAskedForFriendshipModel;
    }

    public ReceivedSingleFileOffersModel getReceivedSingleFileOffersModel() {
        return receivedSingleFileOffersModel;
    }

    public WarningModel getWarningsModel() {
        return warningsModel;
    }

    public ValueModel getChatNotificationsValueModel() {
        return chatNotificationsValueModel;
    }

    public ValueModel getSystemNotificationsValueModel() {
        return systemNotificationsValueModel;
    }

    public ValueModel getUseOSModel() {
        return useOSModel;
    }

    public LicenseModel getLicenseModel() {
        return licenseModel;
    }
}
