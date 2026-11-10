package com.homedatacenter.app.ui.ops

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.R
import com.homedatacenter.app.ui.components.GlassActionButton
import com.homedatacenter.app.ui.components.GlassTag
import com.homedatacenter.app.ui.components.GlassTagType
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.LiquidGlassCard
import dev.chrisbanes.haze.HazeState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AUDIT_STATUS_FILTERS = listOf(
    "ALL" to "全部",
    "COMPLETED" to "已完成",
    "FAILED" to "失败",
    "BLOCKED" to "已拦截",
    "ROLLED_BACK" to "已回滚",
    "EXECUTION_UNKNOWN" to "状态未知"
)

/**
 * Agent 操作审计 — Tamper-evident lifecycle audit runs card + chain verifier + filter chips.
 */
@Composable
internal fun AgentAuditHistorySection(
    runs: List<AgentAuditRunUi>,
    verifyResult: AuditVerifyResultUi?,
    isLoading: Boolean,
    isVerifying: Boolean,
    auditError: String?,
    onRefresh: (String?) -> Unit,
    onVerifyChain: () -> Unit,
    onSelectRun: (String) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    val tokens = GlassTheme.tokens
    var selectedStatus by remember { mutableStateOf("ALL") }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header & Chain Verification Banner Card
        LiquidGlassCard(
            modifier = Modifier.fillMaxWidth(),
            hazeState = hazeState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_security_shield),
                            contentDescription = null,
                            tint = tokens.accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Agent 操作审计",
                            style = TextStyle(
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.textColor
                            )
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassActionButton(
                            text = if (isVerifying) "校验中..." else "校验审计链",
                            enabled = !isVerifying,
                            onClick = onVerifyChain
                        )
                    }
                }

                Text(
                    text = "覆盖自主恢复、提案审批、安全防御与离线应急的全链路不可变操作轨迹 (SHA-256 哈希链 + 外部密钥 HMAC 检校)。",
                    style = TextStyle(fontSize = 11.5.sp, color = tokens.textSecondaryColor)
                )

                if (verifyResult != null) {
                    val verifyTagType = if (verifyResult.valid) GlassTagType.Success else GlassTagType.Error
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(tokens.surfaceColor.copy(alpha = 0.4f))
                            .border(1.dp, tokens.cardBorder, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlassTag(text = verifyResult.integrityStatus, type = verifyTagType)
                            Text(
                                text = "事件 ${verifyResult.checkedEvents} | 检查点 ${verifyResult.checkedCheckpoints}",
                                style = TextStyle(fontSize = 11.sp, color = tokens.textSecondaryColor)
                            )
                        }
                        if (verifyResult.failureReason.isNotBlank()) {
                            Text(
                                text = "异常原因: ${verifyResult.failureReason}",
                                style = TextStyle(fontSize = 11.sp, color = tokens.statusDanger)
                            )
                        }
                        if (verifyResult.limitationNote.isNotBlank()) {
                            Text(
                                text = verifyResult.limitationNote,
                                style = TextStyle(fontSize = 10.5.sp, color = tokens.textSecondaryColor)
                            )
                        }
                    }
                }

                // Filter Chips + Refresh Trigger
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AUDIT_STATUS_FILTERS.forEach { (statusKey, label) ->
                        val active = selectedStatus == statusKey
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (active) tokens.accentBg else tokens.controlBg)
                                .border(
                                    1.dp,
                                    if (active) tokens.accentColor.copy(alpha = 0.45f) else tokens.cardBorder,
                                    RoundedCornerShape(9.dp)
                                )
                                .clickable {
                                    selectedStatus = statusKey
                                    onRefresh(if (statusKey == "ALL") null else statusKey)
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = label,
                                style = TextStyle(
                                    fontSize = 11.5.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (active) tokens.textColor else tokens.textSecondaryColor
                                )
                            )
                        }
                    }
                }
            }
        }

        if (!auditError.isNullOrBlank()) {
            LiquidGlassCard(modifier = Modifier.fillMaxWidth(), hazeState = hazeState) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "审计读取提示: $auditError",
                        style = TextStyle(fontSize = 12.sp, color = tokens.statusWarning),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onRefresh(if (selectedStatus == "ALL") null else selectedStatus) }) {
                        Text("重试", color = tokens.accentColor, fontSize = 12.sp)
                    }
                }
            }
        }

        if (isLoading && runs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = tokens.accentColor, strokeWidth = 2.dp)
            }
        } else if (runs.isEmpty()) {
            LiquidGlassCard(modifier = Modifier.fillMaxWidth(), hazeState = hazeState) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "暂无匹配的操作审计任务",
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = tokens.textColor)
                    )
                    Text(
                        text = "下拉或点击过滤标签从服务端同步最新链路记录",
                        style = TextStyle(fontSize = 11.5.sp, color = tokens.textSecondaryColor)
                    )
                }
            }
        } else {
            runs.forEach { run ->
                AuditRunCardItem(
                    run = run,
                    hazeState = hazeState,
                    onClick = { onSelectRun(run.runId) }
                )
            }
        }
    }
}

@Composable
private fun AuditRunCardItem(
    run: AgentAuditRunUi,
    hazeState: HazeState?,
    onClick: () -> Unit
) {
    val tokens = GlassTheme.tokens
    val statusType = mapAuditStatusTagType(run.status)

    LiquidGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        hazeState = hazeState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${run.component.ifBlank { "system" }} · ${run.action}",
                        style = TextStyle(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.textColor
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (run.isOffline) {
                        GlassTag(text = "离线应急", type = GlassTagType.Warning)
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                GlassTag(text = run.status, type = statusType)
            }

            if (run.target.isNotBlank() || run.summary.isNotBlank()) {
                Text(
                    text = run.summary.ifBlank { "目标: ${run.target}" },
                    style = TextStyle(fontSize = 12.sp, color = tokens.textColor.copy(alpha = 0.9f)),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "触发: ${run.triggerSource.ifBlank { run.actorId }} · ${run.eventCount} 步",
                    style = TextStyle(fontSize = 11.sp, color = tokens.textSecondaryColor),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatAuditTimestamp(run.startedAtMs)} · seq ${run.firstSeq}..${run.lastSeq}",
                    style = TextStyle(
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = tokens.textSecondaryColor
                    )
                )
            }
        }
    }
}

/**
 * Full-step lifecycle timeline bottom sheet for a selected AgentAuditRun.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentAuditTimelineBottomSheet(
    detail: AgentAuditRunDetailUi,
    onDismiss: () -> Unit
) {
    val tokens = GlassTheme.tokens
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val run = detail.run

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = tokens.bgBase
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${run.component.ifBlank { "ops" }} / ${run.action}",
                        style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = tokens.textColor)
                    )
                    Text(
                        text = "RunID: ${run.runId}",
                        style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = tokens.textSecondaryColor)
                    )
                }
                GlassTag(text = run.status, type = mapAuditStatusTagType(run.status))
            }

            detail.integrity?.let { integrity ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(tokens.cardBg)
                        .border(1.dp, tokens.cardBorder, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassTag(
                        text = integrity.integrityStatus,
                        type = if (integrity.valid) GlassTagType.Success else GlassTagType.Error
                    )
                    Text(
                        text = if (integrity.triggersIntact) "触发器完整" else "触发器异常",
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = if (integrity.triggersIntact) tokens.statusOnline else tokens.statusDanger
                        )
                    )
                }
            }

            Text(
                text = "全链路时间线 (${detail.events.size} 步)",
                style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = tokens.textColor)
            )

            detail.events.forEach { ev ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(tokens.cardBg)
                        .border(1.dp, tokens.cardBorder, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            ev.errorCode.isNotBlank() || ev.result.equals("FAILED", ignoreCase = true) -> tokens.statusDanger
                                            ev.result.equals("BLOCKED", ignoreCase = true) -> tokens.statusWarning
                                            else -> tokens.statusOnline
                                        }
                                    )
                            )
                            Text(
                                text = "#${ev.seq} ${ev.eventType}",
                                style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = tokens.textColor)
                            )
                        }
                        Text(
                            text = formatAuditTimestamp(ev.createdAtMs),
                            style = TextStyle(fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = tokens.textSecondaryColor)
                        )
                    }

                    Text(
                        text = "操作者: ${ev.actorType}/${ev.actorId} | 决策: ${ev.decision.ifBlank { "-" }} | 结果: ${ev.result.ifBlank { "-" }}",
                        style = TextStyle(fontSize = 11.sp, color = tokens.textSecondaryColor)
                    )

                    if (ev.errorCode.isNotBlank()) {
                        Text(
                            text = "错误码: ${ev.errorCode}",
                            style = TextStyle(fontSize = 11.sp, color = tokens.statusDanger)
                        )
                    }
                    if (ev.beforeState.isNotBlank() || ev.afterState.isNotBlank()) {
                        Text(
                            text = "变更: ${ev.beforeState.ifBlank { "{}" }} -> ${ev.afterState.ifBlank { "{}" }}",
                            style = TextStyle(fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = tokens.textSecondaryColor),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (ev.entryHash.isNotBlank()) {
                        Text(
                            text = "Hash: ${ev.entryHash.take(18)}...",
                            style = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = tokens.accentColor)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

private fun mapAuditStatusTagType(status: String): GlassTagType {
    return when (status.uppercase(Locale.US)) {
        "COMPLETED" -> GlassTagType.Success
        "RUNNING", "PENDING_SYNC", "ROLLED_BACK" -> GlassTagType.Warning
        "FAILED", "BLOCKED", "AUDIT_COMMIT_FAILED", "EXECUTION_UNKNOWN" -> GlassTagType.Error
        else -> GlassTagType.Info
    }
}

private fun formatAuditTimestamp(ms: Long): String {
    if (ms <= 0L) return "--:--:--"
    val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(ms))
}
