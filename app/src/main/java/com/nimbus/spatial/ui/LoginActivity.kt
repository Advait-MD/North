package com.nimbus.spatial.ui

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.gms.tasks.Task
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.nimbus.spatial.R

/**
 * Email/password sign-in and sign-up, as two separate buttons. (Auto-creating an account when
 * sign-in failed would let a typo make a junk account and reveal which emails are registered.)
 */
class LoginActivity : AppCompatActivity() {

    private val auth = FirebaseAuth.getInstance()
    private lateinit var emailLayout: TextInputLayout
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var emailInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var progress: View
    private lateinit var signInButton: Button
    private lateinit var createAccountButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)

        emailLayout = findViewById(R.id.til_email)
        passwordLayout = findViewById(R.id.til_password)
        emailInput = findViewById(R.id.et_email)
        passwordInput = findViewById(R.id.et_password)
        progress = findViewById(R.id.progress)
        signInButton = findViewById(R.id.btn_sign_in)
        createAccountButton = findViewById(R.id.btn_create_account)

        signInButton.setOnClickListener { signIn() }
        createAccountButton.setOnClickListener { createAccount() }

        // Errors disappear as soon as the user starts fixing them; "Done" on the keyboard signs in.
        emailInput.doAfterTextChanged { emailLayout.error = null }
        passwordInput.doAfterTextChanged { passwordLayout.error = null }
        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                signIn()
                true
            } else {
                false
            }
        }
    }

    private fun signIn() {
        if (!inputIsValid()) return
        submit(auth.signInWithEmailAndPassword(email(), password())) {
            // Deliberately vague: don't reveal whether this email has an account.
            "Sign-in failed. Check your email and password."
        }
    }

    private fun createAccount() {
        if (!inputIsValid()) return
        submit(auth.createUserWithEmailAndPassword(email(), password())) { error ->
            "Couldn't create account: ${error?.message}"
        }
    }

    private fun email() = emailInput.text?.toString()?.trim().orEmpty()

    private fun password() = passwordInput.text?.toString().orEmpty() // not trimmed: spaces are valid

    /** Shows what's wrong under the field itself, rather than in a pop-up message. */
    private fun inputIsValid(): Boolean {
        emailLayout.error =
            if (Patterns.EMAIL_ADDRESS.matcher(email()).matches()) null else "Enter a valid email address"
        passwordLayout.error =
            if (password().length >= MIN_PASSWORD_LENGTH) null else "At least $MIN_PASSWORD_LENGTH characters"
        return emailLayout.error == null && passwordLayout.error == null
    }

    private fun submit(request: Task<AuthResult>, errorMessage: (Exception?) -> String) {
        setBusy(true)
        request.addOnCompleteListener(this) { result ->
            if (result.isSuccessful) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                setBusy(false)
                showMessage(errorMessage(result.exception), long = true)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        signInButton.isEnabled = !busy
        createAccountButton.isEnabled = !busy
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 6 // Firebase Auth's minimum
    }
}
