/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto;

import android.os.Build;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import henize.proto.lib.sim.Simulator;

/** One encrypted document. Its separate transfer code carries the key and pins this exact export. */
final class EconomyArchive {
    private static final int MAGIC_VERSION = 0x45534301; // ESC, version 1
    private static final int FLAGS = Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE;
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int MAX_COMPRESSED = 16 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    static String write(Simulator economy, OutputStream destination) throws IOException {
        Buffer compressed = new Buffer();
        try (GZIPOutputStream zip = new GZIPOutputStream(compressed)) {
            EconomySnapshot.write(economy, zip);
        }
        if (compressed.size() > MAX_COMPRESSED) throw new IOException("Economy file too large");
        byte[] key = new byte[KEY_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(key);
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce);
            byte[] encrypted = cipher.doFinal(compressed.array(), 0, compressed.size());
            MessageDigest fingerprint = MessageDigest.getInstance("SHA-256");
            DataOutputStream out = new DataOutputStream(
                    new java.security.DigestOutputStream(destination, fingerprint));
            out.writeInt(MAGIC_VERSION);
            out.write(nonce);
            out.write(encrypted);
            out.flush();
            byte[] token = Arrays.copyOf(key, KEY_BYTES + 32);
            System.arraycopy(fingerprint.digest(), 0, token, KEY_BYTES, 32);
            return Base64.encodeToString(token, FLAGS);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not encrypt the economy", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    static Simulator read(InputStream source, String transferKey) throws IOException {
        byte[] token;
        try {
            if (transferKey == null || transferKey.length() != 86) throw new IllegalArgumentException();
            token = Base64.decode(transferKey, FLAGS);
            if (token.length != KEY_BYTES + 32 || !transferKey.equals(Base64.encodeToString(token, FLAGS))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid transfer key");
        }
        try {
            MessageDigest fingerprint = MessageDigest.getInstance("SHA-256");
            DataInputStream in = new DataInputStream(new java.security.DigestInputStream(source, fingerprint));
            if (in.readInt() != MAGIC_VERSION) throw new IOException("Unsupported economy file");
            byte[] nonce = new byte[NONCE_BYTES];
            in.readFully(nonce);
            Buffer encrypted = new Buffer();
            byte[] block = new byte[8192];
            int n;
            while ((n = in.read(block)) != -1) encrypted.write(block, 0, n);
            byte[] expected = Arrays.copyOfRange(token, KEY_BYTES, token.length);
            if (!MessageDigest.isEqual(expected, fingerprint.digest())) throw new IOException("File or key mismatch");

            // Authenticate all bytes before handing anything to the decompressor or the model reader.
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(token, KEY_BYTES), nonce);
            byte[] plain = cipher.doFinal(encrypted.array(), 0, encrypted.size());
            try (InputStream zip = new GZIPInputStream(new ByteArrayInputStream(plain))) {
                return EconomySnapshot.read(zip);
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("File or key mismatch", e);
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec aes = new SecretKeySpec(key, "AES");
        if (Build.VERSION.SDK_INT >= 19) {
            cipher.init(mode, aes, new GCMParameterSpec(TAG_BYTES * 8, nonce));
        } else {
            // Android's pre-KitKat GCM provider accepts an IV and uses the full 128-bit tag.
            cipher.init(mode, aes, new IvParameterSpec(nonce));
        }
        return cipher;
    }

    /** Bound both reads from document providers and the compressed representation we emit. */
    private static final class Buffer extends OutputStream {
        private final Bytes bytes = new Bytes();

        @Override public void write(int value) throws IOException {
            check(1);
            bytes.write(value);
        }

        @Override public void write(byte[] block, int offset, int length) throws IOException {
            check(length);
            bytes.write(block, offset, length);
        }

        private void check(int length) throws IOException {
            if (length > MAX_COMPRESSED + TAG_BYTES - bytes.size()) throw new IOException("Economy file too large");
        }

        byte[] array() { return bytes.array(); }
        int size() { return bytes.size(); }
    }

    private static final class Bytes extends ByteArrayOutputStream {
        byte[] array() { return buf; }
    }

    private EconomyArchive() {}
}
