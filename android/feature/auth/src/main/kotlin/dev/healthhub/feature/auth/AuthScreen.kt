package dev.healthhub.feature.auth

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.healthhub.core.designsystem.HealthHubType
import dev.healthhub.core.designsystem.Spacing

/**
 * Sign in or create an account.
 *
 * Auth0 opens in a browser tab rather than an in-app WebView: the client secret stays on the
 * Worker, the athlete can see the real address bar, and their password never passes through
 * this app at all.
 */
@Composable
fun AuthScreen(
    onSignedIn: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    // Not `rememberSaveable`: a password revealed once should not still be revealed after a
    // rotation, or after the app is restored from the background in front of somebody else.
    var passwordVisible by remember { mutableStateOf(false) }

    val submit = { viewModel.submit(email.trim(), password, displayName.trim()) }
    val canSubmit = !state.busy && email.isNotBlank() && password.isNotBlank()

    LaunchedEffect(state.signedIn) {
        if (state.signedIn) onSignedIn()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        // A form stretched across a tablet is a row of very wide text fields with the label at
        // one end and the cursor at the other. Capped and centred, it reads the same everywhere.
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = FORM_MAX_WIDTH),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
      ) {
        Spacer(Modifier.height(Spacing.xxl))

        Text(stringResource(R.string.auth_app_name), style = HealthHubType.headlineLargeEmphasized)
        Text(
            stringResource(R.string.auth_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Spacing.md))

        Button(
            onClick = {
                val url = Uri.parse(
                    "${BuildConfigBridge.baseUrl}/api/auth/auth0/login" +
                        "?mode=device&deviceName=${Uri.encode(android.os.Build.MODEL ?: "Android")}",
                )
                context.startActivity(Intent(Intent.ACTION_VIEW, url))
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
        ) {
            Text(stringResource(R.string.auth_auth0))
        }

        HorizontalDivider()

        if (state.mode == AuthUiState.Mode.SIGN_UP) {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(stringResource(R.string.auth_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.auth_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.auth_password)) },
            singleLine = true,
            visualTransformation =
                if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            // A reveal toggle rather than dots and hope. A password typed blind on a phone
            // keyboard is the single most common reason a correct password is reported wrong,
            // and this app's own error message for that case is "incorrect email or password".
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(
                            if (passwordVisible) R.string.auth_hide_password
                            else R.string.auth_show_password,
                        ),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            // The keyboard's own Done key submits. Reaching past it for a button is a step
            // nobody should have to take on the app's first screen.
            keyboardActions = KeyboardActions(onDone = { if (canSubmit) submit() }),
            supportingText = if (state.mode == AuthUiState.Mode.SIGN_UP) {
                { Text(stringResource(R.string.auth_password_hint, MIN_PASSWORD)) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )

        state.error?.let { message ->
            // In the error container rather than as red text on the page. Red text beside two
            // fields reads as "one of these is wrong"; a container says the attempt failed,
            // which is what actually happened.
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(Spacing.md),
                )
            }
        }

        Button(
            onClick = submit,
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    stringResource(
                        if (state.mode == AuthUiState.Mode.SIGN_IN) {
                            R.string.auth_sign_in
                        } else {
                            R.string.auth_create_account
                        },
                    ),
                )
            }
        }

        TextButton(
            onClick = viewModel::toggleMode,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(
                stringResource(
                    if (state.mode == AuthUiState.Mode.SIGN_IN) {
                        R.string.auth_switch_to_sign_up
                    } else {
                        R.string.auth_switch_to_sign_in
                    },
                ),
            )
        }
      }
    }
}

/** Wide enough to type in, narrow enough that the label and the cursor stay in one glance. */
private val FORM_MAX_WIDTH = 480.dp

/**
 * The deployment address, supplied by the app module.
 *
 * A feature module cannot read BuildConfig from :app, and threading the URL through the
 * ViewModel only to build a browser Intent would be noise; this is set once at startup.
 */
object BuildConfigBridge {
    var baseUrl: String = ""
}
