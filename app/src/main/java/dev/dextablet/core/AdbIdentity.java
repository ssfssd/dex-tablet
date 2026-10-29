package dev.dextablet.core;

import java.io.*;
import java.math.BigInteger;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.util.Base64;
import javax.crypto.Cipher;

/** App-private ADB identity; never exported or logged. */
public final class AdbIdentity implements AdbConnection.Authenticator {
    private final KeyPair pair;
    public AdbIdentity(KeyPair pair) { this.pair = pair; }
    public static AdbIdentity load(File directory) throws Exception {
        File privateFile = new File(directory, "adb-private.pk8"), publicFile = new File(directory, "adb-public.der");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        if (privateFile.isFile() && publicFile.isFile()) {
            return new AdbIdentity(new KeyPair(factory.generatePublic(new X509EncodedKeySpec(read(publicFile))),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(read(privateFile)))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048);
        KeyPair pair = g.generateKeyPair();
        write(privateFile, pair.getPrivate().getEncoded()); write(publicFile, pair.getPublic().getEncoded());
        return new AdbIdentity(pair);
    }
    private static byte[] read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) { return AdbWire.readFully(in, (int) file.length()); }
    }
    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); out.getFD().sync(); }
    }
    @Override public byte[] sign(byte[] token) throws Exception {
        if (token.length != 20) throw new IOException("ADB 인증 토큰 오류");
        byte[] prefix = {0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};
        byte[] digestInfo = new byte[35];
        System.arraycopy(prefix,0,digestInfo,0,15); System.arraycopy(token,0,digestInfo,15,20);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding"); cipher.init(Cipher.ENCRYPT_MODE, pair.getPrivate());
        return cipher.doFinal(digestInfo);
    }
    @Override public byte[] publicKey() {
        RSAPublicKey key = (RSAPublicKey) pair.getPublic(); BigInteger n = key.getModulus();
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n.mod(two32).modInverse(two32).negate().mod(two32).intValue());
        putLittle(b, n, 256); putLittle(b, BigInteger.ONE.shiftLeft(4096).mod(n), 256); b.putInt(key.getPublicExponent().intValue());
        return (Base64.getEncoder().encodeToString(b.array()) + " dextablet@android\0").getBytes(StandardCharsets.US_ASCII);
    }
    private static void putLittle(ByteBuffer b, BigInteger value, int length) {
        for (int i = 0; i < length; i++) b.put(value.shiftRight(i * 8).byteValue());
    }
}
