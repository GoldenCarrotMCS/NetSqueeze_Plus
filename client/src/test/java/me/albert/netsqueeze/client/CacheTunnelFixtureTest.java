package me.albert.netsqueeze.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CacheTunnelFixtureTest {
    @Test void clientInflatesActualServerV4Carrier() throws Exception {
        Path dir = Path.of(System.getProperty("netsqueeze.fixtures"));
        List<byte[]> carriers = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(dir.resolve("cache-v4.bin")))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) carriers.add(in.readNBytes(in.readInt()));
        }
        ByteBuffer header = ByteBuffer.wrap(carriers.getFirst());
        UUID session = new UUID(header.getLong(), header.getLong());
        List<byte[]> decoded = new ArrayList<>();
        try (TunnelReassembler receiver = new TunnelReassembler(session, true, false, true)) {
            for (byte[] data : carriers) receiver.accept(UnpooledByteBufAllocator.DEFAULT, data, (ByteBuf buf) -> {
                try { byte[] bytes = new byte[buf.readableBytes()]; buf.readBytes(bytes); decoded.add(bytes); }
                finally { buf.release(); }
            });
        }
        assertEquals(1, decoded.size());
        assertArrayEquals(Files.readAllBytes(dir.resolve("cache-v4-expected.bin")), decoded.getFirst());
    }
}
