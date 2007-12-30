/* $Id: FileList.java,v 1.5 2006/03/12 23:13:03 totmacherr Exp $
 */
package de.dal33t.powerfolder.message;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import de.dal33t.powerfolder.Constants;
import de.dal33t.powerfolder.disk.Blacklist;
import de.dal33t.powerfolder.disk.Folder;
import de.dal33t.powerfolder.light.FileInfo;
import de.dal33t.powerfolder.light.FolderInfo;
import de.dal33t.powerfolder.util.Logger;
import de.dal33t.powerfolder.util.Reject;

/**
 * Files of a folder.
 * <p>
 * TODO Improve splitting. Should act upon a List<FileInfo> instead of array
 * 
 * @see de.dal33t.powerfolder.message.FolderFilesChanged
 * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc</a>
 * @version $Revision: 1.5 $
 */
public class FileList extends FolderRelatedMessage {
    private static final Logger LOG = Logger.getLogger(FileList.class);

    private static final long serialVersionUID = 100L;

    public final FileInfo[] files;
    /**
     * The number of following delta filelist to expect.
     * 
     * @see FolderFilesChanged
     */
    public int nFollowingDeltas;

    private FileList(FolderInfo folderInfo, FileInfo[] files, int nDetlas2Follow)
    {
        Reject.ifNull(folderInfo, "FolderInfo is null");
        Reject.ifNull(files, "Files is null");
        Reject.ifTrue(nDetlas2Follow < 0,
            "Invalid number for following detla messages");

        this.files = files;
        this.folder = folderInfo;
        this.nFollowingDeltas = nDetlas2Follow;
    }

    /**
     * Creates the message for the filelist. Filelist gets splitted into smaller
     * ones if required.
     * 
     * @param folder
     * @return the splitted filelist messages.
     */
    public static Message[] createFileListMessages(Folder folder) {
        // Create filelist with blacklist
        return createFileListMessages(folder.getInfo(), folder.getKnownFiles(),
            folder.getBlacklist());
    }

    /**
     * Splits the filelist into smaller ones. Always splits into one
     * <code>FileList</code> and (if required) multiple
     * <code>FolderFilesChanged</code> messages
     * 
     * @param foInfo
     *            the folder for the message
     * @param files
     *            the fileinfos to include.
     * @param blacklist
     *            the blacklist to apply
     * @return the splitted list
     */
    public static Message[] createFileListMessages(FolderInfo foInfo,
        Collection<FileInfo> files, Blacklist blacklist)
    {
        Reject.ifNull(foInfo, "Folder info is null");
        Reject.ifNull(files, "Files is null");
        Reject.ifNull(blacklist, "Blacklist is null");
        Reject.ifTrue(Constants.FILE_LIST_MAX_FILES_PER_MESSAGE <= 0,
            "Unable to split filelist. nFilesPerMessage: "
                + Constants.FILE_LIST_MAX_FILES_PER_MESSAGE);

        if (files.isEmpty()) {
            return new Message[]{new FileList(foInfo, new FileInfo[0], 0)};
        }

        List<Message> messages = new ArrayList<Message>();
        int nDeltas = 0;
        boolean firstMessage = true;
        int curMsgIndex = 0;
        FileInfo[] messageFiles = new FileInfo[Constants.FILE_LIST_MAX_FILES_PER_MESSAGE];
        for (FileInfo fileInfo : files) {
            if (blacklist.isIgnored(fileInfo)) {
                continue;
            }
            messageFiles[curMsgIndex] = fileInfo;
            curMsgIndex++;
            if (curMsgIndex >= Constants.FILE_LIST_MAX_FILES_PER_MESSAGE) {
                if (firstMessage) {
                    messages.add(new FileList(foInfo, messageFiles, 0));
                    firstMessage = false;
                } else {
                    nDeltas++;
                    messages.add(new FolderFilesChanged(foInfo, messageFiles));
                }
                messageFiles = new FileInfo[Constants.FILE_LIST_MAX_FILES_PER_MESSAGE];
                curMsgIndex = 0;
            }
        }

        if (firstMessage && curMsgIndex == 0) {
            // Only ignored files
            return new Message[]{new FileList(foInfo, new FileInfo[0], 0)};
        }
        if (curMsgIndex != 0 && curMsgIndex < messageFiles.length) {
            // Last message
            FileInfo[] lastFiles = new FileInfo[curMsgIndex];
            System.arraycopy(messageFiles, 0, lastFiles, 0, lastFiles.length);
            if (firstMessage) {
                messages.add(new FileList(foInfo, lastFiles, 0));
                firstMessage = false;
            } else {
                nDeltas++;
                messages.add(new FolderFilesChanged(foInfo, lastFiles));
            }
        }

        // Set the actual number of deltas
        ((FileList) messages.get(0)).nFollowingDeltas = nDeltas;

        if (LOG.isVerbose()) {
            LOG.verbose("Splitted filelist into " + messages.size()
                + ", deltas: " + nDeltas + ", folder: " + foInfo
                + "\nSplitted msgs: " + messages);
        }

        return messages.toArray(new Message[0]);
    }

    public String toString() {
        return "FileList of " + folder + ": " + files.length + " file(s)";
    }
}
