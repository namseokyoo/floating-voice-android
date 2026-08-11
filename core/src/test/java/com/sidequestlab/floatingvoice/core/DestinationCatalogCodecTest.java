package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DestinationCatalogCodecTest {
    @Test
    void roundTripPreservesOrderDefaultUnicodeQuotesAndLongTitles() {
        String longTitle = "긴 제목 😄 \"quoted\" | 줄바꿈\n" + "가".repeat(10_000);
        Destination primary = destination(
                "primary", 7L, 100L, 500L,
                "configured_bot", "resolved_bot", longTitle, "가족 · 기본 😄",
                Destination.VerificationStatus.VERIFIED, 4L, 1_700_000_000_000L, true);
        Destination legacy = destination(
                "legacy", 0L, 4242L, 0L,
                "legacy_bot", "legacy_bot", "이전 봇", "",
                Destination.VerificationStatus.NEEDS_REVERIFY, 0L, 0L, true);
        Destination disabled = destination(
                "disabled", 7L, 300L, 700L,
                "disabled_bot", "disabled_bot", "비활성", "보관용",
                Destination.VerificationStatus.DISABLED, 5L, 1_700_000_100_000L, false);
        DestinationCatalog expected = DestinationCatalog.restore(
                List.of(primary, legacy, disabled), "primary");

        byte[] encoded = DestinationCatalogCodec.encode(expected);
        DestinationCatalogCodec.DecodeResult decoded = DestinationCatalogCodec.decode(encoded);

        assertEquals(DestinationCatalogCodec.Status.LOADED, decoded.status());
        DestinationCatalog actual = decoded.catalog().orElseThrow();
        assertEquals(expected.destinations(), actual.destinations());
        assertEquals(expected.defaultLocalId(), actual.defaultLocalId());
        assertArrayEquals(encoded, decoded.sourceBytes());
    }

    @Test
    void unknownTopLevelFieldIsIgnoredWithoutChangingKnownState() {
        DestinationCatalog expected = DestinationCatalog.restore(
                List.of(verified("primary", 100L, 500L)), "primary");
        byte[] withUnknown = appendUnknownTopLevelField(
                DestinationCatalogCodec.encode(expected), 99, "future-field".getBytes(StandardCharsets.UTF_8));

        DestinationCatalogCodec.DecodeResult decoded = DestinationCatalogCodec.decode(withUnknown);

        assertEquals(DestinationCatalogCodec.Status.LOADED, decoded.status());
        assertEquals(expected.destinations(), decoded.catalog().orElseThrow().destinations());
        assertEquals(expected.defaultLocalId(),
                decoded.catalog().orElseThrow().defaultLocalId());
    }

    @Test
    void unsupportedVersionReturnsRecoveryResultAndPreservesOriginalBytes() {
        byte[] encoded = DestinationCatalogCodec.encode(DestinationCatalog.restore(
                List.of(verified("primary", 100L, 500L)), "primary"));
        byte[] future = encoded.clone();
        ByteBuffer.wrap(future).putInt(4, 99);

        DestinationCatalogCodec.DecodeResult decoded = DestinationCatalogCodec.decode(future);

        assertEquals(DestinationCatalogCodec.Status.UNSUPPORTED_VERSION, decoded.status());
        assertTrue(decoded.catalog().isEmpty());
        assertArrayEquals(future, decoded.sourceBytes());
        byte[] exposed = decoded.sourceBytes();
        exposed[0] ^= 0x7f;
        assertArrayEquals(future, decoded.sourceBytes());
    }

    @Test
    void corruptMagicAndTruncatedPayloadNeverBecomeAnEmptyCatalog() {
        byte[] encoded = DestinationCatalogCodec.encode(DestinationCatalog.restore(
                List.of(verified("primary", 100L, 500L)), "primary"));
        byte[] badMagic = encoded.clone();
        badMagic[0] ^= 0x7f;
        assertRecovery(DestinationCatalogCodec.decode(badMagic), badMagic);

        for (int length : List.of(0, 1, 4, 8, 12, 16, 24, encoded.length - 1)) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertRecovery(DestinationCatalogCodec.decode(truncated), truncated);
        }
    }

    @Test
    void oversizedBlobIsRejectedAndRetainedForRecovery() {
        byte[] oversized = new byte[DestinationCatalogCodec.MAX_BLOB_BYTES + 1];
        DestinationCatalogCodec.DecodeResult decoded = DestinationCatalogCodec.decode(oversized);

        assertEquals(DestinationCatalogCodec.Status.CORRUPT, decoded.status());
        assertArrayEquals(oversized, decoded.sourceBytes());
    }

    private static void assertRecovery(DestinationCatalogCodec.DecodeResult decoded,
                                       byte[] expectedSource) {
        assertEquals(DestinationCatalogCodec.Status.CORRUPT, decoded.status());
        assertTrue(decoded.catalog().isEmpty());
        assertArrayEquals(expectedSource, decoded.sourceBytes());
    }

    private static byte[] appendUnknownTopLevelField(byte[] encoded, int fieldId, byte[] value) {
        byte[] result = Arrays.copyOf(encoded, encoded.length + 8 + value.length);
        ByteBuffer header = ByteBuffer.wrap(result);
        header.putInt(8, header.getInt(8) + 1);
        ByteBuffer tail = ByteBuffer.wrap(result, encoded.length, 8 + value.length);
        tail.putInt(fieldId);
        tail.putInt(value.length);
        tail.put(value);
        return result;
    }

    private static Destination verified(String localId, long chatId, long peerUserId) {
        return destination(localId, 7L, chatId, peerUserId,
                localId + "_configured", localId + "_resolved", localId, localId,
                Destination.VerificationStatus.VERIFIED, 1L, 1_700_000_000_000L, true);
    }

    private static Destination destination(
            String localId, long accountUserId, long chatId, long peerUserId,
            String configured, String resolved, String title, String alias,
            Destination.VerificationStatus status, long revision, long verifiedAt,
            boolean enabled) {
        return new Destination(localId, accountUserId, chatId, peerUserId,
                configured, resolved, title, alias, status, revision, verifiedAt, enabled);
    }
}
