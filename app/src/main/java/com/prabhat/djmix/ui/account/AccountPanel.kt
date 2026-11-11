package com.prabhat.djmix.ui.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prabhat.djmix.auth.googleIdToken
import com.prabhat.djmix.ui.theme.DJ

/** The Cloud tab while signed out: what the cloud is for, and the one button that opens it. */
@Composable
fun SignInCard(modifier: Modifier = Modifier, vm: AccountViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Your music stays on this phone", color = DJ.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in with Google to open the shared cloud library: copy any song from it, and back up the songs you add so everyone signed in can use them.",
            color = DJ.muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { vm.signIn { googleIdToken(activity) } }, enabled = !s.busy) {
            if (s.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Sign in with Google")
        }
        s.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = DJ.kill, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        }
    }
}

/** Signed in, but the cloud's rules refused the account: it is not on the owner's invite list. */
@Composable
fun NotInvitedCard(modifier: Modifier = Modifier, vm: AccountViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("This cloud is invite-only", color = DJ.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Ask the owner to add ${s.user?.email ?: "your email"} to the invite list, then tap REFRESH. Everything else works without the cloud.",
            color = DJ.muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = vm::signOut) { Text("Use another account") }
    }
}

/** Who is signed in, the auto-backup switch, and sign out. */
@Composable
fun AccountDialog(onDismiss: () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val user = s.user ?: return // signed out meanwhile: the caller stops showing it
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(user.name ?: "Signed in") },
        text = {
            Column {
                user.email?.let { Text(it, color = DJ.muted, fontSize = 12.sp) }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto backup", color = DJ.text, fontWeight = FontWeight.Bold)
                        Text("New songs, stems and recorded mixes go to the shared cloud by themselves.", color = DJ.muted, fontSize = 11.sp)
                    }
                    Switch(checked = s.autoBackup, onCheckedChange = vm::setAutoBackup)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { vm.signOut(); onDismiss() }) { Text("Sign out", color = DJ.kill) } },
    )
}

/** Compose hands out the screen's Context, which may wrap the Activity that Credential Manager needs. */
private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("no Activity")
}
