/*
 * Copyright 2004 - 2010 Christian Sprajc. All rights reserved.
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
 * $Id: NoticesModel.java 12401 2010-05-20 00:52:17Z harry $
 */
package de.dal33t.powerfolder.ui.model;

import com.jgoodies.binding.value.ValueHolder;
import com.jgoodies.binding.value.ValueModel;
import de.dal33t.powerfolder.ConfigurationEntry;
import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.clientserver.ServerClient;
import de.dal33t.powerfolder.disk.FileInBasePathWarning;
import de.dal33t.powerfolder.light.FolderInfo;
import de.dal33t.powerfolder.message.Invitation;
import de.dal33t.powerfolder.ui.PFUIComponent;
import de.dal33t.powerfolder.ui.WikiLinks;
import de.dal33t.powerfolder.ui.dialog.DialogFactory;
import de.dal33t.powerfolder.ui.dialog.GenericDialogType;
import de.dal33t.powerfolder.ui.notices.*;
import de.dal33t.powerfolder.ui.notification.SystemNotificationHandler;
import de.dal33t.powerfolder.ui.util.Help;
import de.dal33t.powerfolder.ui.wizard.PFWizard;
import de.dal33t.powerfolder.util.ConfigurationLoader;
import de.dal33t.powerfolder.util.StringUtils;
import de.dal33t.powerfolder.util.Translation;

import javax.swing.*;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Model of the notices awaiting action by the user.
 */
public class NoticesModel extends PFUIComponent {

    private final ValueModel allNoticesCountVM = new ValueHolder();
    private final ValueModel unreadNoticesCountVM = new ValueHolder();

    private final List<Notice> notices = new CopyOnWriteArrayList<Notice>();

    /**
     * Constructor
     *
     * @param controller
     */
    public NoticesModel(Controller controller) {
        super(controller);
        updateNoticeCounts();
    }

    /**
     * @return Value model with integer count of received invitations.
     */
    public ValueModel getAllNoticesCountVM() {
        return allNoticesCountVM;
    }

    /**
     * @return Value model with integer count of unread invitations.
     */
    public ValueModel getUnreadNoticesCountVM() {
        return unreadNoticesCountVM;
    }

    private void addNotice(Notice notice) {
        if (notices.contains(notice)) {
            logFine("Ignoring existing notice: " + notice);
        } else {
            notices.add(notice);
            updateNoticeCounts();
        }
    }

    /**
     * @return a reference list of the notices in the model.
     */
    public List<Notice> getAllNotices() {
        return Collections.unmodifiableList(notices);
    }

    public Notice getFirstUnread() {
        for (Notice notice : notices) {
            if (!notice.isRead()) {
                return notice;
            }
        }
        return null;
    }

    public NoticeSeverity getHighestUnreadSeverity() {
        NoticeSeverity unreadSeverity = null;
        for (Notice notice : notices) {
            if (!notice.isRead()){
                if(notice.getNoticeSeverity()==NoticeSeverity.WARINING) {
                    return NoticeSeverity.WARINING;
                } else if(notice.getNoticeSeverity()==NoticeSeverity.INFORMATION) {
                    unreadSeverity = NoticeSeverity.INFORMATION;
                }
            }
        }
        return unreadSeverity;
    }
    /**
     * This handles a notice object. If it is a notification, show in a
     * notification handler. If it is actionable, add to the app model notices.
     *
     * @param notice
     *            the Notice to handle
     */
    public void handleNotice(Notice notice) {
        handleSystemNotice(notice, false);
    }

    /**
     * This handles a system notice object. If it is a notification, show in a
     * notification handler. If it is actionable, add to the app model notices.
     *
     * @param notice
     *            the Notice to handle
     */
    public void handleSystemNotice(Notice notice, boolean suppressPopup) {

        if (!getUIController().isStarted() || getController().isShuttingDown()
            || notices.contains(notice))
        {
            return;
        }

        // PF-164: Remove duplicate invitations:
        removeDuplicateInvitations(notice);

        // Show notice?
        if ((Boolean) getApplicationModel().getSystemNotificationsValueModel()
            .getValue()
            && notice.isNotification()
            && !notice.isRead()
            && !PFWizard.isWizardOpen() && !suppressPopup)
        {
            SystemNotificationHandler noticeHandler = new SystemNotificationHandler(getController(),
                notice);
            noticeHandler.show();
        }

        if (notice.isActionable()) {
            // Invitations are a special case. We do not care about
            // invitations to folders that we have already joined.
            if (notice instanceof InvitationNotice) {
                InvitationNotice in = (InvitationNotice) notice;
                Invitation i = in.getPayload(getController());
                FolderInfo fi = i.folder;
                if (getController().getFolderRepository().hasJoinedFolder(fi)) {
                    return;
                }
            }
            for (Notice candidate : notices) {
                if (candidate.isRead()) {
                    continue;
                }
                // If there is already the SAME notice unread. don't
                // add this duplicate.
                if (candidate.equals(notice)) {
                    return;
                }
            }
            addNotice(notice);
        }
    }

    /**
     * PF-164: Removes duplicate invitations.
     *
     * @param notice The notice to be displayed in the client notification tab.
     */
    private void removeDuplicateInvitations(Notice notice) {

        if (notice instanceof InvitationNotice) {

            InvitationNotice incInvitationNotice = (InvitationNotice) notice;
            Invitation incomingInvitation = incInvitationNotice.getPayload(getController());

            for (Notice n : notices) {
                if (n instanceof InvitationNotice) {
                    InvitationNotice in = (InvitationNotice) n;
                    Invitation i = in.getPayload(getController());
                    if (i.getOID().equals(incomingInvitation.getOID())) {
                        notices.remove(n);
                    }
                }
            }
        }
    }

    /**
     * Handle a notice.
     *
     * @param notice
     */
    public void activateNotice(Notice notice) {
        // @todo Refactor this, moving activation to within notices.
        // There should be no need for 'instanceof' handling.
        if (notice instanceof InvitationNotice) {
            InvitationNotice invitationNotice = (InvitationNotice) notice;
            handleInvitationNotice(invitationNotice);
        } else if (notice instanceof WarningNotice) {
            WarningNotice eventNotice = (WarningNotice) notice;
            SwingUtilities.invokeLater(eventNotice.getPayload(getController()));
        } else if (notice instanceof RunnableNotice) {
            RunnableNotice eventNotice = (RunnableNotice) notice;
            SwingUtilities.invokeLater(eventNotice.getPayload(getController()));
        } else if (notice instanceof FolderAutoCreateNotice) {
            FolderAutoCreateNotice eventNotice = (FolderAutoCreateNotice) notice;
            handleFolderAutoCreateNotice(eventNotice);
        } else if (notice instanceof OutOfMemoryNotice) {
            OutOfMemoryNotice eventNotice = (OutOfMemoryNotice) notice;
            handleOutOfMemoryNotice(eventNotice);
        } else {
            logWarning("Don't know what to do with notice: "
                + notice.getClass().getName() + " : " + notice.toString());
        }
        markRead(notice);
    }

    private void handleOutOfMemoryNotice(OutOfMemoryNotice notice) {
        // http\://www.powerfolder.com/wiki/Memory_configuration
        String memoryConfigHelp = Help.getWikiArticleURL(getController(),
            WikiLinks.MEMORY_CONFIGURATION);
        String infoText = Translation.get(
            "low_memory.error.text", memoryConfigHelp);
        int response = DialogFactory.genericDialog(
            getController(),
            Translation.get("low_memory.error.title"),
            infoText,
            new String[]{
                Translation.get("general.ok"),
                Translation
                    .get("dialog.already_running.exit_button")},
            0, GenericDialogType.ERROR);
        if (response == 1) { // Exit
            getController().exit(0);
        }
    }

    private void handleFolderAutoCreateNotice(FolderAutoCreateNotice
            eventNotice) {
        PFWizard.openFolderAutoCreateWizard(getController(),
                eventNotice.getFolderInfo());
    }

    /**
     * Marks the notice as read.
     *
     * @param notice
     */
    public void markRead(Notice notice) {
        notice.setRead();
        updateNoticeCounts();
    }

    /**
     * Handle an invitation notice.
     *
     * @param invitationNotice
     */
    private void handleInvitationNotice(InvitationNotice invitationNotice) {
        Invitation invitation = invitationNotice.getPayload(getController());
        PFWizard.openInvitationReceivedWizard(getController(), invitation);
    }

    public void clearAll() {
        for (Notice n : notices) {
            if (n instanceof InvitationNotice) {
                Invitation invitation = ((InvitationNotice) n).getPayload(getController());
                if (invitation != null && serverAgreeInvitationEnabled(invitation)) {
                    getController().getThreadPool().execute(
                        new DeclineInvitationTask(((InvitationNotice) n).getPayload(getController())));
                }
            }
        }
        notices.clear();
        updateNoticeCounts();
    }

    public void clearNotice(Notice notice) {
        for (Notice n : notices) {
            if (notice.equals(n)) {
                if (n instanceof InvitationNotice) {
                    Invitation invitation = ((InvitationNotice) n).getPayload(getController());
                    if (invitation != null && serverAgreeInvitationEnabled(invitation)) {
                        getController().getThreadPool().execute(
                            new DeclineInvitationTask(((InvitationNotice) n).getPayload(getController())));
                    }
                }
                notices.remove(notice);
                updateNoticeCounts();
                return;
            }
        }
    }

    /**
     * PF-164: Support federation invites:
     * <p>
     * If we're in a federation environment, we have to ask the service of the invitation if
     * FOLDER_AGREE_INVITATION_ENABLED is enabled.
     *
     * @param invitation The invitation.
     */
    private boolean serverAgreeInvitationEnabled(Invitation invitation) {

        boolean serverAgreeInvitationsEnabled =
                ConfigurationEntry.FOLDER_AGREE_INVITATION_ENABLED.getValueBoolean(getController());

        if (invitation.getServer() != null &&
                ConfigurationEntry.SERVER_FEDERATED_LOGIN.getValueBoolean(getController())) {

            try {
                Properties props = ConfigurationLoader
                        .loadPreConfiguration(invitation.getServer().getWebUrl());
                String agreeInvitations = (String) props.get(ConfigurationEntry.FOLDER_AGREE_INVITATION_ENABLED
                        .getConfigKey());

                if (StringUtils.isNotBlank(agreeInvitations)) {
                    serverAgreeInvitationsEnabled = Boolean.parseBoolean(agreeInvitations);
                }

            } catch (IOException e) {
                logWarning("Failed to get config from federation server "
                        + invitation.getServer().getWebUrl());
                return serverAgreeInvitationsEnabled;
            }
        }

        return serverAgreeInvitationsEnabled;
    }

    /**
     * Clears all FileInBasePathWarnings
     */
    public void clearAllFileInBasePathWarnings() {
        for (Notice notice: this.getAllNotices()) {
            if (notice instanceof FileInBasePathWarning) {
                this.clearNotice(notice);
            }
        }
    }

    private void updateNoticeCounts() {
        allNoticesCountVM.setValue(notices.size());

        int count = 0;
        for (Notice notice : notices) {
            if (!notice.isRead()) {
                count++;
            }
        }
        unreadNoticesCountVM.setValue(count);
    }

    public int getUnreadNoticesCount() {
        return (Integer) unreadNoticesCountVM.getValue();
    }

    private class DeclineInvitationTask implements Runnable {
        Invitation invitation;

        public DeclineInvitationTask(Invitation invitation) {
            this.invitation = invitation;
        }

        @Override
        public void run() {
            ServerClient client = getController().getOSClient();
            if (client.isLoggedIn() && client.getAccount().hasReadPermissions(invitation.getPermission().getFolder())) {
                client.getSecurityService().declineInvitation(invitation);
            }
        }
    }
}
