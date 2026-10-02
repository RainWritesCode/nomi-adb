package gg.nomi.adb;

import java.io.File;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

public final class AdbKey {
    private static final String ALIAS = "adb";

    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final X509Certificate certificate;
    private final String name;
    private SSLContext context;

    private AdbKey(PrivateKey privateKey, RSAPublicKey publicKey, String name) throws Exception {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.name = name;
        this.certificate = Der.selfSigned(publicKey, privateKey, "adb");
    }

    public static synchronized AdbKey loadOrCreate(File directory, String name) throws Exception {
        File file = new File(directory, "adbkey.pk8");
        byte[] encoded;
        if (file.exists()) {
            encoded = Files.readAllBytes(file.toPath());
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            KeyPair pair = generator.generateKeyPair();
            encoded = pair.getPrivate().getEncoded();
            File temporary = new File(directory, "adbkey.pk8.tmp");
            Files.write(temporary.toPath(), encoded);
            if (!temporary.renameTo(file)) throw new IllegalStateException("cannot store key");
        }
        return fromPkcs8(encoded, name);
    }

    public static AdbKey fromPkcs8(byte[] encoded, String name) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
        RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        return new AdbKey(privateKey, publicKey, name);
    }

    public static AdbKey fromPem(String pem, String name) throws Exception {
        String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return fromPkcs8(Base64.getDecoder().decode(body), name);
    }

    public byte[] adbPublicKey() {
        BigInteger modulus = publicKey.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(modulus.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), modulus);
        ByteBuffer buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(64);
        buffer.putInt(n0inv.intValue());
        buffer.put(littleEndian(modulus, 256));
        buffer.put(littleEndian(rr, 256));
        buffer.putInt(publicKey.getPublicExponent().intValue());
        String text = Base64.getEncoder().encodeToString(buffer.array()) + " " + name + "\u0000";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] littleEndian(BigInteger value, int size) {
        byte[] big = value.toByteArray();
        byte[] out = new byte[size];
        for (int i = 0; i < size && i < big.length; i++) out[i] = big[big.length - 1 - i];
        return out;
    }

    public synchronized SSLContext sslContext() throws Exception {
        if (context == null) {
            SSLContext created = SSLContext.getInstance("TLSv1.3");
            created.init(new KeyManager[] {new FixedKeyManager()}, new TrustManager[] {new AnyTrustManager()},
                    new SecureRandom());
            context = created;
        }
        return context;
    }

    private final class FixedKeyManager extends X509ExtendedKeyManager {
        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[] {ALIAS};
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return ALIAS;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return ALIAS;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return new X509Certificate[] {certificate};
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return privateKey;
        }
    }

    private static final class AnyTrustManager extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
