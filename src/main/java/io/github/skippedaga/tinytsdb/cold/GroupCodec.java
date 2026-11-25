package io.github.skippedaga.tinytsdb.cold;

import io.github.skippedaga.tinytsdb.CounterStore;
import io.github.skippedaga.tinytsdb.Grain;
import io.github.skippedaga.tinytsdb.Labels;
import io.github.skippedaga.tinytsdb.Parts;
import io.github.skippedaga.tinytsdb.SeriesGroup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link SeriesGroup} as bytes for a {@link ColdStore} row: per series its labels without the group's (the row's
 * key holds it), its first-added time and its buckets. Versions are not kept: a group read back is added, not compared.
 */
public final class GroupCodec {

    private static final int VERSION = 1;

    private GroupCodec() {
    }

    public static byte[] encode(SeriesGroup group) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256 + group.series().size() * 128);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            out.writeInt(group.series().size());
            for (SeriesGroup.Member s : group.series()) {
                Labels l = s.labels();
                int n = 0;
                for (int i = 0; i < l.size(); i++) {
                    if (!l.name(i).equals(group.label())) {
                        n++;
                    }
                }
                out.writeShort(n);
                for (int i = 0; i < l.size(); i++) {
                    if (!l.name(i).equals(group.label())) {
                        out.writeUTF(l.name(i));
                        out.writeUTF(l.value(i));
                    }
                }
                out.writeLong(s.firstAddedMs());
                out.writeInt(s.buckets().size());
                for (CounterStore.BucketWrite b : s.buckets()) {
                    out.writeByte(b.grain().ordinal());
                    out.writeLong(b.start());
                    out.writeLong(b.delta().added());
                    out.writeLong(b.delta().retracted());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** The group {@code label=value} of {@code data} (versions 0). */
    public static SeriesGroup decode(String label, String value, byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int version = in.readByte();
            if (version != VERSION) {
                throw new IOException("Cold group version " + version + ", this build reads " + VERSION);
            }
            int count = in.readInt();
            List<SeriesGroup.Member> out = new ArrayList<>(count);
            Grain[] grains = Grain.values();
            for (int s = 0; s < count; s++) {
                int n = in.readShort();
                Map<String, String> labels = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    labels.put(in.readUTF(), in.readUTF());
                }
                labels.put(label, value);
                long firstAdded = in.readLong();
                int buckets = in.readInt();
                List<CounterStore.BucketWrite> writes = new ArrayList<>(buckets);
                for (int b = 0; b < buckets; b++) {
                    Grain g = grains[in.readByte()];
                    writes.add(new CounterStore.BucketWrite(g, in.readLong(), new Parts(in.readLong(), in.readLong())));
                }
                out.add(new SeriesGroup.Member(Labels.of(labels), firstAdded, 0, writes));
            }
            return new SeriesGroup(label, value, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
