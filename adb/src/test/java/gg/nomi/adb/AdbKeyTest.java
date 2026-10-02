package gg.nomi.adb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

public class AdbKeyTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static BigInteger littleEndian(byte[] data, int offset, int length) {
        byte[] big = new byte[length + 1];
        for (int i = 0; i < length; i++) big[length - i] = data[offset + i];
        return new BigInteger(big);
    }

    @Test
    public void publicKeyUsesTheAdbFormat() throws Exception {
        KeyPair pair = rsa();
        AdbKey key = AdbKey.fromPkcs8(pair.getPrivate().getEncoded(), "test@host");
        String text = new String(key.adbPublicKey(), StandardCharsets.US_ASCII);
        assertTrue(text.endsWith(" test@host\u0000"));
        byte[] blob = Base64.getDecoder().decode(text.substring(0, text.indexOf(' ')));
        assertEquals(524, blob.length);
        ByteBuffer buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(64, buffer.getInt());
        int n0inv = buffer.getInt();
        BigInteger modulus = ((RSAPublicKey) pair.getPublic()).getModulus();
        assertEquals(-1, n0inv * modulus.intValue());
        assertEquals(modulus, littleEndian(blob, 8, 256));
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(modulus), littleEndian(blob, 264, 256));
        assertEquals(65537, buffer.getInt(520));
    }

    @Test
    public void certificateIsSelfSignedByTheKey() throws Exception {
        KeyPair pair = rsa();
        X509Certificate certificate = Der.selfSigned(pair.getPublic(), pair.getPrivate(), "adb");
        certificate.verify(pair.getPublic());
        assertEquals(3, certificate.getVersion());
        assertEquals("CN=adb", certificate.getSubjectX500Principal().getName());
        assertEquals(certificate.getSubjectX500Principal(), certificate.getIssuerX500Principal());
        assertArrayEquals(pair.getPublic().getEncoded(), certificate.getPublicKey().getEncoded());
    }

    @Test
    public void storedKeyIsReused() throws Exception {
        File directory = folder.newFolder();
        AdbKey first = AdbKey.loadOrCreate(directory, "test@host");
        AdbKey second = AdbKey.loadOrCreate(directory, "test@host");
        assertArrayEquals(first.adbPublicKey(), second.adbPublicKey());
        assertTrue(new File(directory, "adbkey.pk8").isFile());
    }
}
