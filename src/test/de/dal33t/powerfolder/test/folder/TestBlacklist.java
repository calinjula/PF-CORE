package de.dal33t.powerfolder.test.folder;

import junit.framework.TestCase;
import de.dal33t.powerfolder.disk.Blacklist;
import de.dal33t.powerfolder.light.FileInfo;
import de.dal33t.powerfolder.light.FolderInfo;

public class TestBlacklist extends TestCase {

    public void testBlackList() {
        Blacklist blacklist = new Blacklist();
        FolderInfo folderInfo = new FolderInfo("foldername", "id", true);
        FileInfo fileInfo = new FileInfo(folderInfo, "thumbs.db");
        FileInfo fileInfo2 = new FileInfo(folderInfo, "thumbs.db");
        FileInfo fileInfo3 = new FileInfo(folderInfo, "somefile.txt");
        FileInfo fileInfo4 = new FileInfo(folderInfo,
            "A_UPPER_case_FILENAME.xxx");
        blacklist.add(fileInfo);
        assertTrue(blacklist.isIgnored(fileInfo));
        // other instance but equals
        assertTrue(blacklist.isIgnored(fileInfo2));
        // not blacklisted
        assertFalse(blacklist.isIgnored(fileInfo3));
        // after remove allow download again
        blacklist.remove(fileInfo);
        assertFalse(blacklist.isIgnored(fileInfo));
        // Mix-case filename test
        blacklist.add(fileInfo4);
        assertTrue(blacklist.isIgnored(fileInfo4));
    }

    public void testBlacklistPatterns() {
        Blacklist blacklist = new Blacklist();
        FolderInfo folderInfo = new FolderInfo("foldername", "id", true);
        blacklist.addPattern("*thumbs.db");
        blacklist.addPattern("*THAMBS.db");

        assertTrue(blacklist.isIgnored(new FileInfo(folderInfo, "thumbs.db")));
        assertTrue(blacklist.isIgnored(new FileInfo(folderInfo,
            "somewhere/in/a/sub/thumbs.db")));
        assertFalse(blacklist
            .isIgnored(new FileInfo(folderInfo, "thusssmbs.db")));

        blacklist.removePattern("*thumbs.db");

        assertFalse(blacklist.isIgnored(new FileInfo(folderInfo, "thumbs.db")));
        assertFalse(blacklist.isIgnored(new FileInfo(folderInfo,
            "somewhere/in/a/sub/thumbs.db")));
        assertFalse(blacklist
            .isIgnored(new FileInfo(folderInfo, "thusssmbs.db")));

        Blacklist blacklist2 = new Blacklist();
        blacklist2.addPattern("images/*thumbs.db");

        assertFalse(blacklist2.isIgnored(new FileInfo(folderInfo, "thumbs.db")));
        assertTrue(blacklist2.isIgnored(new FileInfo(folderInfo,
            "images/thumbs.db")));
        assertTrue(blacklist2.isIgnored(new FileInfo(folderInfo,
            "images/deepinimages/thumbs.db")));

        // Mixed case pattern. Should match!
        assertTrue(blacklist2.isIgnored(new FileInfo(folderInfo,
            "images/deepinimages/THUMBS.db")));
        assertTrue(blacklist2.isIgnored(new FileInfo(folderInfo,
            "images/deepinimages/thambs.db")));

    }
}
