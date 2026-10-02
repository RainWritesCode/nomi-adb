package gg.nomi.adb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

final class Der {
    private Der() {}

    static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = value.length;
        if (length < 128) {
            out.write(length);
        } else if (length < 256) {
            out.write(0x81);
            out.write(length);
        } else if (length < 65536) {
            out.write(0x82);
            out.write(length >> 8);
            out.write(length & 0xff);
        } else {
            out.write(0x83);
            out.write(length >> 16);
            out.write((length >> 8) & 0xff);
            out.write(length & 0xff);
        }
        out.write(value, 0, value.length);
        return out.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    static byte[] sequence(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    static byte[] integer(BigInteger value) {
        return tlv(0x02, value.toByteArray());
    }

    static byte[] nothing() {
        return new byte[] {0x05, 0x00};
    }

    static byte[] oid(int... arcs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int value = arcs[i];
            int shift = 28;
            while (shift > 0 && (value >>> shift) == 0) shift -= 7;
            while (shift > 0) {
                out.write(0x80 | ((value >>> shift) & 0x7f));
                shift -= 7;
            }
            out.write(value & 0x7f);
        }
        return tlv(0x06, out.toByteArray());
    }

    static byte[] utf8(String text) {
        return tlv(0x0c, text.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] utcTime(String text) {
        return tlv(0x17, text.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] generalizedTime(String text) {
        return tlv(0x18, text.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] bitString(byte[] bits) {
        byte[] value = new byte[bits.length + 1];
        System.arraycopy(bits, 0, value, 1, bits.length);
        return tlv(0x03, value);
    }

    static byte[] explicit(int number, byte[] inner) {
        return tlv(0xa0 | number, inner);
    }

    static X509Certificate selfSigned(PublicKey publicKey, PrivateKey privateKey, String commonName) throws Exception {
        byte[] algorithm = sequence(oid(1, 2, 840, 113549, 1, 1, 11), nothing());
        byte[] name = sequence(set(sequence(oid(2, 5, 4, 3), utf8(commonName))));
        byte[] validity = sequence(utcTime("250101000000Z"), generalizedTime("20550101000000Z"));
        BigInteger serial = new BigInteger(63, new SecureRandom()).add(BigInteger.ONE);
        byte[] tbs = sequence(explicit(0, integer(BigInteger.valueOf(2))), integer(serial), algorithm, name, validity, name,
                publicKey.getEncoded());
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(tbs);
        byte[] certificate = sequence(tbs, algorithm, bitString(signature.sign()));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificate));
    }
}
