/*
 * Copyright 2004 - 2009 Christian Sprajc. All rights reserved.
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
 * $Id: AddLicenseHeader.java 4282 2008-06-16 03:25:09Z tot $
 */
package de.dal33t.powerfolder.test.folder;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;

import de.dal33t.powerfolder.disk.Folder;
import de.dal33t.powerfolder.disk.SyncProfile;
import de.dal33t.powerfolder.light.DirectoryInfo;
import de.dal33t.powerfolder.util.FileUtils;
import de.dal33t.powerfolder.util.IdGenerator;
import de.dal33t.powerfolder.util.logging.LoggingManager;
import de.dal33t.powerfolder.util.test.ConditionWithMessage;
import de.dal33t.powerfolder.util.test.FiveControllerTestCase;
import de.dal33t.powerfolder.util.test.TestHelper;

/**
 * TRAC #378
 * <p>
 * Sync 1
 * <p>
 * Delete
 * <p>
 * Structure with many subdirs
 * <p>
 * Many/random changes
 * <p>
 * 
 * @author sprajc
 */
public class DirectorySyncTest extends FiveControllerTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        assertTrue(tryToConnectSimpsons());
        joinTestFolder(SyncProfile.AUTOMATIC_SYNCHRONIZATION);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    public void testInitialSync() {
        File dirBart = new File(getFolderAtBart().getLocalBase(), "testDir");
        assertTrue(dirBart.mkdir());
        TestHelper.waitMilliSeconds(3000);
        final File dirLisa = new File(getFolderAtLisa().getLocalBase(), dirBart
            .getName());
        assertTrue(dirLisa.mkdir());

        scanFolder(getFolderAtBart());
        scanFolder(getFolderAtLisa());

        assertEquals(1, getFolderAtBart().getKnownFilesCount());
        DirectoryInfo infoBart = getFolderAtBart().getKnownDirectories()
            .iterator().next();
        assertDirMatch(dirBart, infoBart, getContollerBart());

        assertEquals(1, getFolderAtLisa().getKnownFilesCount());
        DirectoryInfo infoLisa = getFolderAtLisa().getKnownDirectories()
            .iterator().next();
        assertDirMatch(dirLisa, infoLisa, getContollerLisa());
    }

    public void testSyncMixedStrucutre() throws IOException {
        int maxDepth = 2;
        int dirsPerDir = 10;
        final List<File> createdFiles = new ArrayList<File>();
        int createdDirs = createSubDirs(getFolderAtBart().getLocalBase(),
            dirsPerDir, 1, maxDepth, new DirVisitor() {
                public void created(File dir) {
                    createdFiles.add(TestHelper.createRandomFile(dir));
                }
            });

        assertTrue("Created dirs: " + createdDirs, createdDirs > 10);
        assertTrue(createdFiles.size() > 10);
        scanFolder(getFolderAtBart());
        assertFilesAndDirs(getFolderAtBart(), createdDirs, createdFiles.size());
        assertFilesAndDirs(getFolderAtHomer(), createdDirs, createdFiles.size());
        assertFilesAndDirs(getFolderAtMarge(), createdDirs, createdFiles.size());
        assertFilesAndDirs(getFolderAtLisa(), createdDirs, createdFiles.size());
        assertFilesAndDirs(getFolderAtMaggie(), createdDirs, createdFiles
            .size());

        // Now delete
        for (File file : getFolderAtHomer().getLocalBase().listFiles()) {
            if (file.isDirectory() && !getFolderAtHomer().isSystemSubDir(file))
            {
                FileUtils.recursiveDelete(file);
            }
        }
        // Leave 1 file to omitt disconnect detection under Linux
        scanFolder(getFolderAtHomer());
        assertFilesAndDirs(getFolderAtHomer(), createdDirs,
            createdFiles.size(), 0);
        Collection<DirectoryInfo> dirs = getFolderAtHomer()
            .getKnownDirectories();
        for (DirectoryInfo directoryInfo : dirs) {
            assertTrue("Dir not detected as deleted: "
                + directoryInfo.toDetailString(), directoryInfo.isDeleted());
            assertEquals(1, directoryInfo.getVersion());
        }

        // Wait for delete
        waitForEmptyFolder(getFolderAtBart());
        assertFilesAndDirs(getFolderAtBart(), createdDirs, createdFiles.size(),
            0);
        dirs = getFolderAtBart().getKnownDirectories();
        for (DirectoryInfo directoryInfo : dirs) {
            assertTrue("Dir not detected as deleted: "
                + directoryInfo.toDetailString(), directoryInfo.isDeleted());
            File diskFile = directoryInfo.getDiskFile(getContollerBart()
                .getFolderRepository());
            assertFalse(diskFile + " info " + directoryInfo.toDetailString(),
                diskFile.exists());
            assertEquals(1, directoryInfo.getVersion());
        }

        // Check others.
        waitForEmptyFolder(getFolderAtMarge());
        assertFilesAndDirs(getFolderAtMarge(), createdDirs,
            createdFiles.size(), 0);
        waitForEmptyFolder(getFolderAtLisa());
        assertFilesAndDirs(getFolderAtLisa(), createdDirs, createdFiles.size(),
            0);
        waitForEmptyFolder(getFolderAtMaggie());
        assertFilesAndDirs(getFolderAtMaggie(), createdDirs, createdFiles
            .size(), 0);
    }

    private void waitForEmptyFolder(final Folder folder) {
        TestHelper.waitForCondition(5, new ConditionWithMessage() {
            public String message() {
                return "Files for " + folder + ": "
                    + Arrays.asList(folder.getLocalBase().list());
            }

            public boolean reached() {
                return folder.getLocalBase().listFiles(new FileFilter() {
                    public boolean accept(File pathname) {
                        return !pathname.equals(folder.getSystemSubDir());
                    }
                }).length == 0;
            }
        });
    }

    public void testSyncDeepStrucutre() {
        int maxDepth = 9;
        int dirsPerDir = 2;
        int createdDirs = createSubDirs(getFolderAtBart().getLocalBase(),
            dirsPerDir, 1, maxDepth, null);

        assertTrue(createdDirs > 100);
        scanFolder(getFolderAtBart());
        assertFilesAndDirs(getFolderAtBart(), createdDirs, 0);
        assertFilesAndDirs(getFolderAtHomer(), createdDirs, 0);
        assertFilesAndDirs(getFolderAtMarge(), createdDirs, 0);
        assertFilesAndDirs(getFolderAtLisa(), createdDirs, 0);
        assertFilesAndDirs(getFolderAtMaggie(), createdDirs, 0);
    }

    private void assertFilesAndDirs(final Folder folder,
        final int expectedDirs, final int expectedFiles)
    {
        assertFilesAndDirs(folder, expectedDirs, expectedFiles, expectedDirs);
    }

    private void assertFilesAndDirs(final Folder folder,
        final int expectedDirs, final int expectedFiles,
        int expectedActualSubdirs)
    {
        TestHelper.waitForCondition(60, new ConditionWithMessage() {
            public String message() {
                return folder + " known: " + folder.getKnownFilesCount()
                    + " expected dirs: " + expectedDirs + " expected file: "
                    + expectedFiles;
            }

            public boolean reached() {
                return folder.getKnownFilesCount() == expectedDirs
                    + expectedFiles;
            }
        });
        int subdirs = countSubDirs(folder.getLocalBase());
        assertEquals(expectedActualSubdirs, subdirs);
        assertEquals(expectedDirs + expectedFiles, folder.getKnownFilesCount());
        assertEquals(expectedDirs, folder.getKnownDirectories().size());
        assertEquals(expectedFiles, folder.getKnownFiles().size());
    }

    private static int countSubDirs(File baseDir) {

        File[] subdirs = baseDir.listFiles(new FileFilter() {
            public boolean accept(File pathname) {
                return pathname.isDirectory()
                    && !pathname.getName().startsWith(".Power");
            }
        });
        int i = subdirs.length;
        for (int j = 0; j < subdirs.length; j++) {
            File file = subdirs[j];
            i += countSubDirs(file);
        }
        return i;
    }

    private static int createSubDirs(File baseDir, int nsubdirs, int depth,
        int maxdepth, DirVisitor visitor)
    {
        int created = 0;
        for (int i = 0; i < nsubdirs; i++) {
            File dir = FileUtils.createEmptyDirectory(baseDir, depth + "-"
                + IdGenerator.makeId().substring(1, 5));
            if (visitor != null) {
                visitor.created(dir);
            }
            created++;
            if (depth < maxdepth) {
                created += createSubDirs(dir, nsubdirs, depth + 1, maxdepth,
                    visitor);
            }
        }
        return created;
    }

    private interface DirVisitor {
        void created(File dir);
    }

    public void testSyncSingleDir() {
        File dirBart = new File(getFolderAtBart().getLocalBase(), "testDir");
        assertTrue(dirBart.mkdir());
        scanFolder(getFolderAtBart());
        assertEquals(1, getFolderAtBart().getKnownFilesCount());
        DirectoryInfo infoBart = getFolderAtBart().getKnownDirectories()
            .iterator().next();
        assertDirMatch(dirBart, infoBart, getContollerBart());

        // Check remote syncs
        final File dirLisa = new File(getFolderAtLisa().getLocalBase(), dirBart
            .getName());
        TestHelper.waitForCondition(5, new ConditionWithMessage() {
            public String message() {
                return "Dir at lisa not existing: " + dirLisa;
            }

            public boolean reached() {
                return dirLisa.exists() && dirLisa.isDirectory()
                    && getFolderAtLisa().getKnownFilesCount() == 1;
            }
        });
        assertEquals(1, getFolderAtLisa().getKnownFilesCount());
        DirectoryInfo infoLisa = getFolderAtLisa().getKnownDirectories()
            .iterator().next();
        assertDirMatch(dirLisa, infoLisa, getContollerLisa());

        LoggingManager.setConsoleLogging(Level.FINE);
        // Now delete at Lisa
        assertTrue(dirLisa.delete());
        scanFolder(getFolderAtLisa());
        assertEquals(getFolderAtLisa().getKnownFiles().toString(), 1,
            getFolderAtLisa().getKnownFilesCount());
        infoLisa = getFolderAtLisa().getKnownDirectories().iterator().next();
        assertTrue("Dir should have been detected as deleted: "
            + infoLisa.toDetailString(), infoLisa.isDeleted());
        assertEquals(infoLisa.toDetailString(), 1, infoLisa.getVersion());
        assertDirMatch(dirLisa, infoLisa, getContollerLisa());

        // Restore at Homer
        final File dirHomer = new File(getFolderAtHomer().getLocalBase(),
            "testDir");
        TestHelper.waitForCondition(5, new ConditionWithMessage() {
            public String message() {
                return "Dir at homer existing: " + dirLisa;
            }

            public boolean reached() {
                return !dirHomer.exists()
                    && getFolderAtHomer().getKnownFilesCount() == 1
                    && getFolderAtHomer().getKnownDirectories().iterator()
                        .next().isDeleted();
            }
        });
        assertFalse(dirHomer.exists());
        DirectoryInfo infoHomer = getFolderAtHomer().getKnownDirectories()
            .iterator().next();
        assertDirMatch(dirHomer, infoHomer, getContollerHomer());
        assertEquals(infoHomer.toDetailString(), 1, infoHomer.getVersion());
    }
}
