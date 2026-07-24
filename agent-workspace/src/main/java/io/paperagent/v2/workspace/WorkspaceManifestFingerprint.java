package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Canonical, iteration-order-independent source-manifest fingerprint.
 */
final class WorkspaceManifestFingerprint {
    private static final byte[] DOMAIN =
            "paperagent.workspace.source-manifest.v1".getBytes(StandardCharsets.UTF_8);
    private static final Comparator<String> UTF8_ORDER =
            (left, right) -> compareUnsigned(utf8(left), utf8(right));

    private WorkspaceManifestFingerprint() {
    }

    static ContentHash calculate(ProjectVersionSnapshot snapshot) {
        WorkspaceValues.require(snapshot, "sourceManifestFingerprint");
        MessageDigest digest = WorkspaceHashes.newSha256Digest();
        field(digest, DOMAIN);
        field(digest, utf8(snapshot.version().projectId()));
        field(digest, utf8(snapshot.version().versionId()));
        metadata(digest, snapshot.metadata());

        List<ProjectFileSnapshot> files = new ArrayList<>(snapshot.files());
        files.sort((left, right) -> UTF8_ORDER.compare(
                left.path().value(),
                right.path().value()));
        integer(digest, files.size());
        for (ProjectFileSnapshot file : files) {
            field(digest, utf8(file.path().value()));
            longInteger(digest, file.content().length);
            field(digest, utf8(file.hash().value()));
            metadata(digest, file.metadata());
        }
        return new ContentHash("sha256", WorkspaceHashes.lowercaseHex(digest.digest()));
    }

    private static void metadata(MessageDigest digest, Map<String, String> metadata) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(metadata.entrySet());
        entries.sort((left, right) -> {
            int key = UTF8_ORDER.compare(left.getKey(), right.getKey());
            return key != 0 ? key : UTF8_ORDER.compare(left.getValue(), right.getValue());
        });
        integer(digest, entries.size());
        for (Map.Entry<String, String> entry : entries) {
            field(digest, utf8(entry.getKey()));
            field(digest, utf8(entry.getValue()));
        }
    }

    private static void field(MessageDigest digest, byte[] bytes) {
        integer(digest, bytes.length);
        digest.update(bytes);
    }

    private static void integer(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void longInteger(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int comparison = Integer.compare(
                    Byte.toUnsignedInt(left[index]),
                    Byte.toUnsignedInt(right[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }
}
