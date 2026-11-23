package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The one place the Wi-Fi passphrase is stored, encrypted with a key that never
 * leaves the Android Keystore.
 *
 * <p>SharedPreferences alone only protects a file with the app's uid; a backup,
 * a debug dump or a rooted device can still read it. AES-GCM with a Keystore key
 * means the stored XML contains only ciphertext and a random IV, which are
 * useless without the hardware-backed key.
 *
 * <p>Older builds wrote the passphrase in plaintext under {@link #LEGACY_KEY}.
 * The first read migrates that value in one edit, so an upgrade keeps the saved
 * network instead of asking for the password again.
 */
final class PassphraseStore {
    private static final String TAG = Ensurer.TAG;
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "local_wifi_passphrase";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;

    /** The plaintext key older versions used; read once, then removed. */
    static final String LEGACY_KEY = "passphrase";
    private static final String KEY_CIPHERTEXT = "passphrase_ciphertext";
    private static final String KEY_IV = "passphrase_iv";

    private PassphraseStore() {}

    /** Returns the passphrase, or null when the Keystore cannot decrypt it. */
    static String get(Context c) {
        SharedPreferences prefs = prefs(c);
        String ciphertext = prefs.getString(KEY_CIPHERTEXT, null);
        String iv = prefs.getString(KEY_IV, null);
        if (ciphertext != null && iv != null) {
            try {
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, key(false),
                        new GCMParameterSpec(GCM_TAG_BITS, Base64Codec.decode(iv)));
                return new String(cipher.doFinal(Base64Codec.decode(ciphertext)),
                        StandardCharsets.UTF_8);
            } catch (Exception e) {
                // A lost or invalidated key cannot be recovered; treat the value
                // as unset rather than crash the service or the Network page.
                Log.e(TAG, "wifi passphrase: cannot decrypt, treating as unset", e);
                return null;
            }
        }

        String legacy = prefs.getString(LEGACY_KEY, null);
        if (legacy == null) return "";
        if (legacy.isEmpty()) {
            prefs.edit().remove(LEGACY_KEY).apply();
            return "";
        }
        if (put(c, legacy)) {
            Log.i(TAG, "wifi passphrase: migrated to encrypted storage");
        } else {
            Log.w(TAG, "wifi passphrase: migration failed, plaintext left in place");
        }
        return legacy;
    }

    /** Encrypts and stores the value; false means the Keystore failed. */
    static boolean put(Context c, String value) {
        String clean = value == null ? "" : value;
        if (clean.isEmpty()) {
            clear(c);
            return true;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] encrypted = cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8));
            // One edit: the ciphertext appears and the old plaintext disappears
            // together, so a crash between writes cannot leave both behind.
            prefs(c).edit()
                    .putString(KEY_CIPHERTEXT, Base64Codec.encode(encrypted))
                    .putString(KEY_IV, Base64Codec.encode(cipher.getIV()))
                    .remove(LEGACY_KEY)
                    .apply();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "wifi passphrase: encryption failed, value not stored", e);
            return false;
        }
    }

    static void clear(Context c) {
        prefs(c).edit()
                .remove(KEY_CIPHERTEXT)
                .remove(KEY_IV)
                .remove(LEGACY_KEY)
                .apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(LocalWifi.PREFS, Context.MODE_PRIVATE);
    }

    /**
     * The AES key lives in the Keystore, not in prefs. It is created on first
     * use and reused after that; no user authentication is required because the
     * keeper also reconnects from a background service after a reboot.
     */
    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            KeyStore.Entry entry = store.getEntry(ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
        }
        if (!create) {
            throw new java.security.UnrecoverableKeyException("no passphrase key");
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build());
        return generator.generateKey();
    }
}
