package dev.ravindran.ythub.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CloudLibraryScreen() {
    Box(Modifier.fillMaxSize().padding(horizontal=20.dp,vertical=12.dp)) {
        DeviceLibrarySection()
    }
}
