package com.nimbus.spatial

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth

/**
 * Sign-in and sign-up are separate actions. Previously a failed sign-in silently tried to create
 * an account, so a typo'd email made a junk account and the error revealed whether an email
 * was registered.
 */
class LoginActivity : AppCompatActivity() {

    private val auth = FirebaseAuth.getInstance()
    private lateinit var emailEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var createAccountButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        emailEditText = findViewById(R.id.emailEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        loginButton = findViewById(R.id.loginButton)
        createAccountButton = findViewById(R.id.createAccountButton)

        loginButton.setOnClickListener {
            readCredentials()?.let { (email, password) ->
                submit(auth.signInWithEmailAndPassword(email, password)) {
                    // Deliberately generic: don't reveal whether the email has an account.
                    "Sign-in failed. Check your email and password."
                }
            }
        }
        createAccountButton.setOnClickListener {
            readCredentials()?.let { (email, password) ->
                submit(auth.createUserWithEmailAndPassword(email, password)) { e ->
                    "Couldn't create account: ${e?.message}"
                }
            }
        }
    }

    /** Returns (email, password), or null after showing what's wrong. The password is not trimmed. */
    private fun readCredentials(): Pair<String, String>? {
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString()
        return when {
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> null.also { toast("Enter a valid email address") }
            password.length < MIN_PASSWORD_LENGTH -> null.also { toast("Password must be at least $MIN_PASSWORD_LENGTH characters") }
            else -> email to password
        }
    }

    private fun submit(task: Task<AuthResult>, errorMessage: (Exception?) -> String) {
        setBusy(true)
        task.addOnCompleteListener(this) { result ->
            setBusy(false)
            if (result.isSuccessful) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                toast(errorMessage(result.exception))
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        loginButton.isEnabled = !busy
        createAccountButton.isEnabled = !busy
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val MIN_PASSWORD_LENGTH = 6 // Firebase Auth's minimum
    }
}
