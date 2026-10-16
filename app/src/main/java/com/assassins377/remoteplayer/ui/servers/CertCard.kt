package com.assassins377.remoteplayer.ui.servers

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.assassins377.remoteplayer.data.CertInfo

/** Certificate details + explicit "trust" action. Shown on TLS failure (unknown or changed certificate). */
@Composable
fun CertCard(cert: CertInfo, onTrust: () -> Unit) {
    val colors = if (cert.pinMismatch) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors()
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (cert.pinMismatch) "Сертификат изменился! Если вы не обновляли сертификат на сервере, не доверяйте ему."
                else "Сервер использует сертификат, которому система не доверяет" + if (cert.selfSigned) " (самоподписанный)." else ".",
                style = MaterialTheme.typography.titleSmall,
            )
            Text("Кому выдан: ${cert.subject}", style = MaterialTheme.typography.bodySmall)
            Text("Кем выдан: ${cert.issuer}", style = MaterialTheme.typography.bodySmall)
            Text("Действителен: ${cert.validity()}", style = MaterialTheme.typography.bodySmall)
            Text("SHA-256:", style = MaterialTheme.typography.bodySmall)
            Text(cert.fingerprintDisplay(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text("Сверьте отпечаток с настройками сервера (NAS) перед тем, как доверять.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onTrust) { Text(if (cert.pinMismatch) "Доверять новому сертификату" else "Доверять этому сертификату") }
        }
    }
}
