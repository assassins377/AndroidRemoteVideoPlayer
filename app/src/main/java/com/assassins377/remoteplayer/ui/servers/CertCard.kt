package com.assassins377.remoteplayer.ui.servers

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.assassins377.remoteplayer.data.CertInfo
import com.assassins377.remoteplayer.R
import androidx.compose.ui.res.stringResource

/** Certificate details + explicit "trust" action. Shown on TLS failure (unknown or changed certificate). */
@Composable
fun CertCard(cert: CertInfo, onTrust: () -> Unit) {
    val colors = if (cert.pinMismatch) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors()
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(when { cert.pinMismatch -> R.string.cert_changed_title; cert.selfSigned -> R.string.cert_untrusted_self; else -> R.string.cert_untrusted_title }),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(stringResource(R.string.cert_subject, cert.subject), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.cert_issuer, cert.issuer), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.cert_valid, cert.validity()), style = MaterialTheme.typography.bodySmall)
            Text("SHA-256:", style = MaterialTheme.typography.bodySmall)
            Text(cert.fingerprintDisplay(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text(stringResource(R.string.cert_verify), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onTrust) { Text(stringResource(if (cert.pinMismatch) R.string.cert_trust_new else R.string.cert_trust)) }
        }
    }
}
