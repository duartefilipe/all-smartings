package com.allsmartings.ring.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Exigida pelo Health Connect: explica o uso dos dados (aberta pelo link "política de privacidade"
 * na tela de permissões).
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Como o app usa seus dados", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "O Smart Ring Sync lê passos e frequência cardíaca do seu anel por Bluetooth e " +
                                "grava esses dados no Health Connect, para que outros apps de saúde possam usá-los.",
                        )
                        Text(
                            "O app só pede permissão de escrita. Não lê dados de outros apps, não envia nada " +
                                "para a internet e não tem servidor próprio. As configurações ficam apenas neste aparelho.",
                        )
                        Text("Você pode revogar as permissões a qualquer momento nas configurações do Health Connect.")
                    }
                }
            }
        }
    }
}
