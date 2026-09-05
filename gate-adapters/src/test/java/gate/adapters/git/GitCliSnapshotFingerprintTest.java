package gate.adapters.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/**
 * R4 fingerprint column alignment: {@code ls-files -s} puts the blob sha in column 1
 * ("&lt;mode&gt; &lt;sha&gt; &lt;stage&gt;"), {@code ls-tree -r} in the last column
 * ("&lt;mode&gt; blob &lt;sha&gt;"). Reading the wrong column for ls-tree yielded the literal
 * "blob" as the base fingerprint, so every clone with a tracked .gitignore raised a spurious R4.
 */
@Tag("slow")
class GitCliSnapshotFingerprintTest {

    private static final String SHA = "4341a12345ee9d172e73886957f6b92b70207231";

    @Test
    void lsTreeShaComesFromLastColumnNotTypeColumn() {
        String listing = "100644 blob " + SHA + "\t.gitignore\n"
                + "100644 blob 0b489abb5c1e2f2c26b8e9c2f9f1e2d3c4b5a697\tdir/sub/.gitignore\n";
        assertEquals(".gitignore=" + SHA + ";dir/sub/.gitignore=0b489abb5c1e2f2c26b8e9c2f9f1e2d3c4b5a697",
                GitCliSnapshot.fingerprint(listing, false));
    }

    @Test
    void lsFilesShaComesFromSecondColumn() {
        String listing = "100644 " + SHA + " 0\t.gitignore\n";
        assertEquals(".gitignore=" + SHA, GitCliSnapshot.fingerprint(listing, true));
    }

    /** The invariant R4 actually compares: identical content must fingerprint identically. */
    @Test
    void sameContentFingerprintsIdenticallyAcrossBothFormats() {
        String lsFiles = "100644 " + SHA + " 0\t.gitignore\n";
        String lsTree = "100644 blob " + SHA + "\t.gitignore\n";
        assertEquals(GitCliSnapshot.fingerprint(lsFiles, true), GitCliSnapshot.fingerprint(lsTree, false));
    }

    @Test
    void nonGitignorePathsAndMalformedLinesAreSkipped() {
        String listing = "100644 blob " + SHA + "\tREADME.md\n"
                + "garbage line without tab\n"
                + "\n"
                + "100644 blob " + SHA + "\t.gitignore\n";
        assertEquals(".gitignore=" + SHA, GitCliSnapshot.fingerprint(listing, false));
    }
}
