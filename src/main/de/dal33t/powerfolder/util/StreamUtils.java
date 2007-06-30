package de.dal33t.powerfolder.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Class containing utility methods when working with streams.
 * 
 * @author <a href="mailto:sprajc@riege.com">Christian Sprajc</a>
 * @version $Revision: 1.5 $
 */
public class StreamUtils {
    private static final Logger LOG = Logger.getLogger(StreamUtils.class);

    private static final int BUFFER_SIZE = 1024;

    private StreamUtils() {
    }

    /**
     * Copies the file to the destination stream.
     * 
     * @param source
     * @param destination
     * @throws IOException
     */
    public static void copyToStream(File source, OutputStream destination)
        throws IOException
    {

        Reject.ifNull(source, "Source is null");
        Reject.ifNull(destination, "Destination is null");
        Reject.ifNull(source.exists(), "Source file does not exist");
        Reject.ifNull(source.canRead(), "Unable to read source file");

        FileInputStream in = new FileInputStream(source);
        copyToStream(in, destination);
        in.close();
    }

    /**
     * Copies the content stream into the output stream unless no more data is
     * availble. Uses internal buffer to speed up operation.
     * 
     * @param source
     * @param destination
     * @throws IOException
     */
    public static void copyToStream(InputStream source, OutputStream destination)
        throws IOException
    {
        copyToStream(source, destination, -1);
    }

    /**
     * Copies the content stream into the output stream unless no more data is
     * availble. Uses internal buffer to speed up operation.
     * 
     * @param source
     * @param destination
     * @param bytesToTransfer
     *            the bytes to transfer.
     * @throws IOException
     */
    public static void copyToStream(InputStream source,
        OutputStream destination, long bytesToTransfer) throws IOException
    {
        Reject.ifNull(source, "Source is null");
        Reject.ifNull(destination, "Destination is null");

        byte[] buf = new byte[BUFFER_SIZE];
        int len;
        long totalRead = 0;
        long bytesLeft;
        while (true) {
            bytesLeft = bytesToTransfer - totalRead;
            if (bytesLeft >= BUFFER_SIZE) {
                len = source.read(buf);
            } else if (bytesLeft > 0) {
                len = source.read(buf, 0, (int) bytesLeft);
            } else {
                break;
            }
            if (len < 0) {
                break;
            }
            totalRead += len;
            destination.write(buf, 0, len);
            if (bytesToTransfer >= 0 && totalRead >= bytesToTransfer) {
                break;
            }
        }
    }

    /**
     * Reads a specific amout of data from a stream. Wait util enough data is
     * available
     * 
     * @param in
     *            the inputstream
     * @param buffer
     *            the buffer to put in the data
     * @param offset
     *            the start offset in the buffer
     * @param size
     *            the number of bytes to read
     * @throws IOException
     *             if stream error
     */
    public static void read(InputStream in, byte[] buffer, int offset, int size)
        throws IOException
    {
        int nTotalRead = 0;
        int nRead = 0;
        do {
            try {
                nRead = in.read(buffer, offset + nTotalRead, size - nTotalRead);
            } catch (IndexOutOfBoundsException e) {
                LOG.error("buffer.lenght: " + buffer.length + ", offset");
                throw e;
            }
            if (nRead < 0) {
                throw new IOException("EOF, nothing more to read");
            }
            nTotalRead += nRead;
        } while (nTotalRead < size);
    }

    /**
     * Reads an int from the stream. The int is expected to be encoded as 4 byte
     * (32-bit).
     * 
     * @param in
     *            the input buf
     * @return the int.
     * @throws IOException
     */
    public static int readInt(InputStream in) throws IOException {
        byte[] buf = new byte[4];
        read(in, buf, 0, buf.length);
        return Convert.convert2Int(buf);
    }
}
