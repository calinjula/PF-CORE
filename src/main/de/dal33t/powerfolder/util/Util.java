/* $Id: Util.java,v 1.64 2006/04/30 19:37:05 schaatser Exp $
 */
package de.dal33t.powerfolder.util;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import de.dal33t.powerfolder.util.os.OSUtil;

/**
 * Util helper class.
 * 
 * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc </a>
 * @version $Revision: 1.64 $
 */
public class Util {

    private static final Logger LOG = Logger.getLogger(Util.class);

    /**
     * Used building output as Hex
     */
    private static final char[] DIGITS = {'0', '1', '2', '3', '4', '5', '6',
        '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

    // No instance possible
    private Util() {
    }

    /**
     * @return true if the pro version is running.
     */
    public static final boolean isRunningProVersion() {
        return Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("web-resources/js/ajax.js") != null;
    }

    /**
     * Compares with a marge of 2000 milliseconds to solve the rounding problems
     * or some filesystems.
     * 
     * @true if dates are the same within a marge of 2000 milliseconds
     */
    public final static boolean equalsFileDateCrossPlattform(Date date1,
        Date date2)
    {
        return equalsFileDateCrossPlattform(date1.getTime(), date2.getTime());
    }

    /**
     * Compares with a marge of 2000 milliseconds to solve the rounding problems
     * or some filesystems.
     * 
     * @see Convert#convertToGlobalPrecision(long)
     * @true if times are the same within a marge of 2000 milliseconds
     */
    public final static boolean equalsFileDateCrossPlattform(long time1,
        long time2)
    {
        if (time1 == time2) {
            return true;
        }
        long difference;
        if (time1 > time2) {
            difference = time1 - time2;
        } else {
            difference = time2 - time1;
        }
        return difference <= 2000;
    }

    /**
     * Compares with a marge of 2000 milliseconds to solve the rounding problems
     * or some filesystems.
     * 
     * @return true if date1 is a newer date than date2
     */
    public final static boolean isNewerFileDateCrossPlattform(Date date1,
        Date date2)
    {
        return isNewerFileDateCrossPlattform(date1.getTime(), date2.getTime());
    }

    /**
     * Compares with a marge of 2000 milliseconds to solve the rounding problems
     * or some filesystems.
     * 
     * @return true if time1 is a newer time than time2
     */
    public final static boolean isNewerFileDateCrossPlattform(long time1,
        long time2)
    {
        if (time1 == time2) {
            return false;
        }
        long difference = time1 - time2;
        if (difference > 2000) {
            return true;
        }
        return false;
    }

    public static String removeInvalidFilenameChars(String folderName) {
        String invalidChars = "/\\:*?\"<>|";
        for (int i = 0; i < invalidChars.length(); i++) {
            char c = invalidChars.charAt(i);
            while (folderName.indexOf(c) != -1) {
                int index = folderName.indexOf(c);
                folderName = folderName.substring(0, index)
                    + folderName.substring(index + 1, folderName.length());
            }
        }
        return folderName;
    }

    public static final boolean equals(Object a, Object b) {
        if (a == null) {
            // a == null here
            return b == null;

        }
        return a.equals(b);
    }

    /**
     * @return The created file or null if resource not found
     * @param resource
     *            filename of the resource
     * @param altLocation
     *            possible alternative (root is tried first) location (directory
     *            structure like etc/files)
     * @param destination
     *            Directory where to create the file (must exists and must be a
     *            directory))
     * @param deleteOnExit
     *            indicates if this file must be deleted on program exit
     */
    public static File copyResourceTo(String resource, String altLocation,
        File destination, boolean deleteOnExit)
    {
        if (!destination.exists()) {
            throw new IllegalArgumentException("destination must exists");
        }
        if (!destination.isDirectory()) {
            throw new IllegalArgumentException(
                "destination must be a directory");
        }
        InputStream in = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream(resource);
        if (in == null) {
            LOG.verbose("Unable to find resource: " + resource);
            // try harder
            in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(altLocation + "/" + resource);
            if (in == null) {
                LOG.warn("Unable to find resource: " + altLocation + "/"
                    + resource);
                return null;
            }
        }
        File target = new File(destination, resource);
        if (deleteOnExit) {
            target.deleteOnExit();
        }
        try {
            FileUtils.copyFromStreamToFile(in, target);
        } catch (IOException ioe) {
            LOG.warn("Unable to create target for resource: " + target);
            return null;
        }
        LOG.verbose("created target for resource: " + target);
        return target;
    }

    /**
     * Creates a desktop shortcut. currently only available on windows systems
     * 
     * @param shortcutName
     * @param shortcutTarget
     * @return true if succeeded
     */
    public static boolean createDesktopShortcut(String shortcutName,
        File shortcutTarget)
    {
        if (!OSUtil.isWindowsSystem()) {
            return false;
        }

        LOG.verbose("Creating desktop shortcut to "
            + shortcutTarget.getAbsolutePath());

        File desktop = new File(System.getProperty("user.home") + "/Desktop");
        if (!desktop.exists()) {
            LOG.warn("Unable to create desktop shortcut '" + shortcutName
                + "' to " + shortcutTarget + ". Desktop not found at "
                + desktop.getAbsolutePath());
            return false;
        }

        File folderLnk = new File(desktop, shortcutName + ".lnk");
        if (folderLnk.exists()) {
            LOG.verbose("Desktop shortcut '" + shortcutName
                + "' already exists");
            return false;
        }

        InputStream in = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("etc/createshortcut.vbs");
        if (in == null) {
            // try harder
            in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("createshortcut.vbs");
            if (in == null) {
                LOG.warn("Unable to create desktop shortcut '" + shortcutName
                    + "'");
                return false;
            }
        }

        File createShortCutVBS = null;
        try {
            createShortCutVBS = new File("createshortcut.vbs");
            createShortCutVBS.deleteOnExit();
            FileUtils.copyFromStreamToFile(in, createShortCutVBS);
            Process vbProc = Runtime.getRuntime().exec(
                "cscript \"" + createShortCutVBS.getAbsolutePath() + "\" \""
                    + shortcutName + "\" \"" + shortcutTarget.getAbsolutePath()
                    + "\"");
            vbProc.waitFor();
            createShortCutVBS.delete();
            LOG.debug("Desktop shortcut for "
                + shortcutTarget.getAbsolutePath() + " created");
            return true;
        } catch (IOException e) {
            LOG.warn("Unable to create desktop shortcut '" + shortcutName
                + "'. " + e.getMessage());
            LOG.verbose(e);
        } catch (InterruptedException e) {
            LOG.warn("Unable to create desktop shortcut '" + shortcutName
                + "'. " + e.getMessage());
            LOG.verbose(e);
        }
        return false;
    }

    /**
     * Removes a desktop shortcut. currently only available on windows systems
     * 
     * @param shortcutName
     * @return true if succeeded
     */
    public static boolean removeDesktopShortcut(String shortcutName) {
        if (!OSUtil.isWindowsSystem()) {
            return false;
        }

        LOG.verbose("Removing desktop shortcut: " + shortcutName);

        File desktop = new File(System.getProperty("user.home") + "/Desktop");
        if (!desktop.exists()) {
            LOG.warn("Unable to remove desktop shortcut '" + shortcutName
                + "'. Desktop not found at " + desktop.getAbsolutePath());
            return false;
        }

        File folderLnk = new File(desktop, shortcutName + ".lnk");
        if (folderLnk.exists() && folderLnk.isFile()) {
            return folderLnk.delete();
        }
        return false;
    }

    /**
     * Returns the plain url content as string
     * 
     * @param url
     * @return
     */
    public static String getURLContent(URL url) {
        if (url == null) {
            throw new NullPointerException("URL is null");
        }
        try {
            Object content = url.getContent();
            if (!(content instanceof InputStream)) {
                LOG.error("Unable to get content from " + url
                    + ". content is of type " + content.getClass().getName());
                return null;
            }
            InputStream in = (InputStream) content;

            StringBuffer buf = new StringBuffer();
            while (in.available() > 0) {
                buf.append((char) in.read());
            }
            return buf.toString();
        } catch (IOException e) {
            LOG.error(
                "Unable to get content from " + url + ". " + e.toString(), e);
        }
        return null;
    }

    /**
     * Place a String on the clipboard
     * 
     * @param aString
     *            the string to place in the clipboard
     */
    public static void setClipboardContents(String aString) {
        StringSelection stringSelection = new StringSelection(aString);
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(stringSelection, new ClipboardOwner() {
            public void lostOwnership(Clipboard aClipboard,
                Transferable contents)
            {
                // Ignore
            }
        });
    }

    /**
     * Encodes a url fragment, thus special characters are tranferred into a url
     * compatible style
     * 
     * @param aURLFragment
     * @return
     */
    public static String endcodeForURL(String aURLFragment) {
        String result = null;
        try {
            result = URLEncoder.encode(aURLFragment, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new RuntimeException("UTF-8 not supported", ex);
        }
        return result;
    }

    /**
     * Decodes a url fragment, thus special characters are tranferred from a url
     * compatible style
     * 
     * @param aURLFragment
     * @return
     */
    public static String decodeFromURL(String aURLFragment) {
        String result = null;
        try {
            result = URLDecoder.decode(aURLFragment, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new RuntimeException("UTF-8 not supported", ex);
        }
        return result;
    }

    /**
     * compares two ip addresses.
     * 
     * @param ip1
     * @param ip2
     * @return true if different, false otherwise.
     */

    public static boolean compareIpAddresses(byte[] ip1, byte[] ip2) {
        for (int i = 0; i < ip1.length; i++) {
            if (ip1[i] != ip2[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits an array into a list of smaller arrays with the maximum size of
     * <code>size</code>.
     * 
     * @param src
     *            the source array
     * @param size
     *            the maximum size of the chunks
     * @return the list of resulting arrays
     */
    public static List<byte[]> splitArray(byte[] src, int size) {
        int nChunks = src.length / size;
        List<byte[]> chunkList = new ArrayList<byte[]>(nChunks + 1);
        if (size >= src.length) {
            chunkList.add(src);
            return chunkList;
        }
        for (int i = 0; i < nChunks; i++) {
            byte[] chunk = new byte[size];
            System.arraycopy(src, i * size, chunk, 0, chunk.length);
            chunkList.add(chunk);
        }
        int lastChunkSize = src.length % size;
        if (lastChunkSize > 0) {
            byte[] lastChunk = new byte[lastChunkSize];
            System.arraycopy(src, nChunks * size, lastChunk, 0,
                lastChunk.length);
            chunkList.add(lastChunk);
        }
        return chunkList;
    }

    /**
     * Merges a list of arrays into one big array.
     * 
     * @param arrayList
     *            the list of byte[] arryas.
     * @return the resulting array.
     */
    public static byte[] mergeArrayList(List<byte[]> arrayList) {
        Reject.ifNull(arrayList, "list of arrays is null");
        int totalSize = 0;
        for (byte[] bs : arrayList) {
            totalSize += bs.length;
        }
        if (totalSize == 0) {
            return new byte[0];
        }
        byte[] result = new byte[totalSize];
        int pos = 0;
        for (byte[] bs : arrayList) {
            System.arraycopy(bs, 0, result, pos, bs.length);
            pos += bs.length;
        }
        return result;
    }

    /**
     * @param email
     *            the email string to check.
     * @return true if the input is a valid email address.
     */
    public static boolean isValidEmail(String email) {
        if (email == null) {
            return false;
        }
        int etIndex = email.indexOf('@');
        return etIndex > 0 && email.lastIndexOf('.') > etIndex;
    }

    /**
     * Converts an array of bytes into an array of characters representing the
     * hexidecimal values of each byte in order. The returned array will be
     * double the length of the passed array, as it takes two characters to
     * represent any given byte.
     *
     * @param data
     *            a byte[] to convert to Hex characters
     * @return A char[] containing hexidecimal characters
     */
    public static char[] encodeHex(byte[] data) {
        int l = data.length;

        char[] out = new char[l << 1];

        // two characters form the hex value.
        for (int i = 0, j = 0; i < l; i++) {
            out[j++] = DIGITS[(0xF0 & data[i]) >>> 4];
            out[j++] = DIGITS[0x0F & data[i]];
        }

        return out;
    }

    // Encoding stuff *********************************************************

    /**
     * Calculates the MD5 digest and returns the value as a 16 element
     * <code>byte[]</code>.
     *
     * @param data
     *            Data to digest
     * @return MD5 digest
     */
    public static byte[] md5(byte[] data) {
        return getMd5Digest().digest(data);
    }

    /**
     * Returns a MessageDigest for the given <code>algorithm</code>.
     *
     * @param algorithm
     *            The MessageDigest algorithm name.
     * @return An MD5 digest instance.
     * @throws RuntimeException
     *             when a {@link java.security.NoSuchAlgorithmException} is
     *             caught,
     */
    private static MessageDigest getDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    /**
     * Returns an MD5 MessageDigest.
     *
     * @return An MD5 digest instance.
     * @throws RuntimeException
     *             when a {@link java.security.NoSuchAlgorithmException} is
     *             caught,
     */
    private static MessageDigest getMd5Digest() {
        return getDigest("MD5");
    }

}