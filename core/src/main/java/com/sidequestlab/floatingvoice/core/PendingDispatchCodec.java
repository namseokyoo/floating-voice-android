package com.sidequestlab.floatingvoice.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;

/** Versioned binary codec for one durable dispatch record. */
public final class PendingDispatchCodec {
    private static final int VERSION = 1;

    private PendingDispatchCodec() { }

    public static byte[] encode(PendingDispatch dispatch) {
        Objects.requireNonNull(dispatch, "dispatch");
        try {
            ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(payloadBuffer);
            out.writeInt(VERSION);
            writeString(out, dispatch.dispatchId());
            DispatchTargetSnapshot target = dispatch.target();
            out.writeLong(target.routeAttemptId());
            writeString(out, target.localId());
            out.writeLong(target.accountUserId());
            out.writeLong(target.chatId());
            out.writeLong(target.peerUserId());
            writeString(out, target.configuredUsername());
            writeString(out, target.resolvedUsername());
            writeString(out, target.resolvedTitle());
            writeString(out, target.userAlias());
            out.writeLong(target.verificationRevision());
            out.writeLong(target.verifiedAtEpochMillis());
            writeString(out, dispatch.absolutePath());
            out.writeInt(dispatch.durationSeconds());
            out.writeLong(dispatch.createdAtEpochMillis());
            out.writeInt(dispatch.state().ordinal());
            out.writeLong(dispatch.temporaryMessageId());
            out.writeInt(dispatch.errorCode());
            writeString(out, dispatch.errorMessage());
            out.writeBoolean(dispatch.serverRetryable());
            out.writeInt(dispatch.retryAfterSeconds());
            out.flush();

            byte[] payload = payloadBuffer.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(payload);
            ByteArrayOutputStream recordBuffer = new ByteArrayOutputStream(payload.length + 8);
            recordBuffer.write(payload);
            DataOutputStream record = new DataOutputStream(recordBuffer);
            record.writeLong(crc.getValue());
            record.flush();
            return recordBuffer.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static PendingDispatch decode(byte[] encoded) {
        if (encoded == null || encoded.length < 12) {
            throw new IllegalArgumentException("dispatch payload is truncated");
        }
        int payloadLength = encoded.length - 8;
        byte[] payload = Arrays.copyOf(encoded, payloadLength);
        CRC32 crc = new CRC32();
        crc.update(payload);
        try {
            DataInputStream checksumInput = new DataInputStream(
                    new ByteArrayInputStream(encoded, payloadLength, 8));
            if (checksumInput.readLong() != crc.getValue()) {
                throw new IllegalArgumentException("dispatch checksum mismatch");
            }
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            int version = in.readInt();
            if (version != VERSION) {
                throw new IllegalArgumentException("unsupported dispatch version: " + version);
            }
            String dispatchId = readString(in);
            DispatchTargetSnapshot target = DispatchTargetSnapshot.restore(
                    in.readLong(), readString(in), in.readLong(), in.readLong(), in.readLong(),
                    readString(in), readString(in), readString(in), readString(in),
                    in.readLong(), in.readLong());
            String path = readString(in);
            int duration = in.readInt();
            long createdAt = in.readLong();
            int stateOrdinal = in.readInt();
            DispatchState[] states = DispatchState.values();
            if (stateOrdinal < 0 || stateOrdinal >= states.length) {
                throw new IllegalArgumentException("invalid dispatch state");
            }
            PendingDispatch decoded = new PendingDispatch(
                    dispatchId, target, path, duration, createdAt, states[stateOrdinal],
                    in.readLong(), in.readInt(), readString(in), in.readBoolean(), in.readInt());
            if (in.available() != 0) {
                throw new IllegalArgumentException("dispatch payload has trailing bytes");
            }
            return decoded;
        } catch (IOException | RuntimeException error) {
            if (error instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalArgumentException("invalid dispatch payload", error);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 1_000_000 || length > in.available()) {
            throw new IllegalArgumentException("invalid string length");
        }
        byte[] bytes = in.readNBytes(length);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
