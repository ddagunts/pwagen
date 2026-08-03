/*
 * Copyright (C) 2026 pwagen contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.pwagen.app.generate

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.UnrecoverableKeyException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/**
 * The device-local key that signs every APK pwagen generates.
 *
 * The private key is created inside the Android Keystore and is **non-exportable**:
 * apksig's entire signing path is `Signature.initSign(key)`, so the signature is
 * computed inside the TEE (or StrongBox) and the key material never enters app
 * memory, let alone a backup.
 *
 * ### Lifetime
 *
 * Keystore entries are scoped to the package name, UID and signing certificate of
 * the app that created them, so this key survives every ordinary pwagen update.
 * It is destroyed by:
 *
 * - uninstalling pwagen,
 * - switching install source, which is an uninstall plus install rather than an
 *   update,
 * - a factory reset or new device.
 *
 * When that happens the generated PWAs remain installed but can no longer be
 * updated in place, because Android requires a matching signature. Recovery is to
 * reinstall them from a backup, which is why backups carry configuration and icons
 * rather than a key that could not be exported anyway.
 */
class KeystoreSigningKey private constructor(
    override val privateKey: PrivateKey,
    override val certificates: List<X509Certificate>,
    /** Whether the key sits in dedicated secure hardware rather than the TEE. */
    val strongBoxBacked: Boolean,
) : SigningKey {

    /** Colon-separated SHA-256 of the certificate, for display next to a PWA. */
    val fingerprint: String by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(certificates.first().encoded)
            .joinToString(":") { "%02X".format(it) }
    }

    /**
     * Raised when a key exists but cannot be used — documented, if uncommon, after
     * some OS updates. It is surfaced rather than swallowed because the
     * consequence is specific and the user needs to hear it: existing PWAs can no
     * longer be updated in place, and must be reinstalled from backup.
     */
    class Unusable(cause: Throwable) : Exception(
        "pwagen's signing key is present but unusable. Existing web apps can no " +
            "longer be updated in place and will need reinstalling from a backup.",
        cause,
    )

    companion object {
        private const val TAG = "pwagen"
        private const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "pwagen-signing"

        /**
         * Returns the existing key, creating one on first run.
         *
         * @throws Unusable if an entry exists but its key cannot be loaded.
         */
        fun loadOrCreate(): KeystoreSigningKey {
            val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

            if (keyStore.containsAlias(ALIAS)) {
                return load(keyStore)
            }
            generate()
            return load(KeyStore.getInstance(PROVIDER).apply { load(null) })
        }

        /** Whether a key already exists, without creating one as a side effect. */
        fun exists(): Boolean =
            KeyStore.getInstance(PROVIDER).apply { load(null) }.containsAlias(ALIAS)

        private fun load(keyStore: KeyStore): KeystoreSigningKey {
            val privateKey = try {
                keyStore.getKey(ALIAS, null) as PrivateKey
            } catch (e: UnrecoverableKeyException) {
                throw Unusable(e)
            } catch (e: ClassCastException) {
                throw Unusable(e)
            }

            val chain = keyStore.getCertificateChain(ALIAS)
                ?.filterIsInstance<X509Certificate>()
                .orEmpty()
            if (chain.isEmpty()) {
                throw Unusable(IllegalStateException("Keystore entry has no certificate"))
            }

            // The provider name is the only reliable signal of where the key
            // actually ended up, since StrongBox generation falls back silently.
            val strongBox = privateKey.toString().contains("StrongBox", ignoreCase = true)
            return KeystoreSigningKey(privateKey, chain, strongBox)
        }

        /**
         * Generates the key, preferring StrongBox and falling back to the TEE.
         *
         * Not every device has StrongBox, and some that advertise it reject EC
         * key generation, so the fallback is a normal path rather than an error.
         */
        private fun generate() {
            try {
                generate(strongBox = true)
                Log.i(TAG, "Signing key generated in StrongBox")
            } catch (e: StrongBoxUnavailableException) {
                Log.i(TAG, "StrongBox unavailable, generating key in the TEE", e)
                generate(strongBox = false)
            } catch (e: Exception) {
                Log.w(TAG, "StrongBox key generation failed, falling back to the TEE", e)
                generate(strongBox = false)
            }
        }

        private fun generate(strongBox: Boolean) {
            val notBefore = Calendar.getInstance()
            val notAfter = (notBefore.clone() as Calendar).apply { add(Calendar.YEAR, 100) }

            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                // An APK must be signable without a prompt, and a lock-screen
                // change would otherwise invalidate the key and strand every
                // generated app.
                .setUserAuthenticationRequired(false)
                .setCertificateSubject(X500Principal("CN=pwagen, O=pwagen"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(notBefore.time)
                .setCertificateNotAfter(notAfter.time)
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()

            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
                .apply { initialize(spec) }
                .generateKeyPair()
        }
    }
}
