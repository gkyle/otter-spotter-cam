package com.kylescholz.otterspotter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kylescholz.otterspotter.databinding.ActivitySignInBinding

class SignInActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignInBinding
    private var googleSignInClient: GoogleSignInClient? = null

    companion object {
        private const val TAG = "SignInActivity"
        const val EXTRA_ALLOW_CANCEL = "extra_allow_cancel"
        const val DEBUG_SHA1 = "6F:BD:62:3C:FA:35:E3:CE:CD:D7:BA:78:DA:A0:39:FC:EC:14:BF:8F"
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        setLoading(false)
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            handleSignInSuccess(account)
        } catch (e: ApiException) {
            Log.w(TAG, "Google sign in failed: statusCode=${e.statusCode}, message=${e.message}", e)
            handleSignInError(e.statusCode, e.message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already authenticated or in guest session, proceed directly to camera
        val allowCancel = intent.getBooleanExtra(EXTRA_ALLOW_CANCEL, false)
        if (!allowCancel && AuthManager.isLoggedIn(this)) {
            navigateToCamera()
            return
        }

        binding = ActivitySignInBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGoogleSignInClient(requestIdToken = true)
        setupListeners()
    }

    private fun setupGoogleSignInClient(requestIdToken: Boolean = true) {
        val webClientId = getString(R.string.default_web_client_id)
        val isPlaceholder = webClientId.startsWith("YOUR_GOOGLE_WEB_CLIENT_ID")

        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()

        if (requestIdToken && !isPlaceholder) {
            try {
                gsoBuilder.requestIdToken(webClientId)
                Log.d(TAG, "Configured requestIdToken with: $webClientId")
            } catch (e: Exception) {
                Log.w(TAG, "Could not set requestIdToken: ${e.message}")
            }
        } else {
            Log.d(TAG, "Configured basic GoogleSignIn without requestIdToken (isPlaceholder=$isPlaceholder, requestIdToken=$requestIdToken)")
        }

        googleSignInClient = GoogleSignIn.getClient(this, gsoBuilder.build())
    }

    private fun setupListeners() {
        binding.btnGoogleSignIn.setOnClickListener {
            initiateGoogleSignIn()
        }

        binding.btnContinueGuest.setOnClickListener {
            AuthManager.setGuestMode(this)
            Toast.makeText(this, "Operating in Offline Guest Mode", Toast.LENGTH_SHORT).show()
            navigateToCamera()
        }
    }

    private fun initiateGoogleSignIn() {
        val webClientId = getString(R.string.default_web_client_id)
        val isPlaceholder = webClientId.startsWith("YOUR_GOOGLE_WEB_CLIENT_ID")

        if (isPlaceholder) {
            showConfigNoticeDialog()
            return
        }

        startGoogleFlow()
    }

    private fun startGoogleFlow() {
        setLoading(true)
        val client = googleSignInClient ?: run {
            setupGoogleSignInClient(requestIdToken = true)
            googleSignInClient!!
        }
        val signInIntent = client.signInIntent
        googleSignInLauncher.launch(signInIntent)
    }

    private fun handleSignInSuccess(account: GoogleSignInAccount) {
        val user = OtterUser(
            id = account.id ?: account.email ?: "user_${System.currentTimeMillis()}",
            email = account.email ?: "unknown@gmail.com",
            displayName = account.displayName ?: account.givenName ?: "Otter Spotter User",
            photoUrl = account.photoUrl?.toString(),
            idToken = account.idToken,
            isGuest = false
        )

        AuthManager.saveUser(this, user)
        Log.i(TAG, "User signed in successfully: ${user.email} (hasIdToken=${user.idToken != null})")
        val tokenStatus = if (user.idToken != null) " (Synced with Cloud)" else " (Profile Linked)"
        Toast.makeText(this, "Welcome, ${user.displayName}!$tokenStatus", Toast.LENGTH_SHORT).show()

        navigateToCamera()
    }

    private fun handleSignInError(statusCode: Int, message: String?) {
        val userMsg = when (statusCode) {
            12501 -> "Sign-in cancelled"
            12500 -> "Sign-in configuration error. Ensure package 'com.kylescholz.otterspotter' and debug SHA-1 are registered in Google Cloud Console."
            10 -> "Developer configuration error (Code 10: DEVELOPER_ERROR). Check package name and SHA-1 in Google Cloud Console."
            7 -> "Network error. Please check your internet connection."
            else -> "Google Sign-In error ($statusCode): ${message ?: "Unknown"}"
        }

        binding.tvSignInStatus.text = userMsg
        binding.tvSignInStatus.visibility = View.VISIBLE

        if (statusCode == 10 || statusCode == 12500) {
            val currentSha1 = getAppCertificateSha1()
            val configuredId = getString(R.string.default_web_client_id)
            MaterialAlertDialogBuilder(this)
                .setTitle("Google Cloud Setup Notice")
                .setMessage(
                    "Google Play Services returned error code $statusCode (DEVELOPER_ERROR).\n\n" +
                    "Package Name:\ncom.kylescholz.otterspotter\n\n" +
                    "Signing Certificate SHA-1:\n$currentSha1\n\n" +
                    "Client ID in build:\n$configuredId\n\n" +
                    "Ensure this package name and SHA-1 are registered as an Android OAuth Client in Google Cloud Console."
                )
                .setPositiveButton("Copy SHA-1") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("OtterSpotter SHA1", currentSha1))
                    Toast.makeText(this, "SHA-1 copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Sign in Demo") { _, _ ->
                    signInDemoUser()
                }
                .setNeutralButton("Guest Mode") { _, _ ->
                    AuthManager.setGuestMode(this)
                    navigateToCamera()
                }
                .show()
        } else if (statusCode != 12501) {
            Toast.makeText(this, userMsg, Toast.LENGTH_LONG).show()
        }
    }

    private fun showConfigNoticeDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Google OAuth Configuration")
            .setMessage(
                "Google Sign-In requires an OAuth 2.0 Web Client ID in strings.xml.\n\n" +
                "Package Name: com.kylescholz.otterspotter\n" +
                "Debug SHA-1: 6F:BD:62:3C:FA:35:E3:CE:CD:D7:BA:78:DA:A0:39:FC:EC:14:BF:8F\n\n" +
                "How would you like to proceed for testing?"
            )
            .setPositiveButton("Sign in (Demo Biologist)") { _, _ ->
                signInDemoUser()
            }
            .setNegativeButton("Try Real Google Sign-In") { _, _ ->
                startGoogleFlow()
            }
            .setNeutralButton("Continue as Guest") { _, _ ->
                AuthManager.setGuestMode(this)
                navigateToCamera()
            }
            .show()
    }

    private fun getAppCertificateSha1(): String {
        return try {
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES)
            }
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }
            val cert = signatures?.firstOrNull()?.toByteArray() ?: return DEBUG_SHA1
            val md = java.security.MessageDigest.getInstance("SHA-1")
            val digest = md.digest(cert)
            digest.joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read app signing certificate", e)
            DEBUG_SHA1
        }
    }

    private fun signInDemoUser() {
        val demoUser = OtterUser(
            id = "google_user_demo_12345",
            email = "researcher@otterspotter.org",
            displayName = "Dr. River Biologist",
            photoUrl = null,
            idToken = "mock_jwt_id_token_google_oauth_sample",
            isGuest = false
        )
        AuthManager.saveUser(this, demoUser)
        Toast.makeText(this, "Signed in as ${demoUser.displayName}", Toast.LENGTH_SHORT).show()
        navigateToCamera()
    }

    private fun setLoading(isLoading: Boolean) {
        binding.pbSignInLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.btnGoogleSignIn.isEnabled = !isLoading
        binding.btnContinueGuest.isEnabled = !isLoading
        if (isLoading) {
            binding.tvSignInStatus.visibility = View.GONE
        }
    }

    private fun navigateToCamera() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
