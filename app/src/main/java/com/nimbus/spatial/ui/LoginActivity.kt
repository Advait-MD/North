package com.nimbus.spatial.ui

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.nimbus.spatial.R

/**
 * Email/password sign-in and sign-up, as two separate buttons. (Auto-creating an account when
 * sign-in failed would let a typo make a junk account and reveal which emails are registered.)
 */
class LoginActivity : AppCompatActivity() {

    private val auth = FirebaseAuth.getInstance()
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var signInButton: Button
    private lateinit var createAccountButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        emailInput = findViewById(R.id.et_email)
        passwordInput = findViewById(R.id.et_password)
        signInButton = findViewById(R.id.btn_sign_in)
        createAccountButton = findViewById(R.id.btn_create_account)

        signInButton.setOnClickListener { signIn() }
        createAccountButton.setOnClickListener { createAccount() }
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

    private fun email() = emailInput.text.toString().trim()

    private fun password() = passwordInput.text.toString() // not trimmed: spaces are valid characters

    private fun inputIsValid(): Boolean {
        if (!Patterns.EMAIL_ADDRESS.matcher(email()).matches()) {
            toast("Enter a valid email address")
            return false
        }
        if (password().length < MIN_PASSWORD_LENGTH) {
            toast("Password must be at least $MIN_PASSWORD_LENGTH characters")
            return false
        }
        return true
    }

    private fun submit(request: Task<AuthResult>, errorMessage: (Exception?) -> String) {
        setButtonsEnabled(false)
        request.addOnCompleteListener(this) { result ->
            setButtonsEnabled(true)
            if (result.isSuccessful) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                toast(errorMessage(result.exception), long = true)
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        signInButton.isEnabled = enabled
        createAccountButton.isEnabled = enabled
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 6 // Firebase Auth's minimum
    }
}
