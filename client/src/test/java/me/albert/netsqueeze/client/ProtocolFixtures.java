package me.albert.netsqueeze.client;

import io.netty.buffer.ByteBuf;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Root-test fixtures shared by mixed-frame and dictionary regression suites. */
final class ProtocolFixtures {
    private ProtocolFixtures() { }
    static Path path(String name) { return Path.of(System.getProperty("netsqueeze.fixtures"), name); }
    static List<byte[]> records(String name) throws Exception {
        List<byte[]> result = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path(name)))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) result.add(in.readNBytes(in.readInt()));
        }
        return result;
    }
    static void capture(List<byte[]> output, ByteBuf packet) {
        try {
            byte[] bytes = new byte[packet.readableBytes()];
            packet.readBytes(bytes);
            output.add(bytes);
        } finally { packet.release(); }
    }
    static void assertPackets(List<byte[]> expected, List<byte[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertArrayEquals(expected.get(i), actual.get(i), "packet " + i);
    }
}
