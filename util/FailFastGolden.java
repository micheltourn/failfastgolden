package util;

import java.io.*;
import java.nio.charset.Charset;
import java.util.Arrays;
import org.apache.commons.lang3.ArrayUtils;

import util.test.Assert;

/** A golden-file comparator that calls Assert.fail as soon as a differing silver line is emitted.
 *  This makes it easier to track down the cause of test failure.
 *  
 *  Usage: 1. failFast = true;
 *            out = GoldenJIT.newSilver(golden, silver, failFast); 
 *         2. JUnit test case writes to out, out.close()
 *   
 *  To (re)generate the golden file use failFast = false then copy silver onto golden.
 */
public class FailFastGolden {
    private final FilterOutputStream fsilver;

    public static OutputStream newSilver(File goldenFile, File silverFile, boolean failFast)
            throws IOException {
        if(failFast) {
            FailFastGolden g = new FailFastGolden(goldenFile, silverFile);
            return g.silver();
        } else {
            return new BufferedOutputStream(new FileOutputStream(silverFile));
        }
    }

    public FailFastGolden(InputStream golden, OutputStream silver) throws IOException {
        this.fsilver = new CompareOutputStream(golden, silver);
    }

    public FailFastGolden(File goldenFile, File silverFile) throws IOException {
        this(emptyOrFileInputStream(goldenFile), new FileOutputStream(silverFile));
    }

    private static InputStream emptyOrFileInputStream(File goldenFile) {
        if(goldenFile.exists()) {
            try {
                return new FileInputStream(goldenFile);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e); // unlikely
            }
        } else {
            return new ByteArrayInputStream(new byte[] {}) {
            };
        }
    }

    public OutputStream silver() {
        return fsilver;
    }
}

class CompareOutputStream extends FilterOutputStream {
    protected final static Charset UTF8 = Charset.forName("UTF-8");
    protected final InputStream golden;
    protected int pos;
    ByteArrayOutputStream gbuf = new ByteArrayOutputStream();
    ByteArrayOutputStream sbuf = new ByteArrayOutputStream();

    public CompareOutputStream(InputStream golden, OutputStream out) {
        super(ga(out));
        this.golden = golden;
        this.pos = 0;
    }

    private static OutputStream ga(OutputStream out) {
        return out;
    }

    protected void handleDiff(int pos) {
        String diff = getDiffContext(pos);
        Assert.fail(diff);
    }

    protected String getDiffContext(int pos) {
        String[] glines = getContextLines(gbuf, pos);
        String[] slines = getContextLines(sbuf, pos);
        StringBuffer buf = new StringBuffer();
        int ibound = Math.min(glines.length, slines.length);
        for(int i = 0; i < ibound; i++) {
            if(glines[i].equals(slines[i])) {
                buf.append("= " + glines[i] + "\n");
            } else {
                buf.append("< " + glines[i] + "\n");
                buf.append("> " + slines[i] + "\n");
            }
        }
        if(slines.length > glines.length) {
            for(int i = ibound; i < slines.length; i++) {
                buf.append("> " + slines[i] + "\n");
            }
        } else {
            for(int i = ibound; i < glines.length; i++) {
                buf.append("< " + glines[i] + "\n");
            }

        }
        return buf.toString();
    }

    protected String[] getContextLines(ByteArrayOutputStream buf, int pos) {
        return new String(getContext(buf, pos, 5, 500), UTF8).split("\\n");
    }

    protected byte[] getContext(ByteArrayOutputStream buf, int pos, int maxBackLines,
            int maxBackBytes) {
        byte[] g = buf.toByteArray();
        if(g.length == 0) {
            return g;
        }
        int p = pos;
        int i;
        for(i = 0; i < maxBackLines; i++) {
            if(p == 0) {
                break;
            }
            int p2 = ArrayUtils.lastIndexOf(g, (byte)'\n', p - 1);
            if(p2 == ArrayUtils.INDEX_NOT_FOUND) {
                break;
            }
            p = p2;
        }
        if(pos - p > maxBackBytes) {
            p = pos - maxBackBytes;
        }
        int toEndPos = g.length;
        return Arrays.copyOfRange(g, p, toEndPos);
    }

    public void write(int b) throws IOException {
        int gb = golden.read();
        if(gb == -1) {
            handleDiff(pos + 1);
        } else {
            pos += 1;
            gbuf.write(b);
            sbuf.write(b);
            out.write(b);
        }
    }

    public void write(byte block[], int off, int len) throws IOException {
        byte[] gblock = new byte[len];
        int need = len;
        int glen = 0;
        int oldpos = pos;
        while(need > 0) {
            int gread = golden.read(gblock, glen, need);
            if(gread == -1) {
                // include silver buffer in diff context even if golden just ended.
                sbuf.write(block, glen, len);
                handleDiff(oldpos + 1);
                return;
            }
            sbuf.write(block, glen, gread);
            gbuf.write(gblock, glen, gread);
            pos += gread;
            glen += gread;
            need -= gread;
        }
        out.write(block, off, len);
        int d = firstDiffIndex(gblock, Arrays.copyOfRange(block, off, len));
        if(d != NO_DIFF) {
            // A post-diff context can be obtained from golden with no downside.
            // (Whereas reading silver further would defeat the purpose of GoldenJIT)
            // This is very useful when silver is a prefix of golden but is confusing
            // when silver has a diff then a non-diff that we never read.
            // See '< seven' in TestGoldenJIT#silverLateDiff. Would be '= seven' in a real diff.
            int maxPastLen = 50;
            gblock = new byte[maxPastLen];
            int gread = golden.read(gblock, 0, maxPastLen);
            gbuf.write(gblock, 0, gread);
            if(gread != -1) {
                glen += gread;
            }
            handleDiff(oldpos + d); // +1
        }
    }

    public void close() {
        byte[] gblock = new byte[200];
        int gread;
        try {
            gread = golden.read(gblock, 0, gblock.length);
            if(gread > 0) {
                gbuf.write(gblock, 0, gread);
                handleDiff(pos + 1);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    final static int NO_DIFF = -1;

    static int firstDiffIndex(byte[] a, byte[] a2) {
        int length = a.length;
        if(a2.length != length) {
            return Math.min(length, a2.length) + 1;
        }
        for(int i = 0; i < length; i++) {
            if(a[i] != a2[i]) {
                return i;
            }
        }
        return NO_DIFF;
    }

}
