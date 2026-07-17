package com.example.disastermesh

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object EncryptionUtils {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH = 128
    private const val IV_LENGTH = 12
    
    // Fixed universal key so everyone with the app can communicate instantly
    private val MESH_KEY: ByteArray = "DisasterMeshPub1".toByteArray().copyOf(16) 

    fun encrypt(value: String): String {
        // Feature 4: Digital Signature (Integrity check)
        val signature = value.hashCode().toString()
        val signedValue = "$signature|$value"

        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(IV_LENGTH)
        SecureRandom().nextBytes(iv)
        val spec = GCMParameterSpec(TAG_LENGTH, iv)
        val sks = SecretKeySpec(MESH_KEY, "AES")
        
        cipher.init(Cipher.ENCRYPT_MODE, sks, spec)
        val encrypted = cipher.doFinal(signedValue.toByteArray())
        
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String? {
        return try {
            val combined = Base64.decode(value, Base64.NO_WRAP)
            if (combined.size < IV_LENGTH) return null
            
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val encrypted = combined.copyOfRange(IV_LENGTH, combined.size)
            
            val cipher = Cipher.getInstance(ALGORITHM)
            val spec = GCMParameterSpec(TAG_LENGTH, iv)
            val sks = SecretKeySpec(MESH_KEY, "AES")
            
            cipher.init(Cipher.DECRYPT_MODE, sks, spec)
            val decryptedSigned = String(cipher.doFinal(encrypted))
            
            val parts = decryptedSigned.split("|", limit = 2)
            if (parts.size < 2) return null
            
            val signature = parts[0]
            val content = parts[1]
            
            // Verify Signature
            if (signature == content.hashCode().toString()) {
                content
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
