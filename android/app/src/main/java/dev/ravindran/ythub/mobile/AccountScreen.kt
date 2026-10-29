package dev.ravindran.ythub.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(onCloudLibrary: () -> Unit = {}) {
    val account by CloudAccount.state.collectAsStateWithLifecycle()
    val sync by CloudSync.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var mode by remember { mutableStateOf("Sign in") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    fun action(block: suspend ()->Unit) { scope.launch { busy=true;message="";try { block() } catch(e: Exception) { message=e.message ?: "Request failed" } finally {busy=false;password=""} } }
    LaunchedEffect(account.status) { if(account.status=="Account recovery") mode="New password" }
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
    Surface(modifier=Modifier.widthIn(max=520.dp).fillMaxSize(),color=MaterialTheme.colorScheme.background.copy(alpha=.58f)) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Cloud,null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.primary)
            Text("Your music, anywhere",style=MaterialTheme.typography.titleLarge)
            Text("Sync your library. Keep your music offline.",style=MaterialTheme.typography.bodySmall,textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(!CloudAccount.configured) {
            Text("Cloud setup required",style=MaterialTheme.typography.titleLarge)
            Text("This build has no Supabase connection configured. Follow docs/development-setup.md and rebuild. Downloading and local playback are available in guest mode.")
        } else if(account.userId!=null && mode!="New password") {
            AccountGroup {
            Text("YOUR ACCOUNT",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(account.email,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("${if(account.verified) "Email verified" else "Verification pending"} · ${account.status}")
            }
            Spacer(Modifier.height(8.dp))
            AccountGroup {
            Text("Cloud & Sync",style=MaterialTheme.typography.titleLarge)
            Row(verticalAlignment=Alignment.CenterVertically) { Text("Sync library metadata",Modifier.weight(1f));Switch(sync.enabled,{CloudSync.configure(enabled=it)}) }
            Text("Sync song details across devices. Audio uploads stay manual.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment=Alignment.CenterVertically) { Text("Playlists",Modifier.weight(1f));Switch(sync.playlists,{CloudSync.configure(playlists=it)}) }
            Row(verticalAlignment=Alignment.CenterVertically) { Text("Favorites",Modifier.weight(1f));Switch(sync.favorites,{CloudSync.configure(favorites=it)}) }
            Row(verticalAlignment=Alignment.CenterVertically) { Text("Player preferences",Modifier.weight(1f));Switch(sync.preferences,{CloudSync.configure(preferences=it)}) }
            Text(sync.status)
            sync.lastSync?.let { Text("Updated " + runCatching { java.time.Instant.parse(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM, h:mm a")) }.getOrDefault("recently"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            if(sync.status == "Sync failed") OutlinedButton(onClick={CloudSync.retry()},enabled=sync.enabled) { Text("Try again") }
            if(sync.queue.isNotEmpty() && sync.status != "Sync failed") Text("${sync.queue.size} changes waiting to sync",style=MaterialTheme.typography.bodySmall)
            sync.queue.filter { it.status == "conflict" }.forEach { op ->
                if(op.status=="conflict") { Text("Changed on another device. Choose which edit to keep.");Row { TextButton(onClick={CloudSync.resolve(op.operation_id,false)}) {Text("Use remote")};TextButton(onClick={CloudSync.resolve(op.operation_id,true)}) {Text("Use mine")} } }
            }
            }
            Spacer(Modifier.height(8.dp))
            AccountGroup {
            var externalStorage by remember {mutableStateOf(DownloadStore.cloudExternalStorage)}
            Row(verticalAlignment=Alignment.CenterVertically) {Text("Use external app storage",Modifier.weight(1f));Switch(externalStorage,{externalStorage=it;DownloadStore.cloudExternalStorage=it})}
            Text("Applies to new downloads. Existing files stay in their current location.",style=MaterialTheme.typography.bodySmall)
            var reserve by remember {mutableStateOf(DownloadStore.cloudStorageReserveMb)}
            Text("Keep $reserve MB of storage free",style=MaterialTheme.typography.bodySmall)
            Slider(value=reserve.toFloat(),onValueChange={reserve=it.toInt();DownloadStore.cloudStorageReserveMb=reserve},valueRange=16f..512f)
            var wifiOnly by remember {mutableStateOf(DownloadStore.cloudWifiOnly)}
            var concurrency by remember {mutableStateOf(DownloadStore.cloudConcurrency)}
            Row(verticalAlignment=Alignment.CenterVertically) {Text("Download over Wi-Fi only",Modifier.weight(1f));Switch(wifiOnly,{wifiOnly=it;DownloadStore.cloudWifiOnly=it})}
            Text("Cloud download slots: $concurrency",style=MaterialTheme.typography.bodySmall)
            Slider(value=concurrency.toFloat(),onValueChange={concurrency=it.toInt();DownloadStore.cloudConcurrency=concurrency},valueRange=1f..3f,steps=1)
            var autoRetry by remember {mutableStateOf(DownloadStore.cloudAutoRetry)}
            Row(verticalAlignment=Alignment.CenterVertically) {Text("Retry interrupted transfers",Modifier.weight(1f));Switch(autoRetry,{autoRetry=it;DownloadStore.cloudAutoRetry=it})}
            Text("Cloud downloads share the Downloads concurrency setting and save to app-managed storage.",style=MaterialTheme.typography.bodySmall)
            MusicActionRow("Cloud library",Icons.Outlined.LibraryMusic,onCloudLibrary,subtitle="Browse songs and download to this device",forward=true)
            Text("Synchronized songs and playlists appear in Library. Tracks without a file on this phone remain unavailable for playback.")
            Text("${sync.rows["library_items"].orEmpty().count { it.get("deleted_at")?.isJsonNull!=false }} synchronized songs")
            }
            Spacer(Modifier.height(8.dp))
            AccountGroup { CloudFilesSection() }
            Spacer(Modifier.height(8.dp))
            AccountGroup {
            AccountExportButton()
            OutlinedButton(onClick={mode="New password"},enabled=!busy) {Text("Change password")}
            OutlinedButton(onClick={action {CloudAccount.signOut(true);message="Other refresh sessions revoked. Existing access tokens expire under the server policy."}},enabled=!busy) {Text("Sign out other sessions")}
            TextButton(onClick={deleting=true},enabled=!busy) {Text("Delete account")}
            if(deleting) AlertDialog(onDismissRequest={if(!busy)deleting=false},title={Text("Permanently delete account?")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Your cloud files, playlists and metadata will be deleted. Local downloaded files remain. This cannot be undone.")
                OutlinedTextField(confirmation,{confirmation=it},label={Text("Type DELETE MY ACCOUNT")})
                OutlinedTextField(password,{password=it},label={Text("Current password")},visualTransformation=PasswordVisualTransformation())
            }},confirmButton={TextButton(enabled=!busy&&confirmation=="DELETE MY ACCOUNT"&&password.isNotEmpty(),onClick={action {
                CloudAccount.api("/functions/v1/account-files","POST",json("action" to "delete-account","confirmation" to confirmation,"password" to password))
                CloudAccount.forgetDeletedAccount();deleting=false;message="Account deleted. Local files remain."
            }}){Text("Delete permanently")}},dismissButton={TextButton(enabled=!busy,onClick={deleting=false;password=""}){Text("Cancel")}})
            Button(onClick={action {CloudAccount.signOut()}},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("Sign out")}
            }
        } else {
            Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(mode,style=MaterialTheme.typography.titleLarge)
            if(mode!="New password") OutlinedTextField(email,{email=it},label={Text("Email address")},leadingIcon={Icon(Icons.Outlined.Email,null)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),singleLine=true,modifier=Modifier.fillMaxWidth())
            if(mode=="Create account") OutlinedTextField(name,{name=it.take(100)},label={Text("Display name")},leadingIcon={Icon(Icons.Outlined.Person,null)},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(mode in listOf("Sign in","Create account","New password")) OutlinedTextField(password,{password=it},label={Text("Password")},leadingIcon={Icon(Icons.Outlined.Lock,null)},supportingText={if(mode!="Sign in") Text("At least 12 characters")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            if(mode in listOf("Verify email","Recovery")) OutlinedTextField(code,{code=it.trim()},label={Text("Email code")},leadingIcon={Icon(Icons.Outlined.Pin,null)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
            Button(onClick={action {
                when(mode) {
                    "Sign in" -> CloudAccount.signIn(email,password)
                    "Create account" -> {CloudAccount.signUp(email,password,name);mode="Verify email";message="If registration is available, check your email for a code."}
                    "Verify email" -> CloudAccount.verify(email,code,false)
                    "Recovery" -> if(code.isBlank()) {CloudAccount.sendCode(email,true);message="If eligible, this address will receive a recovery code."} else CloudAccount.verify(email,code,true)
                    "New password" -> {CloudAccount.password(password);mode="Sign in";message="Password updated"}
                }
            }},enabled=!busy && account.status!="Initializing",modifier=Modifier.fillMaxWidth()) { Text(if(busy) "Please wait…" else if(mode=="Recovery" && code.isBlank()) "Send recovery code" else mode) }
            Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {TextButton(onClick={mode=if(mode=="Sign in") "Create account" else "Sign in";code=""},enabled=!busy) {Text(if(mode=="Sign in") "Create account" else "Back to sign in")};TextButton(onClick={mode="Recovery";code=""},enabled=!busy) {Text("Forgot password?")}}
            if(mode=="Verify email") TextButton(onClick={action {CloudAccount.sendCode(email,false);message="If eligible, a new code has been sent."}},enabled=!busy) {Text("Resend code")}
            Text("Local playback and downloads work without an account.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
            } }
        }
        if(message.isNotBlank()) Text(message,color=MaterialTheme.colorScheme.primary)
        if(account.message.isNotBlank()) Text(account.message)
        Spacer(Modifier.height(110.dp))
    }
    }
    }
}

@Composable
private fun AccountGroup(content: @Composable ColumnScope.() -> Unit) {
    LiquidGlassPanel(modifier=Modifier.fillMaxWidth(),backdrop=LocalAtmosphereLayer.current,radius=24.dp,animateOnTouch=false) {
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
