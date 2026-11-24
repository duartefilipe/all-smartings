package com.allsmartings.ring.protocol

/**
 * Protocolos suportados, detectados pelo serviço GATT.
 *
 * Os UUIDs são comparados pelo prefixo de 8 dígitos, igual ao painel Python
 * (`src/smartring/ble.py`).
 */
enum class RingProtocol(
    val label: String,
    val servicePrefixes: List<String>,
    val writePrefix: String,
    val notifyPrefixes: List<String>,
    /** YuCheng exige escrita com resposta; Colmi escreve sem resposta. */
    val writeWithResponse: Boolean,
    val manufacturer: String,
) {
    YUCHENG(
        label = "YuCheng (R11M)",
        servicePrefixes = listOf("be940000"),
        writePrefix = "be940001",
        notifyPrefixes = listOf("be940001", "be940003"),
        writeWithResponse = true,
        manufacturer = "YuCheng",
    ),
    COLMI(
        label = "Colmi/QRing (R03)",
        servicePrefixes = listOf("6e40fff0", "6e400001"),
        writePrefix = "6e400002",
        notifyPrefixes = listOf("6e400003"),
        writeWithResponse = false,
        manufacturer = "Colmi",
    );

    companion object {
        /** Detecta o protocolo a partir dos UUIDs de serviço (qualquer caixa). */
        fun detect(serviceUuids: Collection<String>): RingProtocol? {
            val lower = serviceUuids.map { it.lowercase() }
            return entries.firstOrNull { p -> lower.any { u -> p.servicePrefixes.any { u.startsWith(it) } } }
        }

        fun matches(uuid: String, prefix: String): Boolean = uuid.lowercase().startsWith(prefix)
    }
}
