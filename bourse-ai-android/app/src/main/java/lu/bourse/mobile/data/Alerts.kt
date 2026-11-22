package lu.bourse.mobile.data

import com.google.gson.*
import java.security.MessageDigest

data class Alert(val id: Long, val portfolioId: Long, val type: String, val message: String, val status: String, val dataTime: String?, val createdAt: String?, val eventKey: String?)
fun alerts(value: JsonElement) = value.rows().map { row -> Alert(row.id() ?: throw ContractFailure("Alerte sans identifiant"), row.id("portfolioId") ?: throw ContractFailure("Portefeuille de l’alerte absent"), row.required("alertType"), row.required("message"), row.required("status"), row.text("dataTime"), row.text("createdTime"), row.text("eventKey")) }
fun monitoringScope(config: ConnectionConfig): String = MessageDigest.getInstance("SHA-256").digest("${config.server.trimEnd('/')}|${config.portfolioId}".toByteArray()).joinToString("") { "%02x".format(it) }
fun notificationKey(config: ConnectionConfig, alert: Alert) = "${monitoringScope(config)}:${alert.id}:${alert.createdAt}:${alert.eventKey}"
fun pendingNotifications(config: ConnectionConfig, rows: List<Alert>, seen: Set<String>): List<Alert> = if (config.demo || config.portfolioId == null) emptyList() else rows.filter { it.portfolioId == config.portfolioId && it.status == "NEW" && notificationKey(config, it) !in seen }.distinctBy { notificationKey(config, it) }
