package com.sidequestlab.floatingvoice.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Versioned length-delimited codec. Unknown tagged fields are skipped; corrupt bytes are retained. */
public final class DestinationCatalogCodec {
    public static final int MAX_BLOB_BYTES = 1024 * 1024;
    private static final int MAGIC = 0x46564337;
    private static final int VERSION = 1;
    private static final int MAX_FIELDS = 64;
    private static final int MAX_DESTINATIONS = 1000;

    private static final int TOP_DEFAULT_ID = 1;
    private static final int TOP_DESTINATIONS = 2;

    private DestinationCatalogCodec() {}

    public enum Status {
        LOADED,
        CORRUPT,
        UNSUPPORTED_VERSION
    }

    public static final class DecodeResult {
        private final Status status;
        private final DestinationCatalog catalog;
        private final byte[] sourceBytes;

        private DecodeResult(Status status, DestinationCatalog catalog, byte[] sourceBytes) {
            this.status = Objects.requireNonNull(status);
            this.catalog = catalog;
            this.sourceBytes = sourceBytes.clone();
        }

        public Status status() { return status; }
        public Optional<DestinationCatalog> catalog() { return Optional.ofNullable(catalog); }
        public byte[] sourceBytes() { return sourceBytes.clone(); }
    }

    public static byte[] encode(DestinationCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        try {
            List<Field> top = new ArrayList<>();
            catalog.defaultLocalId().ifPresent(id ->
                    top.add(new Field(TOP_DEFAULT_ID, utf8(id))));
            top.add(new Field(TOP_DESTINATIONS,
                    encodeDestinations(catalog.destinations())));

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(top.size());
                writeFields(output, top);
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_BLOB_BYTES) {
                throw new IllegalArgumentException("catalog blob exceeds maximum size");
            }
            return encoded;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encode destination catalog", e);
        }
    }

    public static DecodeResult decode(byte[] source) {
        byte[] raw = source == null ? new byte[0] : source.clone();
        if (raw.length > MAX_BLOB_BYTES) return result(Status.CORRUPT, null, raw);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(raw))) {
            if (input.readInt() != MAGIC) return result(Status.CORRUPT, null, raw);
            int version = input.readInt();
            if (version != VERSION) return result(Status.UNSUPPORTED_VERSION, null, raw);
            Map<Integer, byte[]> top = readFields(input, input.readInt());
            if (input.available() != 0 || !top.containsKey(TOP_DESTINATIONS)) {
                return result(Status.CORRUPT, null, raw);
            }
            String defaultId = top.containsKey(TOP_DEFAULT_ID)
                    ? decodeUtf8(top.get(TOP_DEFAULT_ID)) : null;
            List<Destination> destinations = decodeDestinations(top.get(TOP_DESTINATIONS));
            DestinationCatalog catalog = DestinationCatalog.restore(destinations, defaultId);
            return result(Status.LOADED, catalog, raw);
        } catch (Exception e) {
            return result(Status.CORRUPT, null, raw);
        }
    }

    private static byte[] encodeDestinations(List<Destination> destinations) throws Exception {
        if (destinations.size() > MAX_DESTINATIONS) {
            throw new IllegalArgumentException("too many destinations");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(destinations.size());
            for (Destination destination : destinations) {
                List<Field> fields = List.of(
                        new Field(1, utf8(destination.localId())),
                        new Field(2, longBytes(destination.accountUserId())),
                        new Field(3, longBytes(destination.chatId())),
                        new Field(4, longBytes(destination.peerUserId())),
                        new Field(5, utf8(destination.configuredUsername())),
                        new Field(6, utf8(destination.resolvedUsername())),
                        new Field(7, utf8(destination.resolvedTitle())),
                        new Field(8, utf8(destination.userAlias())),
                        new Field(9, utf8(destination.verificationStatus().name())),
                        new Field(10, longBytes(destination.verificationRevision())),
                        new Field(11, longBytes(destination.verifiedAtEpochMillis())),
                        new Field(12, new byte[]{(byte) (destination.enabled() ? 1 : 0)}));
                output.writeInt(fields.size());
                writeFields(output, fields);
            }
        }
        return bytes.toByteArray();
    }

    private static List<Destination> decodeDestinations(byte[] block) throws Exception {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(block))) {
            int count = input.readInt();
            if (count < 0 || count > MAX_DESTINATIONS) {
                throw new IllegalArgumentException("invalid destination count");
            }
            List<Destination> destinations = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Map<Integer, byte[]> fields = readFields(input, input.readInt());
                for (int required = 1; required <= 12; required++) {
                    if (!fields.containsKey(required)) {
                        throw new IllegalArgumentException("missing destination field " + required);
                    }
                }
                byte[] enabled = fields.get(12);
                if (enabled.length != 1 || (enabled[0] != 0 && enabled[0] != 1)) {
                    throw new IllegalArgumentException("invalid enabled flag");
                }
                destinations.add(new Destination(
                        decodeUtf8(fields.get(1)),
                        decodeLong(fields.get(2)),
                        decodeLong(fields.get(3)),
                        decodeLong(fields.get(4)),
                        decodeUtf8(fields.get(5)),
                        decodeUtf8(fields.get(6)),
                        decodeUtf8(fields.get(7)),
                        decodeUtf8(fields.get(8)),
                        Destination.VerificationStatus.valueOf(decodeUtf8(fields.get(9))),
                        decodeLong(fields.get(10)),
                        decodeLong(fields.get(11)),
                        enabled[0] == 1));
            }
            if (input.available() != 0) {
                throw new IllegalArgumentException("trailing destination bytes");
            }
            return destinations;
        }
    }

    private static Map<Integer, byte[]> readFields(DataInputStream input, int count)
            throws Exception {
        if (count < 0 || count > MAX_FIELDS) {
            throw new IllegalArgumentException("invalid field count");
        }
        Map<Integer, byte[]> fields = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            int id = input.readInt();
            int length = input.readInt();
            if (id <= 0 || length < 0 || length > MAX_BLOB_BYTES || length > input.available()) {
                throw new IllegalArgumentException("invalid field envelope");
            }
            byte[] value = new byte[length];
            input.readFully(value);
            if (fields.putIfAbsent(id, value) != null) {
                throw new IllegalArgumentException("duplicate field " + id);
            }
        }
        return fields;
    }

    private static void writeFields(DataOutputStream output, List<Field> fields)
            throws Exception {
        for (Field field : fields) {
            output.writeInt(field.id());
            output.writeInt(field.value().length);
            output.write(field.value());
        }
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String decodeUtf8(byte[] value) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value)).toString();
    }

    private static byte[] longBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static long decodeLong(byte[] value) {
        if (value.length != Long.BYTES) {
            throw new IllegalArgumentException("invalid long field");
        }
        return ByteBuffer.wrap(value).getLong();
    }

    private static DecodeResult result(Status status, DestinationCatalog catalog, byte[] raw) {
        return new DecodeResult(status, catalog, raw);
    }

    private record Field(int id, byte[] value) {}
}
