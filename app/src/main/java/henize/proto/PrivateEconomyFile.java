/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import henize.proto.lib.sim.Simulator;

/** Private slots keep their archive key sealed to the installation and the slot's name. */
final class PrivateEconomyFile {
    static final int MAGIC = 0x50534501; // PSE + version
    private static final int APP_PRIVATE_KEY = 1;
    private static final int KEYSTORE_KEY = 2;
    private static final int NONCE_BYTES = 12;
    private static final int BINDING_BYTES = 32;
    private static final int CODE_BYTES = 86;
    private static final int TAG_BYTES = 16;
    private static final int SEALED_BYTES = BINDING_BYTES + CODE_BYTES + TAG_BYTES;
    private static final int HEADER_BYTES = 4 + 1 + NONCE_BYTES + SEALED_BYTES;
    private static final String ALIAS = "proto.economy.private.v1";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The caller commits the completed, synced file by renaming it over the previous slot. */
    static void write(Context context, String slot, Simulator economy, File pending) throws IOException {
        int keyType = Build.VERSION.SDK_INT >= 23 ? KEYSTORE_KEY : APP_PRIVATE_KEY;
        try (FileOutputStream file = new FileOutputStream(pending)) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKey key = wrappingKey(context, keyType, true);
            if (keyType == KEYSTORE_KEY) {
                // AndroidKeyStore generates and enforces its own unique IV on encryption.
                cipher.init(Cipher.ENCRYPT_MODE, key);
            } else {
                byte[] nonce = new byte[NONCE_BYTES];
                RANDOM.nextBytes(nonce);
                initialize(cipher, Cipher.ENCRYPT_MODE, key, nonce);
            }
            byte[] nonce = cipher.getIV();
            if (nonce.length != NONCE_BYTES) throw new IOException("Unexpected private save nonce length");

            // Reserve the small header so the archive can write directly to disk. Only encrypted
            // bytes reach the temporary file, including if the write is interrupted.
            file.write(new byte[HEADER_BYTES]);
            String code = EconomyArchive.write(economy, file);
            byte[] clear = new byte[BINDING_BYTES + CODE_BYTES];
            byte[] codeBytes = code.getBytes(UTF8);
            try {
                System.arraycopy(binding(slot), 0, clear, 0, BINDING_BYTES);
                if (codeBytes.length != CODE_BYTES) throw new IOException("Unexpected archive key length");
                System.arraycopy(codeBytes, 0, clear, BINDING_BYTES, CODE_BYTES);
                byte[] sealed = cipher.doFinal(clear);
                if (sealed.length != SEALED_BYTES) throw new IOException("Unexpected wrapped key length");
                file.getChannel().position(0);
                DataOutputStream header = new DataOutputStream(file);
                header.writeInt(MAGIC);
                header.writeByte(keyType);
                header.write(nonce);
                header.write(sealed);
                header.flush();
                file.getFD().sync();
            } finally {
                Arrays.fill(clear, (byte) 0);
                Arrays.fill(codeBytes, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not protect the private save", e);
        }
    }

    static Simulator read(Context context, String slot, File source) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(source)))) {
            if (in.readInt() != MAGIC) throw new IOException("Unknown private save format");
            int keyType = in.readUnsignedByte();
            byte[] nonce = new byte[NONCE_BYTES];
            byte[] sealed = new byte[SEALED_BYTES];
            in.readFully(nonce);
            in.readFully(sealed);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            initialize(cipher, Cipher.DECRYPT_MODE, wrappingKey(context, keyType, false), nonce);
            byte[] clear = cipher.doFinal(sealed);
            try {
                if (clear.length != BINDING_BYTES + CODE_BYTES
                        || !MessageDigest.isEqual(binding(slot), Arrays.copyOf(clear, BINDING_BYTES))) {
                    throw new IOException("This save belongs to a different slot");
                }
                return EconomyArchive.read(in, new String(clear, BINDING_BYTES, CODE_BYTES, UTF8));
            } finally {
                Arrays.fill(clear, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not authenticate the private save", e);
        }
    }

    private static byte[] binding(String slot) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(slot.getBytes(UTF8));
    }

    private static synchronized SecretKey wrappingKey(Context context, int type, boolean create)
            throws GeneralSecurityException, IOException {
        if (type == KEYSTORE_KEY && Build.VERSION.SDK_INT >= 23) return AndroidKeys.get(create);
        if (type != APP_PRIVATE_KEY) throw new IOException("Unsupported private save key type");

        // AndroidKeyStore gained symmetric AES keys in API 23. On older releases, keep the
        // random wrapping key in the app sandbox, outside both the save list and shared storage.
        File directory = context.getDir("economy_keys", Context.MODE_PRIVATE);
        File target = new File(directory, "private-v1");
        byte[] bytes = new byte[32];
        try {
            if (target.exists()) {
                try (DataInputStream in = new DataInputStream(new FileInputStream(target))) {
                    in.readFully(bytes);
                    if (in.read() != -1) throw new IOException("Invalid private save key");
                }
            } else {
                if (!create) throw new IOException("Private save key is unavailable");
                RANDOM.nextBytes(bytes);
                File temp = File.createTempFile("new-key-", ".tmp", directory);
                try {
                    try (FileOutputStream out = new FileOutputStream(temp)) {
                        out.write(bytes);
                        out.getFD().sync();
                    }
                    if (!temp.renameTo(target)) throw new IOException("Could not store the private save key");
                } finally {
                    if (temp.exists()) temp.delete();
                }
            }
            return new SecretKeySpec(bytes, "AES");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void initialize(Cipher cipher, int mode, SecretKey key, byte[] nonce)
            throws GeneralSecurityException {
        if (Build.VERSION.SDK_INT >= 19) {
            cipher.init(mode, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
        } else {
            cipher.init(mode, key, new IvParameterSpec(nonce));
        }
    }

    @TargetApi(23)
    private static final class AndroidKeys {
        static SecretKey get(boolean create) throws GeneralSecurityException, IOException {
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
            keys.load(null);
            Key key = keys.getKey(ALIAS, null);
            if (key instanceof SecretKey) return (SecretKey) key;
            if (key != null || !create) throw new IOException("Private save key is unavailable");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            return generator.generateKey();
        }
    }

    private PrivateEconomyFile() {}
}
