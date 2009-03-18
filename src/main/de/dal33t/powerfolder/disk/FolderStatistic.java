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
package de.dal33t.powerfolder.disk;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import de.dal33t.powerfolder.Constants;
import de.dal33t.powerfolder.Member;
import de.dal33t.powerfolder.PFComponent;
import de.dal33t.powerfolder.transfer.TransferManager;
import de.dal33t.powerfolder.event.FolderAdapter;
import de.dal33t.powerfolder.event.FolderEvent;
import de.dal33t.powerfolder.event.FolderMembershipEvent;
import de.dal33t.powerfolder.event.FolderMembershipListener;
import de.dal33t.powerfolder.event.NodeManagerEvent;
import de.dal33t.powerfolder.event.NodeManagerListener;
import de.dal33t.powerfolder.event.TransferAdapter;
import de.dal33t.powerfolder.event.TransferManagerEvent;
import de.dal33t.powerfolder.light.FileInfo;
import de.dal33t.powerfolder.util.Format;
import de.dal33t.powerfolder.util.TransferCounter;
import de.dal33t.powerfolder.util.ui.SimpleTimeEstimator;

/**
 * Class to hold pre-calculated static data for a folder. Only freshly
 * calculated if needed.
 * 
 * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc </a>
 * @version $Revision: 1.22 $
 */
public class FolderStatistic extends PFComponent {

    /**
     * if the number of files is more than MAX_ITEMS the updates will be delayed
     * to a maximum, one update every 20 seconds
     */
    private static final int MAX_ITEMS = 5000;
    private static final long DELAY_10S = Constants.MILLIS_PER_SECOND * 10;

    private final Folder folder;

    private CalculationResult calculating;
    private CalculationResult current;
    private SimpleTimeEstimator estimator;

    private final AtomicBoolean transferMonitorRunning = new AtomicBoolean();

    /**
     * The Date of the last change to a folder file.
     */
    private Date lastFileChangeDate;

    // Contains this Folder's download progress
    // It differs from other counters in that it does only count
    // the "accepted" traffic. (= If the downloaded chunk was saved to a file)
    // Used to calculate ETA
    private TransferCounter downloadCounter;
    private long lastCalc;
    private MyCalculatorTask calculatorTask;

    FolderStatistic(Folder folder) {
        super(folder.getController());
        // Empty at start
        current = new CalculationResult();
        estimator = new SimpleTimeEstimator();
        this.folder = folder;
        downloadCounter = new TransferCounter();

        folder.addFolderListener(new MyFolderListener());
        folder.addMembershipListener(new MyFolderMembershipListener());

        // Add to NodeManager
        getController().getNodeManager().addNodeManagerListener(
            new MyNodeManagerListener());

        // Listener on transfer manager
        folder.getController().getTransferManager().addListener(
            new MyTransferManagerListener());
    }

    // Ensure that a task is running to watch for partail download changes.
    private void checkTransferMonitor() {

        if (transferMonitorRunning.getAndSet(true)) {
            // Monitor is running.
            return;
        }

        MyTransferMonitorTask transferMonitorTask = new MyTransferMonitorTask();
        try {
            getController().schedule(transferMonitorTask, DELAY_10S);
        } catch (IllegalStateException ise) {
            // ignore, this happens if this shutdown in debug mode
            logFiner("IllegalStateException", ise);
        }

    }

    // package protected called from Folder
    void scheduleCalculate() {
        if (calculating != null) {
            return;
        }
        if (!getController().getFolderRepository().hasJoinedFolder(
            folder.getInfo()))
        {
            logWarning("Unable to calc stats. Folder not joined");
            return;
        }
        long millisPast = System.currentTimeMillis() - lastCalc;
        if (calculatorTask != null) {
            return;
        }
        if (millisPast > DELAY_10S || current.totalFilesCount < MAX_ITEMS) {
            setCalculateIn(50);
        } else {
            setCalculateIn(DELAY_10S);
        }
    }

    // Calculator timer code
    // *************************************************************

    private void setCalculateIn(long timeToWait) {
        if (calculatorTask != null) {
            return;
        }
        calculatorTask = new MyCalculatorTask();
        try {
            getController().schedule(calculatorTask, timeToWait);
        } catch (IllegalStateException ise) {
            // ignore this happends if this shutdown in debug mode
            logFiner("IllegalStateException", ise);
        }
    }

    /**
     * Calculates the statistics
     * 
     * @private public because for test
     */
    public synchronized void calculate0() {
        if (isFiner()) {
            logFiner("-------------Recalculation statisitcs on " + folder);
        }
        long startTime = System.currentTimeMillis();
        // clear statistics before
        calculating = new CalculationResult();

        Collection<Member> members = folder.getMembersAsCollection();
        Collection<Member> membersCalulated = new ArrayList<Member>(members
            .size());
        // considered.clear();
        for (Member member : members) {
            if (member.isCompleteyConnected() || member.isMySelf()) {
                calculateMemberStats(member, membersCalulated);
                membersCalulated.add(member);
            }
        }
        calculateMemberAndTotalSync(membersCalulated);

        // Switch figures
        current = calculating;
        calculating = null;
        lastCalc = System.currentTimeMillis();

        // Recalculate the last modified date of the folder.
        Date date = null;
        for (FileInfo fileInfo : folder.getKnownFiles()) {
            if (fileInfo.getModifiedDate() != null) {
                if (date == null
                    || date.compareTo(fileInfo.getModifiedDate()) < 0)
                {
                    date = fileInfo.getModifiedDate();
                }
            }
        }
        if (date != null) {
            lastFileChangeDate = date;
        }

        if (isFiner()) {
            logFiner("---------calc stats  " + folder.getName() + " done @: "
                + (System.currentTimeMillis() - startTime));
        }

        // Fire event
        folder.fireStatisticsCalculated();
    }

    /**
     * Gets the date that one of the files in the folder changed.
     * 
     * @return
     */
    public Date getLastFileChangeDate() {
        return lastFileChangeDate;
    }

    private static boolean inSync(FileInfo fInfo, FileInfo newestFileInfo) {
        if (fInfo == null) {
            return false;
        }
        return !newestFileInfo.isNewerThan(fInfo);
    }

    private void calculateMemberStats(Member member,
        Collection<Member> alreadyConsidered)
    {
        Collection<FileInfo> files = folder.getFilesAsCollection(member);
        if (files == null) {
            logFiner("Unable to calc stats on member, no filelist yet: "
                + member);
            return;
        }

        FolderRepository repo = getController().getFolderRepository();
        int memberFilesCount = 0;
        int memberFilesCountInSync = 0;
        // The total size of the folder at the member (including files not in
        // sync).
        long memberSize = 0;
        // Total size of files completely in sync at the member.
        long memberSizeInSync = 0;
        for (FileInfo fInfo : files) {
            if (fInfo.isDeleted()) {
                continue;
            }
            if (folder.getDiskItemFilter().isExcluded(fInfo)) {
                continue;
            }

            FileInfo newestFileInfo = fInfo.getNewestVersion(repo);
            FileInfo myFileInfo = folder.getFile(fInfo);

            boolean inSync = inSync(fInfo, newestFileInfo);

            if (inSync && !newestFileInfo.isDeleted()) {
                boolean incoming = true;
                for (Member alreadyM : alreadyConsidered) {
                    FileInfo otherMemberFile = alreadyM.getFile(fInfo);
                    if (otherMemberFile == null) {
                        continue;
                    }

                    boolean otherInSync = inSync(otherMemberFile,
                        newestFileInfo);
                    if (otherInSync) {
                        incoming = false;
                        break;
                    }
                }
                if (incoming
                    && (myFileInfo == null || newestFileInfo
                        .isNewerThan(myFileInfo)))
                {
                    calculating.incomingFilesCount++;
                }
            }

            // Count file
            memberFilesCount++;
            memberSize += fInfo.getSize();
            if (inSync) {
                memberFilesCountInSync++;
                memberSizeInSync += fInfo.getSize();
            }

            boolean addToTotals = !newestFileInfo.isDeleted();
            for (Member alreadyM : alreadyConsidered) {
                FileInfo otherMemberFile = alreadyM.getFile(fInfo);
                if (otherMemberFile == null) {
                    continue;
                }
                boolean otherInSync = inSync(otherMemberFile, newestFileInfo);
                if (otherInSync) {
                    // File already added to totals
                    addToTotals = false;
                    break;
                }
            }
            if (addToTotals) {
                calculating.totalFilesCount++;
                calculating.totalSize += fInfo.getSize();
            }
        }

        calculating.filesCount.put(member, memberFilesCount);
        calculating.filesCountInSync.put(member, memberFilesCountInSync);
        calculating.sizes.put(member, memberSize);
        calculating.sizesInSync.put(member, memberSizeInSync);
    }

    public String toString() {
        return "Folder statistic on '" + folder.getName() + '\'';
    }

    private void calculateMemberAndTotalSync(Collection<Member> members) {
        double totalSync = 0;
        int considered = 0;
        for (Member member : members) {
            Long inSync = calculating.sizesInSync.get(member);
            Long sizeInSync = inSync == null ? 0 : inSync;
            if (sizeInSync == null) {
                calculating.syncPercentages.put(member, -1.0d);
                continue;
            }
            double sync = ((double) sizeInSync) / calculating.totalSize * 100;
            if (sync > 100) {
                logWarning("Over 100% sync: " + sync + "% sync: "
                        + member.getNick() + ", size(in sync): "
                        + Format.formatBytesShort(sizeInSync) + ", size: "
                        + Format.formatBytesShort(calculating.sizesInSync
                        .get(member)) + ", totalsize: "
                        + Format.formatBytesShort(calculating.totalSize));
            }
            if (calculating.totalSize == 0) {
                logFiner("Got total size 0");
                sync = 100;
            }
            calculating.syncPercentages.put(member, sync);
            totalSync += sync;
            considered++;

            if (isFiner()) {
                logFiner(member.getNick() + ": size: "
                    + calculating.sizes.get(member) + ", size(insync): "
                    + sizeInSync + ": " + sync + '%');
            }
        }
        calculating.totalSyncPercentage = totalSync / considered;
        calculating.estimatedSyncDate = estimator
            .updateEstimate(calculating.totalSyncPercentage);
    }

    public long getTotalSize() {
        return current.totalSize;
    }

    public int getTotalFilesCount() {
        return current.totalFilesCount;
    }

    public int getIncomingFilesCount() {
        return current.incomingFilesCount;
    }

    /**
     * @param member
     * @return the number of files this member has
     */
    public int getFilesCount(Member member) {
        Integer count = current.filesCount.get(member);
        return count != null ? count : 0;
    }

    /**
     * @param member
     * @return the number of files this member has in sync
     */
    public int getFilesCountInSync(Member member) {
        Integer count = current.filesCountInSync.get(member);
        return count != null ? count : 0;
    }

    /**
     * @param member
     * @return the members ACTUAL size of this folder.
     */
    public long getSize(Member member) {
        Long size = current.sizes.get(member);
        return size != null ? size : 0;
    }

    /**
     * @param member
     * @return the members size of this folder that is in sync with the latest
     *         version.
     */
    public long getSizeInSync(Member member) {
        Long size = current.sizesInSync.get(member);
        return size != null ? size : 0;
    }

    /**
     * @return number of local files
     */
    public int getLocalFilesCount() {
        Integer integer = current.filesCount.get(getController().getMySelf());
        return integer != null ? integer : 0;
    }

    /**
     * Answers the sync percentage of a member
     * 
     * @param member
     * @return the sync percentage of the member, -1 if unknown
     */
    public double getSyncPercentage(Member member) {
        Double sync = current.syncPercentages.get(member);
        return sync != null ? sync : -1;
    }

    /**
     * @return the local sync percentage.-1 if unknown.
     */
    public double getLocalSyncPercentage() {
        Double d = current.syncPercentages.get(getController().getMySelf());
        return d != null ? d : -1;
    }

    /**
     * @return the total sync percentange across all members.
     */
    public double getTotalSyncPercentage() {
        return current.totalSyncPercentage;
    }

    /**
     * @return the estimated date the folder will be in sync. May be null.
     */
    public Date getEstimatedSyncDate() {
        return current.estimatedSyncDate;
    }

    /**
     * @return my ACTUAL size of this folder.
     */
    public long getLocalSize() {
        Long size = current.sizes.get(getController().getMySelf());
        return size != null ? size : 0;
    }

    /**
     * @return the most important sync percentage for the selected sync profile.
     *         -1 if unknown
     */
    public double getHarmonizedSyncPercentage() {
        if (SyncProfile.AUTOMATIC_SYNCHRONIZATION.equals(folder
            .getSyncProfile()))
        {
            return getTotalSyncPercentage();
        } else if (SyncProfile.BACKUP_SOURCE.equals(folder.getSyncProfile())) {
            // In this case only remote sides matter.
            // Calc average sync % of remote sides.
            double maxSync = -1;
            for (Member member : folder.getMembersAsCollection()) {
                if (member.isMySelf()) {
                    continue;
                }
                double memberSync = getSyncPercentage(member);
                maxSync = Math.max(maxSync, memberSync);
            }
            return maxSync;
        }
        return getLocalSyncPercentage();
    }

    /**
     * Returns the download-TransferCounter for this Folder
     * 
     * @return a TransferCounter or null if no such information is available
     *         (might be available later)
     */
    public TransferCounter getDownloadCounter() {
        return downloadCounter;
    }

    @Override
    public String getLoggerName() {
        return super.getLoggerName() + " '" + folder.getName() + '\'';
    }

    // Inner classes *********************************************************

    private class MyCalculatorTask extends TimerTask {
        public void run() {
            calculate0();
            calculatorTask = null;
        }
    }

    private class MyTransferMonitorTask extends TimerTask {

        public void run() {

            TransferManager transferManager = getController()
                .getTransferManager();

            // Keep rolling while there are up/downloads for this folder.
            while (transferManager.countActiveDownloads(folder) > 0 ||
                transferManager.countActiveUploads(folder) > 0) {

                logFiner("monitoring downloads");

                // Removed until the test works.
                //calculate0();

                try {
                    Thread.sleep(DELAY_10S);
                } catch (InterruptedException e) {
                    // Ignore
                }

            }

            // Free the task
            transferMonitorRunning.set(false);
            logFiner("finished monitoring transfers");
        }
    }

    /**
     * FolderMembershipListener
     */
    private class MyFolderMembershipListener implements
        FolderMembershipListener
    {
        public void memberJoined(FolderMembershipEvent folderEvent) {
            // Recalculate statistics
            scheduleCalculate();
        }

        public void memberLeft(FolderMembershipEvent folderEvent) {
            // Recalculate statistics
            scheduleCalculate();
        }

        public boolean fireInEventDispatchThread() {
            return false;
        }

    }

    private class MyFolderListener extends FolderAdapter {

        public void remoteContentsChanged(FolderEvent folderEvent) {
            // Recalculate statistics
            scheduleCalculate();
        }

        public void scanResultCommited(FolderEvent folderEvent) {
            if (folderEvent.getScanResult().isChangeDetected()) {
                // Recalculate statistics
                scheduleCalculate();
            }
        }

        public void fileChanged(FolderEvent folderEvent) {
            // Recalculate statistics
            scheduleCalculate();
        }

        public void filesDeleted(FolderEvent folderEvent) {
            // Recalculate statistics
            scheduleCalculate();
        }

        public void syncProfileChanged(FolderEvent folderEvent) {
            // Recalculate statistics
            scheduleCalculate();
        }

        public void statisticsCalculated(FolderEvent folderEvent) {
            // do not implement may cause loop!
        }

        public boolean fireInEventDispatchThread() {
            return false;
        }
    }

    /**
     * Listener on transfermanager
     * 
     * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc </a>
     */
    private class MyTransferManagerListener extends TransferAdapter {

        public void downloadStarted(TransferManagerEvent event) {
            if (event.getFile().getFolderInfo().equals(folder.getInfo())) {
                checkTransferMonitor();
            }
        }

        public void uploadStarted(TransferManagerEvent event) {
            if (event.getFile().getFolderInfo().equals(folder.getInfo())) {
                checkTransferMonitor();
            }
        }

        public boolean fireInEventDispatchThread() {
            return false;
        }
    }

    /**
     * Listens to the nodemanager and triggers recalculation if required
     * 
     * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc </a>
     */
    private class MyNodeManagerListener implements NodeManagerListener {
        public void nodeRemoved(NodeManagerEvent e) {
            calculateIfRequired(e);
        }

        public void nodeAdded(NodeManagerEvent e) {
            calculateIfRequired(e);
        }

        public void nodeConnected(NodeManagerEvent e) {
            calculateIfRequired(e);
        }

        public void nodeDisconnected(NodeManagerEvent e) {
            calculateIfRequired(e);
        }

        public void friendAdded(NodeManagerEvent e) {
            calculateIfRequired(e);
        }

        public void friendRemoved(NodeManagerEvent e) {
            calculateIfRequired(e);
        }

        public void settingsChanged(NodeManagerEvent e) {
            // Do nothing
        }

        public void startStop(NodeManagerEvent e) {
            // Do nothing
        }

        public boolean fireInEventDispatchThread() {
            return false;
        }

        private void calculateIfRequired(NodeManagerEvent e) {
            if (!folder.hasMember(e.getNode())) {
                // Member not on folder
                return;
            }
            scheduleCalculate();
        }
    }

    private static class CalculationResult {

        // Total size of folder in bytes
        public long totalSize;

        // Total number of files
        public int totalFilesCount;

        // The total sync percentage of the folder
        public double totalSyncPercentage;

        // Date at which the folder should be synchronized.
        public Date estimatedSyncDate;

        // Finer values
        public int incomingFilesCount;

        // Contains the sync percentages of the members
        // Member -> Double
        public Map<Member, Double> syncPercentages = new HashMap<Member, Double>();

        // Number of files
        // Member -> Integer
        public Map<Member, Integer> filesCount = new HashMap<Member, Integer>();

        // Number of files in sync
        // Member -> Integer
        public Map<Member, Integer> filesCountInSync = new HashMap<Member, Integer>();

        // Size of folder per member
        // member -> Long
        public Map<Member, Long> sizes = new HashMap<Member, Long>();

        // Size of folder that are in sync per member
        // member -> Long
        public Map<Member, Long> sizesInSync = new HashMap<Member, Long>();
    }
}