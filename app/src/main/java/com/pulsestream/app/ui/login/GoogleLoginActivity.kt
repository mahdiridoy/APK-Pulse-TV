package com.pulsestream.app.ui.login

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.pulsestream.app.R
import com.pulsestream.app.databinding.ActivityGoogleLoginBinding
import com.pulsestream.app.ui.account.AccountSelectActivity

/**
 * Mandatory Google Login screen.
 * Shown on every app launch if the user is not logged in.
 * There is NO way to skip â€” Google login is required to use the app.
 * Firebase Auth persists login across app updates/uninstalls
 * (unless the user explicitly logs out or clears app data).
 */
@Suppress("DEPRECATION")
class GoogleLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGoogleLoginBinding
    private lateinit var googleSignInClient: GoogleSignInClient
    private var auth: FirebaseAuth? = null
    private var firebaseAvailable = true

    companion object {
        private const val TAG = "GoogleLogin"
        private const val PREFS_NAME = "pulsestream_auth"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_PHOTO = "user_photo_url"
        private const val KEY_USER_ID = "user_id"
    }

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                val email = account.email
                val maskedEmail = email?.let { val parts = it.split("@"); if (parts.size == 2) "${parts[0].take(2)}***@${parts[1]}" else "***" } ?: "***"
                Log.d(TAG, "Google sign-in success: $maskedEmail")
                firebaseAuthWithGoogle(account.idToken!!)
            } catch (e: ApiException) {
                Log.e(TAG, "Google sign-in failed: ${e.statusCode}", e)
                setLoading(false)
                when (e.statusCode) {
                    12501 -> Toast.makeText(this, "Sign-in was cancelled. Please try again.", Toast.LENGTH_LONG).show()
                    12500 -> Toast.makeText(this, "Sign-in error. Check your Google account settings.", Toast.LENGTH_LONG).show()
                    12502 -> Toast.makeText(this, "Sign-in in progress. Please wait.", Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(this, "Sign-in failed (code: ${e.statusCode}). Please try again.", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in result handling failed", e)
            setLoading(false)
            Toast.makeText(this, "Sign-in failed. Please try again.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // On API 33+ (Android 13+), onBackPressed() is no longer called.
        // Register a callback to block back navigation entirely (mandatory login).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Cannot go back â€” login is mandatory. Do nothing.
            }
        })

        // Initialize Firebase Auth FIRST (before isLoggedIn which uses auth.currentUser).
        // CRITICAL FIX: Wrap in try/catch so a Firebase init failure (e.g. network/initialization
        // issue on a clean install) doesn't crash the activity, which would cause Android to
        // immediately relaunch it and produce an infinite splash/flash loop.
        try {
            auth = FirebaseAuth.getInstance()
        } catch (e: Throwable) {
            Log.e(TAG, "Firebase init failed, will rely on local prefs only", e)
            auth = null
            firebaseAvailable = false
        }

        // If already logged in, go straight to the app. Only do this check if Firebase
        // actually initialized; otherwise we have no way to verify the user and must show
        // the login screen.
        if (firebaseAvailable && isLoggedIn()) {
            proceedToApp()
            return
        }

        binding = ActivityGoogleLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            // Configure Google Sign-In
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .requestProfile()
                .build()

            googleSignInClient = GoogleSignIn.getClient(this, gso)

            binding.googleSignInButton.setOnClickListener {
                setLoading(true)
                val signInIntent = googleSignInClient.signInIntent
                signInLauncher.launch(signInIntent)
            }
        } catch (e: Exception) {
            // If Google Play Services is not available (some TV boxes, Fire TV, etc.)
            // or google-services.json is not configured, show an error and allow the user to proceed
            Log.e(TAG, "Google Sign-In initialization failed", e)
            Toast.makeText(
                this,
                "Google Sign-In is not available on this device. Please sign in on a device with Google Play Services.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Authenticate with Firebase using the Google ID token.
     */
    private fun firebaseAuthWithGoogle(idToken: String) {
        val fbAuth = auth ?: run {
            // Firebase isn't available (shouldn't happen since we got an ID token, but
            // guard anyway). Fall through to a graceful login using the Google profile data.
            Toast.makeText(this, "Firebase unavailable â€” using limited sign-in.", Toast.LENGTH_SHORT).show()
            proceedToApp()
            return
        }
        setLoading(true)
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        fbAuth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Firebase auth success")
                    val user = fbAuth.currentUser

                    // Save login state to SharedPreferences (persists across updates)
                    saveLoginState(
                        userId = user?.uid.orEmpty(),
                        email = user?.email.orEmpty(),
                        name = user?.displayName.orEmpty(),
                        photoUrl = user?.photoUrl?.toString().orEmpty()
                    )

                    // Activate cloud sync and Community presence for this user.
                    // FirestoreRestSyncManager: setEnabled(true) unblocks the manager;
                    // initialize() fetches the Firebase ID token and pulls the user
                    // document from Firestore so bookmarks/history/settings from other
                    // devices become visible locally.
                    // FirebaseRTDBManager: registers the user/device in RTDB and starts
                    // presence tracking for Community live watching stats.
                    try {
                        com.pulsestream.app.sync.FirestoreRestSyncManager.setEnabled(true)
                        com.pulsestream.app.sync.FirestoreRestSyncManager.initialize()
                        com.pulsestream.app.sync.FirebaseRTDBManager.initialize()
                        com.pulsestream.app.sync.FirebaseRTDBManager.startPresence()
                    } catch (_: Exception) {
                    }

                    Toast.makeText(this, "Welcome, ${user?.displayName ?: user?.email}!", Toast.LENGTH_SHORT).show()
                    proceedToApp()
                } else {
                    Log.e(TAG, "Firebase auth failed", task.exception)
                    setLoading(false)
                    Toast.makeText(this, "Authentication failed. Please try again.", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun proceedToApp() {
        startActivity(Intent(this, AccountSelectActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun setLoading(loading: Boolean) {
        binding.googleSignInButton.isEnabled = !loading
        binding.loginLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.googleSignInButton.alpha = if (loading) 0.5f else 1f
    }

    // --- Persistent login state ---

    private fun isLoggedIn(): Boolean {
        // First check Firebase Auth (most reliable — persists in app-internal storage)
        val firebaseUser = auth?.currentUser
        if (firebaseUser != null) {
            // Re-sync SharedPreferences in case it was cleared
            saveLoginState(
                userId = firebaseUser.uid,
                email = firebaseUser.email.orEmpty(),
                name = firebaseUser.displayName.orEmpty(),
                photoUrl = firebaseUser.photoUrl?.toString().orEmpty()
            )
            return true
        }
        // Firebase Auth says no user — the SharedPreferences fallback is stale/invalid.
        // Clear it to prevent bypassing a valid sign-out or token expiry.
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_IS_LOGGED_IN, false)) {
            Log.w(TAG, "Firebase Auth has no user but SharedPreferences says logged in — clearing stale auth state")
            prefs.edit().clear().apply()
        }
        return false
    }

    private fun saveLoginState(userId: String, email: String, name: String, photoUrl: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putBoolean(KEY_IS_LOGGED_IN, true)
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_EMAIL, email)
            putString(KEY_USER_NAME, name)
            putString(KEY_USER_PHOTO, photoUrl)
            apply()
        }
    }

    /**
     * Full sign-out: Firebase Auth + Google Sign-In + SharedPreferences + RTDB presence.
     * Call this when the user wants to log out of the app completely.
     */
    fun clearLoginState() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply()
        try {
            auth?.signOut()
        } catch (e: Exception) {
            Log.w(TAG, "Firebase signOut failed", e)
        }
        try {
            com.pulsestream.app.sync.FirebaseRTDBManager.stopPresence()
        } catch (_: Exception) {}
        try {
            com.pulsestream.app.sync.FirestoreRestSyncManager.reset()
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Cannot go back — login is mandatory (for API < 33)
        // On API 33+, the OnBackPressedCallback above handles this.
        // Do nothing, user must sign in to proceed
    }
}
